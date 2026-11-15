"""Talk to Ollama about which models exist and what they can do.

The client picks a model from this list; the assistant runtime uses the same
capability info to decide whether it's safe to send `think` (a non-thinking
model errors on it). Results are cached in-process — models rarely change and
a gateway restart clears the cache.
"""

from __future__ import annotations

import httpx

from app.config import settings

# model name -> capabilities list (e.g. ["completion","tools","thinking","vision"])
_caps_cache: dict[str, list[str]] = {}


def _base() -> str:
    return settings.ollama_base_url.rstrip("/")


async def _capabilities(client: httpx.AsyncClient, model: str) -> list[str]:
    if model in _caps_cache:
        return _caps_cache[model]
    caps: list[str] = []
    try:
        r = await client.post(f"{_base()}/api/show", json={"model": model})
        r.raise_for_status()
        caps = r.json().get("capabilities") or []
    except Exception:  # noqa: BLE001 — capability probing must never break a request
        caps = []
    _caps_cache[model] = caps
    return caps


async def list_models() -> list[dict]:
    """Local models with the capability flags the UI cares about. Empty list
    if Ollama is unreachable."""
    try:
        async with httpx.AsyncClient(timeout=15) as client:
            r = await client.get(f"{_base()}/api/tags")
            r.raise_for_status()
            names = [m.get("name") for m in r.json().get("models", []) if m.get("name")]
            out = []
            for name in names:
                caps = await _capabilities(client, name)
                out.append({
                    "name": name,
                    "thinking": "thinking" in caps,
                    "tools": "tools" in caps,
                    "vision": "vision" in caps,
                })
            return out
    except Exception:  # noqa: BLE001
        return []


async def model_flags(model: str) -> dict:
    """Capability flags the assistant runtime needs before routing to a model:
    `tools` (can it drive skills at all) and `thinking` (safe to send `think`).
    Unknown/unreachable models default to both True — the historical
    assumption for the resident model, so behavior is unchanged when Ollama
    can't be probed."""
    caps = _caps_cache.get(model)
    if caps is None:
        try:
            async with httpx.AsyncClient(timeout=15) as client:
                caps = await _capabilities(client, model)
        except Exception:  # noqa: BLE001
            return {"tools": True, "thinking": True, "vision": True}
    return {"tools": "tools" in caps, "thinking": "thinking" in caps, "vision": "vision" in caps}
