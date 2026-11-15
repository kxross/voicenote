"""Tools for the `wiki` skill — the learned-knowledge plugin.

`wiki_search` is read-only retrieval over the user's APPROVED entries. `propose_
wiki_entry` mirrors the schedules skill's approval gate: it only ever drafts a
proposal (writes to proposed/ + records a card on the per-request state); nothing
is activated until the user approves. Content lives as files via app/wiki_store.py.
"""

from __future__ import annotations

from collections.abc import Callable

from app.wiki_store import store

ToolSpec = tuple[dict, Callable]

WIKI_SEARCH_SCHEMA = {
    "type": "function",
    "function": {
        "name": "wiki_search",
        "description": (
            "Search the user's learned wiki (approved reusable knowledge, formats, "
            "procedures, preferences) and return the most relevant entries. Call this "
            "before doing work you may have established before, so you can apply what "
            "was already worked out instead of re-deriving it."
        ),
        "parameters": {
            "type": "object",
            "properties": {
                "query": {"type": "string", "description": "What you're looking for (topic, deliverable, task)."},
            },
            "required": ["query"],
        },
    },
}

PROPOSE_WIKI_SCHEMA = {
    "type": "function",
    "function": {
        "name": "propose_wiki_entry",
        "description": (
            "Propose saving (or amending) a reusable wiki entry — a format, procedure, "
            "or standing preference this conversation established that the user will "
            "want again. This does NOT save it: it drafts a proposal the user approves "
            "on their phone. To AMEND an existing entry (it's out of date / incomplete), "
            "pass its entry_id from the WIKI INDEX or wiki_search; the user sees a diff. "
            "Only propose genuinely reusable things, not one-off answers."
        ),
        "parameters": {
            "type": "object",
            "properties": {
                "title": {"type": "string", "description": "Short, searchable name, e.g. 'Weekly ops report format'."},
                "when_to_use": {"type": "string", "description": "One line on when this entry applies — what future turns match against."},
                "body": {"type": "string", "description": "The actual knowledge/procedure in markdown; concrete and self-contained."},
                "kind": {"type": "string", "description": "'procedure' (a how-to) or 'knowledge' (facts/preferences). Default knowledge."},
                "entry_id": {"type": "string", "description": "Set ONLY to amend an existing entry (its id from the index/search); omit to create a new one."},
            },
            "required": ["title", "when_to_use", "body"],
        },
    },
}


def build_tools(state: dict) -> list[ToolSpec]:
    async def wiki_search(query: str) -> str:
        results = store().search(query, limit=5)
        if not results:
            return "No matching wiki entries. (Nothing learned yet for this, or try other words.)"
        blocks = []
        for e in results:
            blocks.append(
                f"## {e.get('title', '(untitled)')}  [id: {e.get('id')}]\n"
                f"_when to use: {e.get('when_to_use', '')}_\n\n{(e.get('body') or '').strip()}"
            )
        return "Relevant wiki entries:\n\n" + "\n\n---\n\n".join(blocks)

    async def propose_wiki_entry(
        title: str, when_to_use: str, body: str,
        kind: str = "knowledge", entry_id: str = "",
    ) -> str:
        if not (title or "").strip() or not (body or "").strip():
            return "[error] A wiki entry needs at least a title and a body."
        prop = store().propose(
            title=title.strip(), when_to_use=(when_to_use or "").strip(), body=body,
            kind=kind, source="assistant", source_thread=str(state.get("session") or ""),
            entry_id=(entry_id or None),
        )
        card = {k: prop.get(k) for k in
                ("proposal_id", "op", "target_id", "title", "when_to_use", "kind", "body")}
        state.setdefault("wiki_proposals", []).append(card)
        verb = "update the existing" if prop["op"] == "amend" else "save a new"
        return (
            f"Drafted a proposal to {verb} wiki entry '{title}'. This is a DRAFT ONLY — "
            "it is NOT active and nothing is saved yet. The user must approve it (they'll "
            "see a card, with a diff for an amendment). In your reply, briefly say what you "
            "captured and that they can approve it to reuse next time."
        )

    return [
        (WIKI_SEARCH_SCHEMA, wiki_search),
        (PROPOSE_WIKI_SCHEMA, propose_wiki_entry),
    ]


__all__ = ["build_tools"]
