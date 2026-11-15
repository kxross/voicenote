"""System One — typed-decision models behind one conforming surface.

System One is TypeSafe's API for *decisions*, not text: the caller sends a
`state` plus named `questions`, each typed as

  * ``noul``   — yes/no  → ``{"type":"noul","noul": P(yes)}``
  * ``choice`` — pick one of 1–255 named options → ``{"choice", "confidence",
                 "probabilities"}``
  * ``score``  — place on an ordered 1–255-level scale → ``{"score",
                 "confidence", "legend", "probabilities"}``

and gets calibrated answers back (no generated text). Two interchangeable
backends speak the identical API:

  * ``kev`` — the open-source reproduction (github.com/jaredpalmer/kev,
    Apache-2.0), self-hosted on the WSL host via ``kev.serve`` on :8009.
    This is the first-version default.
  * ``jev`` — TypeSafe's hosted model. Same request/response; configure
    ``JEV_BASE_URL`` + ``JEV_API_KEY`` from TypeSafe (or a System One-compatible
    router) to switch it on.

Routing: a request ``model`` beginning with ``kev``/``jev`` selects that
backend for the call; otherwise ``SYSTEMONE_PROVIDER`` decides. The gateway
forwards the body unchanged (only defaulting ``model``) and passes the upstream
status, body and ``x-typesafe-request-id`` straight back, so any System One
client — including TypeSafe's SDK pointed at the gateway — works unchanged.

Internal callers (planned: smart approvals that score LLM-generated code before
it runs, browser-control decisions) use :func:`decide` / :func:`noul` instead of
HTTP; those calls show up as a ``kev``/``jev`` round-trip on the active /diag
trace.
"""

from __future__ import annotations

import time
from dataclasses import dataclass
from typing import Any

import httpx

from app import diagnostics as diag
from app.config import settings

PROVIDERS = ("kev", "jev")
QUESTION_TYPES = ("noul", "choice", "score")
MAX_OPTIONS = 255  # documented cap for choice options / score levels


class SystemOneError(Exception):
    """Raised for configuration, validation, or upstream failures. Carries the
    HTTP status the gateway should answer with."""

    def __init__(self, status: int, detail: Any):
        super().__init__(str(detail))
        self.status = status
        self.detail = detail


@dataclass
class Upstream:
    provider: str
    base_url: str
    api_key: str
    model: str


def enabled() -> bool:
    return settings.systemone_provider in PROVIDERS


def upstream_for(model: str | None) -> Upstream:
    """Pick the backend for a call: an explicit kev*/jev* model wins, else the
    configured default provider."""
    m = (model or "").strip().lower()
    if m.startswith("jev"):
        provider = "jev"
    elif m.startswith("kev"):
        provider = "kev"
    else:
        provider = settings.systemone_provider

    if provider == "kev":
        base, key, default_model = settings.kev_base_url, settings.kev_api_key, settings.kev_model
    elif provider == "jev":
        base, key, default_model = settings.jev_base_url, settings.jev_api_key, settings.jev_model
    else:
        raise SystemOneError(503, "System One is not configured (set SYSTEMONE_PROVIDER to kev or jev)")
    if not base:
        raise SystemOneError(503, f"System One provider '{provider}' has no base URL ({provider.upper()}_BASE_URL)")
    return Upstream(provider=provider, base_url=base.rstrip("/"), api_key=key, model=model or default_model)


def validate(body: Any) -> None:
    """Check the documented hard constraints so a bad request fails fast with a
    clear 422 instead of after an upstream round trip (or a cold start). The
    upstream stays authoritative for everything else (token limits, etc.)."""
    if not isinstance(body, dict):
        raise SystemOneError(422, "body must be a JSON object")
    if body.get("state") is None:
        raise SystemOneError(422, "`state` is required (string, object, or array)")
    qs = body.get("questions")
    if not isinstance(qs, dict) or not qs:
        raise SystemOneError(422, "`questions` must be a non-empty object of {id: question}")
    for qid, q in qs.items():
        if not isinstance(q, dict):
            raise SystemOneError(422, f"question '{qid}' must be an object")
        qtype = q.get("type")
        if qtype not in QUESTION_TYPES:
            raise SystemOneError(422, f"question '{qid}': type must be one of {', '.join(QUESTION_TYPES)}")
        criteria = q.get("criteria")
        if qtype == "choice":
            if not isinstance(criteria, dict) or not (1 <= len(criteria) <= MAX_OPTIONS):
                raise SystemOneError(422, f"question '{qid}': choice criteria must map 1–{MAX_OPTIONS} option names to a description or null")
            bad = [k for k, v in criteria.items() if v is not None and not isinstance(v, str)]
            if bad:
                raise SystemOneError(422, f"question '{qid}': option descriptions must be strings or null ({', '.join(bad)})")
        elif qtype == "score":
            if not isinstance(criteria, list) or not (1 <= len(criteria) <= MAX_OPTIONS):
                raise SystemOneError(422, f"question '{qid}': score criteria must be a list of 1–{MAX_OPTIONS} levels, lowest first")
        elif criteria is not None and not isinstance(criteria, dict):
            raise SystemOneError(422, f"question '{qid}': noul criteria, if given, must be an object (true/false descriptions)")


def _headers(up: Upstream) -> dict:
    h = {"Content-Type": "application/json"}
    if up.api_key:
        h["Authorization"] = f"Bearer {up.api_key}"
    return h


def _summary(path: str, body: dict | None) -> dict:
    """Trace-friendly view of a request: which questions, of what type — the
    state itself can be large, so it is recorded capped by diagnostics."""
    qs = (body or {}).get("questions") or {}
    return {
        "path": path,
        "questions": {qid: (q or {}).get("type") for qid, q in qs.items()} if isinstance(qs, dict) else qs,
        "state": (body or {}).get("state"),
    }


async def forward(path: str, body: dict | None = None, method: str = "POST") -> tuple[int, Any, dict, Upstream]:
    """Send one request to the selected upstream and return
    ``(status, parsed_body, passthrough_headers, upstream)``. Never raises for an
    upstream HTTP error status — that is returned verbatim for the caller to
    relay; raises SystemOneError only when the upstream can't be reached."""
    up = upstream_for((body or {}).get("model"))
    if body is not None and not body.get("model") and up.model:
        body = {**body, "model": up.model}

    t0 = time.monotonic()
    try:
        async with httpx.AsyncClient(timeout=settings.systemone_timeout_seconds) as client:
            resp = await client.request(method, f"{up.base_url}{path}", json=body, headers=_headers(up))
    except httpx.TimeoutException:
        raise SystemOneError(504, f"{up.provider} timed out after {settings.systemone_timeout_seconds}s ({up.base_url})")
    except httpx.HTTPError as e:
        raise SystemOneError(503, f"{up.provider} unreachable at {up.base_url}: {e.__class__.__name__}")
    dur = int((time.monotonic() - t0) * 1000)

    try:
        parsed: Any = resp.json()
    except ValueError:
        parsed = {"detail": resp.text[:2000]}

    passthrough = {"x-systemone-provider": up.provider}
    rid = resp.headers.get("x-typesafe-request-id")
    if rid:
        passthrough["x-typesafe-request-id"] = rid

    # Attach as a round-trip on whatever trace is active (the HTTP route opens
    # one; internal callers inherit the turn's), tagged with the provider so the
    # /diag dashboard shows where the decision was routed.
    tr = diag.current_trace.get()
    diag.record_ollama_call(
        index=tr.num_calls if tr else 0,
        request={"provider": up.provider, "model": (body or {}).get("model"), **_summary(path, body)},
        response={"status": resp.status_code, **(parsed if isinstance(parsed, dict) else {"body": parsed})},
        duration_ms=dur,
    )
    return resp.status_code, parsed, passthrough, up


# --- internal API (smart approvals, browser control, …) ---------------------

async def decide(state: Any, questions: dict, model: str | None = None) -> dict:
    """Ask System One one or more typed questions about ``state``; return the
    parsed response (``{"model", "answers", "usage", "latency_ms"}``). Raises
    SystemOneError on validation, configuration, or upstream failure."""
    body: dict = {"state": state, "questions": questions}
    if model:
        body["model"] = model
    validate(body)
    status, parsed, _, _ = await forward("/v1/systemone", body)
    if status >= 400:
        raise SystemOneError(status, parsed)
    return parsed


async def noul(state: Any, instructions: str, criteria: dict | None = None, model: str | None = None) -> float:
    """Convenience: P(yes) for a single yes/no question about ``state``."""
    q: dict = {"type": "noul", "instructions": instructions}
    if criteria:
        q["criteria"] = criteria
    res = await decide(state, {"q": q}, model=model)
    return float(res["answers"]["q"]["noul"])
