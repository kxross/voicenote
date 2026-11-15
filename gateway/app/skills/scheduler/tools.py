"""Tools for the `schedules` skill — let the assistant propose recurring
scheduled runs (a cron job that fires an agent with a prompt).

APPROVAL GATE BY DESIGN: these tools never write a schedule. `propose_schedule`
only validates + drafts and records the draft on the per-request state; the
envelope carries it to the phone as an approval card, and the schedule is
created/updated only when the user taps Approve (the app then calls the
gateway's /v1/schedules CRUD). So a schedule change always needs explicit user
approval and always shows a draft first, independent of the sandbox approval
mode.

`list_schedules` is read-only (no approval) so the model can see what exists
before proposing an edit.
"""

from __future__ import annotations

import uuid
from collections.abc import Callable

ToolSpec = tuple[dict, Callable]

_DAYS = ["Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat"]


def _fmt_time(hour: str, minute: str) -> str:
    try:
        h, m = int(hour), int(minute)
        ampm = "AM" if h < 12 else "PM"
        hh = 12 if h % 12 == 0 else h % 12
        return f"{hh}:{m:02d} {ampm}"
    except (ValueError, TypeError):
        return f"{hour}:{minute}"


def _dow_days(dow: str) -> list[int] | None:
    """Expand a day-of-week field (commas + ranges, 0=Sun..6=Sat, 7->Sun) to a
    sorted list of 0..6, or None if it isn't plain."""
    out: set[int] = set()
    for part in dow.split(","):
        part = part.strip()
        if "-" in part:
            a, b = part.split("-", 1)
            if not (a.isdigit() and b.isdigit()):
                return None
            lo, hi = int(a), int(b)
            if lo > hi:
                return None
            for d in range(lo, hi + 1):
                out.add(d % 7)
        elif part.isdigit():
            out.add(int(part) % 7)
        else:
            return None
    return sorted(out) if out else None


def _describe_cron(cron: str) -> str:
    """A friendly plain-English gloss of a 5-field cron, for the draft. Falls
    back to the raw cron for anything it can't phrase cleanly."""
    f = (cron or "").split()
    if len(f) != 5:
        return f"cron: {cron}"
    minute, hour, dom, mon, dow = f
    # Only phrase the simple, common shapes; anything with steps/ranges in
    # minute/hour/month falls back to the raw cron.
    if mon != "*" or not minute.isdigit():
        return f"cron: {cron}"
    if dom == "*" and dow == "*":
        if hour == "*":
            return f"every hour at :{int(minute):02d}"
        if not hour.isdigit():
            return f"cron: {cron}"
        return f"every day at {_fmt_time(hour, minute)}"
    if dom == "*" and dow != "*" and hour.isdigit():
        days = _dow_days(dow)
        if days is None:
            return f"cron: {cron}"
        if days == [1, 2, 3, 4, 5]:
            label = "weekday"
        elif days == [0, 6]:
            label = "weekend"
        else:
            label = ", ".join(_DAYS[d] for d in days)
        return f"every {label} at {_fmt_time(hour, minute)}"
    if dom.isdigit() and dow == "*" and hour.isdigit():
        return f"on day {dom} of each month at {_fmt_time(hour, minute)}"
    return f"cron: {cron}"


LIST_SCHEDULES_SCHEMA = {
    "type": "function",
    "function": {
        "name": "list_schedules",
        "description": (
            "List the user's existing recurring schedules (id, title, time, "
            "agent, on/off). Read-only. Call this before proposing an EDIT so you "
            "have the schedule's id, or to answer a question about what's set up."
        ),
        "parameters": {"type": "object", "properties": {}, "required": []},
    },
}

PROPOSE_SCHEDULE_SCHEMA = {
    "type": "function",
    "function": {
        "name": "propose_schedule",
        "description": (
            "Propose creating or editing a recurring schedule — a cron job that "
            "fires an agent with a prompt (e.g. 'every weekday at 8am, assistant: "
            "summarize my unread'). This does NOT create the schedule: it drafts "
            "it and sends it to the user for approval on their phone. ALWAYS tell "
            "the user what you drafted (time + what it will do) and that they need "
            "to approve it. For an edit, first call list_schedules to get the id."
        ),
        "parameters": {
            "type": "object",
            "properties": {
                "action": {
                    "type": "string",
                    "description": "'create' for a new schedule, 'update' to edit an existing one.",
                },
                "title": {
                    "type": "string",
                    "description": "Short name shown in the notification/title, e.g. 'Morning groceries'.",
                },
                "cron": {
                    "type": "string",
                    "description": "5-field cron: 'minute hour day-of-month month day-of-week' (dow 0=Sun..6=Sat). E.g. '0 8 * * 1-5' = weekdays 8:00.",
                },
                "prompt": {
                    "type": "string",
                    "description": "What the agent should do each time it fires.",
                },
                "agent": {
                    "type": "string",
                    "description": "Which agent runs it: 'assistant' (default) or 'list_manager'.",
                },
                "timezone": {
                    "type": "string",
                    "description": "Optional IANA zone (e.g. 'America/New_York'). Omit to use the user's device zone.",
                },
                "tz_mode": {
                    "type": "string",
                    "description": "'device' (follows the user as they travel, default) or 'fixed' (pinned zone).",
                },
                "enabled": {
                    "type": "boolean",
                    "description": "Whether it's active (default true).",
                },
                "schedule_id": {
                    "type": "string",
                    "description": "Required for action='update': the id from list_schedules.",
                },
            },
            "required": ["action", "title", "cron", "prompt"],
        },
    },
}


def build_tools(state: dict) -> list[ToolSpec]:
    async def list_schedules() -> str:
        st = state.get("app_state")
        if st is None:
            return "[error] the scheduler is not available in this context."
        try:
            rows = await st.list_schedules()
        except Exception as e:  # noqa: BLE001
            return f"[error] couldn't read schedules: {e}"
        if not rows:
            return "No schedules are set up yet."
        lines = []
        for r in rows:
            on = "on" if r.get("enabled") else "off"
            tz = r.get("timezone") or "server"
            lines.append(
                f"- id={r['id']} | {r.get('notification_title') or '(untitled)'} | "
                f"{_describe_cron(r.get('cron', ''))} [{r.get('cron')}] | "
                f"agent={r.get('model')} | {on} | tz={tz}/{r.get('tz_mode')}"
            )
        return "Current schedules:\n" + "\n".join(lines)

    async def propose_schedule(
        action: str, title: str, cron: str, prompt: str,
        agent: str = "assistant", timezone: str = "", tz_mode: str = "device",
        enabled: bool = True, schedule_id: str = "",
    ) -> str:
        action = (action or "create").strip().lower()
        if action not in ("create", "update"):
            return "[error] action must be 'create' or 'update'."
        cron = (cron or "").strip()
        try:
            from apscheduler.triggers.cron import CronTrigger
            CronTrigger.from_crontab(cron)
        except Exception as e:  # noqa: BLE001
            return (
                f"[error] invalid cron '{cron}': {e}. Use 5 fields: "
                "'minute hour day-of-month month day-of-week' (dow 0=Sun..6=Sat)."
            )
        if action == "update" and not (schedule_id or "").strip():
            return "[error] action='update' needs schedule_id — call list_schedules first."
        tzm = tz_mode if tz_mode in ("fixed", "device") else "device"
        prop = {
            "id": uuid.uuid4().hex[:8],
            "action": action,
            "schedule_id": (schedule_id or "").strip(),
            "title": (title or "").strip() or "(untitled)",
            "cron": cron,
            "agent": (agent or "assistant").strip() or "assistant",
            "prompt": (prompt or "").strip(),
            "timezone": (timezone or "").strip(),
            "tz_mode": tzm,
            "enabled": bool(enabled),
            "summary": _describe_cron(cron),
        }
        state.setdefault("schedule_proposals", []).append(prop)
        verb = "create a new schedule" if action == "create" else f"edit schedule {prop['schedule_id']}"
        return (
            f"Drafted a proposal to {verb}: '{prop['title']}' — {prop['summary']} "
            f"(cron '{cron}'), run by the {prop['agent']} agent, which will: "
            f"\"{prop['prompt'][:100]}\". This is a DRAFT ONLY — it is NOT active. "
            "The user must approve it on their phone. In your reply, describe the "
            "schedule you drafted and ask them to approve it."
        )

    return [
        (LIST_SCHEDULES_SCHEMA, list_schedules),
        (PROPOSE_SCHEDULE_SCHEMA, propose_schedule),
    ]


__all__ = ["build_tools"]
