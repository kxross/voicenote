"""Server-side cron scheduler for agent runs — DB-backed and dynamic.

Schedules live in `state.db` (the source of truth); this registers one
APScheduler cron job per enabled entry and reloads jobs live as the CRUD API
mutates the DB — no restart. `schedules.yml` is used only to seed an empty DB
on first boot.

On trigger (`_fire` → `_run_schedule`): load the entry fresh from the DB, build
the same {prompt, lists, notes, goals} payload the Android client sends, run the
agent directly (no HTTP), parse its {reply, ops} envelope, and fan out to the
pushers.

Each schedule carries its own IANA `timezone`; the job fires in that zone
(correct across DST). `tz_mode='fixed'` pins it; `tz_mode='device'` is re-timed
by the app as the phone travels.
"""

from __future__ import annotations

import json
import logging
from dataclasses import dataclass
from pathlib import Path
from zoneinfo import ZoneInfo, ZoneInfoNotFoundError

import yaml
from apscheduler.schedulers.asyncio import AsyncIOScheduler
from apscheduler.triggers.cron import CronTrigger

from app import diagnostics as diag
from app.delivery import parse_envelope
from app.push.pusher import Pusher
from app.registry import AgentRegistry
from app.schemas import ChatMessage
from app.state import State

log = logging.getLogger(__name__)


@dataclass
class ScheduleEntry:
    id: str
    cron: str
    model: str
    session_id: str
    prompt: str
    notification_title: str
    enabled: bool = True
    timezone: str = ""        # IANA; empty => scheduler's local zone
    tz_mode: str = "fixed"    # "fixed" | "device"

    @classmethod
    def from_row(cls, d: dict) -> "ScheduleEntry":
        return cls(
            id=d["id"], cron=d["cron"], model=d["model"],
            session_id=d.get("session_id") or "voicenote-default",
            prompt=d["prompt"], notification_title=d.get("notification_title") or "",
            enabled=bool(d.get("enabled", True)),
            timezone=d.get("timezone") or "", tz_mode=d.get("tz_mode") or "fixed",
        )


def load_yaml_schedules(path: str | Path) -> list[dict]:
    """Parse schedules.yml into dicts (for seeding an empty DB)."""
    p = Path(path)
    if not p.exists():
        return []
    with p.open() as f:
        raw = yaml.safe_load(f) or {}
    return list(raw.get("schedules", []))


def _trigger(entry: ScheduleEntry) -> CronTrigger:
    tz = None
    if entry.timezone:
        try:
            tz = ZoneInfo(entry.timezone)
        except (ZoneInfoNotFoundError, ValueError):
            log.warning("[scheduler] bad timezone %r for %s; using local", entry.timezone, entry.id)
    return CronTrigger.from_crontab(entry.cron, timezone=tz)


async def _fire(sid: str, state: State, pushers: list[Pusher], registry: AgentRegistry) -> None:
    """Job entry point: always loads the entry fresh so edits take effect."""
    row = await state.get_schedule(sid)
    if row is None or not row.get("enabled", True):
        return
    await _run_schedule(ScheduleEntry.from_row(row), state, pushers, registry)


# Scheduled runs executing right now, per session. GET /v1/pending reports it
# as `scheduled_running` so the phone keeps checking back instead of sleeping.
running: dict[str, int] = {}


async def _run_schedule(
    entry: ScheduleEntry,
    state: State,
    pushers: list[Pusher],
    registry: AgentRegistry,
) -> None:
    running[entry.session_id] = running.get(entry.session_id, 0) + 1
    try:
        await _run_schedule_inner(entry, state, pushers, registry)
    finally:
        n = running.get(entry.session_id, 1) - 1
        if n > 0:
            running[entry.session_id] = n
        else:
            running.pop(entry.session_id, None)


async def _run_schedule_inner(
    entry: ScheduleEntry,
    state: State,
    pushers: list[Pusher],
    registry: AgentRegistry,
) -> None:
    log.info("[scheduler] firing %s (model=%s)", entry.id, entry.model)
    agent = registry.get(entry.model)
    if agent is None:
        log.warning("[scheduler] unknown model %s for schedule %s", entry.model, entry.id)
        return

    snapshot = await state.get_snapshot(entry.session_id)
    notes = await state.get_notes(entry.session_id)
    goals = await state.get_goals(entry.session_id)
    payload = json.dumps(
        {"prompt": entry.prompt, "lists": snapshot, "notes": notes, "goals": goals}
    )
    msg = ChatMessage(role="user", content=payload)

    diag.start_trace(
        trigger=f"schedule:{entry.id}",
        model=entry.model,
        session=entry.session_id,
        request={"prompt": entry.prompt},
    )
    try:
        result = await agent.runtime.arun([msg], session_id=entry.session_id)
    except Exception as e:  # noqa: BLE001 - top-level scheduler safety net
        log.exception("[scheduler] agent %s raised for schedule %s: %s", entry.model, entry.id, e)
        await diag.finish_trace(state, "error", response={"error": repr(e)})
        return

    # Agents wrap output as {"reply", "ops", canvas?, steps?, ...}; the shared
    # parser turns that into a delivery + an honest trace status (paused with a
    # resume id / error / ok). Same parse the async interactive turn uses.
    env = parse_envelope(result)
    await diag.finish_trace(
        state, env.status, response=result.text, num_ops=len(env.ops),
        reply_preview=env.reply, resume_run_id=env.resume_run_id,
    )

    for pusher in pushers:
        try:
            await pusher.deliver(
                entry.session_id, entry.notification_title,
                env.reply, env.ops, env.extras,
            )
        except Exception as e:  # noqa: BLE001
            log.exception("[scheduler] pusher %s failed: %s", type(pusher).__name__, e)


class Scheduler:
    def __init__(self, state: State, pushers: list[Pusher], registry: AgentRegistry) -> None:
        self._scheduler = AsyncIOScheduler()
        self._state = state
        self._pushers = pushers
        self._registry = registry

    def start(self) -> None:
        self._scheduler.start()

    def shutdown(self) -> None:
        self._scheduler.shutdown(wait=False)

    # ---- dynamic registration ---------------------------------------------

    def _register(self, entry: ScheduleEntry) -> None:
        """Add or replace one job; remove it if disabled."""
        if not entry.enabled:
            self.remove(entry.id)
            return
        try:
            trigger = _trigger(entry)
        except ValueError as e:
            log.error("[scheduler] bad cron %r for %s: %s", entry.cron, entry.id, e)
            return
        self._scheduler.add_job(
            _fire,
            trigger=trigger,
            args=[entry.id, self._state, self._pushers, self._registry],
            id=entry.id,
            replace_existing=True,
        )
        log.info("[scheduler] registered %s @ %s (%s)", entry.id, entry.cron,
                 entry.timezone or "local")

    def add_or_update(self, row: dict) -> None:
        self._register(ScheduleEntry.from_row(row))

    def remove(self, schedule_id: str) -> None:
        if self._scheduler.get_job(schedule_id) is not None:
            self._scheduler.remove_job(schedule_id)
            log.info("[scheduler] removed %s", schedule_id)

    async def load_from_db(self) -> None:
        """(Re)register every schedule from the DB — used at startup."""
        for row in await self._state.list_schedules():
            self._register(ScheduleEntry.from_row(row))

    async def reload_ids(self, ids: list[str]) -> None:
        """Re-register specific schedules (e.g. after a device-tz change)."""
        for sid in ids:
            row = await self._state.get_schedule(sid)
            if row is not None:
                self._register(ScheduleEntry.from_row(row))

    def next_run_time(self, schedule_id: str):
        job = self._scheduler.get_job(schedule_id)
        return job.next_run_time if job is not None else None

    async def run_now(self, schedule_id: str) -> bool:
        row = await self._state.get_schedule(schedule_id)
        if row is None:
            return False
        await _run_schedule(ScheduleEntry.from_row(row), self._state, self._pushers, self._registry)
        return True
