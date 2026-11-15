"""Runtime #3: wraps the Claude Agent SDK behind the same BaseAgentRuntime
interface as the Ollama and LangGraph runtimes. This one exists mainly to
prove the registry is genuinely heterogeneous — an agent backed by a
completely different SDK, protocol, and (optionally) model provider slots
into the same gateway with zero changes to main.py/registry.py.

Verified against the installed `claude-agent-sdk` package in this scaffold:
  - `ClaudeAgentOptions` is a dataclass with `system_prompt`, `model`,
    `allowed_tools`, `mcp_servers`, `session_id`, etc.
  - `ClaudeSDKClient` is an async context manager; call `.query(prompt,
    session_id=...)` then iterate `.receive_response()` for
    UserMessage/AssistantMessage/SystemMessage/ResultMessage/StreamEvent.
  - `AssistantMessage.content` is a list of blocks; text lives in
    `TextBlock.text`. Final `ResultMessage.usage`/`total_cost_usd` carry
    accounting info, though its key names don't line up with OpenAI's
    prompt_tokens/completion_tokens — map them explicitly if you need
    accurate usage reporting upstream.

Requires ANTHROPIC_API_KEY in the environment (see .env.example).
"""

from claude_agent_sdk import (
    AssistantMessage,
    ClaudeAgentOptions,
    ClaudeSDKClient,
    ResultMessage,
    TextBlock,
)

from app.agent_base import AgentResult, BaseAgentRuntime
from app.schemas import ChatMessage


def _flatten_to_prompt(messages: list[ChatMessage]) -> str:
    """The SDK's `query()` takes one prompt string, not an OpenAI-style
    message list. For a scaffold, folding history into a single prompt is
    good enough; for real multi-turn use, prefer the SDK's own
    `session_id`/`continue_conversation` options over re-sending history.
    """
    lines = []
    for m in messages:
        if m.role == "system":
            continue
        speaker = "User" if m.role == "user" else "Assistant"
        lines.append(f"{speaker}: {m.content}")
    return "\n".join(lines)


class ClaudeAgentSDKRuntime(BaseAgentRuntime):
    def __init__(
        self,
        *,
        system_prompt: str | None = None,
        model: str | None = None,
        allowed_tools: list[str] | None = None,
        mcp_servers: dict | None = None,
    ) -> None:
        self.options = ClaudeAgentOptions(
            system_prompt=system_prompt,
            model=model,  # None = SDK/CLI default; set explicitly to pin a model
            allowed_tools=allowed_tools or [],
            mcp_servers=mcp_servers or {},
        )

    async def arun(self, messages: list[ChatMessage], *, session_id: str | None = None) -> AgentResult:
        prompt = _flatten_to_prompt(messages)
        text_parts: list[str] = []
        usage: dict = {}

        async with ClaudeSDKClient(options=self.options) as client:
            await client.query(prompt, session_id=session_id or "default")
            async for msg in client.receive_response():
                if isinstance(msg, AssistantMessage):
                    for block in msg.content:
                        if isinstance(block, TextBlock):
                            text_parts.append(block.text)
                elif isinstance(msg, ResultMessage) and isinstance(msg.usage, dict):
                    usage = msg.usage

        return AgentResult(text="".join(text_parts), usage=usage)

    # astream() falls back to BaseAgentRuntime's fake-stream-after-arun.
    # For real streaming, `client.receive_response()` already yields
    # incremental StreamEvent/AssistantMessage chunks if
    # `include_partial_messages=True` is set on ClaudeAgentOptions —
    # left out here to keep this runtime's happy path readable.
