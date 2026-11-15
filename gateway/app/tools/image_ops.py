"""Tools for the `images` skill — the escalation ladder for photo recall.

Three rungs, cheapest first:

  get_image_text(id)          cached OCR transcription  -> 0 vision calls
  describe_image(id, q)       targeted vision call      -> 1 call, then cached
  list_images()               ids + summaries           -> 0 vision calls

The point is that the summary a note carries is a *gist*. When the user
asks for specifics — text, numbers, small details — the model must climb
the ladder rather than guessing from the summary. `visible_text` is
captured once at caption time, so text extraction is permanently free.
"""

import json
from collections.abc import Callable

ToolSpec = tuple[dict, Callable]


LIST_IMAGES_SCHEMA = {
    "type": "function",
    "function": {
        "name": "list_images",
        "description": (
            "List the user's recent images: id, summary, whether they contain "
            "readable text, and how dense that text is. Use this to find the "
            "right image when the note's inline [img_...] entries aren't enough."
        ),
        "parameters": {"type": "object", "properties": {}, "required": []},
    },
}

GET_IMAGE_TEXT_SCHEMA = {
    "type": "function",
    "function": {
        "name": "get_image_text",
        "description": (
            "Return the VERBATIM text transcribed from an image (prices, totals, "
            "dates, labels, names — character for character). This is read from "
            "cache and costs nothing, so ALWAYS try this before describe_image "
            "when the user asks about text, numbers, or written content. Accepts "
            "the short id shown in a note's [img_xxxx] entry or the full id."
        ),
        "parameters": {
            "type": "object",
            "properties": {
                "image_id": {"type": "string", "description": "Image id (short form from [img_xxxx] is fine)."},
            },
            "required": ["image_id"],
        },
    },
}

DESCRIBE_IMAGE_SCHEMA = {
    "type": "function",
    "function": {
        "name": "describe_image",
        "description": (
            "Ask a specific question about an image; a vision model looks at the "
            "actual picture and answers. Use this ONLY when the summary and "
            "get_image_text can't answer — e.g. colours, spatial relationships, "
            "or details nobody transcribed. Costs a model call, so ask one "
            "precise question rather than 'describe this'."
        ),
        "parameters": {
            "type": "object",
            "properties": {
                "image_id": {"type": "string", "description": "Image id (short form from [img_xxxx] is fine)."},
                "question": {"type": "string", "description": "One specific question about the image."},
            },
            "required": ["image_id", "question"],
        },
    },
}


def build_image_tools(state: dict) -> list[ToolSpec]:
    """`state` needs "session" (str) and "app_state" (the State instance) —
    unlike the list/goal tools these read from the gateway's own store,
    since images live server-side."""

    app_state = state.get("app_state")
    session = state.get("session") or ""

    async def _resolve(image_id: str) -> str | None:
        """Accept the short 8-char form shown in note index entries."""
        if not app_state or not image_id:
            return None
        sid = image_id.strip().removeprefix("img_")
        if len(sid) >= 32 and await app_state.get_image(sid):
            return sid
        for rec in await app_state.images_for_session(session, limit=200):
            if rec["sha"].startswith(sid):
                return rec["sha"]
        return None

    async def list_images() -> str:
        if not app_state:
            return json.dumps([])
        rows = await app_state.images_for_session(session, limit=50)
        return json.dumps([
            {
                "id": r["sha"][:8],
                "summary": r.get("summary") or "(not yet described)",
                "has_text": bool(r.get("has_text")),
                "text_density": r.get("text_density", "none"),
            }
            for r in rows
        ])

    async def get_image_text(image_id: str) -> str:
        sha = await _resolve(image_id)
        if sha is None:
            return f"[error] no image matching '{image_id}'. Try list_images."
        rec = await app_state.get_image(sha)
        if not rec:
            return f"[error] no image matching '{image_id}'."
        text = (rec.get("visible_text") or "").strip()
        if not text:
            if rec.get("summary"):
                return (
                    "[no text found in this image] Summary: "
                    f"{rec['summary']}. If you need a visual detail, use describe_image."
                )
            return "[not yet described] This image hasn't been captioned yet — use describe_image."
        return text

    async def describe_image(image_id: str, question: str) -> str:
        sha = await _resolve(image_id)
        if sha is None:
            return f"[error] no image matching '{image_id}'. Try list_images."
        from app import images as image_store
        return await image_store.answer_about_image(app_state, sha, question)

    return [
        (LIST_IMAGES_SCHEMA, list_images),
        (GET_IMAGE_TEXT_SCHEMA, get_image_text),
        (DESCRIBE_IMAGE_SCHEMA, describe_image),
    ]
