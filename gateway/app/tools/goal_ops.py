"""Tools for the `goals` skill.

Same contract as app/tools/list_ops.py: each mutating tool queues one
operation into a per-request state dict; the client applies the ops to
its local Room database. Goals are referenced by title and tasks by
text — case-insensitive matching happens client-side. The server never
holds authoritative goal state, only the cached snapshot in
state["goals"].
"""

import json
from collections.abc import Callable

ToolSpec = tuple[dict, Callable]

# Goals are flat; decomposition happens INSIDE the plan — a task may have
# one level of subtasks (nested checklist items). Deeper nesting is
# refused: voice-first dialogue and notification-sized replies degrade
# quickly with deep trees.


def goal_index(goals: list[dict]) -> list[dict]:
    """One compact row per goal — enough for the model to answer "which
    goal is this about?", spot duplicates before create_goal, and see at a
    glance which goals have unanswered Q: entries or a draft plan. The
    index is what get_goals() returns and what the runtime injects into
    the system prompt in place of the full goals; call get_goal(title) to
    load a specific goal's full context/plan/notes.

    Single source of truth for the row shape — used by both the runtime
    injection and get_goals(), so the two can never drift.
    """
    out = []
    for g in goals:
        ctx = g.get("context", []) or []
        open_q = sum(
            1 for c in ctx
            if c.get("source") == "agent" and str(c.get("text", "")).startswith("Q:")
        )
        plan = g.get("plan")
        row = {
            "title": g.get("title"),
            "status": g.get("status", "draft"),
            "well_specified": bool(g.get("well_specified", False)),
            "context_count": len(ctx),
            "open_questions": open_q,
            "notes_count": len(g.get("notes", []) or []),
        }
        if plan:
            tasks = plan.get("tasks", []) or []
            row["plan"] = {
                "status": plan.get("status", "draft"),
                "tasks": len(tasks),
                "done": sum(1 for t in tasks if t.get("done")),
            }
        out.append(row)
    return out


GET_GOALS_SCHEMA = {
    "type": "function",
    "function": {
        "name": "get_goals",
        "description": (
            "Return a compact INDEX of the user's goals: title, status, "
            "well_specified, counts of context entries / open Q: questions / "
            "notes, and a plan summary (status + task count + done count). "
            "Use this to spot duplicates before create_goal, see which goals "
            "have unanswered questions or a draft plan, and decide which one "
            "the current utterance is about. Call get_goal(title) to fetch a "
            "specific goal's FULL context, notes, and plan."
        ),
        "parameters": {"type": "object", "properties": {}, "required": []},
    },
}

GET_GOAL_SCHEMA = {
    "type": "function",
    "function": {
        "name": "get_goal",
        "description": (
            "Fetch ONE goal in full: statement, all context entries (with "
            "source), scoped notes, and the plan with milestones/tasks/subtasks. "
            "Match is case-insensitive with a substring fallback, so a paraphrase "
            "of the title usually works. Call this BEFORE reasoning about or "
            "mutating a goal — the goals block in your system prompt is only an "
            "index."
        ),
        "parameters": {
            "type": "object",
            "properties": {
                "title": {
                    "type": "string",
                    "description": "The goal's title as shown in the goals index.",
                },
            },
            "required": ["title"],
        },
    },
}

CREATE_GOAL_SCHEMA = {
    "type": "function",
    "function": {
        "name": "create_goal",
        "description": (
            "Create a new draft goal. Only call this for recurring or clearly "
            "articulated intent — not passing mentions. Check get_goals first to "
            "avoid duplicates. Goals are flat — decompose big goals INSIDE their "
            "plan using tasks with subtasks, not by creating more goals."
        ),
        "parameters": {
            "type": "object",
            "properties": {
                "title": {"type": "string", "description": "Short unique name, e.g. 'Run a 10k'."},
                "statement": {
                    "type": "string",
                    "description": "One-sentence articulation of the goal as currently understood.",
                },
            },
            "required": ["title"],
        },
    },
}

UPDATE_GOAL_SCHEMA = {
    "type": "function",
    "function": {
        "name": "update_goal",
        "description": (
            "Refine a goal's statement, move it through its lifecycle "
            "(draft/active/achieved/abandoned), or mark it well_specified once "
            "outcome, timeframe, and motivation/constraints are all known."
        ),
        "parameters": {
            "type": "object",
            "properties": {
                "goal": {"type": "string", "description": "Title of the goal to update."},
                "statement": {"type": "string", "description": "New refined statement."},
                "status": {
                    "type": "string",
                    "enum": ["draft", "active", "achieved", "abandoned"],
                },
                "well_specified": {
                    "type": "boolean",
                    "description": "Set true only when outcome, timeframe, and at least one motivation/constraint are all captured in context.",
                },
            },
            "required": ["goal"],
        },
    },
}

ADD_GOAL_CONTEXT_SCHEMA = {
    "type": "function",
    "function": {
        "name": "add_goal_context",
        "description": (
            "Attach a short factual context entry to a goal. Use source='note' for "
            "facts mined from the user's notes, source='agent' for your own open "
            "questions (prefix those with 'Q:'), source='user' for direct answers."
        ),
        "parameters": {
            "type": "object",
            "properties": {
                "goal": {"type": "string"},
                "text": {"type": "string", "description": "Short, factual. Open questions prefixed 'Q:'."},
                "source": {"type": "string", "enum": ["note", "agent", "user"]},
            },
            "required": ["goal", "text", "source"],
        },
    },
}

SET_PLAN_SCHEMA = {
    "type": "function",
    "function": {
        "name": "set_plan",
        "description": (
            "Create or replace a goal's draft plan wholesale. Use this to draft "
            "the FIRST plan as soon as the goal has a discernible outcome — the "
            "draft is your proposal for the user to react to; don't wait for a "
            "fully specified goal. State any assumptions you made (timeframe, "
            "cadence) in the plan summary. The plan starts as a draft the user "
            "must approve. NEVER call this for a goal whose plan is already "
            "approved — describe the proposed change in your reply and wait for "
            "the user's go-ahead instead."
        ),
        "parameters": {
            "type": "object",
            "properties": {
                "goal": {"type": "string"},
                "summary": {"type": "string", "description": "1-2 sentence plan overview with the overall timeline."},
                "tasks": {
                    "type": "array",
                    "items": {
                        "type": "object",
                        "properties": {
                            "text": {"type": "string"},
                            "milestone": {"type": "string", "description": "Grouping label, e.g. 'Week 1-2: Base building'."},
                            "due": {"type": "string", "description": "Rough timing hint, e.g. 'by end of September'."},
                            "subtasks": {
                                "type": "array",
                                "description": "Optional nested checklist items under this task — ONE level only. Use for decomposing a chunky task; subtasks cannot nest further.",
                                "items": {
                                    "type": "object",
                                    "properties": {
                                        "text": {"type": "string"},
                                        "due": {"type": "string"},
                                    },
                                    "required": ["text"],
                                },
                            },
                        },
                        "required": ["text"],
                    },
                },
            },
            "required": ["goal", "summary", "tasks"],
        },
    },
}

SET_PLAN_STATUS_SCHEMA = {
    "type": "function",
    "function": {
        "name": "set_plan_status",
        "description": (
            "Change a plan's status. Use status='approved' only when the user has "
            "explicitly approved the plan (e.g. said 'approve my 10k plan'). "
            "Approval ACTIVATES the goal: the client also moves the goal from "
            "draft to active — they are one transition."
        ),
        "parameters": {
            "type": "object",
            "properties": {
                "goal": {"type": "string"},
                "status": {"type": "string", "enum": ["draft", "approved"]},
            },
            "required": ["goal", "status"],
        },
    },
}

ADD_TASK_SCHEMA = {
    "type": "function",
    "function": {
        "name": "add_task",
        "description": (
            "Append one task to a goal's existing plan. Pass `parent_task` (the "
            "text of an existing TOP-LEVEL task) to add it as a subtask — one "
            "level of nesting only."
        ),
        "parameters": {
            "type": "object",
            "properties": {
                "goal": {"type": "string"},
                "text": {"type": "string"},
                "milestone": {"type": "string"},
                "due": {"type": "string"},
                "parent_task": {
                    "type": "string",
                    "description": "Optional: text of the existing top-level task to nest under.",
                },
            },
            "required": ["goal", "text"],
        },
    },
}

SET_TASK_DONE_SCHEMA = {
    "type": "function",
    "function": {
        "name": "set_task_done",
        "description": "Mark a plan task done or not done. Matches by task text, case-insensitive.",
        "parameters": {
            "type": "object",
            "properties": {
                "goal": {"type": "string"},
                "task": {"type": "string", "description": "Text of the task."},
                "done": {"type": "boolean"},
            },
            "required": ["goal", "task", "done"],
        },
    },
}

REMOVE_TASK_SCHEMA = {
    "type": "function",
    "function": {
        "name": "remove_task",
        "description": "Remove a task from a goal's plan. Matches by task text.",
        "parameters": {
            "type": "object",
            "properties": {
                "goal": {"type": "string"},
                "task": {"type": "string"},
            },
            "required": ["goal", "task"],
        },
    },
}

DELETE_GOAL_SCHEMA = {
    "type": "function",
    "function": {
        "name": "delete_goal",
        "description": (
            "Delete a goal and everything under it (context, plan, tasks). Only "
            "when the user explicitly asks, or clearly abandons the goal."
        ),
        "parameters": {
            "type": "object",
            "properties": {"goal": {"type": "string"}},
            "required": ["goal"],
        },
    },
}


def build_goal_tools(state: dict) -> list[ToolSpec]:
    """Return (schema, async fn) pairs bound to the given per-request state.

    `state` must contain:
      - "goals": list[dict]  — the client's current goals snapshot, read-only
      - "ops":   list[dict]  — shared op queue; mutating tools append here
    """

    async def get_goals() -> str:
        # Index only — call get_goal(title) for the full detail of a specific one.
        return json.dumps(goal_index(state.get("goals", [])))

    def _find_goal(title: str) -> dict | None:
        lc = title.lower()
        goals = state.get("goals", [])
        # Exact (case-insensitive) match wins.
        for g in goals:
            if g.get("title", "").lower() == lc:
                return g
        # Substring fallback either direction — the model often paraphrases
        # ("10k" vs stored "Run a 10k"). Matches the client's fuzzy lookup.
        for g in goals:
            gt = g.get("title", "").lower()
            if gt and (gt in lc or lc in gt):
                return g
        return None

    async def get_goal(title: str) -> str:
        g = _find_goal(title)
        if g is None:
            titles = [x.get("title") for x in state.get("goals", [])]
            return json.dumps({
                "error": f"no goal matching '{title}'",
                "known": titles,
            })
        return json.dumps(g)

    async def create_goal(title: str, statement: str = "") -> str:
        state["ops"].append({"type": "create_goal", "title": title, "statement": statement})
        return "ok"

    async def update_goal(
        goal: str,
        statement: str | None = None,
        status: str | None = None,
        well_specified: bool | None = None,
    ) -> str:
        op: dict = {"type": "update_goal", "goal": goal}
        if statement is not None:
            op["statement"] = statement
        if status is not None:
            op["status"] = status
        if well_specified is not None:
            op["well_specified"] = well_specified
        state["ops"].append(op)
        return "ok"

    async def add_goal_context(goal: str, text: str, source: str) -> str:
        state["ops"].append(
            {"type": "add_goal_context", "goal": goal, "text": text, "source": source}
        )
        return "ok"

    async def set_plan(goal: str, summary: str, tasks: list) -> str:
        # Guard: never overwrite an approved plan even if the model ignores
        # the prompt. A draft plan, on the other hand, is meant to be created
        # eagerly and iterated on — no proposal gate.
        target = _find_goal(goal)
        if target is not None:
            plan = target.get("plan")
            if plan and plan.get("status") == "approved":
                return (
                    "[refused] this goal's plan is already approved. Describe the "
                    "proposed change in your reply and wait for the user's go-ahead."
                )
        state["ops"].append(
            {"type": "set_plan", "goal": goal, "summary": summary, "tasks": tasks}
        )
        return "ok"

    async def set_plan_status(goal: str, status: str) -> str:
        state["ops"].append({"type": "set_plan_status", "goal": goal, "status": status})
        return "ok"

    async def add_task(
        goal: str,
        text: str,
        milestone: str = "",
        due: str = "",
        parent_task: str = "",
    ) -> str:
        # Depth guard: a subtask's parent must be a TOP-LEVEL task of the
        # goal's plan. One level of nesting, enforced here and client-side.
        if parent_task:
            target = _find_goal(goal)
            plan = (target or {}).get("plan") or {}
            parent = next(
                (
                    t for t in plan.get("tasks", [])
                    if t.get("text", "").lower() == parent_task.lower()
                ),
                None,
            )
            if parent is None:
                return (
                    f"[refused] no top-level task '{parent_task}' in this goal's plan. "
                    f"Subtasks can only nest under top-level tasks."
                )
        op: dict = {"type": "add_task", "goal": goal, "text": text, "milestone": milestone, "due": due}
        if parent_task:
            op["parent_task"] = parent_task
        state["ops"].append(op)
        return "ok"

    async def set_task_done(goal: str, task: str, done: bool) -> str:
        state["ops"].append(
            {"type": "set_task_done", "goal": goal, "task": task, "done": done}
        )
        return "ok"

    async def remove_task(goal: str, task: str) -> str:
        state["ops"].append({"type": "remove_task", "goal": goal, "task": task})
        return "ok"

    async def delete_goal(goal: str) -> str:
        state["ops"].append({"type": "delete_goal", "goal": goal})
        return "ok"

    return [
        (GET_GOALS_SCHEMA, get_goals),
        (GET_GOAL_SCHEMA, get_goal),
        (CREATE_GOAL_SCHEMA, create_goal),
        (UPDATE_GOAL_SCHEMA, update_goal),
        (ADD_GOAL_CONTEXT_SCHEMA, add_goal_context),
        (SET_PLAN_SCHEMA, set_plan),
        (SET_PLAN_STATUS_SCHEMA, set_plan_status),
        (ADD_TASK_SCHEMA, add_task),
        (SET_TASK_DONE_SCHEMA, set_task_done),
        (REMOVE_TASK_SCHEMA, remove_task),
        (DELETE_GOAL_SCHEMA, delete_goal),
    ]
