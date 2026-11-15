"""Runtime #2: a LangGraph ReAct-style agent, backed by the same local
Ollama server, so you can compare "hand-rolled HTTP + tool loop"
(ollama_runtime.py) against "let a framework own the loop" side by side
in the same registry.

Verified against the installed package versions in this scaffold:
  - `langgraph.prebuilt.create_react_agent` is deprecated upstream in favor
    of `langchain.agents.create_agent` (same shape: model, tools,
    system_prompt=...). If your installed LangChain predates that move,
    swap the import back to `from langgraph.prebuilt import create_react_agent`
    and use `prompt=` instead of `system_prompt=`.
  - Memory here uses LangGraph's own checkpointer (`InMemorySaver`) keyed by
    `thread_id`, which is a different (and arguably more idiomatic) approach
    than the hand-rolled `MemoryStore` used by ollama_runtime.py — worth
    noticing that heterogeneous agents can reasonably use heterogeneous
    memory strategies too.
"""

from langchain.agents import create_agent
from langchain_core.messages import AIMessage, HumanMessage, SystemMessage
from langchain_core.tools import tool
from langchain_ollama import ChatOllama
from langgraph.checkpoint.memory import InMemorySaver

from app.agent_base import AgentResult, BaseAgentRuntime
from app.config import settings
from app.schemas import ChatMessage
from app.tools.code_interpreter import code_interpreter
from app.tools.web_search import web_search


@tool
async def web_search_tool(query: str) -> str:
    """Search the web and return the top results for a query."""
    return await web_search(query)


@tool
async def code_interpreter_tool(code: str) -> str:
    """Execute a snippet of Python and return stdout/stderr."""
    return await code_interpreter(code)


DEFAULT_TOOLS = [web_search_tool, code_interpreter_tool]


def _to_langchain_messages(messages: list[ChatMessage]) -> list:
    role_map = {"user": HumanMessage, "assistant": AIMessage, "system": SystemMessage}
    return [role_map.get(m.role, HumanMessage)(content=m.content) for m in messages]


class LangGraphRuntime(BaseAgentRuntime):
    def __init__(
        self,
        *,
        model: str,
        base_url: str | None = None,
        system_prompt: str | None = None,
        tools: list | None = None,
        temperature: float | None = None,
    ) -> None:
        llm = ChatOllama(
            model=model,
            base_url=base_url or settings.ollama_base_url,
            temperature=temperature,
        )
        self._checkpointer = InMemorySaver()
        self.graph = create_agent(
            llm,
            tools if tools is not None else DEFAULT_TOOLS,
            system_prompt=system_prompt,
            checkpointer=self._checkpointer,
        )

    async def arun(self, messages: list[ChatMessage], *, session_id: str | None = None) -> AgentResult:
        lc_messages = _to_langchain_messages(messages)
        config = {"configurable": {"thread_id": session_id or "default"}}
        result = await self.graph.ainvoke({"messages": lc_messages}, config=config)
        final = result["messages"][-1]
        return AgentResult(text=final.content)

    # astream() falls back to BaseAgentRuntime's fake-stream-after-arun.
    # For real token streaming, look at `graph.astream(..., stream_mode="messages")`
    # or `graph.astream_events(...)` — left out here since the exact event
    # shape shifts across LangGraph versions more often than the rest of
    # this API.
