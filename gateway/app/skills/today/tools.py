"""Tools exported by the `today` skill.

Thin wrapper over app.tools.today, exposing the loader's expected
`build_tools(state) -> [(schema, fn)]` factory.
"""

from app.tools.today import OLLAMA_TOOL_SCHEMA, today


def build_tools(state: dict):
    return [(OLLAMA_TOOL_SCHEMA, today)]


__all__ = ["build_tools"]
