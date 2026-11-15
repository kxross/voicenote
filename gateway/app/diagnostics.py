"""Request tracing + Ollama concurrency gate for the /diag dashboard.

Every model-driving request becomes a Trace: a tree of Ollama round
trips, each with the tool calls it triggered. The active trace lives in
a contextvar, so the runtime layers attach to it without any per-call
plumbing — if no trace is active, every recorder is a no-op.

The same module owns the Ollama concurrency gate (a semaphore + in-flight
/ queued counters). Capping concurrency keeps a big local model from
being swamped, and the counters feed the dashboard's live gauge — one
mechanism, two payoffs.
"""

from __future__ import annotations

import asyncio
import json
import time
import uuid
from contextvars import ContextVar
from dataclasses import dataclass, field, asdict

from app.config import settings

_MAX_FIELD_BYTES = 48 * 1024


def strip_images(value):
    """Replace base64 image payloads with a short marker before a trace
    records them. Five attachments would otherwise blow past cap() and
    drown the /diag view in unreadable base64.

    Handles both wire shapes: Ollama's `images: [b64, ...]` on a message, and
    OpenAI content-parts, where the payload hides in
    `{"image_url": {"url": "data:image/jpeg;base64,..."}}` — a bare string,
    which is why the string branch checks for the data: prefix.
    """
    if isinstance(value, dict):
        out = {}
        for k, v in value.items():
            if k == "images" and isinstance(v, list):
                out[k] = [_marker(b) if isinstance(b, str) else b for b in v]
            else:
                out[k] = strip_images(v)
        return out
    if isinstance(value, list):
        return [strip_images(v) for v in value]
    if isinstance(value, str) and value.startswith("data:image"):
        return _marker(value)
    return value


def _marker(b64: str) -> str:
    return f"<image {len(b64) * 3 // 4 // 1024}KB base64>"


def cap(value) -> object:
    """Serialize-safe truncation: any field whose JSON exceeds 16KB is
    replaced with a truncated string marker so one big goals snapshot
    can't bloat a trace."""
    value = strip_images(value)
    try:
        encoded = json.dumps(value, default=str)
    except (TypeError, ValueError):
        encoded = str(value)
    if len(encoded) <= _MAX_FIELD_BYTES:
        return value
    return encoded[:_MAX_FIELD_BYTES] + f"…(truncated {len(encoded) - _MAX_FIELD_BYTES} bytes)"


# --- trace data model -------------------------------------------------------

@dataclass
class ToolEvent:
    name: str
    args: object
    result: object
    duration_ms: int
    error: str | None = None


@dataclass
class OllamaCall:
    index: int
    duration_ms: int = 0
    request: dict = field(default_factory=dict)
    response: dict = field(default_factory=dict)
    tools: list[ToolEvent] = field(default_factory=list)
    # The model's reasoning for this round (Ollama returns it when think=True).
    # Surfaced to the client's activity accordion and the /diag view.
    thinking: str = ""


@dataclass
class Trace:
    id: str
    created_at: float
    trigger: str
    model: str
    session: str | None
    request: object = None
    response: object = None
    status: str = "ok"
    duration_ms: int = 0
    num_ops: int = 0
    num_images: int = 0
    reply_preview: str = ""
    # Set when a run paused at a checkpoint (status="paused"): the run id the
    # client passes to resume. None for ordinary ok/error runs.
    resume_run_id: str | None = None
    calls: list[OllamaCall] = field(default_factory=list)

    @property
    def num_calls(self) -> int:
        return len(self.calls)

    def summary(self) -> dict:
        return {
            "id": self.id,
            "created_at": self.created_at,
            "trigger": self.trigger,
            "model": self.model,
            "session": self.session,
            "status": self.status,
            "duration_ms": self.duration_ms,
            "num_calls": self.num_calls,
            "num_ops": self.num_ops,
            "num_images": self.num_images,
            "reply_preview": self.reply_preview,
            "resume_run_id": self.resume_run_id,
        }

    def full(self) -> dict:
        d = asdict(self)
        d["num_calls"] = self.num_calls
        return d


current_trace: ContextVar[Trace | None] = ContextVar("current_trace", default=None)

# In-flight traces, keyed by id. A trace lives here from start_trace() until
# finish_trace(); the /diag endpoints surface these as "running" rows so the
# dashboard shows work as it happens, not only once it lands in the DB.
_active: dict[str, Trace] = {}


def _live_summary(tr: Trace) -> dict:
    """A running trace's summary: status forced to 'running' with a live
    elapsed, calls/ops counted so far."""
    s = tr.summary()
    s["status"] = "running"
    s["duration_ms"] = int((time.monotonic() - getattr(tr, "_start", time.monotonic())) * 1000)
    return s


def active_traces() -> list[dict]:
    """Summaries of every in-flight trace, newest first — prepended to the
    /diag/traces list so running work shows above the finished history."""
    return sorted(
        (_live_summary(tr) for tr in list(_active.values())),
        key=lambda d: d["created_at"],
        reverse=True,
    )


def active_trace_full(trace_id: str) -> dict | None:
    """Full call tree of one in-flight trace (for drilling into a run while it
    executes). None if it isn't currently running."""
    tr = _active.get(trace_id)
    if tr is None:
        return None
    d = tr.full()
    d["status"] = "running"
    d["duration_ms"] = int((time.monotonic() - getattr(tr, "_start", time.monotonic())) * 1000)
    return d


# --- recorders (no-op when no active trace) --------------------------------

def start_trace(trigger: str, model: str, session: str | None, request, num_images: int = 0) -> Trace:
    tr = Trace(
        id=uuid.uuid4().hex[:12],
        created_at=time.time(),
        trigger=trigger,
        model=model,
        session=session,
        request=cap(request),
        num_images=num_images,
    )
    current_trace.set(tr)
    tr._start = time.monotonic()  # type: ignore[attr-defined]
    _active[tr.id] = tr
    from app import progress as _prog
    progress_publish(session, {"type": "progress", "phase": "start", "label": _prog.STARTING})
    return tr


async def finish_trace(state, status: str, response=None, num_ops: int = 0,
                       reply_preview: str = "", resume_run_id: str | None = None) -> None:
    tr = current_trace.get()
    if tr is None:
        return
    tr.duration_ms = int((time.monotonic() - getattr(tr, "_start", time.monotonic())) * 1000)
    tr.status = status
    tr.response = cap(response)
    tr.num_ops = num_ops
    tr.reply_preview = (reply_preview or "")[:200]
    tr.resume_run_id = resume_run_id
    current_trace.set(None)
    _active.pop(tr.id, None)
    from app import progress as _prog
    progress_publish(
        tr.session,
        {"type": "progress", "phase": "done", "label": _prog.DONE, "status": status},
    )
    if state is not None:
        try:
            await state.insert_trace(tr)
        except Exception:  # noqa: BLE001 — diagnostics must never break a request
            pass
    await _broadcast({"type": "trace", "trace": tr.summary()})


def record_ollama_call(
    index: int, request: dict, response: dict, duration_ms: int, thinking: str = ""
) -> OllamaCall | None:
    tr = current_trace.get()
    call = OllamaCall(
        index=index,
        duration_ms=duration_ms,
        request={k: cap(v) for k, v in request.items()},
        response={k: cap(v) for k, v in response.items()},
        thinking=str(thinking or ""),
    )
    if tr is not None:
        tr.calls.append(call)
    return call


def record_tool(call: OllamaCall | None, name: str, args, result, duration_ms: int, error: str | None = None) -> None:
    if call is None:
        return
    call.tools.append(
        ToolEvent(name=name, args=cap(args), result=cap(result), duration_ms=duration_ms, error=error)
    )
    # Live progress: narrate this step to anyone tailing the session. load_skill
    # reads as the area ("Updating your lists"); other tools as the action.
    tr = current_trace.get()
    if tr is not None:
        from app import progress as _prog

        if name == "load_skill":
            sid = args.get("skill_id") if isinstance(args, dict) else None
            progress_publish(
                tr.session,
                {"type": "progress", "phase": "skill", "name": sid or "",
                 "label": _prog.label_for("skill", sid or ""), "ok": error is None},
            )
        elif name not in ("resolve_thread", "no_response"):
            progress_publish(
                tr.session,
                {"type": "progress", "phase": "tool", "name": name,
                 "label": _prog.detail_label("tool", name, args), "ok": error is None},
            )


# --- per-provider concurrency gates + live stats ---------------------------
# Ollama and a remote API want opposite things: one big local model serialises
# inference anyway (cap 1-2 keeps it from thrashing), while OpenRouter is
# elastic and latency-bound, so a shared single slot would needlessly queue
# every user, schedule, and caption behind one another. Hence a gate per
# provider rather than one global semaphore.

_gates: dict[str, asyncio.Semaphore] = {}
_in_flight: dict[str, int] = {}
_queued: dict[str, int] = {}


# Depth of the current call within a sub-agent fan-out (0 = a normal, top-level
# turn; >0 = running as a delegated sub-agent). Set by app/subagents.py around
# each sub-agent, inherited by that coroutine's model calls. ollama_gate() reads
# it to route sub-agent calls to their own 2-wide gate, and the run_subagents
# tool reads it to forbid a sub-agent spawning further sub-agents.
subagent_depth: ContextVar[int] = ContextVar("subagent_depth", default=0)


def _limit(provider: str) -> int:
    if provider == "openrouter":
        return max(1, settings.openrouter_max_concurrency)
    if provider == "vllm":
        return max(1, settings.vllm_max_concurrency)
    if provider == "ollama-sub":
        return max(1, settings.subagent_concurrency)
    return max(1, settings.ollama_max_concurrency)


def _get_gate(provider: str) -> asyncio.Semaphore:
    if provider not in _gates:
        _gates[provider] = asyncio.Semaphore(_limit(provider))
    return _gates[provider]


class _GateCtx:
    def __init__(self, provider: str):
        self.provider = provider

    async def __aenter__(self):
        p = self.provider
        gate = _get_gate(p)
        _queued[p] = _queued.get(p, 0) + 1
        await _publish_stats()
        await gate.acquire()
        _queued[p] -= 1
        _in_flight[p] = _in_flight.get(p, 0) + 1
        await _publish_stats()
        return self

    async def __aexit__(self, *exc):
        p = self.provider
        _in_flight[p] = max(0, _in_flight.get(p, 0) - 1)
        _get_gate(p).release()
        await _publish_stats()
        return False


def gate(provider: str = "ollama") -> _GateCtx:
    """`async with gate(provider):` around each model call caps that
    provider's concurrency and updates the live gauge."""
    return _GateCtx(provider)


def ollama_gate() -> _GateCtx:
    """Gate for a local Ollama call. A call made inside a sub-agent fan-out
    (subagent_depth > 0) acquires the dedicated 'ollama-sub' gate so two
    delegated sub-agents run in parallel, while top-level turns stay on the
    main 'ollama' gate (concurrency 1). Existing call sites need no change."""
    return gate("ollama-sub" if subagent_depth.get() > 0 else "ollama")


def stats() -> dict:
    """Aggregate gauge, the shape /diag's dashboard renders."""
    return {
        "in_flight": sum(_in_flight.values()),
        "queued": sum(_queued.values()),
        "max": sum(_limit(p) for p in set(_gates) | {"ollama"}),
    }


def provider_stats() -> dict:
    """Per-provider breakdown for /healthz."""
    providers = set(_gates) | {"ollama"}
    return {
        p: {
            "in_flight": _in_flight.get(p, 0),
            "queued": _queued.get(p, 0),
            "max": _limit(p),
        }
        for p in sorted(providers)
    }


# --- SSE pub/sub ------------------------------------------------------------

_subscribers: set[asyncio.Queue] = set()


def subscribe() -> asyncio.Queue:
    q: asyncio.Queue = asyncio.Queue(maxsize=100)
    _subscribers.add(q)
    return q


def unsubscribe(q: asyncio.Queue) -> None:
    _subscribers.discard(q)


async def _broadcast(msg: dict) -> None:
    for q in list(_subscribers):
        try:
            q.put_nowait(msg)
        except asyncio.QueueFull:
            pass


async def _publish_stats() -> None:
    await _broadcast({"type": "stats", "stats": stats()})


# --- per-session live progress feed ----------------------------------------
# A lightweight fan-out keyed by session, so the app's in-flight status line can
# tail exactly its own turn. Separate from the /diag broadcast (which is a global
# admin tail of finished traces + stats). The last event per session is kept so a
# client that connects mid-turn immediately sees the current step, not silence.

_progress_subs: dict[str, set[asyncio.Queue]] = {}
_progress_last: dict[str, dict] = {}


def progress_subscribe(session: str) -> asyncio.Queue:
    q: asyncio.Queue = asyncio.Queue(maxsize=100)
    _progress_subs.setdefault(session, set()).add(q)
    last = _progress_last.get(session)
    if last is not None:
        try:
            q.put_nowait(last)
        except asyncio.QueueFull:
            pass
    return q


def progress_unsubscribe(session: str, q: asyncio.Queue) -> None:
    subs = _progress_subs.get(session)
    if subs is not None:
        subs.discard(q)
        if not subs:
            _progress_subs.pop(session, None)


def progress_publish(session: str | None, event: dict) -> None:
    """Fan a progress event out to the session's subscribers. Sync (called from
    the recorders); never awaits, never raises into a request."""
    if not session:
        return
    if event.get("phase") == "done":
        _progress_last.pop(session, None)
    else:
        _progress_last[session] = event
    for q in list(_progress_subs.get(session, ())):
        try:
            q.put_nowait(event)
        except asyncio.QueueFull:
            pass
