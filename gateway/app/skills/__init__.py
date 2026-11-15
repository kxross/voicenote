"""Skill loader.

Scans this package for subdirectories that contain a `SKILL.md`. For each
one:
  1. Parses YAML frontmatter → id, description.
  2. Reads the markdown body (everything after the frontmatter).
  3. Imports the sibling `tools.py` module and grabs its `build_tools`
     factory function.
  4. Registers a Skill with app.skill_registry.skill_registry.

Also validates that no two skills export tools with the same name, since
tools share a flat namespace inside a runtime's tool loop.

Call `load_all_skills()` from main.py before importing app.agents.definitions,
because agent constructors resolve skill ids against the registry.
"""

from __future__ import annotations

import importlib
import logging
from pathlib import Path

import yaml

from app.skill_registry import Skill, skill_registry

log = logging.getLogger(__name__)

_SKILLS_DIR = Path(__file__).parent


def _parse_skill_md(path: Path) -> tuple[dict, str]:
    """Return (frontmatter_dict, body_text)."""
    text = path.read_text(encoding="utf-8")
    if not text.startswith("---"):
        raise ValueError(f"{path}: missing YAML frontmatter (expected leading '---')")
    # Find the closing '---' on its own line.
    end = text.find("\n---", 3)
    if end < 0:
        raise ValueError(f"{path}: unterminated YAML frontmatter")
    front_raw = text[3:end].strip()
    body = text[end + 4 :].lstrip("\n")
    front = yaml.safe_load(front_raw) or {}
    if not isinstance(front, dict):
        raise ValueError(f"{path}: frontmatter must be a mapping")
    return front, body


def load_all_skills() -> None:
    """Idempotent — safe to call multiple times, though the registry itself
    will refuse duplicate registrations."""
    seen_tool_names: dict[str, str] = {}  # tool name -> skill id (for collision reporting)

    for entry in sorted(_SKILLS_DIR.iterdir()):
        if not entry.is_dir():
            continue
        skill_md = entry / "SKILL.md"
        if not skill_md.exists():
            continue

        front, body = _parse_skill_md(skill_md)
        skill_id = front.get("id") or entry.name
        description = front.get("description")
        if not isinstance(description, str) or not description.strip():
            raise ValueError(f"{skill_md}: frontmatter must include a non-empty 'description'")

        tools_module_path = f"app.skills.{entry.name}.tools"
        try:
            tools_module = importlib.import_module(tools_module_path)
        except ImportError as e:
            raise ImportError(f"skill '{skill_id}': could not import {tools_module_path}: {e}") from e

        build_tools = getattr(tools_module, "build_tools", None)
        if build_tools is None or not callable(build_tools):
            raise AttributeError(
                f"skill '{skill_id}': {tools_module_path} must define a callable "
                f"`build_tools(state: dict) -> list[(schema, fn)]`"
            )

        # Validate no tool-name collisions across skills.
        try:
            probe = build_tools({
                "snapshot": [], "notes": [], "goals": [], "ops": [],
                "session": "", "app_state": None,
            })
        except Exception as e:
            raise RuntimeError(
                f"skill '{skill_id}': build_tools raised at load-time probe: {e}"
            ) from e
        for schema, _fn in probe:
            name = schema.get("function", {}).get("name")
            if not name:
                continue
            if name in seen_tool_names and seen_tool_names[name] != skill_id:
                raise ValueError(
                    f"tool name collision: '{name}' is defined by both "
                    f"skill '{seen_tool_names[name]}' and skill '{skill_id}'. "
                    f"Rename one."
                )
            seen_tool_names[name] = skill_id

        skill_registry.register(
            Skill(
                id=skill_id,
                description=description.strip(),
                body=body,
                build_tools=build_tools,
            )
        )
        log.info("[skills] registered %s (%d tools)", skill_id, len(probe))
