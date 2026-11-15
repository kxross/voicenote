"""Content-addressed image blob store + structured captioning.

Blobs live at state/images/<aa>/<sha256>.jpg, keyed by content hash, so
the same photo attached twice costs one file. The client already
downscales (~1024px / JPEG q80) before upload, which is the single
biggest lever in the whole feature — everything downstream (upload time,
base64 inflation, VRAM, latency) scales off that.

Captioning is the recall mechanism: one vision call per image, EVER,
producing a structured record — a short `summary` plus an OCR-grade
`visible_text`. Because `visible_text` is captured once and cached,
"extract the text from this image" is answerable forever at zero vision
cost, including during scheduled runs. Only the short summary is
denormalized into note text; the verbatim transcription stays here and is
reachable through the images skill, so it never bloats the LLM hot path.
"""

from __future__ import annotations

import base64
import hashlib
import json
import logging
from pathlib import Path

import httpx

from app import diagnostics as diag
from app.config import settings

log = logging.getLogger(__name__)

CAPTION_PROMPT = """Describe this image for later retrieval. Reply with ONLY a JSON object:

{"summary": "<one sentence, <=25 words, what this image is and its most salient facts>",
 "visible_text": "<VERBATIM transcription of every piece of legible text, preserving line breaks and numbers exactly. Empty string if there is no text.>",
 "tags": ["<3-6 short keywords>"],
 "has_text": <true|false>,
 "text_density": "<none|sparse|dense>"}

Be exact in visible_text — prices, totals, dates, labels and names must be transcribed character-for-character. No commentary, no markdown fences."""


def _root() -> Path:
    p = Path(settings.state_dir) / "images"
    p.mkdir(parents=True, exist_ok=True)
    return p


def _path_for(sha: str) -> Path:
    d = _root() / sha[:2]
    d.mkdir(parents=True, exist_ok=True)
    return d / f"{sha}.jpg"


def put_blob(data: bytes) -> tuple[str, bool]:
    """Store bytes content-addressed. Returns (sha256, already_existed)."""
    sha = hashlib.sha256(data).hexdigest()
    path = _path_for(sha)
    if path.exists():
        return sha, True
    path.write_bytes(data)
    return sha, False


def get_blob(sha: str) -> bytes | None:
    path = _path_for(sha)
    return path.read_bytes() if path.exists() else None


def blob_b64(sha: str) -> str | None:
    data = get_blob(sha)
    return base64.b64encode(data).decode() if data else None


def vision_model() -> str:
    return settings.vision_model or settings.default_model


async def _vision_call(sha: str, prompt: str) -> str:
    """One image + one prompt -> text. Goes through the same concurrency
    gate as every other Ollama call so captioning can't swamp the box."""
    b64 = blob_b64(sha)
    if b64 is None:
        return ""
    payload = {
        "model": vision_model(),
        "messages": [{"role": "user", "content": prompt, "images": [b64]}],
        "stream": False,
        "keep_alive": "30m",
    }
    async with diag.ollama_gate():
        async with httpx.AsyncClient(timeout=300) as client:
            resp = await client.post(
                f"{settings.ollama_base_url.rstrip('/')}/api/chat", json=payload
            )
            resp.raise_for_status()
            data = resp.json()
    return (data.get("message", {}) or {}).get("content", "") or ""


async def vision_call_many(images_b64: list[str], prompt: str) -> str:
    """Several images + one prompt -> text, on the local vision model. The
    fallback the `video` skill uses when the turn's main model can't see:
    frames are described here and only the text goes back to the main loop."""
    if not images_b64:
        return ""
    payload = {
        "model": vision_model(),
        "messages": [{"role": "user", "content": prompt, "images": images_b64}],
        "stream": False,
        "keep_alive": "30m",
        "options": {"num_ctx": 16384},
    }
    async with diag.ollama_gate():
        async with httpx.AsyncClient(timeout=300) as client:
            resp = await client.post(
                f"{settings.ollama_base_url.rstrip('/')}/api/chat", json=payload
            )
            resp.raise_for_status()
            data = resp.json()
    return (data.get("message", {}) or {}).get("content", "") or ""


def _parse_caption(text: str) -> dict:
    """Tolerant parse of the structured caption — same forgiving pattern as
    the {reply, ops} envelope. Falls back to treating the whole response as
    a prose summary rather than losing the call's work."""
    raw = text.strip()
    if raw.startswith("```"):
        raw = raw.strip("`")
        if raw.lstrip().lower().startswith("json"):
            raw = raw.lstrip()[4:]
    start, end = raw.find("{"), raw.rfind("}")
    if start >= 0 and end > start:
        try:
            obj = json.loads(raw[start : end + 1])
            if isinstance(obj, dict):
                vis = str(obj.get("visible_text", "") or "")
                return {
                    "summary": str(obj.get("summary", "") or "")[:400],
                    "visible_text": vis,
                    "tags": obj.get("tags") if isinstance(obj.get("tags"), list) else [],
                    "has_text": bool(obj.get("has_text", bool(vis.strip()))),
                    "text_density": str(obj.get("text_density", "none") or "none"),
                }
        except (ValueError, TypeError):
            pass
    return {
        "summary": text.strip()[:400],
        "visible_text": "",
        "tags": [],
        "has_text": False,
        "text_density": "none",
    }


async def caption_image(state, sha: str) -> dict | None:
    """Caption once, ever. Skips if the stored record already matches the
    current prompt version, so this is safe to call opportunistically."""
    row = await state.get_image(sha)
    if row and row.get("caption_version") == settings.caption_prompt_version and row.get("summary"):
        return row
    try:
        text = await _vision_call(sha, CAPTION_PROMPT)
    except Exception as e:  # noqa: BLE001 — captioning must never break a request
        log.warning("caption_image(%s) failed: %s", sha[:12], e)
        return None
    if not text:
        return None
    rec = _parse_caption(text)
    await state.set_caption(
        sha,
        summary=rec["summary"],
        visible_text=rec["visible_text"],
        tags=rec["tags"],
        has_text=rec["has_text"],
        text_density=rec["text_density"],
        model=vision_model(),
        version=settings.caption_prompt_version,
    )
    log.info(
        "captioned %s: %r (text=%s/%s)",
        sha[:12], rec["summary"][:60], rec["has_text"], rec["text_density"],
    )
    return await state.get_image(sha)


async def housekeep(state) -> dict:
    """Blob-store hygiene, safe to fire-and-forget:

      1. Orphan GC — blobs on disk with no `images` row (e.g. an upload
         whose DB write failed) are deleted.
      2. Size cap — if the store exceeds image_store_max_mb, evict the
         oldest blobs (LRU by created_at). Caption records and cached
         answers are KEPT: pixels are evictable, text recall is durable,
         so get_image_text keeps working after the JPEG is long gone.
    """
    known = await state.image_shas_by_age()  # oldest first
    known_set = set(known)
    sizes: dict[str, int] = {}
    orphans = 0
    total = 0
    for path in _root().rglob("*.jpg"):
        sha = path.stem
        if sha not in known_set:
            path.unlink(missing_ok=True)
            orphans += 1
            continue
        size = path.stat().st_size
        sizes[sha] = size
        total += size

    evicted = 0
    cap_bytes = settings.image_store_max_mb * 1024 * 1024
    if cap_bytes > 0 and total > cap_bytes:
        for sha in known:  # oldest first
            if total <= cap_bytes:
                break
            size = sizes.get(sha)
            if size is None:
                continue  # blob already gone (previously evicted)
            _path_for(sha).unlink(missing_ok=True)
            total -= size
            evicted += 1

    if orphans or evicted:
        log.info(
            "image housekeep: %d orphan(s) removed, %d evicted, store now %dKB",
            orphans, evicted, total // 1024,
        )
    return {"orphans_removed": orphans, "evicted": evicted, "store_bytes": total}


async def answer_about_image(state, sha: str, question: str) -> str:
    """Targeted vision call for what the cached record can't answer.
    Cached by (sha, normalized question) so repeats are free."""
    norm = " ".join(question.lower().split())
    cached = await state.get_image_answer(sha, norm)
    if cached is not None:
        return cached
    prompt = (
        f"Answer this question about the image as precisely and concisely as "
        f"possible. If the answer isn't visible, say so plainly.\n\nQuestion: {question}"
    )
    try:
        text = (await _vision_call(sha, prompt)).strip()
    except Exception as e:  # noqa: BLE001
        return f"[error] vision call failed: {e}"
    if text:
        await state.set_image_answer(sha, norm, text)
    return text
