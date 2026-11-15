"""Runtime #1: a plain, dependency-light agent that talks straight to a
local Ollama server over its native /api/chat endpoint, with a hand-rolled
tool-call loop. No LangChain/LangGraph involved — this is the "just HTTP
calls" end of the spectrum, useful for simple agents that don't need a full
orchestration framework.

Ollama's native tool-calling shape (for models that support it, e.g. Qwen):
  request:  {"model": ..., "messages": [...], "tools": [...], "stream": bool}
  response: {"message": {"role": "assistant", "content": "...",
                          "tool_calls": [{"function": {"name": ..., "arguments": {...}}}]},
             "done": bool, ...}

If the target model doesn't support tool calling, just construct this
runtime with tools=[] — it degrades to a plain chat loop.
"""

import json
import logging
import time

import httpx

from app import diagnostics as diag
from app import tool_result
from app.agent_base import AgentResult, BaseAgentRuntime
from app.config import settings
from app.schemas import ChatMessage
from app.tools.memory import MemoryStore

log = logging.getLogger(__name__)

ToolSpec = tuple[dict, callable]  # (ollama tool schema, async fn(**kwargs) -> str)

# num_ctx resolved once per model name (see OllamaRuntime._num_ctx). Shared
# across runtime instances since the trained context window is a property of
# the model, not the agent.
_NUM_CTX_CACHE: dict[tuple[str, bool], int] = {}


def _cap_result(text: str, limit: int) -> str:
    """Bound a tool result before it goes back to the model, so one big output
    can't flood the context window. Keeps the head and points the model at the
    file-and-grep pattern for the rest."""
    text = text if isinstance(text, str) else str(text)
    if limit <= 0 or len(text) <= limit:
        return text
    dropped = len(text) - limit
    return (
        text[:limit]
        + f"\n\n…[truncated {dropped} chars. This output is large — if you need "
        "more, save it to a file in work/ and grep/extract just the part you need, "
        "rather than printing it all.]"
    )


def _tool_sig(tool_calls) -> tuple | None:
    """A hashable signature of a tool-call batch, for detecting a model that
    keeps issuing the exact same call. None when there are no tool calls."""
    if not tool_calls:
        return None
    parts = []
    for c in tool_calls:
        fn = c.get("function", {}) if isinstance(c, dict) else {}
        name = fn.get("name", "")
        args = fn.get("arguments", "")
        if not isinstance(args, str):
            try:
                args = json.dumps(args, sort_keys=True, default=str)
            except (TypeError, ValueError):
                args = str(args)
        parts.append((name, args))
    return tuple(parts)


class OllamaRuntime(BaseAgentRuntime):
    def __init__(
        self,
        *,
        model: str,
        base_url: str | None = None,
        system_prompt: str | None = None,
        tools: list[ToolSpec] | None = None,
        memory: MemoryStore | None = None,
        think: bool | None = None,
        should_stop: "callable | None" = None,
    ) -> None:
        self.model = model
        self.base_url = (base_url or settings.ollama_base_url).rstrip("/")
        self.system_prompt = system_prompt
        self.tools = tools or []
        self.memory = memory
        # Optional predicate checked after each tool batch: when it returns True
        # (e.g. the model called resolve_thread / no_response) the loop ends
        # instead of handing the model yet another turn.
        self.should_stop = should_stop
        # Per-agent thinking override; None falls back to settings.ollama_think.
        # Tool-heavy agents on qwen3 are measurably more reliable with
        # thinking on, so they may opt in even when the global default is off.
        self.think = think if think is not None else settings.ollama_think

    # -- tool plumbing -----------------------------------------------------

    def _tool_schemas(self) -> list[dict]:
        return [schema for schema, _ in self.tools]

    def _tool_fn(self, name: str):
        for schema, fn in self.tools:
            if schema["function"]["name"] == name:
                return fn
        return None

    # -- message/session plumbing ------------------------------------------

    def _build_conversation(self, messages: list[ChatMessage], session_id: str | None) -> list[dict]:
        convo: list[dict] = []
        if self.system_prompt:
            convo.append({"role": "system", "content": self.system_prompt})
        if self.memory and session_id:
            summary = self.memory.get(session_id).long_term_summary
            if summary:
                convo.append({"role": "system", "content": f"Relevant memory: {summary}"})
        for m in messages:
            entry = {"role": m.role, "content": m.content}
            # Ollama's native multimodal shape: base64 images on the message.
            # Only turn-1 attachments carry these; everything later rides on
            # cached captions instead.
            imgs = getattr(m, "images", None)
            if imgs:
                entry["images"] = imgs
            convo.append(entry)
        if self.memory and session_id and messages:
            self.memory.append_turn(session_id, messages[-1].role, messages[-1].content)
        return convo

    # -- raw Ollama calls ----------------------------------------------------

    async def _num_ctx(self) -> int:
        """Target context window = ctx_fraction × the model's trained max
        context, read once from Ollama's /api/show and cached per (model, scope).
        Falls back to settings.ollama_num_ctx_fallback when the window can't be
        determined. Never raises — a sizing hiccup must not fail a turn.

        A call made inside a sub-agent fan-out (subagent_depth > 0) is clamped to
        the smaller settings.subagent_num_ctx so two sub-agent contexts fit in
        VRAM at once; top-level turns keep the full ollama_num_ctx_max window."""
        sub = diag.subagent_depth.get() > 0
        cap = settings.subagent_num_ctx if sub else settings.ollama_num_ctx_max
        key = (self.model, sub)
        cached = _NUM_CTX_CACHE.get(key)
        if cached is not None:
            return cached
        n = settings.ollama_num_ctx_fallback
        try:
            async with httpx.AsyncClient(timeout=30) as client:
                resp = await client.post(
                    f"{self.base_url}/api/show", json={"model": self.model}
                )
                resp.raise_for_status()
                info = resp.json().get("model_info", {}) or {}
            # The context length lives under an architecture-prefixed key, e.g.
            # "qwen3.context_length" — pick the first key that ends with it.
            max_ctx = next(
                (int(v) for k, v in info.items()
                 if k.endswith(".context_length") and isinstance(v, (int, float))),
                None,
            )
            if max_ctx:
                # 0.8× the trained window, but clamped to a VRAM-safe ceiling —
                # some models advertise a 256k window whose full KV cache would
                # OOM the GPU and 500 the very runs we're trying to save. The cap
                # is tighter for sub-agents so two fit side by side.
                scaled = int(max_ctx * settings.ollama_ctx_fraction)
                n = max(2048, min(scaled, cap))
                log.info("[ollama] %s context_length=%d -> num_ctx=%d (%.0f%%, cap=%d, sub=%s)",
                         self.model, max_ctx, n, settings.ollama_ctx_fraction * 100,
                         cap, sub)
        except Exception as e:  # noqa: BLE001 — never fail a turn over sizing
            log.warning("[ollama] /api/show failed for %s (%s); num_ctx=%d fallback",
                        self.model, e, n)
        _NUM_CTX_CACHE[key] = n
        return n

    async def _call_once(self, convo: list[dict]) -> dict:
        # keep_alive holds the model in VRAM between calls — without it,
        # Ollama's 5-minute default means infrequent requests pay a full
        # large-model reload on top of generation time.
        num_ctx = await self._num_ctx()
        payload = {
            "model": self.model,
            "messages": convo,
            "stream": False,
            "keep_alive": "30m",
            "think": self.think,
            "options": {"num_ctx": num_ctx},
        }
        if self.tools:
            payload["tools"] = self._tool_schemas()

        # The concurrency gate serialises Ollama across the whole gateway
        # (default 1); the trace record captures this round trip.
        t0 = time.monotonic()
        async with diag.ollama_gate():
            async with httpx.AsyncClient(timeout=300) as client:
                resp = await client.post(f"{self.base_url}/api/chat", json=payload)
                resp.raise_for_status()
                data = resp.json()
        dur = int((time.monotonic() - t0) * 1000)

        msg = data.get("message", {})
        thinking = msg.get("thinking") or ""
        diag.record_ollama_call(
            index=diag.current_trace.get().num_calls if diag.current_trace.get() else 0,
            request={
                # Local Ollama. "ollama-sub" when running as a delegated
                # sub-agent (depth>0), so the diag UI can tell them apart.
                "provider": "ollama-sub" if diag.subagent_depth.get() > 0 else "ollama",
                "model": self.model,
                "think": self.think,
                "keep_alive": "30m",
                "num_ctx": num_ctx,
                "num_messages": len(convo),
                "tools_offered": [s["function"]["name"] for s in self._tool_schemas()],
                "messages": convo,
            },
            response={
                "content": msg.get("content", ""),
                "tool_calls": msg.get("tool_calls", []),
                "thinking": thinking,
                "done_reason": data.get("done_reason"),
                "eval_count": data.get("eval_count"),
            },
            duration_ms=dur,
            thinking=thinking,
        )
        return data

    async def _call_stream(self, convo: list[dict]):
        payload = {
            "model": self.model,
            "messages": convo,
            "stream": True,
            "keep_alive": "30m",
            "think": self.think,
            "options": {"num_ctx": await self._num_ctx()},
        }
        # Gate the streaming path too so concurrency stays capped. Detailed
        # per-round-trip tracing targets the (non-streaming) tool loop; here
        # we just hold the gate for the stream's lifetime.
        async with diag.ollama_gate():
            async with httpx.AsyncClient(timeout=300) as client:
                async with client.stream("POST", f"{self.base_url}/api/chat", json=payload) as resp:
                    resp.raise_for_status()
                    async for line in resp.aiter_lines():
                        if line:
                            yield json.loads(line)

    async def _resolve_tool_calls(self, convo: list[dict], message: dict) -> str | None:
        """If `message` requests tool calls, execute them, append results to
        `convo` in place, and return None (caller should loop again). If no
        tool calls were requested, return the final text.
        """
        tool_calls = message.get("tool_calls")
        if not tool_calls:
            return message.get("content", "")

        # Attach tool events to the call this message came from (the last
        # recorded round trip in this request's trace).
        tr = diag.current_trace.get()
        diag_call = tr.calls[-1] if (tr and tr.calls) else None

        convo.append(message)
        # Frames returned by tools this round (ToolResult); shown in one
        # follow-up user message after all the tool results.
        frame_images: list[str] = []
        frame_captions: list[str] = []
        for call in tool_calls:
            fn_name = call["function"]["name"]
            raw_args = call["function"].get("arguments", {})
            try:
                args = json.loads(raw_args) if isinstance(raw_args, str) else raw_args
            except json.JSONDecodeError as e:
                err = f"[error] arguments for {fn_name} are not valid JSON: {e}"
                diag.record_tool(diag_call, fn_name, raw_args, err, 0, error=str(e))
                convo.append({"role": "tool", "content": err})
                continue
            fn = self._tool_fn(fn_name)
            t0 = time.monotonic()
            tool_error = None
            if fn is None:
                result_text = f"[error] no such tool: {fn_name}"
                tool_error = "no such tool"
            else:
                # Models hallucinate argument names (e.g. set_plan(sources=...)).
                # A bad call must come back as a tool-result error the model can
                # read and retry — never crash the whole request.
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
            # Bound what the model sees, so a giant output can't flood context.
            result_text = _cap_result(result_text, settings.max_tool_result_chars)
            diag.record_tool(
                diag_call, fn_name, args, result_text,
                int((time.monotonic() - t0) * 1000), error=tool_error,
            )
            convo.append({"role": "tool", "content": result_text})
        if frame_images:
            tool_result.retire_frames(convo)
            convo.append(tool_result.frames_message_ollama(frame_captions, frame_images))
        return None

    async def _closing_text(self, convo: list[dict]) -> str:
        """One final model call with NO tools offered, to force a short text
        reply when the loop ended (resolve/guard/cap) without the model ever
        writing prose. Falls back to a generic line if that too fails."""
        msgs = convo + [{
            "role": "system",
            "content": (
                "Reply to the user now in one or two short sentences based on "
                "what you did and the results above. Do not call any tools."
            ),
        }]
        payload = {
            "model": self.model, "messages": msgs, "stream": False,
            "keep_alive": "30m", "think": False,
            "options": {"num_ctx": await self._num_ctx()},
        }
        try:
            async with diag.ollama_gate():
                async with httpx.AsyncClient(timeout=120) as client:
                    resp = await client.post(f"{self.base_url}/api/chat", json=payload)
                    resp.raise_for_status()
                    data = resp.json()
            return (data.get("message", {}).get("content") or "").strip() or "Done."
        except Exception:  # noqa: BLE001 — never fail the request on the fallback
            return "Done."

    # -- BaseAgentRuntime interface ------------------------------------------

    async def arun(self, messages: list[ChatMessage], *, session_id: str | None = None) -> AgentResult:
        convo = self._build_conversation(messages, session_id)
        last_content = ""      # best answer seen so far (even alongside tool calls)
        prev_sig: tuple | None = None
        repeats = 0
        nudged = False

        async def _finish(text: str, reason: str = "stop") -> AgentResult:
            # Ensure a non-empty reply: if the model only ever called tools and
            # never wrote prose, ask once more for a plain-text summary.
            reply = (text or "").strip() or await self._closing_text(convo)
            if self.memory and session_id and reply:
                self.memory.append_turn(session_id, "assistant", reply)
            return AgentResult(text=reply, finish_reason=reason)

        for _ in range(settings.max_tool_iterations):
            try:
                data = await self._call_once(convo)
            except Exception as e:  # noqa: BLE001 — model/transport failure mid-loop
                # Don't let one bad round trip kill the whole run. Return the best
                # answer so far with an "error" finish_reason; the skillful runtime
                # turns that into a one-tap resume for a checkpointed research run,
                # or a clean partial otherwise. No extra model call here — the
                # model is the thing that just failed.
                log.warning("[ollama] model call failed mid-loop (%s): %s",
                            type(e).__name__, e)
                reply = (last_content or "").strip()
                if self.memory and session_id and reply:
                    self.memory.append_turn(session_id, "assistant", reply)
                return AgentResult(text=reply, finish_reason="error")
            msg = data["message"]
            if msg.get("content"):
                last_content = msg["content"]

            # (3) Loop guard: count identical consecutive tool-call batches.
            sig = _tool_sig(msg.get("tool_calls"))
            if sig is not None and sig == prev_sig:
                repeats += 1
            else:
                repeats, prev_sig = 0, sig

            text = await self._resolve_tool_calls(convo, msg)
            if text is not None:
                # A turn with no tool calls — this is the final answer.
                if self.memory and session_id:
                    self.memory.append_turn(session_id, "assistant", text)
                return AgentResult(text=text)

            # (1) Control tools (resolve_thread / no_response) asked to finish.
            if self.should_stop is not None and self.should_stop():
                return await _finish(last_content, "stop")

            # (3) The model is repeating the same call — nudge once, then break.
            if repeats >= 1 and not nudged:
                convo.append({
                    "role": "system",
                    "content": (
                        "You already ran that and have the result above. Do NOT "
                        "call that tool again. Reply to the user now in one or two "
                        "short sentences, and call resolve_thread if the request "
                        "is complete."
                    ),
                })
                nudged = True
            elif repeats >= 2:
                return await _finish(last_content, "stop")

        # (2) Cap hit — return the best answer we have rather than an error.
        return await _finish(last_content, "length")

    async def astream(self, messages: list[ChatMessage], *, session_id: str | None = None):
        if self.tools:
            # Real token streaming *through* a tool loop needs to interleave
            # structured tool_call parsing with partial-token deltas — more
            # than this scaffold needs. Resolve tools first, then
            # fake-stream the final answer (inherited from BaseAgentRuntime).
            async for piece in super().astream(messages, session_id=session_id):
                yield piece
            return

        # No tools configured: stream real tokens straight from Ollama.
        convo = self._build_conversation(messages, session_id)
        full_text: list[str] = []
        async for chunk in self._call_stream(convo):
            piece = chunk.get("message", {}).get("content", "")
            if piece:
                full_text.append(piece)
                yield piece
        if self.memory and session_id:
            self.memory.append_turn(session_id, "assistant", "".join(full_text))
