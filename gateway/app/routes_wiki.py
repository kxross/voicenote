"""Learned-wiki API (/v1/wiki/*) — backs the admin Wiki/Proposals tabs and the
app's approve cards. Operates on the markdown file store (app/wiki_store.py);
every write commits. Api-key gated like the rest of the admin surface."""

from __future__ import annotations

from fastapi import APIRouter, Depends, Header, HTTPException
from pydantic import BaseModel

from app.config import settings
from app.wiki_store import store


async def require_api_key(authorization: str | None = Header(default=None)) -> None:
    if not settings.gateway_api_key:
        return
    if authorization != f"Bearer {settings.gateway_api_key}":
        raise HTTPException(status_code=401, detail="invalid or missing API key")


router = APIRouter(prefix="/v1/wiki", dependencies=[Depends(require_api_key)])


class EntryBody(BaseModel):
    title: str
    when_to_use: str = ""
    body: str
    kind: str = "knowledge"


class ApproveBody(BaseModel):
    edited_body: str | None = None


@router.get("/entries")
async def list_entries(status: str = "active") -> dict:
    s = store()
    entries = s.list_proposed() if status == "proposed" else s.list_active()
    return {"entries": entries, "status": status}


@router.get("/search")
async def search(q: str = "", limit: int = 5) -> dict:
    return {"results": store().search(q, limit=limit)}


@router.get("/proposals/{proposal_id}/diff")
async def proposal_diff(proposal_id: str) -> dict:
    s = store()
    prop = s.get_proposal(proposal_id)
    if prop is None:
        raise HTTPException(404, "no such proposal")
    return {"proposal": {k: prop.get(k) for k in
                         ("proposal_id", "op", "target_id", "title", "when_to_use", "kind")},
            "diff": s.diff(proposal_id)}


@router.post("/entries")
async def create_entry(body: EntryBody) -> dict:
    e = store().create(title=body.title, when_to_use=body.when_to_use,
                       body=body.body, kind=body.kind)
    return {"status": "ok", "id": e["id"]}


@router.put("/entries/{entry_id}")
async def update_entry(entry_id: str, body: EntryBody) -> dict:
    e = store().update(entry_id, title=body.title, when_to_use=body.when_to_use,
                       body=body.body, kind=body.kind)
    if e is None:
        raise HTTPException(404, "no such entry")
    return {"status": "ok", "id": e["id"]}


@router.post("/entries/{entry_id}/archive")
async def archive_entry(entry_id: str) -> dict:
    if not store().archive(entry_id):
        raise HTTPException(404, "no such entry")
    return {"status": "ok"}


@router.post("/proposals/{proposal_id}/approve")
async def approve_proposal(proposal_id: str, body: ApproveBody | None = None) -> dict:
    e = store().approve(proposal_id, edited_body=(body.edited_body if body else None))
    if e is None:
        raise HTTPException(404, "no such proposal")
    return {"status": "ok", "id": e["id"]}


@router.post("/proposals/{proposal_id}/reject")
async def reject_proposal(proposal_id: str) -> dict:
    if not store().reject(proposal_id):
        raise HTTPException(404, "no such proposal")
    return {"status": "ok"}
