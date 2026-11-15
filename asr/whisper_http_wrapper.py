#!/usr/bin/env python3
"""
Wyoming faster-whisper HTTP wrapper — fixed version.

Fixes vs original:
  1. ffmpeg runs via asyncio.create_subprocess_exec (non-blocking, doesn't
     stall the event loop)
  2. Audio is streamed to Wyoming with writer.drain() after every chunk so
     the write buffer never accumulates the entire file in memory
  3. Temp files are always cleaned up, even on errors during conversion
  4. Connection semaphore limits concurrent Wyoming connections so the
     whisper container isn't hammered with parallel requests it can't handle
"""

import asyncio
import os
import tempfile
from fastapi import FastAPI, UploadFile, File, Form
from fastapi.responses import JSONResponse
import uvicorn
import logging
import wave

import httpx

from wyoming.asr import Transcribe, Transcript
from wyoming.audio import AudioStart, AudioStop, wav_to_chunks
from wyoming.event import async_read_event, async_write_event

logging.basicConfig(level=logging.INFO)
logger = logging.getLogger(__name__)

app = FastAPI(title="Transcription HTTP Router")

WYOMING_HOST = os.getenv("WYOMING_HOST", "faster-whisper")
WYOMING_PORT = int(os.getenv("WYOMING_PORT", 10300))
SAMPLES_PER_CHUNK = 4096   # larger chunks = fewer round-trips, less buffer pressure

# Parakeet (NeMo, GPU) is the default engine. Only an explicit
# `model=faster-whisper*` falls back to the Wyoming/CPU path below.
PARAKEET_URL = os.getenv("PARAKEET_URL", "http://parakeet-api:5000/v1/audio")

# Limit concurrent transcriptions — faster-whisper is single-threaded anyway,
# queueing here is better than letting it queue inside the container
_wyoming_sem = asyncio.Semaphore(1)


async def convert_to_wav(audio_data: bytes, filename: str) -> str:
    """
    Convert audio to 16kHz mono WAV using asyncio subprocess.
    Non-blocking: yields control to the event loop while ffmpeg runs.
    Returns path to the WAV file (caller must delete it).
    """
    suffix = os.path.splitext(filename)[-1] or '.m4a'
    inp_fd, inp_path = tempfile.mkstemp(suffix=suffix)
    out_path = inp_path + '.wav'

    try:
        os.write(inp_fd, audio_data)
        os.close(inp_fd)

        proc = await asyncio.create_subprocess_exec(
            'ffmpeg', '-y', '-i', inp_path,
            '-ar', '16000', '-ac', '1',
            '-acodec', 'pcm_s16le',
            out_path,
            stdout=asyncio.subprocess.DEVNULL,
            stderr=asyncio.subprocess.PIPE,   # capture stderr for error messages only
        )
        _, stderr = await proc.communicate()

        if proc.returncode != 0:
            raise RuntimeError(f"ffmpeg failed: {stderr.decode()[-500:]}")

        return out_path

    finally:
        # Always clean up the input temp file
        try:
            os.unlink(inp_path)
        except FileNotFoundError:
            pass


async def transcribe_wyoming(wav_path: str) -> str:
    """
    Stream audio to the Wyoming server and return the transcript.
    Uses a semaphore to serialise requests — faster-whisper processes
    one file at a time anyway, so parallelism here just wastes memory.
    """
    async with _wyoming_sem:
        reader, writer = await asyncio.open_connection(WYOMING_HOST, WYOMING_PORT)
        try:
            await async_write_event(Transcribe().event(), writer)
            await async_write_event(
                AudioStart(rate=16000, width=2, channels=1).event(), writer
            )

            # Stream chunks with drain() so the write buffer stays small
            with wave.open(wav_path, 'rb') as wav_file:
                for chunk in wav_to_chunks(wav_file, SAMPLES_PER_CHUNK):
                    await async_write_event(chunk.event(), writer)
                    await writer.drain()   # <-- THE KEY FIX: flush after every chunk

            await async_write_event(AudioStop().event(), writer)
            await writer.drain()

            logger.info("Waiting for transcript...")

            while True:
                event = await asyncio.wait_for(
                    async_read_event(reader), timeout=60.0
                )
                if event is None:
                    break
                logger.info(f"Wyoming event: {event.type}")
                if Transcript.is_type(event.type):
                    return Transcript.from_event(event).text

            return ""

        except asyncio.TimeoutError:
            raise RuntimeError("Timeout waiting for transcription")
        finally:
            writer.close()
            try:
                await writer.wait_closed()
            except Exception:
                pass


async def transcribe_parakeet(audio_data: bytes, filename: str, model: str) -> str:
    """Forward the raw audio to the Parakeet GPU service, which does its own
    ffmpeg transcode, and return its transcript. Raw bytes are forwarded as-is
    (no local conversion) so there's no double transcode."""
    async with httpx.AsyncClient(timeout=120.0) as client:
        resp = await client.post(
            PARAKEET_URL,
            files={"file": (filename, audio_data, "application/octet-stream")},
            data={"model": model},
        )
        resp.raise_for_status()
        return resp.json().get("text", "")


PARAKEET_HEALTH_URL = PARAKEET_URL.rsplit("/v1/", 1)[0] + "/health"


@app.get("/health")
async def health():
    """Router health + which engine a default request would use right now."""
    parakeet = "down"
    try:
        async with httpx.AsyncClient(timeout=3.0) as client:
            r = await client.get(PARAKEET_HEALTH_URL)
            parakeet = r.json().get("status", "unknown") if r.status_code == 200 else f"http {r.status_code}"
    except Exception:  # noqa: BLE001
        pass
    return {
        "status": "ok",
        "parakeet": parakeet,   # "ok" | "loading" | "down"
        "default_engine": "parakeet" if parakeet == "ok" else "faster-whisper (fallback)",
    }


@app.post("/v1/audio")
async def transcribe(
    file: UploadFile = File(...),
    model: str = Form(default="parakeet-tdt-0.6b-v2"),
):
    wav_path = None
    try:
        audio_data = await file.read()
        logger.info(f"Received: {file.filename}, size: {len(audio_data):,} bytes, model={model}")

        if not audio_data:
            return JSONResponse(status_code=400, content={"error": "No audio data"})

        # Engine routing: explicit faster-whisper -> Wyoming/CPU; everything
        # else (including the default) -> Parakeet/GPU. If Parakeet is down or
        # still loading its model, fall back to faster-whisper rather than
        # failing the note — the response's `engine` says which one answered.
        name = file.filename or "audio.m4a"
        engine = "faster-whisper"
        text = None
        if not model.startswith("faster-whisper"):
            try:
                text = await transcribe_parakeet(audio_data, name, model)
                engine = "parakeet"
            except Exception as e:  # noqa: BLE001
                logger.warning(f"Parakeet unavailable ({e!r}); falling back to faster-whisper")
                engine = "faster-whisper (fallback)"
        if text is None:
            wav_path = await convert_to_wav(audio_data, name)
            text = await transcribe_wyoming(wav_path)

        logger.info(f"Transcription [{engine}] ({model}): '{text}'")
        return JSONResponse({"text": text, "engine": engine})

    except Exception as e:
        logger.error(f"Transcription error: {e}")
        return JSONResponse(status_code=500, content={"error": str(e)})
    finally:
        if wav_path:
            try:
                os.unlink(wav_path)
            except FileNotFoundError:
                pass


@app.get("/models")
async def models():
    return JSONResponse({"models": ["parakeet-tdt-0.6b-v2", "faster-whisper-base"]})


if __name__ == "__main__":
    uvicorn.run(app, host="0.0.0.0", port=5000, log_level="info")
