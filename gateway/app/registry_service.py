"""Composes the live agent registry from code built-ins + a DB overlay, and
rebuilds it on demand (no container restart).

The request path (`chat_completions`, scheduler) is untouched — it still calls
`registry.get(id)`. This module is what *populates* that registry: on startup
and on every admin edit, `rebuild()` builds a fresh set of `AgentConfig`s from
`definitions.builtin_agents()` plus the `reg_agents` rows, applies the
`reg_skill_overlay` rows onto `skill_registry`, and atomically swaps the live
registry. Built-ins stay in code (the base that can't be bricked); the DB only
adds custom agents and overrides.
"""

from __future__ import annotations

import logging

from app.agents.definitions import builtin_agents
from app.config import settings
from app.registry import AgentConfig, registry
from app.runtimes.list_manager_runtime import ListManagerRuntime
from app.runtimes.ollama_runtime import OllamaRuntime
from app.runtimes.openai_runtime import OpenAICompatRuntime
from app.runtimes.skillful_ollama_runtime import SkillfulOllamaRuntime
from app.skill_registry import Skill, skill_registry
from app.tools.code_interpreter import OLLAMA_TOOL_SCHEMA as CODE_SCHEMA
from app.tools.code_interpreter import code_interpreter
from app.tools.memory import memory_store
from app.tools.web_search import OLLAMA_TOOL_SCHEMA as SEARCH_SCHEMA
from app.tools.web_search import web_search

log = logging.getLogger(__name__)

# Standalone tools the UI can attach to an agent (name -> (schema, async fn)).
# Skill-bound tools are attached by attaching the whole skill (a "plugin"), so
# this catalog is only the runtime-level tools, not every tool in the system.
STANDALONE_TOOLS: dict[str, tuple[dict, object]] = {
    "web_search": (SEARCH_SCHEMA, web_search),
    "code_interpreter": (CODE_SCHEMA, code_interpreter),
}

# Runtime types the factory can build, with the params each honors — surfaced
# to the UI so the agent editor can show the right fields.
RUNTIME_TYPES: dict[str, dict] = {
    "ollama": {
        "label": "Ollama (chat / tools)",
        "params": ["model", "base_url", "system_prompt", "think", "memory"],
        "supports_tools": True, "supports_skills": False,
        "description": "Local Ollama with a hand-rolled tool loop. Attach standalone tools.",
    },
    "skillful": {
        "label": "Skillful (progressive-disclosure skills)",
        "params": ["model", "base_url", "system_prompt", "think", "enable_subagents"],
        "supports_tools": False, "supports_skills": True,
        "description": "Loads skills on demand (the assistant's runtime). Attach skills/plugins incl. vm.",
    },
    "list_manager": {
        "label": "List manager",
        "params": ["model", "base_url"],
        "supports_tools": False, "supports_skills": False,
        "description": "Fixed list-ops runtime for the VoiceNote client.",
    },
    "openai_compat": {
        "label": "OpenAI-compatible (OpenRouter)",
        "params": ["model", "base_url", "api_key", "system_prompt", "reasoning_effort", "memory"],
        "supports_tools": True, "supports_skills": False,
        "description": "OpenAI-wire provider (e.g. OpenRouter). Attach standalone tools.",
    },
    "vllm": {
        "label": "vLLM (local, OpenAI-compatible)",
        "params": ["model", "base_url", "system_prompt", "reasoning_effort"],
        "supports_tools": True, "supports_skills": False,
        "description": "Local vLLM endpoint (continuous batching). Defaults to the configured vLLM server/model.",
    },
}

# Captured once, after load_all_skills, so overlays can be reset each rebuild.
_skill_originals: dict[str, tuple[str, str]] = {}


def _capture_skill_originals() -> None:
    if _skill_originals:
        return
    for s in skill_registry.list():
        _skill_originals[s.id] = (s.description, s.body)


def _custom_skill_tools(tool_ids: tuple[str, ...], _state: dict) -> list:
    """build_tools for a UI-created skill: reuse selected standalone tools
    (prompt-only skills just pass none). Standalone tools ignore state."""
    return [STANDALONE_TOOLS[t] for t in tool_ids if t in STANDALONE_TOOLS]


def build_runtime(runtime_type: str, params: dict, skill_ids: list[str], tool_ids: list[str]):
    """Construct a runtime instance from stored config. Raises ValueError on an
    unknown type / tool / skill so a bad agent is rejected, never half-built."""
    params = params or {}
    model = params.get("model") or settings.default_model
    base_url = params.get("base_url") or None
    system_prompt = params.get("system_prompt")
    think = params.get("think")
    use_memory = params.get("memory", True)

    tools = []
    for t in (tool_ids or []):
        if t not in STANDALONE_TOOLS:
            raise ValueError(f"unknown tool id '{t}'")
        tools.append(STANDALONE_TOOLS[t])

    if runtime_type == "ollama":
        return OllamaRuntime(
            model=model, base_url=base_url, system_prompt=system_prompt,
            tools=tools, memory=memory_store if use_memory else None, think=think,
        )
    if runtime_type == "skillful":
        return SkillfulOllamaRuntime(
            model=model, base_url=base_url,
            system_prompt=system_prompt or "You are a helpful assistant.",
            skill_ids=skill_ids or [], think=think,
            enable_subagents=bool(params.get("enable_subagents", False)),
        )
    if runtime_type == "list_manager":
        return ListManagerRuntime(model=model, base_url=base_url)
    if runtime_type == "openai_compat":
        return OpenAICompatRuntime(
            model=model, base_url=base_url, api_key=params.get("api_key"),
            system_prompt=system_prompt, tools=tools,
            memory=memory_store if use_memory else None,
            reasoning_effort=params.get("reasoning_effort"),
        )
    if runtime_type == "vllm":
        return OpenAICompatRuntime(
            model=params.get("model") or settings.vllm_model,
            base_url=params.get("base_url") or settings.vllm_base_url,
            api_key=settings.vllm_api_key,
            system_prompt=system_prompt, tools=tools,
            reasoning_effort=params.get("reasoning_effort"),
            gate_provider="vllm",
        )
    raise ValueError(f"unknown runtime_type '{runtime_type}'")


def _apply_skill_overlays(overlays: list[dict]) -> None:
    _capture_skill_originals()
    overlay_ids = {o["id"] for o in overlays}

    # Reset file skills to their on-disk originals.
    for sid, (desc, body) in _skill_originals.items():
        s = skill_registry.get(sid)
        if s is not None:
            s.description, s.body = desc, body

    # Drop custom skills that no longer have an overlay row.
    for s in list(skill_registry.list()):
        if s.id not in _skill_originals and s.id not in overlay_ids:
            skill_registry.remove(s.id)

    # Apply each overlay: override a builtin skill's text, or register a custom one.
    for o in overlays:
        sid = o["id"]
        base = skill_registry.get(sid)
        if base is not None:
            if o.get("description"):
                base.description = o["description"]
            if o.get("body") is not None:
                base.body = o["body"]
        else:
            tids = tuple(o.get("tool_ids", []))
            skill_registry.upsert(Skill(
                id=sid,
                description=(o.get("description") or "").strip() or f"Custom skill {sid}",
                body=o.get("body") or "",
                build_tools=(lambda _state, _t=tids: _custom_skill_tools(_t, _state)),
            ))


async def rebuild(state) -> None:
    """Recompose the whole registry = built-ins + DB agents, with skill overlays
    applied first (so custom skills exist when an agent references them), then
    atomically swap the live registry. Validation failures skip just that agent."""
    _apply_skill_overlays(await state.list_reg_skill_overlays())

    builtins = list(builtin_agents())
    configs: list[AgentConfig] = list(builtins)
    for row in await state.list_reg_agents():
        if not row.get("enabled", True):
            continue
        try:
            runtime = build_runtime(
                row["runtime_type"], row.get("params", {}),
                row.get("skill_ids", []), row.get("tool_ids", []),
            )
        except Exception as e:  # noqa: BLE001 — a bad custom agent must not break the rest
            log.error("[registry] skipping custom agent %s: %s", row.get("id"), e)
            continue
        configs.append(AgentConfig(
            id=row["id"], runtime=runtime,
            description=row.get("description", ""), skills=row.get("skill_ids", []),
        ))

    # Skill-capable runtimes need a handle on State (blob store / caption cache);
    # orchestrators need their own id to exclude self from delegation.
    for c in configs:
        if hasattr(c.runtime, "app_state"):
            c.runtime.app_state = state
        if hasattr(c.runtime, "agent_id"):
            c.runtime.agent_id = c.id

    registry.replace(configs)
    log.info("[registry] rebuilt — %d agents (%d custom)",
             len(configs), len(configs) - len(builtins))


def builtin_agent_ids() -> set[str]:
    return {a.id for a in builtin_agents()}
