"""PLACEHOLDER chat-session / memory store, in-process and non-persistent —
everything is lost on restart and shared across all workers is not handled.
Swap the storage in `MemoryStore` for Redis/Postgres/a vector store once
you need durability, multi-worker consistency, or semantic recall across
sessions.

Deliberately storage-format agnostic: each runtime decides how to use
`recent_turns` / `long_term_summary` (e.g. prepend as a system message,
feed into a LangGraph checkpointer, etc.).
"""

from collections import defaultdict
from dataclasses import dataclass, field


@dataclass
class SessionState:
    recent_turns: list[dict] = field(default_factory=list)
    long_term_summary: str | None = None


class MemoryStore:
    def __init__(self) -> None:
        self._sessions: dict[str, SessionState] = defaultdict(SessionState)

    def get(self, session_id: str) -> SessionState:
        return self._sessions[session_id]

    def append_turn(self, session_id: str, role: str, content: str) -> None:
        state = self._sessions[session_id]
        state.recent_turns.append({"role": role, "content": content})
        # TODO: once recent_turns grows past some threshold, summarize the
        # oldest turns into long_term_summary (e.g. via a cheap LLM call)
        # instead of letting the transcript grow unbounded.

    def set_summary(self, session_id: str, summary: str) -> None:
        self._sessions[session_id].long_term_summary = summary


# Process-wide singleton for the scaffold. Replace with a proper store
# (and inject it, rather than importing a global) once this needs to
# survive a restart or be shared across workers.
memory_store = MemoryStore()
