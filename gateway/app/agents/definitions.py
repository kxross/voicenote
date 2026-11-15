"""This is the file you edit to add/change/remove agents. Nothing else in
the gateway (main.py, registry.py, response_adapter.py) needs to change
when you add a new one here — that's the whole point of the registry
pattern.

Three agents are registered below, on purpose spanning three different
"how much do I build vs. inherit" points from the stack comparison:

  qwen-chat        -> OllamaRuntime, no tools     (plain chat, cheapest to run)
  research-agent   -> LangGraphRuntime, w/ tools  (framework owns the tool loop)
  claude-json-agent-> ClaudeAgentSDKRuntime        (different SDK entirely)

All three are reachable through the exact same POST /v1/chat/completions,
distinguished only by the `model` field the client sends.
"""

from app.config import settings
from app.registry import AgentConfig
# from app.runtimes.claude_agent_sdk_runtime import ClaudeAgentSDKRuntime
# from app.runtimes.langgraph_runtime import LangGraphRuntime
from app.runtimes.list_manager_runtime import ListManagerRuntime
from app.runtimes.ollama_runtime import OllamaRuntime
from app.runtimes.skillful_ollama_runtime import SkillfulOllamaRuntime
from app.tools.code_interpreter import OLLAMA_TOOL_SCHEMA as CODE_SCHEMA
from app.tools.code_interpreter import code_interpreter
from app.tools.memory import memory_store
from app.tools.web_search import OLLAMA_TOOL_SCHEMA as SEARCH_SCHEMA
from app.tools.web_search import web_search


def builtin_agents() -> list[AgentConfig]:
    """The code-defined base agent set. RegistryService composes the live
    registry from these plus the DB overlay, so this is a pure builder (no
    import-time side effects) that can be re-called on every rebuild."""
    agents: list[AgentConfig] = []

    # --- Agent 1: plain chat, no tools, direct Ollama call ------------------
    agents.append(
        AgentConfig(
            id="qwen-chat",
            description="Plain chat agent, no tools. Local Ollama, real token streaming.",
            runtime=OllamaRuntime(
                model=settings.default_model,
                base_url=settings.ollama_base_url,
                system_prompt="You are a helpful, concise assistant.",
                memory=memory_store,
            ),
        )
    )

    # --- Agent 2: tool-using agent, hand-rolled loop, still local Ollama ----
    agents.append(
        AgentConfig(
            id="qwen-tools",
            description="Ollama + hand-rolled tool loop (web_search, code_interpreter placeholders).",
            runtime=OllamaRuntime(
                model=settings.default_model,
                base_url=settings.ollama_base_url,
                system_prompt="You are a research assistant. Use tools when they would help.",
                tools=[
                    (SEARCH_SCHEMA, web_search),
                    (CODE_SCHEMA, code_interpreter),
                ],
                memory=memory_store,
            ),
        )
    )

    # --- Agent 3 (research-agent, LangGraph) — disabled; needs langgraph dep.

    # --- Agent 4: round-trip test agent for the VoiceNote client -----------
    agents.append(
        AgentConfig(
            id="test_model",
            description="Round-trip test agent for the VoiceNote client. Confirms receipt of a transcript.",
            delegatable=False,
            runtime=OllamaRuntime(
                model=settings.default_model,
                base_url=settings.ollama_base_url,
                system_prompt=(
                    "You are a test agent used to verify a client<->gateway round trip. "
                    "Reply with a single short sentence that acknowledges what the user said "
                    "and quotes 3-6 key words from their input. No preamble, no markdown."
                ),
                memory=memory_store,
            ),
        )
    )

    # --- Agent 5: list_manager for the VoiceNote client --------------------
    agents.append(
        AgentConfig(
            id="list_manager",
            description="Turns a voice transcript + the caller's current lists snapshot into structured edit operations (create/rename/delete lists, add/remove/toggle items).",
            delegatable=False,
            runtime=ListManagerRuntime(
                model=settings.default_model,
                base_url=settings.ollama_base_url,
            ),
        )
    )

    # --- Agent 6: skill-routing conductor ----------------------------------
    agents.append(
        AgentConfig(
            id="assistant",
            description="Skill-routing conductor. Discovers and loads capabilities from app/skills/ on demand.",
            skills=["lists", "notes", "goals", "images", "video", "websearch", "today", "canvas", "artifact", "schedules", "vm", "wiki"],
            runtime=SkillfulOllamaRuntime(
                model=settings.default_model,
                base_url=settings.ollama_base_url,
                system_prompt=(
                    "You are the user's assistant. You have skills that can be loaded on demand — "
                    "see the SKILL CATALOG below. Only load a skill when the user's request "
                    "actually needs it. If none apply, just reply normally without loading anything. "
                    "When an utterance hints at an ambition, deadline, or recurring intent, consider "
                    "whether the goals skill applies — goals are often implied rather than declared. "
                    "\n\nTHE TOOL CONTRACT: you cannot change anything yourself. The state shown in "
                    "your context is READ-ONLY; the only way any list, goal, plan, or task actually "
                    "changes on the user's device is through a tool call made after loading the "
                    "relevant skill. Never say you added, removed, updated, or completed something "
                    "unless you called the tool that does it in this conversation. If you replied "
                    "without tool calls, nothing happened — phrase your reply accordingly. "
                    "\n\nCONTEXT DISCIPLINE: the lists, goals index, and recent notes above are "
                    "already in front of you — don't re-fetch what you can already see. Summarize "
                    "tool results instead of echoing them; keep large output in files (the vm skill) "
                    "and bring back only the conclusion and where the detail lives. "
                    "\n\nReply style: one or two short sentences suitable for a phone notification."
                ),
                skill_ids=["lists", "notes", "goals", "images", "video", "websearch", "today", "canvas", "artifact", "schedules", "vm", "wiki"],
                # Thinking ON for this agent (overrides the global OLLAMA_THINK
                # default): the skill loop is multi-step tool orchestration, and
                # qwen3 is measurably less reliable at it without thinking.
                think=True,
                # Orchestration is PINNED OFF: the run_subagents machinery is
                # built and correct, but Ollama 0.40.14 won't run concurrent
                # requests to a qwen3 model ("architecture does not support
                # parallel requests"), so local fan-out would only serialise.
                # Flip to True (here, or per-agent in the /admin UI) once sub-
                # agents run on a parallel-capable model/Ollama or OpenRouter.
                enable_subagents=False,
            ),
        )
    )

    # --- Agent 7 (claude-json-agent) — disabled; needs ANTHROPIC_API_KEY +
    #     the Claude Agent SDK import uncommented.

    return agents
