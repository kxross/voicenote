"""Web search via Tavily — a search API built for LLM agents.

Swapped in for the DuckDuckGo HTML scraper, which rate-limits datacenter IPs.
Tavily returns clean, ranked results plus an optional synthesized answer, and
never throttles a valid key the way scraping does.

The key lives server-side in the gateway config (TAVILY_API_KEY in .env, same
place as OPENROUTER_API_KEY) and is never sent to the phone. Framework-agnostic:
`OLLAMA_TOOL_SCHEMA` is the shared tool definition and `web_search` is the plain
async function the Ollama and OpenAI tool loops call. An empty key disables the
tool gracefully (a clear message, never a crash), exactly like the old stub.
"""

from __future__ import annotations

import httpx

from app.config import settings

OLLAMA_TOOL_SCHEMA = {
    "type": "function",
    "function": {
        "name": "web_search",
        "description": (
            "Search the web to FIND info and locate sources — returns top "
            "results (title, URL, snippet) plus a synthesized answer when "
            "available. Use it for current events, facts you're unsure of, "
            "prices, documentation, or anything that may have changed since "
            "training. This is for discovery / quick facts. To read a specific "
            "URL's full content, a JS-rendered page, scrape data, or take a "
            "screenshot, use the vm sandbox's headless browser instead."
        ),
        "parameters": {
            "type": "object",
            "properties": {
                "query": {"type": "string", "description": "The search query."},
                "max_results": {
                    "type": "integer",
                    "description": "How many results to return (1-10, default 5).",
                },
            },
            "required": ["query"],
        },
    },
}


async def search(query: str, max_results: int | None = None) -> tuple[str | None, str, list[dict]]:
    """Run one Tavily search. Returns ``(error, answer, results)``: ``error`` is a
    ready-to-return message when the search couldn't run (else None); results
    are ``{title, url, content}`` dicts, already trimmed to ``max_results``."""
    query = (query or "").strip()
    if not query:
        return "[web_search] empty query.", "", []

    if not settings.tavily_api_key:
        return (
            "[web_search] not configured — set TAVILY_API_KEY in the gateway "
            ".env and restart to enable web search."
        ), "", []

    try:
        n = max(1, min(int(max_results or settings.tavily_max_results), 10))
    except (TypeError, ValueError):
        n = settings.tavily_max_results

    payload = {
        "query": query,
        "max_results": n,
        "search_depth": settings.tavily_search_depth,
        "include_answer": True,
    }
    try:
        async with httpx.AsyncClient(timeout=20.0) as client:
            resp = await client.post(
                f"{settings.tavily_base_url.rstrip('/')}/search",
                json=payload,
                headers={"Authorization": f"Bearer {settings.tavily_api_key}"},
            )
            resp.raise_for_status()
            data = resp.json()
    except httpx.HTTPStatusError as e:
        code = e.response.status_code
        if code in (401, 403):
            return "[web_search error] Tavily rejected the key (check TAVILY_API_KEY).", "", []
        if code == 429:
            return "[web_search error] Tavily rate/credit limit reached.", "", []
        return f"[web_search error] Tavily returned HTTP {code}.", "", []
    except Exception as e:  # noqa: BLE001 — a tool must never crash the loop
        return f"[web_search error] could not reach Tavily: {type(e).__name__}: {e}", "", []

    return None, (data.get("answer") or "").strip(), (data.get("results") or [])[:n]


def format_results(query: str, answer: str, numbered: list[tuple]) -> str:
    """Render results for the model. ``numbered`` is ``[(label, result), …]``;
    the label is the per-turn citation number when sources are tracked."""
    lines: list[str] = []
    if answer:
        lines.append(f"Answer: {answer}")
        lines.append("")
    lines.append(f"Top {len(numbered)} web results for {query!r}:")
    for label, r in numbered:
        title = (r.get("title") or "(untitled)").strip()
        url = (r.get("url") or "").strip()
        snippet = (r.get("content") or "").strip()
        entry = f"[{label}] {title}\n   {url}" if label is not None else f"• {title}\n   {url}"
        if snippet:
            entry += f"\n   {snippet}"
        lines.append(entry)
    return "\n".join(lines)


async def web_search(query: str, max_results: int | None = None) -> str:
    """Plain tool (no per-turn source tracking) — used by agents outside the
    skill system. Numbers results 1..n locally."""
    err, answer, results = await search(query, max_results)
    if err:
        return err
    if not results and not answer:
        return f"[web_search] No results for {(query or '').strip()!r}."
    return format_results(query, answer, list(enumerate(results, 1)))
