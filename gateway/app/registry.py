"""The agent registry: a flat map of agent-id -> (runtime, metadata).

This is *the* piece that makes agents "different at the application layer"
tractable — adding a new agent is a registration call in
app/agents/definitions.py, never a change to the gateway itself.
"""

from dataclasses import dataclass, field

from app.agent_base import BaseAgentRuntime


@dataclass
class AgentConfig:
    id: str  # what clients pass as `model`
    runtime: BaseAgentRuntime
    description: str = ""
    # Skill ids this agent may load at runtime (see app/skill_registry.py).
    # Purely metadata at the registry level — the runtime decides how to
    # honor it. SkillfulOllamaRuntime uses it to gate progressive-disclosure
    # `load_skill` calls; plain OllamaRuntime ignores it.
    skills: list[str] = field(default_factory=list)
    # Whether an orchestrator may delegate a subtask to this agent. False for
    # client-only agents (e.g. list_manager / test_model) that expect a specific
    # payload shape. Read by the run_subagents tool's delegatable catalog.
    delegatable: bool = True


class AgentRegistry:
    def __init__(self) -> None:
        self._agents: dict[str, AgentConfig] = {}

    def register(self, config: AgentConfig) -> None:
        if config.id in self._agents:
            raise ValueError(f"agent id '{config.id}' is already registered")
        self._agents[config.id] = config

    def replace(self, configs: list[AgentConfig]) -> None:
        """Atomically swap the whole agent set — used by RegistryService.rebuild
        to apply the DB overlay with no restart. Validates first (rejecting
        duplicate ids) and only then swaps, so a bad rebuild never half-applies."""
        new: dict[str, AgentConfig] = {}
        for c in configs:
            if c.id in new:
                raise ValueError(f"duplicate agent id '{c.id}'")
            new[c.id] = c
        self._agents = new

    def get(self, agent_id: str) -> AgentConfig | None:
        return self._agents.get(agent_id)

    def list(self) -> list[AgentConfig]:
        return list(self._agents.values())


# Process-wide singleton. app/agents/definitions.py populates this at import
# time; app/main.py imports definitions (for the side effect) before serving.
registry = AgentRegistry()
