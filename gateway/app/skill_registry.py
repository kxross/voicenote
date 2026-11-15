"""Second registry, parallel to AgentRegistry, for progressive-disclosure
skills.

A skill is a self-contained bundle:
  * `id`          — matches folder name under app/skills/<id>/
  * `description` — one-line, always in the model's context (metadata level)
  * `body`        — SKILL.md prose, injected mid-conversation on load
  * `build_tools` — factory that returns (schema, async fn) pairs bound to
                    a per-request `state` dict, same shape as
                    app/tools/list_ops.build_list_tools

The `SkillfulOllamaRuntime` consults this registry: agents scope into it
via `AgentConfig.skills=[...]`, the model sees a catalog of scoped skills
in its system context, and calls `load_skill(id)` to activate one.

The registry is populated at import time by app/skills/__init__.py, which
scans SKILL.md files under app/skills/.
"""

from __future__ import annotations

from collections.abc import Callable
from dataclasses import dataclass

# ToolSpec is `tuple[dict, Callable]` — same alias defined in
# app/runtimes/ollama_runtime.py. Keeping the type generic here to avoid a
# circular import at registry load time.
ToolSpec = tuple[dict, Callable]


@dataclass
class Skill:
    id: str
    description: str
    body: str
    build_tools: Callable[[dict], list[ToolSpec]]


class SkillRegistry:
    def __init__(self) -> None:
        self._skills: dict[str, Skill] = {}

    def register(self, skill: Skill) -> None:
        if skill.id in self._skills:
            raise ValueError(f"skill id '{skill.id}' is already registered")
        self._skills[skill.id] = skill

    def upsert(self, skill: Skill) -> None:
        """Insert or overwrite — used by the admin overlay (edited builtin body,
        or a custom skill). Unlike register(), tolerates an existing id."""
        self._skills[skill.id] = skill

    def remove(self, skill_id: str) -> None:
        self._skills.pop(skill_id, None)

    def get(self, skill_id: str) -> Skill | None:
        return self._skills.get(skill_id)

    def list(self) -> list[Skill]:
        return list(self._skills.values())


# Process-wide singleton. app/skills/__init__.py::load_all_skills()
# populates this at startup; app/main.py imports skills before agents so
# runtimes can resolve skill ids at construction.
skill_registry = SkillRegistry()
