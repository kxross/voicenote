"""Goal-relative progress phrasing.

Turns the mechanical skill/tool names the runtime emits into short, human,
"what I'm doing for you" phrases — the Anthropic-style status line ("Searching
the web", "Updating your lists") rather than raw `add_item` / reasoning dumps.

Used in two places so the live feed and the stored activity accordion read the
same: diagnostics.progress_* (live SSE) and skillful_ollama_runtime._build_steps
(the persisted steps).
"""

from __future__ import annotations

# Loading a skill = about to work in that area.
SKILL_PHRASES = {
    "lists": "Updating your lists",
    "notes": "Reviewing your notes",
    "goals": "Working on your goals",
    "images": "Looking at your photos",
    "video": "Watching your video",
    "websearch": "Searching the web",
    "today": "Checking the date & time",
    "canvas": "Building a visual",
    "vm": "Working on the machine",
}

# Calling a tool = doing the specific thing.
TOOL_PHRASES = {
    "add_item": "Adding to a list",
    "remove_item": "Removing from a list",
    "set_item_done": "Checking off an item",
    "create_list": "Creating a list",
    "rename_list": "Renaming a list",
    "delete_list": "Deleting a list",
    "get_lists": "Reading your lists",
    "web_search": "Searching the web",
    "create_goal": "Creating a goal",
    "update_goal": "Updating a goal",
    "delete_goal": "Removing a goal",
    "add_goal_context": "Noting goal context",
    "set_plan": "Drafting a plan",
    "set_plan_status": "Updating a plan",
    "add_task": "Adding a task",
    "set_task_done": "Completing a task",
    "remove_task": "Removing a task",
    "get_goal": "Reviewing a goal",
    "get_goals": "Reviewing your goals",
    "render_canvas": "Building a visual",
    "today": "Checking the date & time",
    "get_image_text": "Reading a photo",
    "video_probe": "Loading the video",
    "video_overview": "Skimming the video",
    "video_frames": "Looking closely at frames",
    "add_note": "Saving a note",
}

STARTING = "Getting started…"
THINKING = "Thinking…"
DONE = "Done"


def _humanize(name: str) -> str:
    return (name or "").replace("_", " ").strip().capitalize() or "Working"


def label_for(kind: str, name: str) -> str:
    """A short goal-relative phrase for a skill load or tool call."""
    if kind == "skill":
        return SKILL_PHRASES.get(name, f"Using {name}")
    if kind == "tool":
        return TOOL_PHRASES.get(name, _humanize(name))
    return _humanize(name)


def _trunc(s, n: int = 52) -> str:
    s = str(s or "").strip().replace("\n", " ")
    return s if len(s) <= n else s[: n - 1] + "…"


def _basename(p) -> str:
    p = str(p or "").rstrip("/")
    return p.rsplit("/", 1)[-1] or p


def detail_label(kind: str, name: str, args: dict | None = None) -> str:
    """Like [label_for] but enriched with the call's arguments, so a generic
    "Vm exec" becomes the actual step — "Running: npm test", "Searching: …",
    "Reading plan.md". The agent may also supply its own one-line `purpose`
    (currently on vm_exec), which wins — the model saying what it's doing.
    """
    a = args if isinstance(args, dict) else {}

    purpose = a.get("purpose")
    if isinstance(purpose, str) and purpose.strip():
        return _trunc(purpose, 72)

    if name == "vm_exec":
        cmd = _trunc(a.get("command"))
        if cmd:
            return f"Running: {cmd}"
    elif name == "web_search":
        q = _trunc(a.get("query"))
        if q:
            return f"Searching: {q}"
    elif name == "vm_read_file":
        if a.get("path"):
            return f"Reading {_basename(a['path'])}"
    elif name == "vm_write_file":
        if a.get("path"):
            return f"Writing {_basename(a['path'])}"
    elif name == "vm_checkpoint":
        step = a.get("next_step") or a.get("note")
        if step:
            return _trunc(step, 72)

    return label_for(kind, name)
