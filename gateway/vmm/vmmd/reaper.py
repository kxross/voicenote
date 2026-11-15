"""Idle-TTL reaper. Every VM carries a ttl_minutes; when it goes untouched
that long, the reaper destroys it (exporting /workspace first — Stage 3
wires the export in). ttl_minutes <= 0 means "never reap" (pin the VM).
"""

from __future__ import annotations

import asyncio
import logging
import time

from .manager import Manager

log = logging.getLogger("vmmd.reaper")


class Reaper:
    def __init__(self, manager: Manager, interval: int = 30):
        self.manager = manager
        self.interval = interval
        self._task: asyncio.Task | None = None

    def start(self) -> None:
        self._task = asyncio.create_task(self._loop())

    async def stop(self) -> None:
        if self._task:
            self._task.cancel()
            try:
                await self._task
            except asyncio.CancelledError:
                pass

    async def _loop(self) -> None:
        while True:
            try:
                await asyncio.sleep(self.interval)
                await self._sweep()
            except asyncio.CancelledError:
                raise
            except Exception:  # noqa: BLE001 — a bad sweep must not kill the loop
                log.exception("reaper sweep failed")

    async def _sweep(self) -> None:
        now = time.time()
        for rec in self.manager.list():
            if rec.ttl_minutes <= 0 or rec.state != "running":
                continue
            idle = now - rec.last_activity
            if idle >= rec.ttl_minutes * 60:
                log.info("reaping %s (idle %ds >= ttl %dm)", rec.id, int(idle), rec.ttl_minutes)
                try:
                    await self.manager.destroy(rec.id)
                except Exception:  # noqa: BLE001
                    log.exception("failed to reap %s", rec.id)
