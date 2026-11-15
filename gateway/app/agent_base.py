"""The one interface every agent — regardless of framework — must implement.

This is the entire point of Stack 3: the gateway, registry, and response
adapter only ever talk to `BaseAgentRuntime`. Whether a given agent is a
three-line Ollama call, a LangGraph ReAct graph, or a Claude Agent SDK
session is invisible past this boundary.
"""

from abc import ABC, abstractmethod
from collections.abc import AsyncIterator
from dataclasses import dataclass, field

from app.schemas import ChatMessage


@dataclass
class AgentResult:
    text: str
    finish_reason: str = "stop"
    usage: dict = field(default_factory=dict)


class BaseAgentRuntime(ABC):
    """Subclass this once per underlying framework, not once per agent.
    Individual agents are *instances* of a runtime with different
    system prompts / tools / models — see app/agents/definitions.py.
    """

    @abstractmethod
    async def arun(
        self,
        messages: list[ChatMessage],
        *,
        session_id: str | None = None,
    ) -> AgentResult:
        """Run to completion and return the final assistant text.

        session_id is whatever the client sent in the `user` field (or a
        header, if you wire that up in main.py) — use it to key into
        whatever memory/session store the agent has, if any.
        """
        raise NotImplementedError

    async def astream(
        self,
        messages: list[ChatMessage],
        *,
        session_id: str | None = None,
    ) -> AsyncIterator[str]:
        """Default implementation: run to completion, then fake-stream the
        result in word-ish chunks. Override this in a runtime if the
        underlying framework supports real incremental token streaming
        (the Ollama runtime does, as an example).
        """
        result = await self.arun(messages, session_id=session_id)
        words = result.text.split(" ")
        for i, word in enumerate(words):
            yield word if i == 0 else " " + word
