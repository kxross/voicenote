"""Content-addressed store for video attachments (sibling of images.py).

Blobs live at state/media/<aa>/<sha256>.<ext>, keyed by content hash, so the
same clip attached twice costs one file. Uploads are streamed to disk while
hashing — a 300 MB video never sits in memory — and checked by magic bytes, not
just the client's declared content type.

Unlike images, the gateway never decodes video itself: all frame work happens
in the sandbox (`vn-video`, driven by the `video` skill), which receives the
file through vmmd's streamed upload. What stays here is the bytes, a little
metadata from the phone (duration, size), and a short text `summary` written
after the agent has watched it — the recall path for later turns, mirroring
image captions.
"""

from __future__ import annotations

import hashlib
import logging
import os
import uuid
from pathlib import Path

from app.config import settings

log = logging.getLogger(__name__)

# Declared/sniffed type -> stored extension. MP4-family containers are all
# "ftyp" boxes; QuickTime is the iPhone/some-Android .mov.
EXT_FOR = {
    "video/mp4": "mp4",
    "video/quicktime": "mov",
    "video/3gpp": "3gp",
    "video/webm": "webm",
    "video/x-matroska": "mkv",
}
_CHUNK = 1 << 20


class MediaError(ValueError):
    """A rejected upload (too big, not a video). Surfaced as a 4xx."""


def _root() -> Path:
    p = Path(settings.state_dir) / "media"
    p.mkdir(parents=True, exist_ok=True)
    return p


def path_for(sha: str, ext: str) -> Path:
    d = _root() / sha[:2]
    d.mkdir(parents=True, exist_ok=True)
    return d / f"{sha}.{ext}"


def find(sha: str) -> Path | None:
    """The stored file for a sha (any extension), or None."""
    if not sha or len(sha) < 8:
        return None
    d = _root() / sha[:2]
    if not d.exists():
        return None
    for p in d.glob(f"{sha}.*"):
        if not p.name.endswith(".part"):
            return p
    return None


def sniff(head: bytes) -> str | None:
    """The real container type from the first bytes, or None if not video."""
    if len(head) >= 12 and head[4:8] == b"ftyp":
        brand = head[8:12]
        if brand == b"qt  ":
            return "video/quicktime"
        if brand.startswith(b"3g"):
            return "video/3gpp"
        return "video/mp4"
    if head[:4] == b"\x1a\x45\xdf\xa3":     # EBML: WebM / Matroska
        return "video/webm" if b"webm" in head[:64] else "video/x-matroska"
    return None


async def put_stream(upload, max_bytes: int) -> tuple[str, str, int, bool]:
    """Stream an UploadFile to disk, hashing as it goes. Returns
    (sha256, mime, bytes, already_existed). Raises MediaError on a non-video
    or an oversized upload (and leaves nothing behind)."""
    tmp = _root() / f"incoming-{uuid.uuid4().hex}.part"
    digest = hashlib.sha256()
    total = 0
    mime: str | None = None
    try:
        with open(tmp, "wb") as out:
            while True:
                chunk = await upload.read(_CHUNK)
                if not chunk:
                    break
                if mime is None:
                    mime = sniff(chunk[:64])
                    if mime is None:
                        raise MediaError("not a supported video (expected MP4, MOV, 3GP or WebM)")
                total += len(chunk)
                if total > max_bytes:
                    raise MediaError(f"video is larger than {max_bytes // (1024 * 1024)} MB")
                digest.update(chunk)
                out.write(chunk)
        if total == 0 or mime is None:
            raise MediaError("empty upload")
        sha = digest.hexdigest()
        existing = find(sha)
        if existing is not None:
            return sha, mime, total, True
        os.replace(tmp, path_for(sha, EXT_FOR.get(mime, "mp4")))
        return sha, mime, total, False
    finally:
        if tmp.exists():
            tmp.unlink(missing_ok=True)


async def housekeep(state) -> None:
    """Drop orphan files, then evict oldest videos past media_store_max_mb.
    Rows (and their summaries) stay, so text recall survives eviction."""
    known = await state.media_shas_by_age()
    known_set = set(known)
    for p in _root().rglob("*"):
        if p.is_file() and p.name.startswith("incoming-") and p.suffix == ".part":
            continue   # an upload in flight
        if p.is_file() and p.stem not in known_set:
            p.unlink(missing_ok=True)
    cap = settings.media_store_max_mb * 1024 * 1024
    if cap <= 0:
        return
    files = [(sha, find(sha)) for sha in known]
    files = [(sha, p) for sha, p in files if p is not None]
    used = sum(p.stat().st_size for _, p in files)
    for sha, p in files:                      # oldest first
        if used <= cap:
            break
        used -= p.stat().st_size
        p.unlink(missing_ok=True)
        log.info("media: evicted %s (store over %d MB)", sha[:12], settings.media_store_max_mb)
