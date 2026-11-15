"""Shapes whatever an AgentRuntime returns into valid OpenAI
chat.completion / chat.completion.chunk wire objects.

Nothing in here knows or cares which framework produced the text.
"""

import json
import uuid
from collections.abc import AsyncIterator

from app.agent_base import AgentResult, BaseAgentRuntime
from app.schemas import (
    ChatCompletionChoice,
    ChatCompletionChunk,
    ChatCompletionChunkChoice,
    ChatCompletionResponse,
    ChatMessage,
    DeltaMessage,
    Usage,
)


def to_chat_completion(result: AgentResult, *, model: str) -> ChatCompletionResponse:
    return ChatCompletionResponse(
        model=model,
        choices=[
            ChatCompletionChoice(
                message=ChatMessage(role="assistant", content=result.text),
                finish_reason=result.finish_reason,
            )
        ],
        usage=Usage(**result.usage) if result.usage else Usage(),
    )


async def stream_chat_completion(
    runtime: BaseAgentRuntime,
    messages: list[ChatMessage],
    *,
    model: str,
    session_id: str | None,
) -> AsyncIterator[bytes]:
    """Wraps runtime.astream(...) as OpenAI-shaped SSE `data: {...}\\n\\n`
    frames, terminated with `data: [DONE]\\n\\n` — exactly what the OpenAI
    Python SDK / any client using `stream=True` expects.
    """
    completion_id = f"chatcmpl-{uuid.uuid4().hex[:24]}"

    # First chunk carries the role, per OpenAI's convention.
    first = ChatCompletionChunk(
        id=completion_id,
        model=model,
        choices=[ChatCompletionChunkChoice(delta=DeltaMessage(role="assistant"))],
    )
    yield f"data: {first.model_dump_json()}\n\n".encode()

    async for piece in runtime.astream(messages, session_id=session_id):
        chunk = ChatCompletionChunk(
            id=completion_id,
            model=model,
            choices=[ChatCompletionChunkChoice(delta=DeltaMessage(content=piece))],
        )
        yield f"data: {chunk.model_dump_json()}\n\n".encode()

    final = ChatCompletionChunk(
        id=completion_id,
        model=model,
        choices=[ChatCompletionChunkChoice(delta=DeltaMessage(), finish_reason="stop")],
    )
    yield f"data: {final.model_dump_json()}\n\n".encode()
    yield b"data: [DONE]\n\n"
