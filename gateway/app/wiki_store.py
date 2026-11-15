"""The learned-wiki file store: markdown entries under `state/wiki/`, the source
of truth for the `wiki` plugin and the /v1/wiki API.

Layout:
    state/wiki/active/<entry_id>.md       approved, searchable entries
    state/wiki/proposed/<proposal_id>.md  drafts awaiting approval (never searched)

Each file is markdown with YAML frontmatter (title, when_to_use, kind, …). The
filesystem IS the store — no DB table. The dir is a git repo (best-effort), so
each approved change is a commit (history + rollback). Nothing is ever activated
without going through approve(); propose() only ever writes to proposed/.
"""

from __future__ import annotations

import difflib
import re
import subprocess
import time
import uuid
from pathlib import Path

import yaml

from app.config import settings


def _slug(title: str) -> str:
    s = re.sub(r"[^a-z0-9]+", "-", (title or "").lower()).strip("-")
    return (s or "entry")[:60]


def _dump(front: dict, body: str) -> str:
    fm = yaml.safe_dump(front, sort_keys=False, allow_unicode=True).strip()
    return f"---\n{fm}\n---\n{(body or '').strip()}\n"


def _parse(text: str) -> tuple[dict, str]:
    if not text.startswith("---"):
        return {}, text
    end = text.find("\n---", 3)
    if end < 0:
        return {}, text
    front = yaml.safe_load(text[3:end].strip()) or {}
    if not isinstance(front, dict):
        front = {}
    return front, text[end + 4:].lstrip("\n")


class WikiStore:
    def __init__(self, root: str | None = None):
        self.root = Path(root or Path(settings.state_dir) / "wiki")
        self.active = self.root / "active"
        self.proposed = self.root / "proposed"
        self.active.mkdir(parents=True, exist_ok=True)
        self.proposed.mkdir(parents=True, exist_ok=True)
        self._git("init")
        # keep empty dirs tracked so a fresh repo has something to commit
        for d in (self.active, self.proposed):
            keep = d / ".gitkeep"
            if not keep.exists():
                keep.write_text("")
        self._commit("wiki: init")

    # ---- git (best-effort; store works without it) ------------------------

    def _git(self, *args: str) -> None:
        try:
            subprocess.run(["git", *args], cwd=self.root, check=False,
                           capture_output=True, timeout=15)
        except Exception:  # noqa: BLE001 — git is optional
            pass

    def _commit(self, message: str) -> None:
        self._git("add", "-A")
        self._git("-c", "user.email=wiki@gateway", "-c", "user.name=wiki",
                  "commit", "-m", message)

    # ---- read -------------------------------------------------------------

    def _read(self, path: Path) -> dict | None:
        try:
            front, body = _parse(path.read_text(encoding="utf-8"))
        except OSError:
            return None
        front = dict(front)
        front["body"] = body
        front.setdefault("id", path.stem)
        return front

    def list_active(self) -> list[dict]:
        out = [self._read(p) for p in sorted(self.active.glob("*.md"))]
        return [e for e in out if e]

    def list_proposed(self) -> list[dict]:
        out = [self._read(p) for p in sorted(self.proposed.glob("*.md"))]
        return [e for e in out if e]

    def get_active(self, entry_id: str) -> dict | None:
        p = self.active / f"{entry_id}.md"
        return self._read(p) if p.exists() else None

    def get_proposal(self, proposal_id: str) -> dict | None:
        p = self.proposed / f"{proposal_id}.md"
        return self._read(p) if p.exists() else None

    def index(self) -> list[dict]:
        """Compact {id, title, when_to_use, kind} for the in-context WIKI INDEX."""
        return [{k: e.get(k, "") for k in ("id", "title", "when_to_use", "kind")}
                for e in self.list_active()]

    def search(self, query: str, limit: int = 5) -> list[dict]:
        """Keyword scan over active entries. Instant at personal scale; swap for
        embeddings later without changing callers."""
        terms = [t for t in re.split(r"\W+", (query or "").lower()) if t]
        scored = []
        for e in self.list_active():
            title = (e.get("title") or "").lower()
            when = (e.get("when_to_use") or "").lower()
            body = (e.get("body") or "").lower()
            score = sum(3 * title.count(t) + 2 * when.count(t) + body.count(t) for t in terms)
            if score or not terms:
                scored.append((score, e))
        scored.sort(key=lambda x: x[0], reverse=True)
        return [e for _, e in scored[:limit]]

    # ---- propose / approve / reject ---------------------------------------

    def propose(self, *, title: str, when_to_use: str, body: str, kind: str = "knowledge",
                source: str = "assistant", source_thread: str = "",
                entry_id: str | None = None) -> dict:
        """Draft an entry into proposed/ — NEVER activates. entry_id set => an
        amend of that active entry; else a create. Returns the proposal."""
        op = "amend" if entry_id and self.get_active(entry_id) else "create"
        target_id = entry_id if op == "amend" else self._unique_active_id(title)
        proposal_id = uuid.uuid4().hex[:10]
        front = {
            "proposal_id": proposal_id, "op": op, "target_id": target_id,
            "title": title, "when_to_use": when_to_use,
            "kind": kind if kind in ("knowledge", "procedure") else "knowledge",
            "status": "proposed", "source": source,
            "source_thread": str(source_thread or ""), "created_at": int(time.time()),
        }
        (self.proposed / f"{proposal_id}.md").write_text(_dump(front, body), encoding="utf-8")
        front["body"] = body
        return front

    def _unique_active_id(self, title: str) -> str:
        base = _slug(title)
        cand, n = base, 2
        while (self.active / f"{cand}.md").exists():
            cand = f"{base}-{n}"
            n += 1
        return cand

    def approve(self, proposal_id: str, edited_body: str | None = None) -> dict | None:
        prop = self.get_proposal(proposal_id)
        if prop is None:
            return None
        target_id = prop.get("target_id") or _slug(prop.get("title", ""))
        body = edited_body if edited_body is not None else prop.get("body", "")
        front = {
            "id": target_id, "title": prop.get("title", ""),
            "when_to_use": prop.get("when_to_use", ""), "kind": prop.get("kind", "knowledge"),
            "status": "active", "source": prop.get("source", "assistant"),
            "source_thread": prop.get("source_thread", ""),
            "approved_at": int(time.time()),
        }
        (self.active / f"{target_id}.md").write_text(_dump(front, body), encoding="utf-8")
        (self.proposed / f"{proposal_id}.md").unlink(missing_ok=True)
        self._commit(f"wiki: {prop.get('op', 'create')} '{front['title']}' ({target_id})")
        front["body"] = body
        return front

    def reject(self, proposal_id: str) -> bool:
        p = self.proposed / f"{proposal_id}.md"
        if not p.exists():
            return False
        p.unlink(missing_ok=True)
        return True

    # ---- user-authored active edits ---------------------------------------

    def create(self, *, title: str, when_to_use: str, body: str, kind: str = "knowledge") -> dict:
        target_id = self._unique_active_id(title)
        front = {"id": target_id, "title": title, "when_to_use": when_to_use,
                 "kind": kind, "status": "active", "source": "user",
                 "source_thread": "", "approved_at": int(time.time())}
        (self.active / f"{target_id}.md").write_text(_dump(front, body), encoding="utf-8")
        self._commit(f"wiki: create '{title}' ({target_id})")
        front["body"] = body
        return front

    def update(self, entry_id: str, *, title: str | None = None, when_to_use: str | None = None,
               body: str | None = None, kind: str | None = None) -> dict | None:
        e = self.get_active(entry_id)
        if e is None:
            return None
        new_body = body if body is not None else e.get("body", "")
        front = {"id": entry_id, "title": title or e.get("title", ""),
                 "when_to_use": when_to_use if when_to_use is not None else e.get("when_to_use", ""),
                 "kind": kind or e.get("kind", "knowledge"), "status": "active",
                 "source": e.get("source", "user"), "source_thread": e.get("source_thread", ""),
                 "approved_at": int(time.time())}
        (self.active / f"{entry_id}.md").write_text(_dump(front, new_body), encoding="utf-8")
        self._commit(f"wiki: edit '{front['title']}' ({entry_id})")
        front["body"] = new_body
        return front

    def archive(self, entry_id: str) -> bool:
        p = self.active / f"{entry_id}.md"
        if not p.exists():
            return False
        p.unlink(missing_ok=True)
        self._commit(f"wiki: archive {entry_id}")
        return True

    # ---- diff (for the approve card / admin review) -----------------------

    def diff(self, proposal_id: str) -> str:
        prop = self.get_proposal(proposal_id)
        if prop is None:
            return ""
        base = self.get_active(prop.get("target_id", "")) if prop.get("op") == "amend" else None
        old = (base.get("body", "") if base else "").splitlines(keepends=True)
        new = (prop.get("body", "") or "").splitlines(keepends=True)
        label = prop.get("target_id", "entry")
        return "".join(difflib.unified_diff(
            old, new, fromfile=f"a/{label}", tofile=f"b/{label}", lineterm="\n"))


# Process-wide singleton (the wiki dir is a single, user-owned store).
_store: WikiStore | None = None


def store() -> WikiStore:
    global _store
    if _store is None:
        _store = WikiStore()
    return _store
