"""Sub-agent orchestration: a `run_subagents` tool that lets an orchestrator
agent delegate independent subtasks to other registered agents and run them in
parallel, then synthesize their replies.

Design (see the plan):
  * Text-only — a sub-agent's reply text crosses back; its ops/artifacts are
    dropped (no nested device mutation).
  * True parallelism via a DEDICATED gate — each sub-agent sets
    diag.subagent_depth=1, which routes its Ollama calls to the 'ollama-sub'
    gate (concurrency subagent_concurrency), so two local sub-agents overlap
    while the main gate stays at 1.
  * Depth guard — a sub-agent (depth>0) can't spawn its own sub-agents.
  * Hard cap subagent_max_parallel; per-sub-agent timeout; nested /diag traces.
"""

from __future__ import annotations

import asyncio
import json
import logging

from app import diagnostics as diag
from app.config import settings
from app.delivery import parse_envelope
from app.registry import registry
from app.schemas import ChatMessage

log = logging.getLogger(__name__)

ToolSpec = tuple[dict, object]


def _delegatable(self_id: str | None) -> list:
    """Registry agents an orchestrator may delegate to: delegatable, not self."""
    return [
        c for c in registry.list()
        if getattr(c, "delegatable", True) and c.id != self_id
    ]


def build_subagents_tool(self_id: str | None, session_id: str | None, app_state) -> ToolSpec:
    """Build the (schema, async fn) for run_subagents, bound to this request.
    The schema enumerates the currently delegatable agents, like the SKILL
    CATALOG, so the model picks ids from a real list."""
    cap = max(1, settings.subagent_max_parallel)
    delegatable = _delegatable(self_id)
    catalog = "\n".join(f"  • {c.id} — {c.description}" for c in delegatable) or "  (none available)"

    schema = {
        "type": "function",
        "function": {
            "name": "run_subagents",
            "description": (
                f"Delegate independent subtasks to other agents and run them IN PARALLEL "
                f"(up to {cap} at once). Use this ONLY when the request splits into pieces "
                "that don't depend on each other (e.g. research two companies at once, or "
                "gather while you draft) — it does not make a single task faster. Each "
                "sub-agent gets ONLY the task text you give it (no shared context), so make "
                "each task self-contained. You receive each agent's reply as text and must "
                "synthesize them into your own answer — do not just paste them. Sub-agents "
                "return TEXT ONLY; they cannot change the user's lists/goals or publish "
                "documents, so don't delegate device edits.\n\nAvailable agents:\n" + catalog
            ),
            "parameters": {
                "type": "object",
                "properties": {
                    "tasks": {
                        "type": "array",
                        "maxItems": cap,
                        "description": f"1-{cap} subtasks to run in parallel.",
                        "items": {
                            "type": "object",
                            "properties": {
                                "agent": {"type": "string", "description": "An agent id from the list above."},
                                "task": {"type": "string", "description": "The full, self-contained subtask for that agent."},
                            },
                            "required": ["agent", "task"],
                        },
                    },
                },
                "required": ["tasks"],
            },
        },
    }

    valid_ids = {c.id for c in delegatable}

    async def _run_one(index: int, agent_id: str, task: str) -> tuple[str, str]:
        agent = registry.get(agent_id)
        if agent is None or agent_id not in valid_ids:
            return agent_id, f"[error] '{agent_id}' is not a delegatable agent."
        sub_session = f"{session_id or 'session'}:sub{index}"
        # Tell the user's live feed what's being delegated.
        diag.progress_publish(session_id, {
            "type": "progress", "phase": "tool", "name": "run_subagents",
            "label": f"Delegating to {agent_id}…", "ok": True,
        })
        # Depth 1 → this coroutine's model calls use the dedicated sub-agent gate
        # and its own run_subagents (if any) is refused. A nested trace shows the
        # sub-run on /diag.
        tok = diag.subagent_depth.set(1)
        diag.start_trace(trigger=f"subagent:{agent_id}", model=agent_id,
                         session=sub_session, request={"task": task})
        try:
            payload = json.dumps({"prompt": task, "lists": [], "notes": [], "goals": []})
            result = await asyncio.wait_for(
                agent.runtime.arun([ChatMessage(role="user", content=payload)], session_id=sub_session),
                timeout=settings.subagent_timeout_seconds,
            )
            env = parse_envelope(result)
            await diag.finish_trace(app_state, env.status, response=result.text,
                                    reply_preview=env.reply)
            return agent_id, (env.reply or "(the sub-agent returned no text)")
        except asyncio.TimeoutError:
            await diag.finish_trace(app_state, "timeout", response={"error": "timeout"})
            return agent_id, f"[timed out after {settings.subagent_timeout_seconds}s]"
        except Exception as e:  # noqa: BLE001 — one sub-agent failing shouldn't abort the turn
            log.exception("[subagents] %s failed", agent_id)
            await diag.finish_trace(app_state, "error", response={"error": repr(e)})
            return agent_id, f"[error running {agent_id}: {e}]"
        finally:
            diag.subagent_depth.reset(tok)

    async def run_subagents(tasks=None) -> str:
        if diag.subagent_depth.get() > 0:
            return "[error] You are already a sub-agent; sub-agents can't delegate further."
        if not isinstance(tasks, list) or not tasks:
            return "[error] Provide `tasks`: a list of 1-{} {{agent, task}} items.".format(cap)
        items = []
        for t in tasks[:cap]:
            if not isinstance(t, dict):
                continue
            items.append((str(t.get("agent", "")).strip(), str(t.get("task", "")).strip()))
        items = [(a, task) for a, task in items if a and task]
        if not items:
            return "[error] Each task needs a non-empty `agent` and `task`."

        results = await asyncio.gather(
            *[_run_one(i, a, task) for i, (a, task) in enumerate(items)]
        )
        return "\n\n".join(f"### {aid}\n{reply}" for aid, reply in results)

    return (schema, run_subagents)
