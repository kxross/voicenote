"""
VoiceNote receiver server.

This is an OPTIONAL middle layer — you can also point the Android app
directly at your Wyoming whisper wrapper (http://your-host:5000/v1/audio)
and skip this file entirely.

Use this server if you want to add a pipeline on top of transcription:
intent classification, routing to different DBs, logging, etc.

It receives audio from the Android app, forwards to the Wyoming
faster-whisper wrapper, gets back {"text": "..."}, then you do
whatever you want with the transcript.

Install:
    pip install fastapi uvicorn python-multipart httpx

Configure via environment variables:
    VOICENOTE_TOKEN   - auth token Android app must send (optional)
    WHISPER_URL       - your Wyoming wrapper base URL  (default: http://localhost:5000)

Run:
    uvicorn server:app --host 0.0.0.0 --port 9000 --reload

Point the Android app at: http://your-server:9000/v1/audio
"""

import os
import uuid
import logging
from datetime import datetime, timezone
from pathlib import Path

import httpx
from fastapi import FastAPI, File, Form, Header, HTTPException, UploadFile
from fastapi.responses import JSONResponse

# ---------------------------------------------------------------------------
# Config
# ---------------------------------------------------------------------------

VOICENOTE_TOKEN: str = os.environ.get("VOICENOTE_TOKEN", "")

# Base URL of your Wyoming faster-whisper HTTP wrapper (the server you showed me)
WHISPER_URL: str = os.environ.get("WHISPER_URL", "http://localhost:5000")

# Save a copy of every audio file for debugging (set to "" to skip)
AUDIO_SAVE_DIR: str = os.environ.get("AUDIO_SAVE_DIR", "received_audio")

# ---------------------------------------------------------------------------

logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s  %(levelname)-8s  %(message)s",
    datefmt="%H:%M:%S",
)
log = logging.getLogger("voicenote")

app = FastAPI(title="VoiceNote Receiver")

if AUDIO_SAVE_DIR:
    Path(AUDIO_SAVE_DIR).mkdir(exist_ok=True)


# ---------------------------------------------------------------------------
# Receive audio from Android app → forward to Wyoming whisper wrapper
# ---------------------------------------------------------------------------

@app.post("/v1/audio")
async def receive_audio(
    file: UploadFile = File(...),
    model: str = Form(default="faster-whisper-base"),
    authorization: str = Header(default=""),
):
    # Auth
    if VOICENOTE_TOKEN:
        token = authorization.removeprefix("Bearer ").strip()
        if token != VOICENOTE_TOKEN:
            raise HTTPException(status_code=401, detail="Unauthorized")

    content: bytes = await file.read()
    filename = f"{datetime.now(timezone.utc).strftime('%Y%m%d_%H%M%S')}_{uuid.uuid4().hex[:6]}.m4a"
    log.info(f"Received  {filename}  ({len(content)/1024:.1f} KB)")

    # Optionally persist
    if AUDIO_SAVE_DIR:
        Path(AUDIO_SAVE_DIR, filename).write_bytes(content)

    # Forward to Wyoming wrapper — same multipart shape it expects
    transcript = await forward_to_whisper(content, filename, model)

    if transcript is None:
        return JSONResponse(status_code=500, content={"error": "transcription failed"})

    log.info(f"Transcript: {transcript!r}")

    # -----------------------------------------------------------------------
    # YOUR PIPELINE GOES HERE
    # e.g. classify intent, route to the right DB, push a reminder, etc.
    # -----------------------------------------------------------------------

    return JSONResponse({"text": transcript})


async def forward_to_whisper(audio_bytes: bytes, filename: str, model: str) -> str | None:
    """POST to the Wyoming faster-whisper wrapper and return transcript text."""
    url = WHISPER_URL.rstrip("/") + "/v1/audio"
    try:
        async with httpx.AsyncClient(timeout=60.0) as client:
            r = await client.post(
                url,
                files={"file": (filename, audio_bytes, "audio/mp4")},
                data={"model": model},
            )
        if r.status_code != 200:
            log.error(f"Whisper wrapper {r.status_code}: {r.text[:200]}")
            return None
        return r.json().get("text", "").strip()
    except httpx.ConnectError:
        log.error(f"Cannot reach whisper wrapper at {url} — is it running?")
        return None
    except Exception as e:
        log.error(f"Whisper error: {e}")
        return None


# ---------------------------------------------------------------------------
# Health
# ---------------------------------------------------------------------------

@app.get("/health")
async def health():
    whisper_ok = False
    try:
        async with httpx.AsyncClient(timeout=3.0) as client:
            r = await client.get(WHISPER_URL.rstrip("/") + "/health")
            whisper_ok = r.status_code == 200
    except Exception:
        pass
    return {"status": "ok", "whisper_url": WHISPER_URL, "whisper_reachable": whisper_ok}


if __name__ == "__main__":
    import uvicorn
    uvicorn.run(app, host="0.0.0.0", port=9000, log_level="info")
