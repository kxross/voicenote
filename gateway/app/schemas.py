"""Minimal, hand-rolled subset of the OpenAI chat.completions schema —
enough for any OpenAI-SDK client (openai-python, curl, LangChain's
ChatOpenAI pointed at a custom base_url, etc.) to talk to this gateway
without knowing it isn't really OpenAI.

This is intentionally not exhaustive (no logprobs, no n>1, no tool-calling
surfaced to the client — see the README notes in main.py for why tool use
is kept server-side / invisible to callers). Extend as you need it.
"""

import time
import uuid
from typing import Literal

from pydantic import BaseModel, Field


class ChatMessage(BaseModel):
    role: Literal["system", "user", "assistant", "tool"]
    content: str
    # Base64 image payloads, provider-neutral: each runtime renders them in
    # its own wire shape (Ollama takes a bare list; the OpenAI-compatible
    # runtime converts them to content-parts with data: URIs). Only ever set
    # server-side, on the turn a note's attachments are first shown to the
    # model — never sent by clients.
    images: list[str] | None = None


class ChatCompletionRequest(BaseModel):
    model: str  # this is the *agent id*, not a real LLM name
    messages: list[ChatMessage]
    stream: bool = False
    temperature: float | None = None
    max_tokens: int | None = None
    user: str | None = None  # used as a session/memory key if present


class Usage(BaseModel):
    prompt_tokens: int = 0
    completion_tokens: int = 0
    total_tokens: int = 0


class ChatCompletionChoice(BaseModel):
    index: int = 0
    message: ChatMessage
    finish_reason: str = "stop"


class ChatCompletionResponse(BaseModel):
    id: str = Field(default_factory=lambda: f"chatcmpl-{uuid.uuid4().hex[:24]}")
    object: Literal["chat.completion"] = "chat.completion"
    created: int = Field(default_factory=lambda: int(time.time()))
    model: str
    choices: list[ChatCompletionChoice]
    usage: Usage = Field(default_factory=Usage)


class DeltaMessage(BaseModel):
    role: Literal["assistant"] | None = None
    content: str | None = None


class ChatCompletionChunkChoice(BaseModel):
    index: int = 0
    delta: DeltaMessage
    finish_reason: str | None = None


class ChatCompletionChunk(BaseModel):
    id: str
    object: Literal["chat.completion.chunk"] = "chat.completion.chunk"
    created: int = Field(default_factory=lambda: int(time.time()))
    model: str
    choices: list[ChatCompletionChunkChoice]


class ModelCard(BaseModel):
    id: str
    object: Literal["model"] = "model"
    created: int = Field(default_factory=lambda: int(time.time()))
    owned_by: str = "agent-gateway"


class ModelList(BaseModel):
    object: Literal["list"] = "list"
    data: list[ModelCard]
