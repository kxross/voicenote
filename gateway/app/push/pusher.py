"""Delivery abstraction for scheduled-agent replies.

Two lanes are wired up:
  * PollingPusher (slow_lane) — writes to the state queue; the Android
    client drains via GET /v1/pending/{session}. Built and used today.
  * FcmPusher     (fast_lane) — real-time push via FCM. Stub for now;
    logs a TODO and no-ops. Wire up per docs/FCM_SETUP.md when you're
    ready for real-time delivery.

The scheduler fans a single delivery out to *all* registered pushers, so
adding FCM later is additive — no changes to the scheduler or the client
delivery-handling code (which is deliberately shared between the polling
worker and any future FCM service).
"""

from __future__ import annotations

import logging
from abc import ABC, abstractmethod

from app.state import State

log = logging.getLogger(__name__)


class Pusher(ABC):
    @abstractmethod
    async def deliver(
        self, session_id: str, title: str, body: str, ops: list,
        extras: dict | None = None,
    ) -> None:
        raise NotImplementedError


class PollingPusher(Pusher):
    """slow_lane: enqueue to SQLite. Delivered when the client next polls."""

    def __init__(self, state: State) -> None:
        self.state = state

    async def deliver(
        self, session_id: str, title: str, body: str, ops: list,
        extras: dict | None = None,
    ) -> None:
        delivery_id = await self.state.queue_delivery(session_id, title, body, ops, extras)
        log.info(
            "[PollingPusher] queued delivery %d for session=%s title=%r",
            delivery_id,
            session_id,
            title,
        )


class FcmPusher(Pusher):
    """fast_lane: FCM data push. Not implemented — see docs/FCM_SETUP.md.

    When wired up, this should send a data-only FCM message containing
    {title, body, ops_json} to the device token registered for session_id.
    The Android FirebaseMessagingService should route the payload to the
    same helpers the polling worker uses (applyOpsJson +
    postReplyNotification), so this path and the slow_lane land in one
    place on the client.
    """

    async def deliver(
        self, session_id: str, title: str, body: str, ops: list,
        extras: dict | None = None,
    ) -> None:
        log.warning(
            "[FcmPusher] TODO — FCM not configured; skipping session=%s title=%r",
            session_id,
            title,
        )
