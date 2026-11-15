"""vmmd HTTP API. Backend-agnostic: the same routes drive Firecracker and
Docker sandboxes, selected per-request (or by default_backend). The approval
gate + /approvals queue land in Stage 4; exec/files are wired here since the
Manager already delegates them to the active backend.

Run on the WSL host:
    cd vmm && uvicorn vmmd.main:app --host 0.0.0.0 --port 5002
"""

from __future__ import annotations

import asyncio
import base64
import hashlib
import json
import logging
from contextlib import asynccontextmanager

from fastapi import FastAPI, HTTPException, Request
from fastapi.responses import Response, StreamingResponse
from pydantic import BaseModel

from .approvals import ApprovalGate
from .backends import build_all
from .config import APPROVAL_MODES, Config
from .review import screen
from .manager import Manager, VMError
from .reaper import Reaper
from .util import SandboxError

logging.basicConfig(level=logging.INFO, format="%(asctime)s %(name)s %(levelname)s %(message)s")
log = logging.getLogger("vmmd")


class CreateVM(BaseModel):
    backend: str | None = None
    profile: str | None = None
    vcpus: int | None = None
    mem_mib: int | None = None
    disk_gb: int | None = None
    ttl_minutes: int | None = None
    # Scope key: when set, POST /vms is idempotent — returns the live sandbox
    # for this key if one exists, else creates it tagged with the key.
    key: str | None = None


class ExecReq(BaseModel):
    command: str
    timeout: int | None = None
    # Optional context for smart-mode screening, supplied by the gateway:
    # {"user_request", "purpose", "files": {path: code the command runs}}.
    # Advisory only — it never bypasses the gate.
    context: dict | None = None


class PutFileReq(BaseModel):
    path: str
    content_b64: str


class ApprovalMode(BaseModel):
    mode: str


@asynccontextmanager
async def lifespan(app: FastAPI):
    cfg = Config.load()

    # Build every backend, keep only those whose preflight passes. The daemon
    # still runs if some are unavailable (e.g. no kvm -> docker only).
    status: dict[str, dict] = {}
    ready: dict[str, object] = {}
    for be in build_all(cfg):
        problems = await be.preflight()
        status[be.name] = {"ready": not problems, "problems": problems}
        if not problems:
            ready[be.name] = be
    log.info("backends ready: %s", ", ".join(ready) or "(none)")
    for name, st in status.items():
        if not st["ready"]:
            log.warning("backend '%s' unavailable:\n  - %s", name, "\n  - ".join(st["problems"]))

    default_backend = cfg.default_backend if cfg.default_backend in ready else (
        next(iter(ready), cfg.default_backend)
    )
    manager = Manager(cfg, ready, default_backend)
    await manager.reconcile()
    reaper = Reaper(manager)
    reaper.start()

    app.state.cfg = cfg
    app.state.manager = manager
    app.state.reaper = reaper
    app.state.gate = ApprovalGate()
    app.state.backend_status = status
    app.state.default_backend = default_backend
    try:
        yield
    finally:
        await reaper.stop()
        await manager.shutdown()


app = FastAPI(title="vmmd", version="0.2.0", lifespan=lifespan)


@app.get("/healthz")
async def healthz() -> dict:
    return {
        "status": "ok",
        "approval_mode": app.state.cfg.approval_mode,
        "review": {"configured": bool(app.state.cfg.review.url),
                   "malicious_threshold": app.state.cfg.review.malicious_threshold},
        "default_backend": app.state.default_backend,
        "backends": app.state.backend_status,
        "vms": len(app.state.manager.list()),
        "limits": {
            "max_vms": app.state.cfg.limits.max_vms,
            "max_total_mem_mib": app.state.cfg.limits.max_total_mem_mib,
        },
    }


# ---- VM lifecycle ---------------------------------------------------------

@app.post("/vms")
async def create_vm(body: CreateVM) -> dict:
    try:
        rec = await app.state.manager.create(**body.model_dump(exclude_none=True))
    except VMError as e:
        raise HTTPException(status_code=400, detail=str(e))
    return rec.public()


@app.get("/vms")
async def list_vms() -> dict:
    return {"vms": [r.public() for r in app.state.manager.list()]}


@app.get("/vms/{vm_id}")
async def get_vm(vm_id: str) -> dict:
    rec = app.state.manager.get(vm_id)
    if rec is None:
        raise HTTPException(status_code=404, detail="no such vm")
    return rec.public()


@app.delete("/vms/{vm_id}")
async def destroy_vm(vm_id: str) -> dict:
    try:
        await app.state.manager.destroy(vm_id)
    except VMError as e:
        raise HTTPException(status_code=404, detail=str(e))
    return {"status": "ok"}


@app.post("/vms/{vm_id}/touch")
async def touch_vm(vm_id: str) -> dict:
    if app.state.manager.get(vm_id) is None:
        raise HTTPException(status_code=404, detail="no such vm")
    app.state.manager.touch(vm_id)
    return {"status": "ok"}


# ---- exec + files (approval gate arrives in Stage 4) ----------------------

@app.post("/vms/{vm_id}/exec")
async def exec_cmd(vm_id: str, body: ExecReq) -> dict:
    cfg = app.state.cfg
    if app.state.manager.get(vm_id) is None:
        raise HTTPException(status_code=404, detail="no such vm")
    timeout = min(body.timeout or cfg.limits.exec_timeout_default, cfg.limits.exec_timeout_max)

    # Smart mode: screen the command (and any code it runs) with System One;
    # only flagged commands are held. Manual mode holds everything. Either way
    # a held command blocks here until a human decides (or it times out), so
    # the caller just sees a slow request.
    review = None
    if cfg.approval_mode == "smart":
        review = await screen(cfg.review, body.command, body.context)
    if cfg.approval_mode == "manual" or (review is not None and review["flagged"]):
        appr = app.state.gate.request(vm_id, body.command, review=review)
        decision = await app.state.gate.wait(appr, timeout=cfg.limits.approval_timeout)
        if decision != "approved":
            why = f" — {review['reason']}" if review else ""
            return {"stdout": "", "stderr": f"[command {decision} by approver{why}]",
                    "exit_code": 126, "truncated": False, "approval": decision,
                    "review": review}

    try:
        res = await app.state.manager.exec(vm_id, body.command, timeout)
    except VMError as e:
        raise HTTPException(status_code=404, detail=str(e))
    return res.as_dict()


# ---- approvals ------------------------------------------------------------

@app.get("/approvals")
async def list_approvals() -> dict:
    return {"pending": app.state.gate.list()}


@app.post("/approvals/{approval_id}/approve")
async def approve(approval_id: str) -> dict:
    if not app.state.gate.decide(approval_id, True):
        raise HTTPException(status_code=404, detail="no such pending approval")
    return {"status": "approved"}


@app.post("/approvals/{approval_id}/deny")
async def deny(approval_id: str) -> dict:
    if not app.state.gate.decide(approval_id, False):
        raise HTTPException(status_code=404, detail="no such pending approval")
    return {"status": "denied"}


@app.get("/approvals/stream")
async def approvals_stream():
    async def gen():
        q = app.state.gate.subscribe()
        try:
            yield f"data: {json.dumps({'type': 'snapshot', 'pending': app.state.gate.list()})}\n\n"
            while True:
                try:
                    msg = await asyncio.wait_for(q.get(), timeout=15)
                    yield f"data: {json.dumps(msg)}\n\n"
                except asyncio.TimeoutError:
                    yield ": keepalive\n\n"
        finally:
            app.state.gate.unsubscribe(q)

    return StreamingResponse(gen(), media_type="text/event-stream")


@app.put("/vms/{vm_id}/file")
async def put_file(vm_id: str, body: PutFileReq) -> dict:
    try:
        data = base64.b64decode(body.content_b64)
        await app.state.manager.put_file(vm_id, body.path, data)
    except VMError as e:
        raise HTTPException(status_code=404, detail=str(e))
    return {"status": "ok", "bytes": len(data)}


@app.get("/vms/{vm_id}/file")
async def get_file(vm_id: str, path: str) -> dict:
    try:
        data = await app.state.manager.get_file(vm_id, path)
    except VMError as e:
        raise HTTPException(status_code=404, detail=str(e))
    return {"path": path, "content_b64": base64.b64encode(data).decode(), "bytes": len(data)}


# Binary I/O for media (videos in, frames out). Like PUT/GET /file these move
# bytes, not commands, so they aren't approval-gated; unlike them they stream
# raw bodies (no base64/JSON, no whole-file buffering) and read single files
# without a tar wrapper.
_MAX_UPLOAD = 2 * 1024 ** 3      # hard ceiling; the gateway enforces its own cap
_MAX_RAW = 64 * 1024 ** 2


def _sandbox_path(path: str) -> str:
    if not path.startswith("/") or "\x00" in path:
        raise HTTPException(status_code=400, detail="path must be absolute")
    return path


@app.put("/vms/{vm_id}/upload")
async def upload_file(vm_id: str, path: str, request: Request) -> dict:
    dest = _sandbox_path(path)
    digest = hashlib.sha256()
    seen = 0

    async def chunks():
        nonlocal seen
        async for chunk in request.stream():
            seen += len(chunk)
            if seen > _MAX_UPLOAD:
                raise HTTPException(status_code=413, detail="upload too large")
            digest.update(chunk)
            yield chunk

    try:
        n = await app.state.manager.put_stream(vm_id, dest, chunks())
    except VMError as e:
        raise HTTPException(status_code=404, detail=str(e))
    except SandboxError as e:
        raise HTTPException(status_code=400, detail=str(e))
    return {"status": "ok", "path": dest, "bytes": n, "sha256": digest.hexdigest()}


@app.get("/vms/{vm_id}/raw")
async def read_raw(vm_id: str, path: str) -> Response:
    try:
        data = await app.state.manager.get_raw(vm_id, _sandbox_path(path), _MAX_RAW)
    except VMError as e:
        raise HTTPException(status_code=404, detail=str(e))
    except SandboxError as e:
        raise HTTPException(status_code=404, detail=str(e))
    return Response(content=data, media_type="application/octet-stream")


# ---- approval mode --------------------------------------------------------

@app.get("/config/approval-mode")
async def get_approval_mode() -> dict:
    return {"mode": app.state.cfg.approval_mode}


@app.put("/config/approval-mode")
async def set_approval_mode(body: ApprovalMode) -> dict:
    if body.mode not in APPROVAL_MODES:
        raise HTTPException(status_code=400, detail=f"mode must be one of {', '.join(APPROVAL_MODES)}")
    app.state.cfg.approval_mode = body.mode
    return {"mode": body.mode}
