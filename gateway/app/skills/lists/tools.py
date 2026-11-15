"""Tools exported by the `lists` skill.

The heavy lifting lives in app.tools.list_ops; this module just re-exposes
the lists-only factory under the name `build_tools`, which is the contract
the skill loader (app/skills/__init__.py) expects.
"""

from app.tools.list_ops import build_lists_only as build_tools

__all__ = ["build_tools"]
