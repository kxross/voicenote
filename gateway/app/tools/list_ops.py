"""Tools for the list_manager agent.

Each mutating tool records an operation into a per-request state dict; the
runtime later serializes those operations back to the client, which applies
them to its local database. The tools do NOT mutate any server-side state —
the source of truth for the user's lists lives on the client (Android's
Room database), and the snapshot passed in per request is the only "view"
the agent has of it.

Tools are built via `build_list_tools(state)` so the runtime can rebind
them per request; a single shared instance would race across concurrent
sessions.
"""

import json
from collections.abc import Callable

ToolSpec = tuple[dict, Callable]


GET_LISTS_SCHEMA = {
    "type": "function",
    "function": {
        "name": "get_lists",
        "description": "Return the caller's current lists and their items as JSON. Call this first if you need to know what already exists before creating or modifying anything.",
        "parameters": {"type": "object", "properties": {}, "required": []},
    },
}

CREATE_LIST_SCHEMA = {
    "type": "function",
    "function": {
        "name": "create_list",
        "description": "Create a new named list. Only call this if a list with a similar name does not already exist.",
        "parameters": {
            "type": "object",
            "properties": {
                "name": {"type": "string", "description": "The name of the new list."}
            },
            "required": ["name"],
        },
    },
}

DELETE_LIST_SCHEMA = {
    "type": "function",
    "function": {
        "name": "delete_list",
        "description": "Delete an entire named list and all of its items.",
        "parameters": {
            "type": "object",
            "properties": {
                "name": {"type": "string", "description": "The name of the list to delete."}
            },
            "required": ["name"],
        },
    },
}

RENAME_LIST_SCHEMA = {
    "type": "function",
    "function": {
        "name": "rename_list",
        "description": "Rename an existing list.",
        "parameters": {
            "type": "object",
            "properties": {
                "old_name": {"type": "string", "description": "The current name of the list."},
                "new_name": {"type": "string", "description": "The new name for the list."},
            },
            "required": ["old_name", "new_name"],
        },
    },
}

ADD_ITEM_SCHEMA = {
    "type": "function",
    "function": {
        "name": "add_item",
        "description": "Add a new item to an existing list.",
        "parameters": {
            "type": "object",
            "properties": {
                "list_name": {"type": "string", "description": "The name of the list to add to."},
                "item_text": {"type": "string", "description": "The text of the new item."},
            },
            "required": ["list_name", "item_text"],
        },
    },
}

REMOVE_ITEM_SCHEMA = {
    "type": "function",
    "function": {
        "name": "remove_item",
        "description": "Remove an item from a list. Matches by item text, case-insensitive.",
        "parameters": {
            "type": "object",
            "properties": {
                "list_name": {"type": "string", "description": "The name of the list."},
                "item_text": {"type": "string", "description": "The text of the item to remove."},
            },
            "required": ["list_name", "item_text"],
        },
    },
}

GET_NOTES_SCHEMA = {
    "type": "function",
    "function": {
        "name": "get_notes",
        "description": (
            "Return the user's saved voice notes (transcripts + timestamps) as JSON, "
            "newest first. Notes are NOT preloaded — they can be many. Prefer a "
            "narrow filtered fetch (goal + contains) over a large `limit`; both "
            "filters are applied BEFORE the limit cap."
        ),
        "parameters": {
            "type": "object",
            "properties": {
                "limit": {
                    "type": "integer",
                    "description": "Max number of notes to return AFTER filters. Defaults to 20.",
                },
                "goal": {
                    "type": "string",
                    "description": "Only return notes scoped to this goal title (case-insensitive; substring match).",
                },
                "contains": {
                    "type": "string",
                    "description": "Only return notes whose transcript contains this text (case-insensitive).",
                },
            },
            "required": [],
        },
    },
}

SET_ITEM_DONE_SCHEMA = {
    "type": "function",
    "function": {
        "name": "set_item_done",
        "description": "Mark an item as done or not done (checked or unchecked).",
        "parameters": {
            "type": "object",
            "properties": {
                "list_name": {"type": "string", "description": "The name of the list."},
                "item_text": {"type": "string", "description": "The text of the item."},
                "done": {"type": "boolean", "description": "True to mark done, false to mark not done."},
            },
            "required": ["list_name", "item_text", "done"],
        },
    },
}


def build_list_tools(state: dict) -> list[ToolSpec]:
    """Return (schema, async fn) pairs bound to the given per-request state.

    `state` must contain:
      - "snapshot": list[dict]  — the client's current lists, read-only
      - "notes":    list[dict]  — the client's voice notes, read-only
      - "ops":      list[dict]  — starts empty; mutating tools append to it
    """

    async def get_lists() -> str:
        return json.dumps(state["snapshot"])

    async def get_notes(
        limit: int = 20,
        goal: str | None = None,
        contains: str | None = None,
    ) -> str:
        # Notes arrive newest-first (the client orders by createdAt DESC).
        notes = state.get("notes", []) or []
        if goal:
            g = goal.strip().lower()
            if g:
                notes = [
                    n for n in notes
                    if g in str(n.get("goal", "") or "").lower()
                ]
        if contains:
            c = contains.strip().lower()
            if c:
                notes = [
                    n for n in notes
                    if c in str(n.get("transcript", "") or "").lower()
                ]
        try:
            n_limit = max(0, int(limit))
        except (TypeError, ValueError):
            n_limit = 20
        return json.dumps(notes[:n_limit])

    async def create_list(name: str) -> str:
        state["ops"].append({"type": "create_list", "name": name})
        return "ok"

    async def delete_list(name: str) -> str:
        state["ops"].append({"type": "delete_list", "name": name})
        return "ok"

    async def rename_list(old_name: str, new_name: str) -> str:
        state["ops"].append({"type": "rename_list", "old": old_name, "new": new_name})
        return "ok"

    async def add_item(list_name: str, item_text: str) -> str:
        state["ops"].append({"type": "add_item", "list": list_name, "text": item_text})
        return "ok"

    async def remove_item(list_name: str, item_text: str) -> str:
        state["ops"].append({"type": "remove_item", "list": list_name, "text": item_text})
        return "ok"

    async def set_item_done(list_name: str, item_text: str, done: bool) -> str:
        state["ops"].append(
            {"type": "set_item_done", "list": list_name, "text": item_text, "done": done}
        )
        return "ok"

    return [
        (GET_LISTS_SCHEMA, get_lists),
        (GET_NOTES_SCHEMA, get_notes),
        (CREATE_LIST_SCHEMA, create_list),
        (DELETE_LIST_SCHEMA, delete_list),
        (RENAME_LIST_SCHEMA, rename_list),
        (ADD_ITEM_SCHEMA, add_item),
        (REMOVE_ITEM_SCHEMA, remove_item),
        (SET_ITEM_DONE_SCHEMA, set_item_done),
    ]


# --- Split factories for the skills system ---------------------------------
#
# The skills system enforces globally-unique tool names, so it can't have two
# skills both defining `get_notes`. `build_list_tools` above is the union
# (for the standalone `list_manager` agent's convenience). The two factories
# below expose the same closures split into lists-only and notes-only halves
# so a lists skill and a notes skill can coexist without collisions.

def build_lists_only(state: dict) -> list[ToolSpec]:
    """Tools that read + mutate the user's lists. Excludes notes access."""
    full = build_list_tools(state)
    keep = {
        "get_lists",
        "create_list",
        "delete_list",
        "rename_list",
        "add_item",
        "remove_item",
        "set_item_done",
    }
    return [(s, f) for (s, f) in full if s["function"]["name"] in keep]


def build_notes_only(state: dict) -> list[ToolSpec]:
    """Tools that read the user's voice notes. Read-only."""
    full = build_list_tools(state)
    keep = {"get_notes"}
    return [(s, f) for (s, f) in full if s["function"]["name"] in keep]
