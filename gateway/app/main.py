"""Client-facing OpenAI-compatible gateway (Stack 3).

    Client (any OpenAI SDK) --> /v1/chat/completions --> AgentRegistry
        --> BaseAgentRuntime.arun()/.astream() --> response_adapter
        --> valid chat.completion / chat.completion.chunk back to client

The client only ever sees a `model` name — that name is an *agent id*,
not a real LLM. Which tools, prompt, framework, and underlying model back
that agent is entirely a server-side registry lookup (app/registry.py,
populated by app/agents/definitions.py).

Tool calls (web search, code exec, etc.) happen *inside* a runtime and are
never surfaced to the client as OpenAI `tool_calls` deltas — the client
just gets a normal assistant message once the agent is done. This sidesteps
having to replicate OpenAI's tool-call streaming wire format exactly.

Beyond the OpenAI surface, this gateway also runs agents on schedules
(app/scheduler.py) and pushes their replies to a client-visible queue
(app/push/pusher.py + app/state.py). The Android client polls
`GET /v1/pending/{session}` to drain it. See docs/FCM_SETUP.md for the
future FCM (fast-lane) alternative.

Run:
    uvicorn app.main:app --reload --port 8000
"""

import os
from contextlib import asynccontextmanager

import asyncio
import json as _json
import logging
from pathlib import Path

import httpx
from fastapi import Depends, FastAPI, File, Form, Header, HTTPException, Response, UploadFile
from fastapi.responses import FileResponse, JSONResponse, StreamingResponse
from pydantic import BaseModel

from app import diagnostics as diag
from app import images
from app import media
from app.config import settings
from app.delivery import parse_envelope
from app.push.pusher import FcmPusher, PollingPusher
from app.registry import registry
from app.response_adapter import stream_chat_completion, to_chat_completion
from app import scheduler as scheduler_mod
from app.scheduler import Scheduler, load_yaml_schedules
from app.schemas import (
    ChatCompletionRequest,
    ChatCompletionResponse,
    ModelCard,
    ModelList,
)
from app.state import State

# Skills must be loaded BEFORE agents are built — agent constructors resolve
# their `skills=[...]` scope against `skill_registry`.
from app.skills import load_all_skills

load_all_skills()

# The registry is composed from code built-ins + the DB overlay by
# RegistryService.rebuild(), called in the lifespan once State is open.
from app import registry_service  # noqa: E402
from app.routes_registry import router as registry_router  # noqa: E402
from app.routes_wiki import router as wiki_router  # noqa: E402
from app.routes_systemone import router as systemone_router  # noqa: E402


STATE_DB_PATH = os.environ.get("STATE_DB_PATH", "state/state.db")
SCHEDULES_PATH = os.environ.get("SCHEDULES_PATH", "schedules.yml")


@asynccontextmanager
async def lifespan(app: FastAPI):
    state = State(STATE_DB_PATH)
    await state.open()
    # Compose the live agent registry = code built-ins + DB overlay.
    await registry_service.rebuild(state)
    pushers = [PollingPusher(state), FcmPusher()]
    scheduler = Scheduler(state, pushers, registry)
    # One-time seed: import schedules.yml only when the DB has no schedules yet.
    # After that the DB (editable via /v1/schedules) is the source of truth.
    if await state.schedules_empty():
        for s in load_yaml_schedules(SCHEDULES_PATH):
            try:
                await state.upsert_schedule(s)
            except Exception:  # noqa: BLE001 — skip a malformed seed entry
                pass
    await scheduler.load_from_db()
    scheduler.start()

    app.state.state = state
    app.state.pushers = pushers
    app.state.scheduler = scheduler
    # registry_service.rebuild (above) already wired app_state onto every
    # skill-capable runtime; admin edits re-run rebuild, keeping that current.

    # Blob-store hygiene on boot: orphan GC + LRU size-cap eviction.
    asyncio.create_task(_safe_housekeep())
    try:
        yield
    finally:
        scheduler.shutdown()
        await state.close()


log = logging.getLogger(__name__)

app = FastAPI(title="agent-gateway", version="0.1.0", lifespan=lifespan)

# Sessions/threads with an async interactive turn currently running. A second
# submit for the same (session, thread) while one is in flight is a retry — we
# answer "in_progress" and start nothing, so "keep trying" can't spawn a second
# competing run (the whole point: a long turn delivers once, via the queue).
_async_inflight: set[tuple[str, int]] = set()

# Registry admin API (/v1/registry/*) backing the /admin web UI.
app.include_router(registry_router)
# Learned-wiki API (/v1/wiki/*): proposals + approved entries.
app.include_router(wiki_router)
# System One typed-decision API (/v1/systemone*): Kev (self-hosted) or Jev.
app.include_router(systemone_router)


async def require_api_key(authorization: str | None = Header(default=None)) -> None:
    if not settings.gateway_api_key:
        return  # auth disabled — fine for local dev
    expected = f"Bearer {settings.gateway_api_key}"
    if authorization != expected:
        raise HTTPException(status_code=401, detail="invalid or missing API key")


@app.get("/v1/models", response_model=ModelList)
async def list_models(_: None = Depends(require_api_key)) -> ModelList:
    return ModelList(
        data=[ModelCard(id=agent.id) for agent in registry.list()]
    )


@app.get("/v1/llm-models")
async def list_llm_models(_: None = Depends(require_api_key)) -> dict:
    """The underlying LLMs across every configured provider (distinct from
    /v1/models, which lists agents). The client shows these so the user can
    pick which model the assistant uses; `default` is what runs when no
    override is sent. Each entry carries its `provider`, which the client
    echoes back in the request envelope."""
    from app import model_catalog
    return {
        "default": settings.default_model,
        "default_provider": model_catalog.OLLAMA,
        "models": await model_catalog.list_models(),
    }


@app.post("/v1/chat/completions")
async def chat_completions(
    req: ChatCompletionRequest,
    _: None = Depends(require_api_key),
    x_trigger: str | None = Header(default=None),
    x_async: str | None = Header(default=None),
    x_thread_id: str | None = Header(default=None),
    x_notification_title: str | None = Header(default=None),
):
    agent = registry.get(req.model)
    if agent is None:
        known = ", ".join(a.id for a in registry.list()) or "(none registered)"
        raise HTTPException(
            status_code=404,
            detail=f"unknown model/agent '{req.model}'. Registered agents: {known}",
        )

    # `user` doubles as a session/memory key. Swap for a dedicated header
    # (e.g. X-Session-Id) if you'd rather not overload that field.
    session_id = req.user
    # Trace label / redaction key (voice / geofence / sms / schedule / http).
    trigger = x_trigger or "http"

    # Async submit: a long turn can't hold a 5-min client socket, so accept it,
    # run it in the background, and deliver the result through the same queue
    # the scheduler uses (the client polls GET /v1/pending). Returns at once;
    # the turn's length no longer touches any client/socket/worker timeout.
    if x_async:
        return await _submit_async(
            req, agent, session_id, trigger, x_thread_id, x_notification_title
        )

    if req.stream:
        return StreamingResponse(
            stream_chat_completion(
                agent.runtime, req.messages, model=agent.id, session_id=session_id
            ),
            media_type="text/event-stream",
        )

    # Trace the full request → tool-loop tree for /diag.
    diag.start_trace(
        trigger=trigger,
        model=agent.id,
        session=session_id,
        request=_trace_request(req.messages, trigger),
        num_images=_count_payload_images(req.messages),
    )
    try:
        result = await agent.runtime.arun(req.messages, session_id=session_id)
    except Exception as e:  # noqa: BLE001 — record then re-raise
        status = "timeout" if e.__class__.__name__.endswith("Timeout") else "error"
        await diag.finish_trace(app.state.state, status, response={"error": repr(e)})
        raise
    ops, reply = _envelope_summary(result.text)
    await diag.finish_trace(
        app.state.state, "ok", response=result.text, num_ops=ops, reply_preview=reply
    )
    # Caption any new attachments AFTER replying, so the user's notification
    # never waits on vision work. One call per image, ever — from here on
    # every downstream path reads the cached text instead of the pixels.
    _schedule_captioning(req.messages)
    return to_chat_completion(result, model=agent.id)


async def _submit_async(
    req: ChatCompletionRequest,
    agent,
    session_id: str | None,
    trigger: str,
    x_thread_id: str | None,
    x_notification_title: str | None,
) -> JSONResponse:
    """Accept a turn, run it in the background, deliver via the polling queue.

    Mirrors scheduler._run_schedule: start a trace (so /diag shows it running),
    run the agent, parse the envelope, finish the trace honestly, then hand the
    reply to every pusher. The client-provided thread id / title ride in the
    delivery's extras so PollWorker lands it in the exact thread that already
    shows the user's message.
    """
    try:
        thread_id = int(x_thread_id) if x_thread_id else -1
    except (TypeError, ValueError):
        thread_id = -1
    # The client percent-encodes this header because HTTP header values are
    # ASCII-only and the title may carry "…" / emoji / accents.
    from urllib.parse import unquote
    title = unquote(x_notification_title) if x_notification_title else "assistant"

    # Dedup only when we have a real thread to key on (-1 means "unthreaded",
    # which would otherwise collide across distinct turns).
    dedup = thread_id >= 0
    key = (session_id or "", thread_id)
    if dedup and key in _async_inflight:
        log.info("[async] duplicate submit for %s — already in flight", key)
        return JSONResponse({"status": "in_progress"}, status_code=202)

    # Set the trace in this request's context; create_task snapshots it, so the
    # runtime's tool-call recording and finish_trace below bind to this trace.
    tr = diag.start_trace(
        trigger=trigger,
        model=agent.id,
        session=session_id,
        request=_trace_request(req.messages, trigger),
        num_images=_count_payload_images(req.messages),
    )
    if dedup:
        _async_inflight.add(key)

    async def _run() -> None:
        try:
            result = await agent.runtime.arun(req.messages, session_id=session_id)
            env = parse_envelope(result)
            await diag.finish_trace(
                app.state.state, env.status, response=result.text,
                num_ops=len(env.ops), reply_preview=env.reply,
                resume_run_id=env.resume_run_id,
            )
            if not env.no_response:
                extras = dict(env.extras or {})
                if thread_id >= 0:
                    extras["thread_id"] = thread_id
                extras["source"] = trigger
                if env.resolve:
                    extras["resolve"] = True
                for pusher in app.state.pushers:
                    try:
                        await pusher.deliver(
                            session_id, title, env.reply, env.ops, extras or None
                        )
                    except Exception:  # noqa: BLE001
                        log.exception("[async] pusher %s failed", type(pusher).__name__)
            _schedule_captioning(req.messages)
        except Exception as e:  # noqa: BLE001
            log.exception("[async] turn failed session=%s thread=%s", session_id, thread_id)
            try:
                await diag.finish_trace(app.state.state, "error", response={"error": repr(e)})
            except Exception:  # noqa: BLE001
                pass
            # Surface the failure in the thread rather than leaving it hanging.
            err_extras: dict = {"source": trigger}
            if thread_id >= 0:
                err_extras["thread_id"] = thread_id
            try:
                for pusher in app.state.pushers:
                    await pusher.deliver(
                        session_id, title, f"[assistant error: {e}]", [], err_extras
                    )
            except Exception:  # noqa: BLE001
                pass
        finally:
            if dedup:
                _async_inflight.discard(key)

    asyncio.create_task(_run())
    return JSONResponse({"status": "queued", "trace_id": tr.id}, status_code=202)


# Triggers whose prompt carries content too sensitive to keep in a trace.
# /diag is deliberately unauthenticated on the tailnet, so anything recorded
# here is readable by anyone who can reach the gateway.
_REDACTED_TRIGGERS = {"sms"}


def _trace_request(messages, trigger: str) -> list:
    """Trace-safe view of the request. For sensitive triggers the envelope's
    `prompt` (the raw SMS bodies) is replaced with a marker, while the rest of
    the envelope — lists/goals context, the resulting ops — stays visible so
    the turn is still debuggable."""
    raw = [{"role": m.role, "content": m.content} for m in messages]
    if trigger not in _REDACTED_TRIGGERS or not raw:
        return raw
    try:
        payload = _json.loads(raw[-1]["content"])
        if isinstance(payload, dict) and "prompt" in payload:
            n = len(str(payload["prompt"]))
            payload["prompt"] = f"<redacted {trigger} prompt, {n} chars>"
            raw[-1]["content"] = _json.dumps(payload)
            return raw
    except (ValueError, TypeError):
        pass
    raw[-1]["content"] = f"<redacted {trigger} payload>"
    return raw


def _count_payload_images(messages) -> int:
    """How many attachments ride on this request's payload envelope —
    surfaced as a chip on the /diag trace row."""
    try:
        payload = _json.loads(messages[-1].content) if messages else None
    except (ValueError, TypeError, IndexError):
        return 0
    if not isinstance(payload, dict):
        return 0
    imgs = payload.get("images")
    return len(imgs) if isinstance(imgs, list) else 0


def _schedule_captioning(messages) -> None:
    try:
        payload = _json.loads(messages[-1].content) if messages else None
    except (ValueError, TypeError, IndexError):
        return
    if not isinstance(payload, dict):
        return
    shas = [
        e.get("id") for e in (payload.get("images") or [])
        if isinstance(e, dict) and e.get("id") and not e.get("summary")
    ]
    if not shas:
        return

    async def run():
        for sha in shas:
            try:
                await images.caption_image(app.state.state, sha)
            except Exception:  # noqa: BLE001 — never surface to the request
                pass

    asyncio.create_task(run())


def _envelope_summary(text: str) -> tuple[int, str]:
    """Best-effort extraction of op count + reply preview from the
    {reply, ops, ...} envelope for the trace summary."""
    try:
        obj = _json.loads(text)
        if isinstance(obj, dict):
            ops = obj.get("ops")
            reply = obj.get("reply") or ""
            return (len(ops) if isinstance(ops, list) else 0), str(reply)
    except (ValueError, TypeError):
        pass
    return 0, text


# ---- snapshot cache -------------------------------------------------------

@app.post("/v1/snapshot/{session_id}")
async def upload_snapshot(
    session_id: str,
    body: dict,
    _: None = Depends(require_api_key),
) -> dict:
    """Android calls this after every list mutation (and after each
    transcript save) so scheduled runs have current state to reason
    about. Body: {"lists": [...], "notes": [...]}. `notes` is optional —
    if omitted, whatever's already cached is preserved.
    """
    lists = body.get("lists")
    if not isinstance(lists, list):
        raise HTTPException(status_code=400, detail="body must contain a `lists` array")
    raw_notes = body.get("notes")
    notes = raw_notes if isinstance(raw_notes, list) else None
    raw_goals = body.get("goals")
    goals = raw_goals if isinstance(raw_goals, list) else None
    await app.state.state.set_snapshot(session_id, lists, notes, goals)
    return {"status": "ok"}


@app.get("/v1/snapshot/{session_id}")
async def get_snapshot(
    session_id: str,
    _: None = Depends(require_api_key),
) -> dict:
    """Debug/echo: returns whatever was last uploaded for this session."""
    return {
        "lists": await app.state.state.get_snapshot(session_id),
        "notes": await app.state.state.get_notes(session_id),
        "goals": await app.state.state.get_goals(session_id),
    }


# ---- pending delivery queue ----------------------------------------------

@app.get("/v1/pending/{session_id}")
async def get_pending(
    session_id: str,
    wait: float = 0,
    _: None = Depends(require_api_key),
) -> dict:
    """Android polls this to drain undelivered messages. Returned entries
    stay in the queue until the client acks them via DELETE.

    `wait` (seconds, ≤55) makes it a long-poll: with nothing pending, hold the
    request until a delivery is queued or the wait runs out. Alongside the
    deliveries it reports what's still coming, so the phone can tell a running
    turn from a lost one and knows when to check back:
      * busy_threads      — threads with an async turn still running
      * scheduled_running — a scheduled check-in is executing now
      * next_due          — the session's next scheduled run (ISO), if any
    """
    st = app.state.state
    ev = st.delivery_event(session_id)   # take before listing — see State
    # busy before pending: a turn queues its delivery before it leaves
    # _async_inflight, so a turn that just finished is in one or the other.
    busy = sorted(t for s, t in _async_inflight if s == session_id and t >= 0)
    deliveries = await st.list_pending(session_id)
    wait = max(0.0, min(float(wait), 55.0))
    if not deliveries and wait > 0:
        if await st.wait_event(ev, wait):
            busy = sorted(t for s, t in _async_inflight if s == session_id and t >= 0)
            deliveries = await st.list_pending(session_id)
    return {
        "deliveries": deliveries,
        "busy_threads": busy,
        "scheduled_running": scheduler_mod.running.get(session_id, 0) > 0,
        "next_due": await _next_due(session_id),
    }


async def _next_due(session_id: str) -> str | None:
    """Earliest next run across the session's enabled schedules (ISO)."""
    try:
        rows = await app.state.state.list_schedules()
    except Exception:  # noqa: BLE001
        return None
    best = None
    for row in rows:
        if not row.get("enabled", True):
            continue
        if (row.get("session_id") or "voicenote-default") != session_id:
            continue
        nrt = app.state.scheduler.next_run_time(row["id"])
        if nrt is not None and (best is None or nrt < best):
            best = nrt
    return best.isoformat() if best else None


@app.delete("/v1/pending/{delivery_id}")
async def ack_pending(
    delivery_id: int,
    _: None = Depends(require_api_key),
) -> dict:
    ok = await app.state.state.mark_delivered(delivery_id)
    if not ok:
        raise HTTPException(status_code=404, detail="delivery not found or already delivered")
    return {"status": "ok"}


# ---- schedules (CRUD; source of truth is state.db) -----------------------


class ScheduleBody(BaseModel):
    cron: str
    model: str
    prompt: str
    notification_title: str = ""
    session_id: str = "voicenote-default"
    enabled: bool = True
    timezone: str = ""        # IANA; empty = scheduler's local zone
    tz_mode: str = "fixed"    # "fixed" | "device"


class _DeviceTzBody(BaseModel):
    tz: str


def _validate_schedule(body: ScheduleBody) -> None:
    from zoneinfo import ZoneInfo, ZoneInfoNotFoundError
    from apscheduler.triggers.cron import CronTrigger
    try:
        CronTrigger.from_crontab(body.cron)
    except (ValueError, TypeError) as e:
        raise HTTPException(status_code=400, detail=f"invalid cron '{body.cron}': {e}")
    if registry.get(body.model) is None:
        known = ", ".join(a.id for a in registry.list())
        raise HTTPException(status_code=404, detail=f"unknown model '{body.model}'. Known: {known}")
    if body.timezone:
        try:
            ZoneInfo(body.timezone)
        except (ZoneInfoNotFoundError, ValueError):
            raise HTTPException(status_code=400, detail=f"invalid timezone '{body.timezone}'")
    if body.tz_mode not in ("fixed", "device"):
        raise HTTPException(status_code=400, detail="tz_mode must be 'fixed' or 'device'")


def _schedule_view(row: dict, last_run: dict | None = None) -> dict:
    nrt = app.state.scheduler.next_run_time(row["id"])
    return {
        **row,
        "next_run_time": nrt.isoformat() if nrt else None,
        # Most recent run's outcome, for the app's per-schedule status badge.
        "last_run": last_run,
    }


@app.get("/v1/schedules")
async def list_schedules_ep(_: None = Depends(require_api_key)) -> dict:
    rows = await app.state.state.list_schedules()
    out = []
    for r in rows:
        last_run = await app.state.state.latest_run_for(r["id"])
        out.append(_schedule_view(r, last_run))
    return {"schedules": out}


@app.get("/v1/schedules/{schedule_id}/runs")
async def list_schedule_runs_ep(
    schedule_id: str, limit: int = 20, _: None = Depends(require_api_key)
) -> dict:
    """Recent runs of one schedule (status / time / calls / reply preview /
    resume handle), newest first. Backed by the trace log."""
    runs = await app.state.state.list_schedule_runs(schedule_id, limit=max(1, min(limit, 100)))
    return {"runs": runs}


@app.post("/v1/schedules")
async def create_schedule_ep(body: ScheduleBody, _: None = Depends(require_api_key)) -> dict:
    import uuid
    _validate_schedule(body)
    sid = uuid.uuid4().hex[:12]
    await app.state.state.upsert_schedule({"id": sid, **body.model_dump()})
    row = await app.state.state.get_schedule(sid)
    app.state.scheduler.add_or_update(row)
    return _schedule_view(row)


# Literal route declared BEFORE the /{schedule_id} param route so it isn't
# captured as schedule_id="device-tz".
@app.put("/v1/schedules/device-tz")
async def set_device_tz_ep(body: _DeviceTzBody, _: None = Depends(require_api_key)) -> dict:
    from zoneinfo import ZoneInfo, ZoneInfoNotFoundError
    try:
        ZoneInfo(body.tz)
    except (ZoneInfoNotFoundError, ValueError):
        raise HTTPException(status_code=400, detail=f"invalid timezone '{body.tz}'")
    ids = await app.state.state.retz_device_schedules(body.tz)
    await app.state.scheduler.reload_ids(ids)
    return {"status": "ok", "updated": ids}


@app.put("/v1/schedules/{schedule_id}")
async def update_schedule_ep(schedule_id: str, body: ScheduleBody, _: None = Depends(require_api_key)) -> dict:
    if await app.state.state.get_schedule(schedule_id) is None:
        raise HTTPException(status_code=404, detail=f"no such schedule '{schedule_id}'")
    _validate_schedule(body)
    await app.state.state.upsert_schedule({"id": schedule_id, **body.model_dump()})
    row = await app.state.state.get_schedule(schedule_id)
    app.state.scheduler.add_or_update(row)
    return _schedule_view(row)


@app.delete("/v1/schedules/{schedule_id}")
async def delete_schedule_ep(schedule_id: str, _: None = Depends(require_api_key)) -> dict:
    if await app.state.state.get_schedule(schedule_id) is None:
        raise HTTPException(status_code=404, detail=f"no such schedule '{schedule_id}'")
    await app.state.state.delete_schedule(schedule_id)
    app.state.scheduler.remove(schedule_id)
    return {"status": "ok"}


@app.post("/v1/schedule/{schedule_id}/run")
async def run_schedule_now(schedule_id: str, _: None = Depends(require_api_key)) -> dict:
    """Fire a schedule immediately instead of waiting for its cron."""
    ok = await app.state.scheduler.run_now(schedule_id)
    if not ok:
        ids = ", ".join(s["id"] for s in await app.state.state.list_schedules()) or "(none)"
        raise HTTPException(status_code=404, detail=f"unknown schedule '{schedule_id}'. Known: {ids}")
    return {"status": "ok"}


# ---- images ---------------------------------------------------------------

@app.post("/v1/images/{session_id}")
async def upload_images(
    session_id: str,
    files: list[UploadFile] = File(...),
    _: None = Depends(require_api_key),
) -> dict:
    """Content-addressed image upload. Re-uploading the same photo is a
    no-op that returns its existing id (and any caption already computed),
    so the client can upload freely without dedup logic of its own."""
    out = []
    for f in files:
        data = await f.read()
        if not data:
            continue
        sha, existed = images.put_blob(data)
        await app.state.state.upsert_image(
            sha, session_id, f.content_type or "image/jpeg", len(data)
        )
        rec = await app.state.state.get_image(sha)
        out.append({
            "id": sha,
            "existing": existed,
            "summary": (rec or {}).get("summary"),
            "has_text": bool((rec or {}).get("has_text")),
        })
    # New bytes may have pushed the store over its cap — enforce it, but
    # never make the uploader wait on a directory walk.
    asyncio.create_task(_safe_housekeep())
    return {"images": out}


async def _safe_housekeep() -> None:
    try:
        await images.housekeep(app.state.state)
    except Exception:  # noqa: BLE001 — hygiene must never surface to a request
        pass


@app.get("/v1/images/{sha}")
async def get_image_meta(sha: str, raw: bool = False, _: None = Depends(require_api_key)):
    """Metadata by default (including the cached caption record); `?raw=1`
    serves the JPEG itself. Api-key protected like the upload — these are the
    user's photos."""
    if raw:
        data = images.get_blob(sha)
        if data is None:
            raise HTTPException(status_code=404, detail="no such image")
        return Response(content=data, media_type="image/jpeg")
    rec = await app.state.state.get_image(sha)
    if rec is None:
        raise HTTPException(status_code=404, detail="no such image")
    return rec


# ---- media (video) --------------------------------------------------------

@app.post("/v1/media/{session_id}")
async def upload_media(
    session_id: str,
    file: UploadFile = File(...),
    duration_s: float = Form(0.0),
    width: int = Form(0),
    height: int = Form(0),
    _: None = Depends(require_api_key),
) -> dict:
    """Content-addressed video upload, streamed to disk (app/media.py).
    Re-uploading the same clip returns its existing id and any summary. The
    phone supplies duration/size (the gateway never decodes video itself)."""
    try:
        sha, mime, nbytes, existed = await media.put_stream(
            file, settings.media_max_mb * 1024 * 1024,
        )
    except media.MediaError as e:
        raise HTTPException(status_code=413 if "larger" in str(e) else 415, detail=str(e))
    await app.state.state.upsert_media(
        sha, session_id, mime, nbytes, float(duration_s or 0), int(width or 0), int(height or 0),
    )
    rec = await app.state.state.get_media(sha) or {}
    asyncio.create_task(_safe_media_housekeep())
    return {"media": {"id": sha, "existing": existed, "mime": mime, "bytes": nbytes,
                      "duration_s": rec.get("duration_s"), "summary": rec.get("summary")}}


async def _safe_media_housekeep() -> None:
    try:
        await media.housekeep(app.state.state)
    except Exception:  # noqa: BLE001 — hygiene must never surface to a request
        pass


@app.get("/v1/media/{sha}")
async def get_media_meta(sha: str, _: None = Depends(require_api_key)) -> dict:
    rec = await app.state.state.get_media(sha)
    if rec is None:
        raise HTTPException(status_code=404, detail="no such video")
    rec["stored"] = media.find(sha) is not None
    return rec


# ---- diagnostics dashboard (open — no api key) ---------------------------

_DIAG_HTML = Path(__file__).parent / "static" / "diag.html"
_ADMIN_HTML = Path(__file__).parent / "static" / "admin.html"


@app.get("/diag")
async def diag_page():
    return FileResponse(_DIAG_HTML)


@app.get("/admin")
async def admin_page():
    # The page itself is static; its data API (/v1/registry/*) is api-key gated.
    return FileResponse(_ADMIN_HTML)


@app.get("/diag/traces")
async def diag_traces(limit: int = 200) -> dict:
    # In-flight traces (newest first) sit above the finished history, so the
    # dashboard shows work as it runs rather than only once it's recorded.
    running = diag.active_traces()
    finished = await app.state.state.list_traces(limit)
    return {"traces": running + finished}


@app.get("/diag/traces/{trace_id}")
async def diag_trace(trace_id: str) -> dict:
    # Prefer the live trace so a run can be inspected mid-flight; fall back to
    # the persisted copy once it has finished.
    tr = diag.active_trace_full(trace_id) or await app.state.state.get_trace(trace_id)
    if tr is None:
        raise HTTPException(status_code=404, detail="no such trace")
    return tr


@app.get("/diag/stats")
async def diag_stats() -> dict:
    return diag.stats()


@app.get("/v1/progress/{session}")
async def progress_stream(session: str, _: None = Depends(require_api_key)):
    """Per-session SSE of live turn progress: one goal-relative status event per
    skill/tool as it happens, plus start/done. The app tails this while a turn
    is in flight to drive its live status line."""
    async def gen():
        q = diag.progress_subscribe(session)
        try:
            yield ": connected\n\n"
            while True:
                try:
                    msg = await asyncio.wait_for(q.get(), timeout=15)
                    yield f"data: {_json.dumps(msg)}\n\n"
                except asyncio.TimeoutError:
                    yield ": keepalive\n\n"
        finally:
            diag.progress_unsubscribe(session, q)

    return StreamingResponse(gen(), media_type="text/event-stream")


# ---- sandbox (vmmd) approval proxy ----------------------------------------
# The app can't reach the host-only daemon directly, so the gateway relays its
# approval API. Thin pass-through to settings.vmmd_base_url; fails fast with 503
# when the daemon is down (never hangs the app).

_VMMD_TIMEOUT = httpx.Timeout(connect=5, read=10, write=10, pool=5)


class _ApprovalModeBody(BaseModel):
    mode: str


async def _vmmd(method: str, path: str, json: dict | None = None):
    url = settings.vmmd_base_url.rstrip("/") + path
    try:
        async with httpx.AsyncClient(timeout=_VMMD_TIMEOUT) as c:
            r = await c.request(method, url, json=json)
    except httpx.HTTPError as e:
        raise HTTPException(status_code=503, detail=f"sandbox daemon unreachable: {e}")
    if r.status_code >= 400:
        try:
            detail = r.json().get("detail", r.text)
        except Exception:  # noqa: BLE001
            detail = r.text
        raise HTTPException(status_code=r.status_code, detail=detail)
    try:
        return r.json()
    except Exception:  # noqa: BLE001
        return {}


@app.get("/v1/sandbox/approval-mode")
async def sandbox_get_mode(_: None = Depends(require_api_key)) -> dict:
    return await _vmmd("GET", "/config/approval-mode")


@app.put("/v1/sandbox/approval-mode")
async def sandbox_set_mode(body: _ApprovalModeBody, _: None = Depends(require_api_key)) -> dict:
    if body.mode not in ("auto", "smart", "manual"):
        raise HTTPException(status_code=400, detail="mode must be 'auto', 'smart' or 'manual'")
    return await _vmmd("PUT", "/config/approval-mode", json={"mode": body.mode})


@app.get("/v1/sandbox/approvals")
async def sandbox_approvals(_: None = Depends(require_api_key)) -> dict:
    return await _vmmd("GET", "/approvals")


@app.post("/v1/sandbox/approvals/{approval_id}/approve")
async def sandbox_approve(approval_id: str, _: None = Depends(require_api_key)) -> dict:
    return await _vmmd("POST", f"/approvals/{approval_id}/approve")


@app.post("/v1/sandbox/approvals/{approval_id}/deny")
async def sandbox_deny(approval_id: str, _: None = Depends(require_api_key)) -> dict:
    return await _vmmd("POST", f"/approvals/{approval_id}/deny")


@app.get("/diag/stream")
async def diag_stream():
    """SSE live tail: new trace summaries + concurrency stat deltas."""
    async def gen():
        q = diag.subscribe()
        try:
            yield f"data: {_json.dumps({'type': 'stats', 'stats': diag.stats()})}\n\n"
            while True:
                try:
                    msg = await asyncio.wait_for(q.get(), timeout=15)
                    yield f"data: {_json.dumps(msg)}\n\n"
                except asyncio.TimeoutError:
                    yield ": keepalive\n\n"
        finally:
            diag.unsubscribe(q)

    return StreamingResponse(gen(), media_type="text/event-stream")


# ---- health --------------------------------------------------------------

@app.get("/healthz")
async def healthz() -> dict:
    from app.skill_registry import skill_registry
    return {
        "status": "ok",
        "agents": [a.id for a in registry.list()],
        "skills": [s.id for s in skill_registry.list()],
        "schedules": (
            [s["id"] for s in await app.state.state.list_schedules()]
            if hasattr(app.state, "state") else []
        ),
        "ollama": diag.stats(),
        "providers": diag.provider_stats(),
    }
