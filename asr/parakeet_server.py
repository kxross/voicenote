#!/usr/bin/env python3
"""Parakeet ASR HTTP server (NVIDIA NeMo parakeet-tdt-0.6b-v2, GPU-resident).

Mirrors the faster-whisper wrapper's HTTP contract exactly — `POST /v1/audio`
with a multipart `file` field, returns `{"text": ...}` — so the whisper-api
router and the Android client treat it as a drop-in engine.

The model is loaded once at startup and kept resident in VRAM (a few GB on a
card with 97 GB, so there's no reason to unload it). Transcription itself is a
blocking NeMo call, so it runs in a thread and is serialised with a lock to
keep GPU use orderly; the event loop stays responsive for health checks.
"""

import asyncio
import logging
import os
import tempfile
from contextlib import asynccontextmanager

import uvicorn
from fastapi import FastAPI, File, Form, UploadFile
from fastapi.responses import JSONResponse

logging.basicConfig(level=logging.INFO)
logger = logging.getLogger("parakeet")

MODEL_NAME = os.getenv("PARAKEET_MODEL", "nvidia/parakeet-tdt-0.6b-v2")

_model = None
# NeMo's transcribe() is synchronous and not thread-safe for concurrent GPU
# use; serialise it. The event loop still runs health checks meanwhile.
_infer_lock = asyncio.Lock()


def _disable_expandable_segments() -> None:
    """NeMo's Lhotse dataloader force-enables the CUDA allocator's
    `expandable_segments` at transcribe() time (overriding even
    PYTORCH_CUDA_ALLOC_CONF). Under WSL2 that mode's virtual-memory mapping is
    broken: the first conv then dies with "CUDA driver error: unknown error"
    and every later call with a CUDACachingAllocator `handles_` assert. Turn
    NeMo's switch into a no-op before it can run."""
    import nemo.collections.common.data.lhotse.dataloader as lhotse_dl

    lhotse_dl.maybe_set_cuda_expandable_segments = lambda enabled=True: None


def _warmup() -> None:
    """Transcribe 1 s of silence so a broken GPU path fails at startup (health
    reports "error") instead of on the user's first note."""
    import numpy as np
    import soundfile as sf

    fd, path = tempfile.mkstemp(suffix=".wav")
    os.close(fd)
    try:
        sf.write(path, np.zeros(16000, dtype="float32"), 16000)
        _model.transcribe([path], batch_size=1, verbose=False)
    finally:
        os.unlink(path)


# "loading" until the model is up AND a warm-up transcription has passed.
_status = "loading"


def _load_model() -> None:
    """Blocking model load — called once in an executor at startup."""
    global _model, _status
    _disable_expandable_segments()
    import nemo.collections.asr as nemo_asr
    import torch

    cuda = torch.cuda.is_available()
    logger.info("Loading %s (cuda=%s, arch=%s) ...", MODEL_NAME, cuda, torch.cuda.get_arch_list())
    model = nemo_asr.models.ASRModel.from_pretrained(model_name=MODEL_NAME)
    if cuda:
        model = model.cuda()
    model.eval()
    _model = model
    try:
        _warmup()
        _status = "ok"
        logger.info("Parakeet model ready (warm-up transcription passed).")
    except Exception:  # noqa: BLE001
        _status = "error"
        logger.exception("Parakeet warm-up transcription FAILED — serving 503s")


@asynccontextmanager
async def lifespan(app: FastAPI):
    loop = asyncio.get_event_loop()
    await loop.run_in_executor(None, _load_model)
    yield


app = FastAPI(title="Parakeet ASR", lifespan=lifespan)


async def convert_to_wav(audio_data: bytes, filename: str) -> str:
    """Transcode to 16 kHz mono PCM WAV with ffmpeg (non-blocking)."""
    suffix = os.path.splitext(filename)[-1] or ".m4a"
    inp_fd, inp_path = tempfile.mkstemp(suffix=suffix)
    out_path = inp_path + ".wav"
    try:
        os.write(inp_fd, audio_data)
        os.close(inp_fd)
        proc = await asyncio.create_subprocess_exec(
            "ffmpeg", "-y", "-i", inp_path,
            "-ar", "16000", "-ac", "1", "-acodec", "pcm_s16le",
            out_path,
            stdout=asyncio.subprocess.DEVNULL,
            stderr=asyncio.subprocess.PIPE,
        )
        _, stderr = await proc.communicate()
        if proc.returncode != 0:
            raise RuntimeError(f"ffmpeg failed: {stderr.decode()[-500:]}")
        return out_path
    finally:
        try:
            os.unlink(inp_path)
        except FileNotFoundError:
            pass


def _transcribe_sync(wav_path: str) -> str:
    """Blocking NeMo inference. Tolerates the return-shape differences across
    NeMo versions (list[str], list[Hypothesis], or a (best, all) tuple)."""
    out = _model.transcribe([wav_path], batch_size=1)
    if isinstance(out, tuple):
        out = out[0]
    if not out:
        return ""
    first = out[0]
    text = getattr(first, "text", first)
    return (text or "").strip()


@app.get("/health")
async def health():
    return {"status": _status, "model": MODEL_NAME}


@app.get("/models")
async def models():
    return {"models": [MODEL_NAME, "parakeet-tdt-0.6b-v2", "parakeet"]}


@app.post("/v1/audio")
async def transcribe(
    file: UploadFile = File(...),
    model: str = Form(default="parakeet-tdt-0.6b-v2"),
):
    if _status != "ok":
        return JSONResponse(status_code=503, content={"error": f"parakeet {_status}"})
    wav_path = None
    try:
        audio_data = await file.read()
        logger.info("Received: %s, size: %s bytes", file.filename, f"{len(audio_data):,}")
        if not audio_data:
            return JSONResponse(status_code=400, content={"error": "No audio data"})
        wav_path = await convert_to_wav(audio_data, file.filename or "audio.m4a")
        async with _infer_lock:
            loop = asyncio.get_event_loop()
            text = await loop.run_in_executor(None, _transcribe_sync, wav_path)
        logger.info("Transcription: %r", text[:120])
        return JSONResponse({"text": text})
    except Exception as e:  # noqa: BLE001
        logger.exception("Transcription error")
        return JSONResponse(status_code=500, content={"error": str(e)})
    finally:
        if wav_path:
            try:
                os.unlink(wav_path)
            except FileNotFoundError:
                pass


if __name__ == "__main__":
    uvicorn.run(app, host="0.0.0.0", port=5000, log_level="info")
