"""Registry admin API (/v1/registry/*) backing the /admin web UI.

CRUD over the DB overlay (custom agents, skill overrides, tool metadata); every
write validates, persists, then calls registry_service.rebuild() so the change
is live with no restart. Built-ins live in code and are read-only here (clone to
customize). The whole surface is api-key gated — attaching the vm skill gives an
agent code-execution reach.
"""

from __future__ import annotations

import json as _json

from fastapi import APIRouter, Depends, Header, HTTPException, Request
from pydantic import BaseModel

from app import registry_service as svc
from app.config import settings
from app.registry import registry
from app.schemas import ChatMessage
from app.skill_registry import skill_registry


async def require_api_key(authorization: str | None = Header(default=None)) -> None:
    if not settings.gateway_api_key:
        return
    if authorization != f"Bearer {settings.gateway_api_key}":
        raise HTTPException(status_code=401, detail="invalid or missing API key")


router = APIRouter(prefix="/v1/registry", dependencies=[Depends(require_api_key)])


# ---- request bodies -------------------------------------------------------

class AgentBody(BaseModel):
    id: str
    description: str = ""
    runtime_type: str
    params: dict = {}
    skill_ids: list[str] = []
    tool_ids: list[str] = []
    enabled: bool = True


class SkillBody(BaseModel):
    id: str
    description: str | None = None
    body: str | None = None
    tool_ids: list[str] = []
    enabled: bool = True
    status: str = "active"


class ToolMetaBody(BaseModel):
    description_override: str | None = None
    enabled: bool = True
    attachable: bool = False


class TestBody(BaseModel):
    prompt: str = "Hello"


# ---- introspection helpers ------------------------------------------------

_RUNTIME_CLASS_TO_TYPE = {
    "OllamaRuntime": "ollama",
    "SkillfulOllamaRuntime": "skillful",
    "ListManagerRuntime": "list_manager",
    "OpenAICompatRuntime": "openai_compat",
}

_PROBE_STATE = {
    "snapshot": [], "notes": [], "goals": [], "ops": [], "loaded": set(),
    "session": "", "app_state": None, "vm_base_url": "", "default_backend": "",
    "vm_key": "", "vm_ttl_minutes": 0,
}


def _runtime_type_of(rt) -> str:
    return _RUNTIME_CLASS_TO_TYPE.get(type(rt).__name__, type(rt).__name__)


def _params_of(rt) -> dict:
    p: dict = {}
    for attr in ("model", "base_url", "think", "reasoning_effort", "enable_subagents"):
        if hasattr(rt, attr) and getattr(rt, attr) is not None:
            p[attr] = getattr(rt, attr)
    sp = getattr(rt, "system_prompt", None) or getattr(rt, "base_system_prompt", None)
    if sp:
        p["system_prompt"] = sp
    return p


def _skill_tool_list(skill) -> list[dict]:
    try:
        probe = skill.build_tools(dict(_PROBE_STATE))
    except Exception:  # noqa: BLE001 — a probe failure shouldn't break the overview
        return []
    out = []
    for schema, _fn in probe:
        fn = schema.get("function", {}) if isinstance(schema, dict) else {}
        if fn.get("name"):
            out.append({"name": fn["name"], "description": fn.get("description", "")})
    return out


# ---- overview (everything the UI renders) ---------------------------------

@router.get("/overview")
async def overview(request: Request) -> dict:
    state = request.app.state.state
    builtin_ids = svc.builtin_agent_ids()
    overlays = {o["id"]: o for o in await state.list_reg_skill_overlays()}
    tool_meta = {t["name"]: t for t in await state.list_reg_tool_meta()}

    # Agents: built-ins (read-only) merged with custom (editable).
    agents = []
    for cfg in registry.list():
        is_builtin = cfg.id in builtin_ids
        agents.append({
            "id": cfg.id,
            "description": cfg.description,
            "runtime_type": _runtime_type_of(cfg.runtime),
            "params": _params_of(cfg.runtime),
            "skill_ids": list(cfg.skills),
            "source": "builtin" if is_builtin else "custom",
            "editable": not is_builtin,
        })
    custom_rows = {r["id"]: r for r in await state.list_reg_agents()}
    for a in agents:
        if a["id"] in custom_rows:
            a["tool_ids"] = custom_rows[a["id"]].get("tool_ids", [])
            a["enabled"] = custom_rows[a["id"]].get("enabled", True)

    # Skills (file + overlay + custom), each with the tools it exposes.
    skills = []
    for s in skill_registry.list():
        ov = overlays.get(s.id)
        skills.append({
            "id": s.id,
            "description": s.description,
            "body": s.body,
            "tools": _skill_tool_list(s),
            "source": "custom" if (ov and ov.get("source") == "custom")
                      else ("overridden" if ov else "builtin"),
            "status": ov.get("status", "active") if ov else "active",
            "enabled": ov.get("enabled", True) if ov else True,
        })

    # Tools catalog: standalone (attachable) + every skill-bound tool.
    tools = []
    for name, (schema, _fn) in svc.STANDALONE_TOOLS.items():
        fn = schema.get("function", {})
        meta = tool_meta.get(name, {})
        tools.append({
            "name": name, "source": "standalone",
            "description": meta.get("description_override") or fn.get("description", ""),
            "schema": schema, "attachable": True,
            "enabled": meta.get("enabled", True),
        })
    for s in skill_registry.list():
        for t in _skill_tool_list(s):
            meta = tool_meta.get(t["name"], {})
            tools.append({
                "name": t["name"], "source": f"skill:{s.id}",
                "description": meta.get("description_override") or t["description"],
                "schema": None, "attachable": False,
                "enabled": meta.get("enabled", True),
            })

    return {
        "agents": agents,
        "skills": skills,
        "tools": tools,
        "catalog": {
            "runtime_types": svc.RUNTIME_TYPES,
            "standalone_tools": list(svc.STANDALONE_TOOLS.keys()),
            "skill_ids": [s.id for s in skill_registry.list()],
            "builtin_agent_ids": sorted(builtin_ids),
        },
    }


# ---- agents ---------------------------------------------------------------

def _validate_agent(body: AgentBody, builtin_ids: set[str]) -> None:
    if body.id in builtin_ids:
        raise HTTPException(400, f"'{body.id}' is a built-in agent id; clone it instead")
    if not body.id.strip():
        raise HTTPException(400, "agent id required")
    if body.runtime_type not in svc.RUNTIME_TYPES:
        raise HTTPException(400, f"unknown runtime_type '{body.runtime_type}'")
    # Construct once to validate skills/tools/params — reject before persisting.
    try:
        svc.build_runtime(body.runtime_type, body.params, body.skill_ids, body.tool_ids)
    except Exception as e:  # noqa: BLE001
        raise HTTPException(400, f"invalid agent config: {e}")


@router.post("/agents")
async def create_agent(body: AgentBody, request: Request) -> dict:
    state = request.app.state.state
    builtin_ids = svc.builtin_agent_ids()
    _validate_agent(body, builtin_ids)
    if registry.get(body.id) is not None:
        raise HTTPException(409, f"agent id '{body.id}' already exists")
    await state.upsert_reg_agent(body.model_dump())
    await svc.rebuild(state)
    return {"status": "ok", "id": body.id}


@router.put("/agents/{agent_id}")
async def update_agent(agent_id: str, body: AgentBody, request: Request) -> dict:
    state = request.app.state.state
    builtin_ids = svc.builtin_agent_ids()
    if agent_id in builtin_ids:
        raise HTTPException(400, "built-in agents are read-only; clone to customize")
    body.id = agent_id
    _validate_agent(body, builtin_ids)
    await state.upsert_reg_agent(body.model_dump())
    await svc.rebuild(state)
    return {"status": "ok", "id": agent_id}


@router.delete("/agents/{agent_id}")
async def delete_agent(agent_id: str, request: Request) -> dict:
    state = request.app.state.state
    if agent_id in svc.builtin_agent_ids():
        raise HTTPException(400, "built-in agents can't be deleted")
    ok = await state.delete_reg_agent(agent_id)
    if not ok:
        raise HTTPException(404, "no such custom agent")
    await svc.rebuild(state)
    return {"status": "ok"}


@router.post("/agents/{agent_id}/clone")
async def clone_agent(agent_id: str, request: Request) -> dict:
    """Fork any agent (built-in or custom) into a new editable custom row."""
    state = request.app.state.state
    src = registry.get(agent_id)
    if src is None:
        raise HTTPException(404, "no such agent")
    new_id = f"{agent_id}-copy"
    n = 2
    while registry.get(new_id) is not None:
        new_id = f"{agent_id}-copy{n}"
        n += 1
    custom_rows = {r["id"]: r for r in await state.list_reg_agents()}
    row = custom_rows.get(agent_id)
    new = {
        "id": new_id,
        "description": (src.description or "") + " (copy)",
        "runtime_type": _runtime_type_of(src.runtime),
        "params": row.get("params") if row else _params_of(src.runtime),
        "skill_ids": list(src.skills),
        "tool_ids": row.get("tool_ids", []) if row else [],
        "enabled": True,
    }
    await state.upsert_reg_agent(new)
    await svc.rebuild(state)
    return {"status": "ok", "id": new_id}


@router.post("/agents/{agent_id}/test")
async def test_agent(agent_id: str, body: TestBody, request: Request) -> dict:
    agent = registry.get(agent_id)
    if agent is None:
        raise HTTPException(404, "no such agent")
    # The skill/list runtimes parse a {prompt, lists, …} envelope; send that so
    # every runtime type handles the sample turn uniformly.
    content = _json.dumps({"prompt": body.prompt, "lists": [], "notes": [], "goals": []})
    try:
        result = await agent.runtime.arun(
            [ChatMessage(role="user", content=content)], session_id="admin-test"
        )
    except Exception as e:  # noqa: BLE001
        raise HTTPException(500, f"agent error: {e}")
    return {"text": result.text, "finish_reason": getattr(result, "finish_reason", None)}


# ---- skills ---------------------------------------------------------------

@router.put("/skills/{skill_id}")
async def upsert_skill(skill_id: str, body: SkillBody, request: Request) -> dict:
    state = request.app.state.state
    body.id = skill_id
    # Custom (new) skills are marked as such so rebuild registers them; an
    # overlay over a file skill keeps source 'builtin-override'.
    is_new = skill_registry.get(skill_id) is None
    payload = body.model_dump()
    payload["source"] = "custom" if is_new else "builtin-override"
    await state.upsert_reg_skill_overlay(payload)
    await svc.rebuild(state)
    return {"status": "ok", "id": skill_id}


@router.post("/skills")
async def create_skill(body: SkillBody, request: Request) -> dict:
    state = request.app.state.state
    if not body.id.strip():
        raise HTTPException(400, "skill id required")
    if skill_registry.get(body.id) is not None:
        raise HTTPException(409, f"skill id '{body.id}' already exists")
    for t in body.tool_ids:
        if t not in svc.STANDALONE_TOOLS:
            raise HTTPException(400, f"unknown tool id '{t}' (only standalone tools attachable)")
    payload = body.model_dump()
    payload["source"] = "custom"
    await state.upsert_reg_skill_overlay(payload)
    await svc.rebuild(state)
    return {"status": "ok", "id": body.id}


@router.delete("/skills/{skill_id}")
async def delete_skill_overlay(skill_id: str, request: Request) -> dict:
    state = request.app.state.state
    ok = await state.delete_reg_skill_overlay(skill_id)
    if not ok:
        raise HTTPException(404, "no overlay for that skill")
    await svc.rebuild(state)
    return {"status": "ok"}


# ---- tools (metadata only) ------------------------------------------------

@router.put("/tools/{name}")
async def upsert_tool_meta(name: str, body: ToolMetaBody, request: Request) -> dict:
    state = request.app.state.state
    payload = body.model_dump()
    payload["name"] = name
    await state.upsert_reg_tool_meta(payload)
    await svc.rebuild(state)
    return {"status": "ok", "name": name}


# ---- reload ---------------------------------------------------------------

@router.post("/reload")
async def reload(request: Request) -> dict:
    await svc.rebuild(request.app.state.state)
    return {"status": "ok", "agents": len(registry.list())}
