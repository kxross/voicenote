"""Shared agent-envelope parsing for the async delivery lanes.

Both the scheduler (`scheduler._run_schedule`) and the async interactive turn
(`main.chat_completions` with `X-Async`) run an agent, then have to turn its
`{reply, ops, canvas, steps, artifacts, resume, schedule_proposals, no_response,
resolve_thread}` envelope into (a) a trace status and (b) a delivery for the
polling pusher. That translation lived inline in the scheduler; it's lifted here
so the two paths can't drift.
"""

from __future__ import annotations

import json
from dataclasses import dataclass, field


# Envelope keys that ride along to the client untouched (same shape as a live
# reply), so a queued turn surfaces widgets / documents / resume cards too.
_EXTRA_KEYS = ("canvas", "steps", "artifacts", "resume", "schedule_proposals", "wiki_proposals", "sources")


@dataclass
class Envelope:
    reply: str
    ops: list = field(default_factory=list)
    # The passthrough extras (canvas/steps/artifacts/resume/schedule_proposals),
    # or None when the envelope carried none.
    extras: dict | None = None
    resume: dict | None = None
    # Trace status honesty: a run that paused at a checkpoint is "paused" (+ a
    # resume id for one-tap Continue); a model failure that returned a partial
    # rather than raising is "error"; otherwise "ok".
    status: str = "ok"
    resume_run_id: str | None = None
    # The agent chose to stay silent — note saved, nothing to deliver.
    no_response: bool = False
    # The agent closed the thread this turn.
    resolve: bool = False


def parse_envelope(result) -> Envelope:
    """Translate an AgentResult into an Envelope. Tolerant of plain-text (non
    JSON) output, which becomes the reply with no ops/extras."""
    reply = result.text
    ops: list = []
    extras: dict | None = None
    resume: dict | None = None
    no_response = False
    resolve = False

    try:
        parsed = json.loads(result.text)
    except (json.JSONDecodeError, TypeError):
        parsed = None

    if isinstance(parsed, dict):
        reply = str(parsed.get("reply", result.text))
        ops = list(parsed.get("ops", []))
        e = {k: parsed[k] for k in _EXTRA_KEYS if parsed.get(k)}
        extras = e or None
        r = parsed.get("resume")
        resume = r if isinstance(r, dict) else None
        no_response = bool(parsed.get("no_response"))
        resolve = bool(parsed.get("resolve_thread"))

    if resume:
        status, resume_run_id = "paused", resume.get("run_id")
    elif getattr(result, "finish_reason", None) == "error":
        status, resume_run_id = "error", None
    else:
        status, resume_run_id = "ok", None

    return Envelope(
        reply=reply, ops=ops, extras=extras, resume=resume,
        status=status, resume_run_id=resume_run_id,
        no_response=no_response, resolve=resolve,
    )
