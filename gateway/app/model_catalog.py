"""Provider-neutral model catalog.

One place that answers "which models can this gateway drive, and what can
each one do". Wraps the Ollama probe (app/ollama_models.py) and, when a key
is configured, OpenRouter's catalog. The client's picker and the assistant's
routing guardrails both read from here.

Capability semantics differ deliberately by provider:
  * Ollama — unknown model defaults to CAPABLE. It's the resident local model
    and an /api/show blip shouldn't break a request that used to work.
  * OpenRouter — unknown model defaults to NOT capable (fail-closed). A remote
    model that can't tool-call would silently reduce the assistant to a
    chatbot, so the safe default is to fall back to the local default.
"""

from __future__ import annotations

import httpx

from app import ollama_models
from app.config import settings

OLLAMA = "ollama"
OPENROUTER = "openrouter"
VLLM = "vllm"

# slug -> {"tools", "thinking", "vision"}; filled by list_models(), reused by
# model_flags() so routing doesn't re-fetch the whole catalog per request.
_openrouter_cache: dict[str, dict] = {}


def openrouter_enabled() -> bool:
    return bool(settings.openrouter_api_key)


async def _openrouter_models() -> list[dict]:
    """Tool-capable OpenRouter models. Anything without `tools` in
    supported_parameters is filtered out — the assistant is tool-driven, so
    offering those in the picker would just be a trap."""
    if not openrouter_enabled():
        return []
    try:
        async with httpx.AsyncClient(timeout=20) as client:
            r = await client.get(
                f"{settings.openrouter_base_url.rstrip('/')}/models",
                headers={"Authorization": f"Bearer {settings.openrouter_api_key}"},
            )
            r.raise_for_status()
            raw = r.json().get("data", []) or []
    except Exception:  # noqa: BLE001 — catalog is best-effort; never break a request
        return []

    out = []
    for m in raw:
        slug = m.get("id")
        if not slug:
            continue
        supported = m.get("supported_parameters") or []
        if "tools" not in supported:
            continue
        modalities = (m.get("architecture") or {}).get("input_modalities") or []
        entry = {
            "provider": OPENROUTER,
            "name": slug,
            "tools": True,
            "thinking": "reasoning" in supported or "include_reasoning" in supported,
            "vision": "image" in modalities,
        }
        _openrouter_cache[slug] = {
            "tools": True, "thinking": entry["thinking"], "vision": entry["vision"],
        }
        out.append(entry)
    out.sort(key=lambda e: e["name"])
    return out


def vllm_enabled() -> bool:
    return bool(settings.vllm_base_url)


async def _vllm_models() -> list[dict]:
    """The local vLLM server's model(s). vLLM is OpenAI-wire, so this reads
    `/v1/models`. We serve a tool- + reasoning-capable qwen3, so both flags are
    True. Falls back to the configured model id if the server isn't reachable,
    so the picker still offers it (the turn then fails fast / falls back)."""
    if not vllm_enabled():
        return []
    default = {"provider": VLLM, "name": settings.vllm_model,
               "tools": True, "thinking": True, "vision": False}
    try:
        async with httpx.AsyncClient(timeout=10) as client:
            r = await client.get(
                f"{settings.vllm_base_url.rstrip('/')}/models",
                headers={"Authorization": f"Bearer {settings.vllm_api_key}"},
            )
            r.raise_for_status()
            raw = r.json().get("data", []) or []
    except Exception:  # noqa: BLE001 — best-effort; never break the catalog
        return [default]
    out = [{"provider": VLLM, "name": m["id"], "tools": True, "thinking": True,
            "vision": False} for m in raw if m.get("id")]
    return out or [default]


async def list_models() -> list[dict]:
    """Every selectable model across providers, each tagged with `provider`."""
    local = [
        {"provider": OLLAMA, **m} for m in await ollama_models.list_models()
    ]
    return local + await _openrouter_models() + await _vllm_models()


async def model_flags(provider: str, model: str) -> dict:
    """Routing guardrail: can this model use tools, is reasoning safe to
    request, and can it see images (`vision` — needed when a turn carries
    uncaptioned photos or a video whose frames it must look at)? See the
    module docstring for why the defaults differ."""
    if provider == VLLM:
        # The vLLM-served qwen3 is tool- + reasoning-capable, served text-only.
        return {"tools": True, "thinking": True, "vision": False}
    if provider == OPENROUTER:
        cached = _openrouter_cache.get(model)
        if cached is None:
            # Not in the cache — warm it once, then fail closed if still unknown.
            await _openrouter_models()
            cached = _openrouter_cache.get(model)
        if cached is not None:
            return {"tools": cached["tools"], "thinking": cached["thinking"],
                    "vision": cached.get("vision", False)}
        return {"tools": False, "thinking": False, "vision": False}
    return await ollama_models.model_flags(model)
