"""Tools that hand pixels back to the model mid-loop.

Normally a tool returns a string, which becomes the `role:"tool"` message. A
tool may instead return a `ToolResult(text, images)`: the text still becomes
the tool message, and the images ride in ONE follow-up user message right after
that round's tool results — the only place every provider (and every chat
template) reliably renders images. The `video` skill uses this to show the
model sampled frames and contact sheets.

Context hygiene: only the newest batch of frames stays attached. When a new
batch arrives, earlier frame messages keep their caption but drop their pixels
("…no longer attached"), so frames are never re-sent on every later call and
context grows by at most one batch.
"""

from __future__ import annotations

from dataclasses import dataclass, field

FRAMES_PREFIX = "[Frames for you to look at"


@dataclass
class ToolResult:
    text: str
    # base64-encoded JPEGs, in the order the model should read them.
    images: list[str] = field(default_factory=list)
    # One line describing the batch, e.g. "8 frames, 00:12.0–00:15.5 of vid_ab12cd34".
    caption: str = ""

    def __str__(self) -> str:      # anything that str()s a result gets the text
        return self.text


def split(result) -> tuple[object, list[str], str]:
    """(text-or-original, images, caption) for any tool return value."""
    if isinstance(result, ToolResult):
        return result.text, list(result.images or []), result.caption or ""
    return result, [], ""


def _caption(captions: list[str], n: int) -> str:
    body = "; ".join(c for c in captions if c) or f"{n} image(s)"
    return f"{FRAMES_PREFIX} — {body}. Timestamps are burned into each frame.]"


def frames_message_ollama(captions: list[str], images: list[str]) -> dict:
    return {"role": "user", "content": _caption(captions, len(images)), "images": images}


def frames_message_openai(captions: list[str], images: list[str]) -> dict:
    parts: list[dict] = [{"type": "text", "text": _caption(captions, len(images))}]
    parts += [{"type": "image_url", "image_url": {"url": f"data:image/jpeg;base64,{b}"}}
              for b in images]
    return {"role": "user", "content": parts}


def _is_frames(msg: dict) -> bool:
    if msg.get("role") != "user":
        return False
    c = msg.get("content")
    if isinstance(c, str):
        return c.startswith(FRAMES_PREFIX)
    if isinstance(c, list) and c and isinstance(c[0], dict):
        return str(c[0].get("text", "")).startswith(FRAMES_PREFIX)
    return False


_RETIRED = " (These frames are no longer attached — request them again if you need another look.)"


def retire_frames(convo: list[dict]) -> None:
    """Strip the pixels from every earlier frames message, keeping its caption."""
    for m in convo:
        if not _is_frames(m):
            continue
        if m.get("images"):
            m.pop("images", None)
            m["content"] = str(m.get("content", "")) + _RETIRED
        elif isinstance(m.get("content"), list):
            text = str(m["content"][0].get("text", ""))
            m["content"] = text + _RETIRED
