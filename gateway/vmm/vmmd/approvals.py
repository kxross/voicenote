"""Command approval gate. In manual mode, every exec is held here until a
human approves or denies it via the API / TUI; the exec HTTP call blocks on
that decision (long-poll), so from the caller's side an awaited approval
just looks like a slow command — no special handling in the gateway tool or
the model. Auto mode bypasses the gate entirely.

Backend-neutral: it gates the *command*, not how the sandbox runs it.
"""

from __future__ import annotations

import asyncio
import time
import uuid
from dataclasses import dataclass, field


@dataclass
class Approval:
    id: str
    vm_id: str
    command: str
    created_at: float = field(default_factory=time.time)
    status: str = "pending"        # pending|approved|denied
    # Smart mode: the System One screening that flagged this command (verdict,
    # p_malicious, human-readable reason). None in manual mode.
    review: dict | None = None
    _event: asyncio.Event = field(default_factory=asyncio.Event, repr=False)

    def public(self) -> dict:
        return {
            "id": self.id, "vm_id": self.vm_id, "command": self.command,
            "created_at": self.created_at, "status": self.status,
            "age_seconds": int(time.time() - self.created_at),
            "review": self.review,
        }


class ApprovalGate:
    def __init__(self):
        self.pending: dict[str, Approval] = {}
        self._subs: set[asyncio.Queue] = set()

    # ---- request/wait (called by the exec route) --------------------------

    def request(self, vm_id: str, command: str, review: dict | None = None) -> Approval:
        appr = Approval(id=uuid.uuid4().hex[:8], vm_id=vm_id, command=command, review=review)
        self.pending[appr.id] = appr
        self._broadcast({"type": "new", "approval": appr.public()})
        return appr

    async def wait(self, appr: Approval, timeout: int) -> str:
        """Block until decided or timeout (auto-deny). Returns final status."""
        try:
            await asyncio.wait_for(appr._event.wait(), timeout=timeout)
        except asyncio.TimeoutError:
            appr.status = "denied"
            self._broadcast({"type": "resolved", "approval": appr.public(), "reason": "timeout"})
        finally:
            self.pending.pop(appr.id, None)
        return appr.status

    # ---- decide (called by the /approvals routes / TUI) -------------------

    def decide(self, approval_id: str, approved: bool) -> bool:
        appr = self.pending.get(approval_id)
        if appr is None:
            return False
        appr.status = "approved" if approved else "denied"
        appr._event.set()
        self._broadcast({"type": "resolved", "approval": appr.public()})
        return True

    def list(self) -> list[dict]:
        return [a.public() for a in self.pending.values()]

    # ---- SSE pub/sub ------------------------------------------------------

    def subscribe(self) -> asyncio.Queue:
        q: asyncio.Queue = asyncio.Queue(maxsize=100)
        self._subs.add(q)
        return q

    def unsubscribe(self, q: asyncio.Queue) -> None:
        self._subs.discard(q)

    def _broadcast(self, msg: dict) -> None:
        for q in list(self._subs):
            try:
                q.put_nowait(msg)
            except asyncio.QueueFull:
                pass
