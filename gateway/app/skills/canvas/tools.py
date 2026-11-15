"""Tools exported by the `canvas` skill.

A "canvas" is an AI-generated React widget the Android client renders inside a
sandboxed WebView (React + ReactDOM + Recharts + Babel, all shipped in the APK;
on-device JSX transpile; strict CSP — no network, no app-state access). The
model writes JSX; this tool captures it into the per-request state so the
runtime can hand it back on the `canvas` field of the response envelope.

Like every other skill tool, `render_canvas` records into the shared per-request
`state` dict rather than touching any server-side state. Exactly one canvas per
reply: a second call overwrites the first.
"""

from __future__ import annotations

from collections.abc import Callable

ToolSpec = tuple[dict, Callable]

RENDER_CANVAS_SCHEMA = {
    "type": "function",
    "function": {
        "name": "render_canvas",
        "description": (
            "Render a React widget ('canvas') in the user's app instead of, or "
            "alongside, a plain text reply. Use when a visual communicates better "
            "than prose — a chart, a comparison table, a dashboard-style readout, "
            "a formatted breakdown. The JSX is transpiled and rendered on-device. "
            "Call this once; then still write a one or two sentence reply that "
            "serves as the caption. Follow the skill instructions for the exact "
            "dialect (a top-level `App` component, Recharts for charts, the "
            "monochrome design tokens)."
        ),
        "parameters": {
            "type": "object",
            "properties": {
                "title": {
                    "type": "string",
                    "description": (
                        "A short title shown above the widget, e.g. 'Weekly spend' "
                        "or 'Plan comparison'. 2-4 words."
                    ),
                },
                "jsx": {
                    "type": "string",
                    "description": (
                        "The complete JSX source. Must define a top-level function "
                        "component named `App` and no imports/exports. React, "
                        "ReactDOM and Recharts are preset globals. See the skill "
                        "body for the full contract."
                    ),
                },
            },
            "required": ["title", "jsx"],
        },
    },
}


def build_tools(state: dict) -> list[ToolSpec]:
    async def render_canvas(title: str, jsx: str) -> str:
        title = (title or "").strip() or "Widget"
        jsx = (jsx or "").strip()
        if not jsx:
            return (
                "[render_canvas] no jsx provided — nothing to render. Pass the "
                "complete JSX source defining a top-level `App` component."
            )
        state["canvas"] = {"title": title, "jsx": jsx}
        return (
            f"Canvas '{title}' captured ({len(jsx)} chars of JSX). It will be "
            "rendered in the app. Now write a short caption as your reply."
        )

    return [(RENDER_CANVAS_SCHEMA, render_canvas)]


__all__ = ["build_tools"]
