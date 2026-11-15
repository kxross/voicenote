"""The `vm` skill: a code interpreter backed by the vmm daemon.

SKILL_BODY is the SKILL.md prose (frontmatter stripped) — the TUI's
agent-loop harness uses it as a system prompt so it drives the tools exactly
the way the gateway's assistant will once this skill is scoped to it.
"""

from pathlib import Path

_md = (Path(__file__).parent / "SKILL.md").read_text(encoding="utf-8")
if _md.startswith("---"):
    # drop the YAML frontmatter block, keep the body
    _end = _md.find("\n---", 3)
    SKILL_BODY = _md[_end + 4:].lstrip("\n") if _end != -1 else _md
else:
    SKILL_BODY = _md
