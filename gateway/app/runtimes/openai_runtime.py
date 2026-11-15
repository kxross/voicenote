"""Runtime for OpenAI-wire-compatible providers (OpenRouter today).

Deliberately a sibling of OllamaRuntime rather than a subclass: the two share
a *shape* (hand-rolled tool loop, same ToolSpec currency, same fault-isolated
dispatch) but disagree on every wire detail. Keeping them separate means
neither is littered with `if provider == ...`.

Tool schemas are byte-identical between the two providers
(`{"type": "function", "function": {...}}`), so every skill in app/skills/
runs here unmodified. What differs:

  * `POST {base}/chat/completions` with a Bearer key (Ollama needs neither).
  * No `keep_alive` / `think` — those are Ollama-only.
  * Replies arrive at `choices[0].message`, not a top-level `message`.
  * Tool-call `arguments` is ALWAYS a JSON string, and every call carries an
    `id` that the matching tool result must echo back as `tool_call_id` —
    OpenAI 400s without it. Ollama has no such correlation.
  * Images ride as content-parts with a `data:` URI, not a bare base64 list.
  * Streaming is SSE (`data: {...}`), not newline-delimited JSON.
"""

from __future__ import annotations

import json
import time

import httpx

from app import diagnostics as diag
from app import tool_result
from app.agent_base import AgentResult, BaseAgentRuntime
from app.config import settings
from app.schemas import ChatMessage
from app.tools.memory import MemoryStore

ToolSpec = tuple[dict, callable]

PROVIDER = "openrouter"


class OpenAICompatRuntime(BaseAgentRuntime):
    def __init__(
        self,
        *,
        model: str,
        base_url: str | None = None,
        api_key: str | None = None,
        system_prompt: str | None = None,
        tools: list[ToolSpec] | None = None,
        memory: MemoryStore | None = None,
        max_tool_iterations: int | None = None,
        reasoning_effort: str | None = None,
        gate_provider: str = PROVIDER,
    ) -> None:
        self.model = model
        self.base_url = (base_url or settings.openrouter_base_url).rstrip("/")
        self.api_key = api_key if api_key is not None else settings.openrouter_api_key
        # Which concurrency gate + trace label this instance uses: "openrouter"
        # (default) or "vllm". Lets one OpenAI-wire runtime serve both providers,
        # each with its own gate (OpenRouter is latency-bound; a local vLLM wants
        # a high gate so its batching isn't throttled at the gateway).
        self.gate_provider = gate_provider
        self.system_prompt = system_prompt
        self.tools = tools or []
        self.memory = memory
        self.max_tool_iterations = (
            max_tool_iterations or settings.openrouter_max_tool_iterations
        )
        # OpenAI's analogue of Ollama's `think` is a string effort, not a bool.
        # None = omit entirely (correct for models that don't reason).
        self.reasoning_effort = reasoning_effort

    # -- tool plumbing -----------------------------------------------------

    def _tool_schemas(self) -> list[dict]:
        return [schema for schema, _ in self.tools]

    def _tool_fn(self, name: str):
        for schema, fn in self.tools:
            if schema["function"]["name"] == name:
                return fn
        return None

    def _headers(self) -> dict:
        headers = {"Content-Type": "application/json"}
        if self.api_key:
            headers["Authorization"] = f"Bearer {self.api_key}"
        # OpenRouter attribution — harmless on other OpenAI-compatible hosts.
        headers["HTTP-Referer"] = settings.openrouter_referer
        headers["X-Title"] = settings.openrouter_title
        return headers

    # -- message plumbing ---------------------------------------------------

    def _build_conversation(
        self, messages: list[ChatMessage], session_id: str | None
    ) -> list[dict]:
        # vLLM's Qwen3 chat template rejects more than one system message (and
        # any system message after a non-system turn) with a 400. The skillful
        # runtime legitimately sends several system messages (catalog prompt +
        # state snapshot, plus memory), so collapse ALL system content into one
        # leading system message. OpenRouter accepts that just as well; Ollama
        # has its own builder and is unaffected.
        system_parts: list[str] = []
        if self.system_prompt:
            system_parts.append(self.system_prompt)
        if self.memory and session_id:
            summary = self.memory.get(session_id).long_term_summary
            if summary:
                system_parts.append(f"Relevant memory: {summary}")
        body: list[dict] = []
        for m in messages:
            imgs = getattr(m, "images", None)
            if getattr(m, "role", None) == "system" and not imgs:
                # Fold the snapshot / any other system turn into the leading block.
                system_parts.append(m.content or "")
                continue
            if imgs:
                # OpenAI multimodal shape: content becomes an array of parts,
                # each image a data: URI. (Ollama takes a bare base64 list.)
                parts: list[dict] = [{"type": "text", "text": m.content}]
                for b64 in imgs:
                    parts.append({
                        "type": "image_url",
                        "image_url": {"url": f"data:image/jpeg;base64,{b64}"},
                    })
                body.append({"role": m.role, "content": parts})
            else:
                body.append({"role": m.role, "content": m.content})
        convo: list[dict] = []
        if system_parts:
            convo.append({"role": "system", "content": "\n\n".join(system_parts)})
        convo.extend(body)
        if self.memory and session_id and messages:
            self.memory.append_turn(session_id, messages[-1].role, messages[-1].content)
        return convo

    # -- raw provider calls -------------------------------------------------

    async def _call_once(self, convo: list[dict]) -> dict:
        payload: dict = {
            "model": self.model,
            "messages": convo,
            "stream": False,
        }
        if self.tools:
            payload["tools"] = self._tool_schemas()
        payload.update(self._reasoning_payload())

        t0 = time.monotonic()
        async with diag.gate(self.gate_provider):
            async with httpx.AsyncClient(timeout=300) as client:
                resp = await client.post(
                    f"{self.base_url}/chat/completions",
                    json=payload,
                    headers=self._headers(),
                )
                if resp.status_code >= 400:
                    # Surface the provider's actual error body (vLLM/OpenRouter
                    # put the reason here — context length, bad param, etc.),
                    # which raise_for_status() otherwise hides behind a bare code.
                    raise httpx.HTTPStatusError(
                        f"{resp.status_code} from {self.gate_provider} "
                        f"({self.model}): {resp.text[:600]}",
                        request=resp.request, response=resp,
                    )
                data = resp.json()
        dur = int((time.monotonic() - t0) * 1000)

        choice = (data.get("choices") or [{}])[0]
        msg = choice.get("message", {}) or {}
        usage = data.get("usage", {}) or {}
        diag.record_ollama_call(
            index=diag.current_trace.get().num_calls if diag.current_trace.get() else 0,
            request={
                "provider": self.gate_provider,
                "model": self.model,
                "reasoning": self.reasoning_effort,
                "num_messages": len(convo),
                "tools_offered": [s["function"]["name"] for s in self._tool_schemas()],
                "messages": convo,
            },
            response={
                "content": msg.get("content", ""),
                "tool_calls": msg.get("tool_calls", []),
                "finish_reason": choice.get("finish_reason"),
                "usage": usage,
            },
            duration_ms=dur,
        )
        return data

    def _reasoning_payload(self) -> dict:
        """Reasoning request fields, which differ by provider. OpenRouter nests
        it under `reasoning.effort`; vLLM's OpenAI server takes a top-level
        `reasoning_effort` (the qwen3 reasoning parser)."""
        if not self.reasoning_effort:
            return {}
        if self.gate_provider == "vllm":
            return {"reasoning_effort": self.reasoning_effort}
        return {"reasoning": {"effort": self.reasoning_effort}}

    async def _call_stream(self, convo: list[dict]):
        payload = {"model": self.model, "messages": convo, "stream": True}
        payload.update(self._reasoning_payload())
        async with diag.gate(self.gate_provider):
            async with httpx.AsyncClient(timeout=300) as client:
                async with client.stream(
                    "POST",
                    f"{self.base_url}/chat/completions",
                    json=payload,
                    headers=self._headers(),
                ) as resp:
                    resp.raise_for_status()
                    async for line in resp.aiter_lines():
                        # SSE: skip blanks, OpenRouter's ": OPENROUTER
                        # PROCESSING" keepalive comments, and the [DONE] tail.
                        if not line or line.startswith(":"):
                            continue
                        if not line.startswith("data:"):
                            continue
                        chunk = line[5:].strip()
                        if chunk == "[DONE]":
                            return
                        try:
                            yield json.loads(chunk)
                        except json.JSONDecodeError:
                            continue

    # -- tool loop ----------------------------------------------------------

    async def _resolve_tool_calls(self, convo: list[dict], message: dict) -> str | None:
        """Execute any requested tools, appending results to `convo` in place
        and returning None so the caller loops again. Returns the final text
        when the model asked for no tools.

        Same fault-isolation contract as the Ollama loop: a hallucinated tool
        name or bad kwargs comes back as readable tool output the model can
        recover from, never an exception.
        """
        tool_calls = message.get("tool_calls")
        if not tool_calls:
            return message.get("content", "") or ""

        tr = diag.current_trace.get()
        diag_call = tr.calls[-1] if (tr and tr.calls) else None

        # The assistant turn must be replayed verbatim (with its tool_calls)
        # before any tool results, or the provider rejects the correlation.
        convo.append({
            "role": "assistant",
            "content": message.get("content") or "",
            "tool_calls": tool_calls,
        })

        # Frames returned by tools this round (ToolResult). Tool messages must
        # stay plain text and follow the assistant turn one-to-one, so the
        # images go in ONE user message after all of them.
        frame_images: list[str] = []
        frame_captions: list[str] = []
        for call in tool_calls:
            call_id = call.get("id", "")
            fn_name = (call.get("function") or {}).get("name", "")
            raw_args = (call.get("function") or {}).get("arguments", "{}")
            try:
                # OpenAI always sends a JSON *string*; tolerate a dict anyway.
                args = json.loads(raw_args) if isinstance(raw_args, str) else raw_args
                if not isinstance(args, dict):
                    raise json.JSONDecodeError("arguments must be an object", "", 0)
            except json.JSONDecodeError as e:
                err = f"[error] arguments for {fn_name} are not valid JSON: {e}"
                diag.record_tool(diag_call, fn_name, raw_args, err, 0, error=str(e))
                convo.append(_tool_msg(call_id, fn_name, err))
                continue

            fn = self._tool_fn(fn_name)
            t0 = time.monotonic()
            tool_error = None
            if fn is None:
                result_text = f"[error] no such tool: {fn_name}"
                tool_error = "no such tool"
            else:
                try:
                    result_text = await fn(**args)
                except TypeError as e:
                    result_text = (
                        f"[error] invalid arguments for {fn_name}: {e}. "
                        f"Check the tool's schema and call it again with exactly "
                        f"the declared parameters."
                    )
                    tool_error = str(e)
                except Exception as e:  # noqa: BLE001 — tool bugs degrade, not 500
                    result_text = f"[error] tool {fn_name} failed: {e}"
                    tool_error = str(e)
            result_text, imgs, cap = tool_result.split(result_text)
            if imgs:
                frame_images += imgs
                frame_captions.append(cap)
            result_text = result_text if isinstance(result_text, str) else str(result_text)
            diag.record_tool(
                diag_call, fn_name, args, result_text,
                int((time.monotonic() - t0) * 1000), error=tool_error,
            )
            convo.append(_tool_msg(call_id, fn_name, result_text))
        if frame_images:
            tool_result.retire_frames(convo)
            convo.append(tool_result.frames_message_openai(frame_captions, frame_images))
        return None

    # -- BaseAgentRuntime interface -----------------------------------------

    async def arun(
        self, messages: list[ChatMessage], *, session_id: str | None = None
    ) -> AgentResult:
        convo = self._build_conversation(messages, session_id)
        usage_total: dict = {}
        for _ in range(self.max_tool_iterations):
            data = await self._call_once(convo)
            usage_total = data.get("usage", {}) or usage_total
            choice = (data.get("choices") or [{}])[0]
            text = await self._resolve_tool_calls(convo, choice.get("message", {}) or {})
            if text is not None:
                if self.memory and session_id:
                    self.memory.append_turn(session_id, "assistant", text)
                # Tokens cost money here, so unlike the local path we actually
                # report them.
                return AgentResult(text=text, usage=_usage(usage_total))
        return AgentResult(
            text="[gateway] max tool iterations exceeded",
            finish_reason="length",
            usage=_usage(usage_total),
        )

    async def astream(self, messages: list[ChatMessage], *, session_id: str | None = None):
        if self.tools:
            # Interleaving tool-call parsing with token deltas is more than
            # this scaffold needs; resolve tools first, then fake-stream.
            async for piece in super().astream(messages, session_id=session_id):
                yield piece
            return
        convo = self._build_conversation(messages, session_id)
        full: list[str] = []
        async for chunk in self._call_stream(convo):
            delta = ((chunk.get("choices") or [{}])[0].get("delta") or {})
            piece = delta.get("content") or ""
            if piece:
                full.append(piece)
                yield piece
        if self.memory and session_id:
            self.memory.append_turn(session_id, "assistant", "".join(full))


def _tool_msg(call_id: str, name: str, content: str) -> dict:
    """A tool result MUST echo the originating call's id — the whole reason
    this can't reuse the Ollama loop's `{"role": "tool", "content": ...}`."""
    msg = {"role": "tool", "content": content}
    if call_id:
        msg["tool_call_id"] = call_id
    if name:
        msg["name"] = name
    return msg


def _usage(u: dict) -> dict:
    if not u:
        return {}
    return {
        "prompt_tokens": u.get("prompt_tokens", 0),
        "completion_tokens": u.get("completion_tokens", 0),
        "total_tokens": u.get("total_tokens", 0),
    }
