"""Smart approvals: screen a sandbox command for malicious code with a System
One typed-decision model before it runs.

Used in approval_mode="smart". Clean commands run immediately; flagged ones
are held in the approval gate exactly like manual mode, carrying this review
so the person deciding sees *why* it was stopped.

The daemon asks the agent-gateway's ``/v1/systemone`` rather than a model
directly: that keeps one routing point for the Kev/Jev backends, and every
review shows up on the gateway's /diag dashboard.

Fail-closed: if the reviewer is unconfigured, unreachable, slow, or answers in
an unexpected shape, the command is flagged ("automatic review unavailable")
and held for a human — never silently run.
"""

from __future__ import annotations

import base64
import binascii
import logging
import re
import time

import httpx

log = logging.getLogger("vmmd.review")

# What the model is asked. The verdict carries the three-way judgment; the
# separate yes/no gives a calibrated P(malicious) for the threshold.
VERDICT_OPTIONS = {
    "safe": (
        "Ordinary work for the task: running, building or testing code, installing "
        "packages from standard registries, reading or writing project files, "
        "calling public APIs."
    ),
    "suspicious": (
        "Unusual or broader than the task — touches credentials, system "
        "configuration or other users' data, or downloads and runs something "
        "unvetted. A person should look before it runs."
    ),
    "malicious": (
        "Harmful: destroys data, exfiltrates secrets, opens a backdoor or reverse "
        "shell, escalates privileges, tries to escape the sandbox, mines crypto, "
        "or runs obfuscated code."
    ),
}

MALICIOUS_QUESTION = {
    "type": "noul",
    "instructions": "Is this command, or any code it runs, malicious or harmful?",
    "criteria": {
        "true": "It would damage, steal, persist, escalate, hide its intent, or escape the sandbox.",
        "false": "It is ordinary, benign work, even if it is broad or unusual.",
    },
}

_ENV = (
    "An isolated Linux sandbox that an AI agent uses to run code on the user's "
    "behalf. /workspace belongs to the agent. The agent wrote this command."
)

# Per-file cap on code sent for review (the state budget is large, but a giant
# generated file shouldn't dominate latency).
_MAX_FILE_CHARS = 6000

# --- deobfuscation ---------------------------------------------------------
# A language model can't read base64, so an encoded payload looks harmless to
# it. Two defences: (1) decode base64 blobs found in the command/code and show
# the model the real payload; (2) treat *executing* encoded content as always
# needing a human — legitimate agent work essentially never does that.
_B64_BLOB = re.compile(r"[A-Za-z0-9+/]{24,}={0,2}")
_EXEC_OF_DECODE = re.compile(
    r"\b(exec|eval)\s*\(.*?\b(b64decode|decodebytes|a85decode|b32decode|fromhex|unhexlify|"
    r"zlib\.decompress|marshal\.loads|codecs\.decode)",
    re.S,
)
_DECODE_PIPED_TO_RUNNER = re.compile(
    r"(base64\s+(-d|--decode)|xxd\s+-r|openssl\s+enc\s+-d)[^|;&]*\|\s*(sudo\s+)?"
    r"(sh|bash|zsh|dash|python3?|perl|ruby|node)\b"
)


def _decoded_payloads(texts: list[str], limit: int = 3) -> list[str]:
    """Readable base64 payloads embedded in the given texts."""
    out: list[str] = []
    for t in texts:
        for blob in _B64_BLOB.findall(t):
            try:
                raw = base64.b64decode(blob + "=" * (-len(blob) % 4), validate=True)
                txt = raw.decode("utf-8")
            except (binascii.Error, ValueError, UnicodeDecodeError):
                continue
            printable = sum(c.isprintable() or c in "\n\t" for c in txt) / max(1, len(txt))
            if printable >= 0.9 and txt.strip():
                out.append(txt[:500])
                if len(out) >= limit:
                    return out
    return out


def _runs_encoded_code(texts: list[str]) -> bool:
    return any(_EXEC_OF_DECODE.search(t) or _DECODE_PIPED_TO_RUNNER.search(t) for t in texts)


def _state(command: str, context: dict, decoded: list[str]) -> dict:
    st: dict = {"environment": _ENV}
    if context.get("user_request"):
        st["what the user asked for"] = str(context["user_request"])[:2000]
    if context.get("purpose"):
        st["the agent's stated purpose (unverified)"] = str(context["purpose"])[:300]
    st["command"] = command
    files = context.get("files") or {}
    if isinstance(files, dict) and files:
        st["code the command runs"] = {
            str(p): str(c)[:_MAX_FILE_CHARS] for p, c in list(files.items())[:4]
        }
    if decoded:
        st["base64 content embedded in it, decoded"] = decoded
    return st


def _texts(command: str, context: dict) -> list[str]:
    files = context.get("files") or {}
    return [command] + ([str(c) for c in files.values()] if isinstance(files, dict) else [])


def _unavailable(reason: str, started: float) -> dict:
    return {
        "flagged": True,
        "verdict": "unreviewed",
        "p_malicious": None,
        "confidence": None,
        "reason": f"Automatic review unavailable ({reason}) — approve only if you expected this.",
        "provider": None,
        "latency_ms": int((time.monotonic() - started) * 1000),
    }


async def screen(cfg, command: str, context: dict | None = None) -> dict:
    """Review one command. Returns a dict with ``flagged`` plus the verdict,
    P(malicious), a human-readable ``reason``, and which backend answered."""
    started = time.monotonic()
    context = context or {}
    texts = _texts(command, context)
    decoded = _decoded_payloads(texts)
    encoded_exec = _runs_encoded_code(texts)
    if not cfg.url:
        return _unavailable("no reviewer configured", started)

    body = {
        "state": _state(command, context, decoded),
        "questions": {
            "verdict": {
                "type": "choice",
                "instructions": "Should this run without a person checking it first?",
                "criteria": VERDICT_OPTIONS,
            },
            "malicious": MALICIOUS_QUESTION,
        },
    }
    headers = {"Content-Type": "application/json"}
    if cfg.api_key:
        headers["Authorization"] = f"Bearer {cfg.api_key}"

    try:
        async with httpx.AsyncClient(timeout=cfg.timeout) as client:
            resp = await client.post(cfg.url, json=body, headers=headers)
    except httpx.TimeoutException:
        return _unavailable("reviewer timed out", started)
    except httpx.HTTPError as e:
        return _unavailable(f"reviewer unreachable: {e.__class__.__name__}", started)

    if resp.status_code != 200:
        return _unavailable(f"reviewer returned HTTP {resp.status_code}", started)
    try:
        answers = resp.json()["answers"]
        verdict = answers["verdict"]["choice"]
        confidence = float(answers["verdict"].get("confidence", 0.0))
        p_mal = float(answers["malicious"]["noul"])
    except (ValueError, KeyError, TypeError):
        return _unavailable("unexpected reviewer response", started)

    flagged = encoded_exec or verdict in cfg.flag_verdicts or p_mal >= cfg.malicious_threshold
    provider = resp.headers.get("x-systemone-provider") or "systemone"
    pct = round(p_mal * 100)
    if encoded_exec:
        peek = f" Decoded: {decoded[0][:120]!r}" if decoded else ""
        reason = f"Runs encoded/obfuscated code ({pct}% likely harmful).{peek}"
    elif verdict == "malicious" or p_mal >= cfg.malicious_threshold:
        reason = f"Flagged as likely malicious ({pct}% likely harmful)."
    elif verdict == "suspicious":
        reason = f"Flagged as unusual for the task ({pct}% likely harmful)."
    else:
        reason = f"Looks safe ({pct}% likely harmful)."
    review = {
        "flagged": flagged,
        "verdict": verdict,
        "p_malicious": round(p_mal, 4),
        "confidence": round(confidence, 4),
        "reason": reason,
        "provider": provider,
        "encoded_exec": encoded_exec,
        "latency_ms": int((time.monotonic() - started) * 1000),
    }
    log.info("smart review [%s] %s p_mal=%.2f -> %s :: %s",
             provider, verdict, p_mal, "HOLD" if flagged else "run", command[:160])
    return review
