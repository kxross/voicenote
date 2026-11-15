"""Tools exported by the `artifact` skill.

An "artifact" is a long-form, rendered document the Android client shows in a
dedicated reader (and keeps in a browsable library) instead of a chat bubble.
Phase 1 handles **markdown**: the model writes GitHub-flavored markdown (tables,
fenced code, ```mermaid diagrams, $…$ / $$…$$ math) and this tool captures it
into the per-request state; the runtime hands it back on the `artifacts` field
of the response envelope, where it rides the normal JSON delivery path (no
server-side storage this phase).

**Revisions.** Documents are client-owned, so the app sends the thread's
recent documents with each turn (envelope `artifacts`, parsed into
``state["thread_docs"]``). The model sees only an index of them; to change one
it calls ``read_artifact`` then ``edit_artifact`` (exact find/replace edits) or
``publish_artifact(..., replaces=id)`` for a full rewrite. A revision keeps the
document's id and is flagged ``revised: true`` on the envelope, and the app
updates the existing document in place (keeping the previous version for
undo) instead of filing a new one. Reading a document adopts its sources into
this turn's registry and renumbers its ``[n]`` markers, so old citations and
new search results share one numbering.
"""

from __future__ import annotations

import uuid
from collections.abc import Callable

from app import sources

ToolSpec = tuple[dict, Callable]

PUBLISH_ARTIFACT_SCHEMA = {
    "type": "function",
    "function": {
        "name": "publish_artifact",
        "description": (
            "Deliver a long-form document ('artifact') to the user's app, shown "
            "in a full-screen reader rather than crammed into the chat reply. Use "
            "for research, writeups, structured breakdowns, step-by-step guides — "
            "anything beyond a few short sentences. Write the body as "
            "GitHub-flavored markdown (headings, tables, fenced code, ```mermaid "
            "diagrams, $…$/$$…$$ math). Call this, then keep your `reply` to a "
            "one or two sentence lead-in (the full body lives ONLY in the "
            "artifact). You may publish more than one; each call adds a document. "
            "To REWRITE an existing document wholesale, pass its id as `replaces` "
            "(for targeted changes prefer edit_artifact)."
        ),
        "parameters": {
            "type": "object",
            "properties": {
                "title": {
                    "type": "string",
                    "description": (
                        "A short document title shown in the reader and library, "
                        "e.g. 'EV market overview' or 'Trip itinerary'. 2-6 words."
                    ),
                },
                "text": {
                    "type": "string",
                    "description": (
                        "The complete document body as GitHub-flavored markdown. "
                        "See the skill body for the supported dialect."
                    ),
                },
                "replaces": {
                    "type": "string",
                    "description": (
                        "Optional: the id of an existing document this one "
                        "replaces (a full rewrite of it). Omit for a new document."
                    ),
                },
            },
            "required": ["title", "text"],
        },
    },
}

READ_ARTIFACT_SCHEMA = {
    "type": "function",
    "function": {
        "name": "read_artifact",
        "description": (
            "Return the full current markdown of a document already published in "
            "this thread (ids are in the DOCUMENTS index), so you can revise it. "
            "Always read a document before editing it."
        ),
        "parameters": {
            "type": "object",
            "properties": {"id": {"type": "string", "description": "The document id."}},
            "required": ["id"],
        },
    },
}

EDIT_ARTIFACT_SCHEMA = {
    "type": "function",
    "function": {
        "name": "edit_artifact",
        "description": (
            "Revise an existing document IN PLACE with exact find/replace edits — "
            "the user's copy is updated, not duplicated. Read it first with "
            "read_artifact. Each `find` must be copied exactly from the current "
            "text and match exactly one place (include enough surrounding text). "
            "To insert, `find` an anchor (e.g. a heading line) and `replace` it "
            "with the anchor plus the new content. An empty `find` appends "
            "`replace` to the end. All edits apply together or none do."
        ),
        "parameters": {
            "type": "object",
            "properties": {
                "id": {"type": "string", "description": "The document id."},
                "edits": {
                    "type": "array",
                    "description": "Edits applied in order.",
                    "items": {
                        "type": "object",
                        "properties": {
                            "find": {"type": "string"},
                            "replace": {"type": "string"},
                        },
                        "required": ["find", "replace"],
                    },
                },
                "title": {
                    "type": "string",
                    "description": "Optional new title; omit to keep the current one.",
                },
            },
            "required": ["id", "edits"],
        },
    },
}


def build_tools(state: dict) -> list[ToolSpec]:
    # The thread's earlier documents (from the client), id -> {title,text,sources}.
    docs: dict = state.get("thread_docs") or {}
    # Documents being revised this turn, id -> {"title", "text"} (sources adopted).
    working: dict = state.setdefault("doc_working", {})

    def _published(doc_id: str) -> dict | None:
        return next((a for a in state.get("artifacts", []) if a.get("id") == doc_id), None)

    def _current(doc_id: str) -> dict | None:
        """The document's current version this turn, adopting a thread
        document's sources on first touch."""
        if doc_id in working:
            return working[doc_id]
        mine = _published(doc_id)
        if mine is not None:
            working[doc_id] = {"title": mine["title"], "text": mine["text"]}
            return working[doc_id]
        d = docs.get(doc_id)
        if d is None:
            return None
        text = sources.adopt(state, d.get("sources"), str(d.get("text") or ""))
        working[doc_id] = {"title": str(d.get("title") or "Document"), "text": text}
        return working[doc_id]

    def _known() -> str:
        ids = [f"{v.get('title')} [{k}]" for k, v in docs.items()]
        ids += [f"{a['title']} [{a['id']}]" for a in state.get("artifacts", []) if a["id"] not in docs]
        return "; ".join(ids) or "(none)"

    def _emit(doc_id: str, title: str, text: str, revised: bool) -> None:
        working[doc_id] = {"title": title, "text": text}
        entry = _published(doc_id)
        if entry is None:
            entry = {"id": doc_id, "kind": "markdown"}
            state.setdefault("artifacts", []).append(entry)
        entry["title"] = title
        entry["text"] = text
        if revised:
            entry["revised"] = True

    async def publish_artifact(title: str, text: str, replaces: str | None = None) -> str:
        title = (title or "").strip() or "Document"
        text = (text or "").strip()
        if not text:
            return (
                "[publish_artifact] no text provided — nothing to publish. Pass "
                "the complete document body as markdown."
            )
        if replaces:
            replaces = replaces.strip()
            if _current(replaces) is None:
                return f"[publish_artifact] no document with id '{replaces}'. Known: {_known()}"
            _emit(replaces, title, text, revised=replaces in docs)
            return (
                f"Document '{title}' rewritten in place ({len(text)} chars) — the user's "
                "copy is updated, not duplicated. Now write a one or two sentence reply "
                "saying what changed — do NOT repeat the body."
            )
        _emit(uuid.uuid4().hex[:12], title, text, revised=False)
        return (
            f"Artifact '{title}' captured ({len(text)} chars of markdown). It will "
            "be delivered to the app's reader. Now write a short one or two "
            "sentence lead-in as your reply — do NOT repeat the body in the reply."
        )

    async def read_artifact(id: str) -> str:  # noqa: A002 — the schema's name
        doc_id = (id or "").strip()
        cur = _current(doc_id)
        if cur is None:
            return f"[read_artifact] no document with id '{doc_id}'. Known: {_known()}"
        return (
            f"Document '{cur['title']}' [id: {doc_id}], {len(cur['text'])} chars. Its "
            "citation numbers are valid for this turn (new web_search results continue "
            "the numbering). Current markdown:\n\n" + cur["text"]
        )

    async def edit_artifact(id: str, edits: list, title: str | None = None) -> str:  # noqa: A002
        doc_id = (id or "").strip()
        cur = _current(doc_id)
        if cur is None:
            return f"[edit_artifact] no document with id '{doc_id}'. Known: {_known()}"
        if not isinstance(edits, list) or not edits:
            return "[edit_artifact] `edits` must be a non-empty list of {find, replace}."
        text = cur["text"]
        problems = []
        for i, e in enumerate(edits, 1):
            if not isinstance(e, dict):
                problems.append(f"edit {i}: not an object")
                continue
            find = str(e.get("find") or "")
            repl = str(e.get("replace") or "")
            if not find:
                text = text.rstrip() + "\n\n" + repl.strip() + "\n"
                continue
            count = text.count(find)
            if count == 1:
                text = text.replace(find, repl, 1)
            elif count == 0:
                problems.append(f"edit {i}: `find` not found: {find[:80]!r}")
            else:
                problems.append(
                    f"edit {i}: `find` matches {count} places — include more surrounding "
                    f"text: {find[:80]!r}"
                )
        if problems:
            return (
                "[edit_artifact] nothing changed — fix these and retry (call "
                "read_artifact to see the exact current text):\n- " + "\n- ".join(problems)
            )
        new_title = (title or "").strip() or cur["title"]
        before = len(cur["text"])
        _emit(doc_id, new_title, text.strip(), revised=doc_id in docs)
        return (
            f"Revised '{new_title}' in place ({len(edits)} edit(s), {before} -> "
            f"{len(text.strip())} chars). The user's copy is updated, not duplicated. "
            "Make any further edits, then write a one-sentence reply saying what changed."
        )

    return [
        (PUBLISH_ARTIFACT_SCHEMA, publish_artifact),
        (READ_ARTIFACT_SCHEMA, read_artifact),
        (EDIT_ARTIFACT_SCHEMA, edit_artifact),
    ]


__all__ = ["build_tools"]
