"""Tools exported by the `websearch` skill.

The search itself lives in app.tools.web_search. This skill's version also
records every result into the turn's source registry (app/sources.py) and
labels it with its stable per-turn number, so the model can cite inline as
``[n]`` and the app shows the sources as a structured, linked card.
"""

from app import sources
from app.tools.web_search import OLLAMA_TOOL_SCHEMA, format_results, search

_CITE_HINT = (
    "\n\nCite what you use inline with its number, e.g. \"Revenue grew 12% [3].\" "
    "The user sees these sources as a linked list automatically — don't paste raw URLs."
)


def build_tools(state: dict):
    async def web_search(query: str, max_results: int | None = None) -> str:
        err, answer, results = await search(query, max_results)
        if err:
            return err
        if not results and not answer:
            return f"[web_search] No results for {(query or '').strip()!r}."
        numbered = [
            (sources.record(state, r.get("url", ""), r.get("title", ""), r.get("content", ""), via="search"), r)
            for r in results
        ]
        return format_results(query, answer, numbered) + _CITE_HINT

    return [(OLLAMA_TOOL_SCHEMA, web_search)]


__all__ = ["build_tools"]
