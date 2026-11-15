"""System One API (/v1/systemone*) — a TypeSafe-conforming typed-decision
surface backed by Kev (self-hosted, default) or Jev (TypeSafe-hosted).
See app/systemone.py for the contract and routing rules.

    POST /v1/systemone            the decision call (many questions per request)
    POST /v1/systemone/permute    one choice question under N option orders
    POST /v1/systemone/separate   each question in its own forward pass
    GET  /v1/systemone/models     the upstream's model cards
    GET  /v1/systemone/health     which backend is active and whether it answers

Bodies and upstream status codes pass through unchanged, so a System One
client pointed at ``<gateway>/`` behaves exactly as against Kev or Jev.
Api-key gated like the rest of /v1/*.
"""

from __future__ import annotations

import httpx
from fastapi import APIRouter, Depends, Header, HTTPException, Request
from fastapi.responses import JSONResponse

from app import diagnostics as diag
from app import systemone
from app.config import settings


async def require_api_key(authorization: str | None = Header(default=None)) -> None:
    if not settings.gateway_api_key:
        return
    if authorization != f"Bearer {settings.gateway_api_key}":
        raise HTTPException(status_code=401, detail="invalid or missing API key")


router = APIRouter(prefix="/v1/systemone", dependencies=[Depends(require_api_key)])


async def _relay(request: Request, path: str, validate: bool) -> JSONResponse:
    try:
        body = await request.json()
    except ValueError:
        return JSONResponse({"detail": "body must be valid JSON"}, status_code=400)

    # One /diag trace per HTTP decision; the forwarder records the upstream
    # round-trip on it, tagged kev/jev.
    model_hint = body.get("model") if isinstance(body, dict) else None
    try:
        provider = systemone.upstream_for(model_hint).provider
    except systemone.SystemOneError as e:
        return JSONResponse({"detail": e.detail}, status_code=e.status)
    diag.start_trace(trigger="systemone", model=f"systemone:{provider}", session=None, request=body)

    state = request.app.state.state
    try:
        if validate:
            systemone.validate(body)
        status, parsed, headers, _ = await systemone.forward(path, body)
    except systemone.SystemOneError as e:
        await diag.finish_trace(state, "error", response={"error": e.detail})
        return JSONResponse({"detail": e.detail}, status_code=e.status)
    await diag.finish_trace(state, "ok" if status < 400 else "error", response=parsed)
    return JSONResponse(parsed, status_code=status, headers=headers)


@router.post("")
async def systemone_decide(request: Request) -> JSONResponse:
    return await _relay(request, "/v1/systemone", validate=True)


@router.post("/permute")
async def systemone_permute(request: Request) -> JSONResponse:
    # Shape differs (one choice question + n_perm); let the upstream validate.
    return await _relay(request, "/v1/systemone/permute", validate=False)


@router.post("/separate")
async def systemone_separate(request: Request) -> JSONResponse:
    return await _relay(request, "/v1/systemone/separate", validate=True)


@router.get("/models")
async def systemone_models(model: str | None = None) -> JSONResponse:
    """The upstream's model cards. `?model=jev-…` lists the backend that model
    name would route to; otherwise the configured default."""
    try:
        up = systemone.upstream_for(model)
        async with httpx.AsyncClient(timeout=settings.systemone_timeout_seconds) as client:
            resp = await client.get(f"{up.base_url}/v1/models", headers=systemone._headers(up))
    except systemone.SystemOneError as e:
        return JSONResponse({"detail": e.detail}, status_code=e.status)
    except httpx.HTTPError as e:
        return JSONResponse({"detail": f"upstream unreachable: {e.__class__.__name__}"}, status_code=503)
    try:
        parsed = resp.json()
    except ValueError:
        parsed = {"detail": resp.text[:2000]}
    return JSONResponse(parsed, status_code=resp.status_code, headers={"x-systemone-provider": up.provider})


@router.get("/health")
async def systemone_health() -> dict:
    """Configured backend + a live probe of it. Never raises."""
    out: dict = {
        "enabled": systemone.enabled(),
        "provider": settings.systemone_provider or None,
        "backends": {
            "kev": {"configured": bool(settings.kev_base_url), "model": settings.kev_model},
            "jev": {"configured": bool(settings.jev_base_url and settings.jev_api_key), "model": settings.jev_model or None},
        },
    }
    if not systemone.enabled():
        return out
    try:
        up = systemone.upstream_for(None)
        async with httpx.AsyncClient(timeout=10) as client:
            resp = await client.get(f"{up.base_url}/v1/models", headers=systemone._headers(up))
        out["reachable"] = resp.status_code == 200
        out["status"] = resp.status_code
        if resp.status_code == 200:
            data = resp.json()
            # System One lists {"models":[{"name":…}]}; tolerate OpenAI-style
            # {"data":[{"id":…}]} from a compatible router too.
            items = data.get("models") or data.get("data") or []
            out["models"] = [m.get("name") or m.get("id") for m in items if isinstance(m, dict)]
    except Exception as e:  # noqa: BLE001 — health must not raise
        out["reachable"] = False
        out["error"] = e.__class__.__name__
    return out
