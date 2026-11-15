"""Persistent gateway-side state, backed by aiosqlite.

Two tables:
  * snapshots           — most-recent lists snapshot per session, uploaded
                          by the Android client. Scheduled runs read this
                          to build the same {prompt, lists} payload the
                          client would have sent, without needing a round
                          trip during agent execution.
  * pending_deliveries  — queue of {title, body, ops} awaiting client
                          pickup. The polling pusher writes here; the
                          `GET /v1/pending/{session}` handler drains it.

The DB file lives at STATE_DB_PATH (defaults to `./state.db`). In Docker
this should be mounted on a persistent volume so the queue and the
snapshot survive container restarts.
"""

from __future__ import annotations

import asyncio
import json
import time
from pathlib import Path

import aiosqlite


class State:
    def __init__(self, db_path: str) -> None:
        self.db_path = db_path
        self._conn: aiosqlite.Connection | None = None
        self._delivery_events: dict[str, asyncio.Event] = {}

    async def open(self) -> None:
        Path(self.db_path).parent.mkdir(parents=True, exist_ok=True)
        self._conn = await aiosqlite.connect(self.db_path)
        await self._conn.executescript(
            """
            CREATE TABLE IF NOT EXISTS snapshots (
                session_id TEXT PRIMARY KEY,
                lists_json TEXT NOT NULL,
                notes_json TEXT NOT NULL DEFAULT '[]',
                goals_json TEXT NOT NULL DEFAULT '[]',
                updated_at INTEGER NOT NULL
            );

            CREATE TABLE IF NOT EXISTS pending_deliveries (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                session_id TEXT NOT NULL,
                title TEXT NOT NULL,
                body TEXT NOT NULL,
                ops_json TEXT NOT NULL,
                created_at INTEGER NOT NULL,
                delivered_at INTEGER
            );

            CREATE INDEX IF NOT EXISTS idx_pending_open
                ON pending_deliveries(session_id, delivered_at);

            CREATE TABLE IF NOT EXISTS traces (
                id TEXT PRIMARY KEY,
                created_at REAL NOT NULL,
                trigger TEXT NOT NULL,
                model TEXT NOT NULL,
                session TEXT,
                status TEXT NOT NULL,
                duration_ms INTEGER NOT NULL,
                num_calls INTEGER NOT NULL,
                num_ops INTEGER NOT NULL,
                reply_preview TEXT,
                payload_json TEXT NOT NULL
            );

            CREATE INDEX IF NOT EXISTS idx_traces_time
                ON traces(created_at DESC);

            CREATE TABLE IF NOT EXISTS images (
                sha TEXT PRIMARY KEY,
                session TEXT,
                mime TEXT,
                bytes INTEGER,
                width INTEGER,
                height INTEGER,
                summary TEXT,
                visible_text TEXT,
                tags_json TEXT NOT NULL DEFAULT '[]',
                has_text INTEGER NOT NULL DEFAULT 0,
                text_density TEXT NOT NULL DEFAULT 'none',
                caption_model TEXT,
                caption_version INTEGER,
                created_at INTEGER NOT NULL
            );

            CREATE TABLE IF NOT EXISTS image_answers (
                sha TEXT NOT NULL,
                question TEXT NOT NULL,
                answer TEXT NOT NULL,
                created_at INTEGER NOT NULL,
                PRIMARY KEY (sha, question)
            );

            -- Video attachments (app/media.py). Bytes live in state/media;
            -- `summary` is written once the agent has watched it (text recall).
            CREATE TABLE IF NOT EXISTS media (
                sha TEXT PRIMARY KEY,
                session TEXT,
                mime TEXT,
                bytes INTEGER,
                duration_s REAL,
                width INTEGER,
                height INTEGER,
                summary TEXT,
                created_at INTEGER NOT NULL
            );

            CREATE TABLE IF NOT EXISTS schedules (
                id TEXT PRIMARY KEY,
                cron TEXT NOT NULL,
                model TEXT NOT NULL,
                session_id TEXT NOT NULL,
                prompt TEXT NOT NULL,
                notification_title TEXT NOT NULL DEFAULT '',
                enabled INTEGER NOT NULL DEFAULT 1,
                timezone TEXT NOT NULL DEFAULT '',
                tz_mode TEXT NOT NULL DEFAULT 'fixed',
                created_at INTEGER NOT NULL,
                updated_at INTEGER NOT NULL
            );

            -- Registry admin overlay (the /admin web UI). Built-in agents/skills
            -- live in code; these tables hold custom agents + overrides, applied
            -- over the code base by RegistryService.rebuild().
            CREATE TABLE IF NOT EXISTS reg_agents (
                id TEXT PRIMARY KEY,
                description TEXT NOT NULL DEFAULT '',
                runtime_type TEXT NOT NULL,
                params_json TEXT NOT NULL DEFAULT '{}',
                skill_ids_json TEXT NOT NULL DEFAULT '[]',
                tool_ids_json TEXT NOT NULL DEFAULT '[]',
                enabled INTEGER NOT NULL DEFAULT 1,
                source TEXT NOT NULL DEFAULT 'custom',
                created_at INTEGER NOT NULL,
                updated_at INTEGER NOT NULL
            );

            CREATE TABLE IF NOT EXISTS reg_skill_overlay (
                id TEXT PRIMARY KEY,
                description TEXT,
                body TEXT,
                tool_ids_json TEXT NOT NULL DEFAULT '[]',
                enabled INTEGER NOT NULL DEFAULT 1,
                source TEXT NOT NULL DEFAULT 'builtin-override',
                status TEXT NOT NULL DEFAULT 'active',
                created_at INTEGER NOT NULL,
                updated_at INTEGER NOT NULL
            );

            CREATE TABLE IF NOT EXISTS reg_tool_meta (
                name TEXT PRIMARY KEY,
                description_override TEXT,
                enabled INTEGER NOT NULL DEFAULT 1,
                attachable INTEGER NOT NULL DEFAULT 0,
                created_at INTEGER NOT NULL,
                updated_at INTEGER NOT NULL
            );
            """
        )
        # Best-effort migrations for DBs created before newer columns existed.
        for ddl in (
            "ALTER TABLE snapshots ADD COLUMN notes_json TEXT NOT NULL DEFAULT '[]'",
            "ALTER TABLE snapshots ADD COLUMN goals_json TEXT NOT NULL DEFAULT '[]'",
            "ALTER TABLE traces ADD COLUMN num_images INTEGER NOT NULL DEFAULT 0",
            # Set when a run paused at a checkpoint (status='paused'); the run id
            # the client resumes. Lets the schedules UI offer one-tap Continue.
            "ALTER TABLE traces ADD COLUMN resume_run_id TEXT",
            # Envelope extras (canvas / steps / artifacts) carried through the
            # async delivery queue so non-synchronous turns don't drop them.
            "ALTER TABLE pending_deliveries ADD COLUMN extras_json TEXT",
        ):
            try:
                await self._conn.execute(ddl)
            except aiosqlite.OperationalError:
                pass  # column already exists
        await self._conn.commit()

    async def close(self) -> None:
        if self._conn is not None:
            await self._conn.close()
            self._conn = None

    # ---- schedules ---------------------------------------------------------

    _SCHED_COLS = (
        "id", "cron", "model", "session_id", "prompt", "notification_title",
        "enabled", "timezone", "tz_mode", "created_at", "updated_at",
    )

    def _sched_row(self, row) -> dict:
        d = dict(zip(self._SCHED_COLS, row))
        d["enabled"] = bool(d["enabled"])
        return d

    async def list_schedules(self) -> list[dict]:
        assert self._conn is not None
        async with self._conn.execute(
            f"SELECT {', '.join(self._SCHED_COLS)} FROM schedules ORDER BY id"
        ) as cur:
            rows = await cur.fetchall()
        return [self._sched_row(r) for r in rows]

    async def get_schedule(self, sid: str) -> dict | None:
        assert self._conn is not None
        async with self._conn.execute(
            f"SELECT {', '.join(self._SCHED_COLS)} FROM schedules WHERE id = ?", (sid,)
        ) as cur:
            row = await cur.fetchone()
        return self._sched_row(row) if row else None

    async def upsert_schedule(self, s: dict) -> None:
        assert self._conn is not None
        now = int(time.time())
        await self._conn.execute(
            """INSERT INTO schedules
                 (id, cron, model, session_id, prompt, notification_title,
                  enabled, timezone, tz_mode, created_at, updated_at)
               VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
               ON CONFLICT(id) DO UPDATE SET
                 cron=excluded.cron, model=excluded.model,
                 session_id=excluded.session_id, prompt=excluded.prompt,
                 notification_title=excluded.notification_title,
                 enabled=excluded.enabled, timezone=excluded.timezone,
                 tz_mode=excluded.tz_mode, updated_at=excluded.updated_at""",
            (
                s["id"], s["cron"], s["model"],
                s.get("session_id") or "voicenote-default",
                s["prompt"], s.get("notification_title") or "",
                int(bool(s.get("enabled", True))),
                s.get("timezone") or "", s.get("tz_mode") or "fixed",
                now, now,
            ),
        )
        await self._conn.commit()

    async def delete_schedule(self, sid: str) -> None:
        assert self._conn is not None
        await self._conn.execute("DELETE FROM schedules WHERE id = ?", (sid,))
        await self._conn.commit()

    async def schedules_empty(self) -> bool:
        assert self._conn is not None
        async with self._conn.execute("SELECT COUNT(*) FROM schedules") as cur:
            (n,) = await cur.fetchone()
        return n == 0

    async def retz_device_schedules(self, tz: str) -> list[str]:
        """Point every tz_mode='device' schedule at `tz`; return affected ids."""
        assert self._conn is not None
        async with self._conn.execute(
            "SELECT id FROM schedules WHERE tz_mode = 'device' AND timezone != ?", (tz,)
        ) as cur:
            ids = [r[0] for r in await cur.fetchall()]
        if ids:
            await self._conn.execute(
                "UPDATE schedules SET timezone = ?, updated_at = ? "
                "WHERE tz_mode = 'device' AND timezone != ?",
                (tz, int(time.time()), tz),
            )
            await self._conn.commit()
        return ids

    # ---- images ------------------------------------------------------------

    async def upsert_image(
        self, sha: str, session: str | None, mime: str, nbytes: int,
        width: int = 0, height: int = 0,
    ) -> None:
        """Register a blob. Never clobbers an existing caption — the same
        photo re-uploaded keeps the work already done on it."""
        assert self._conn is not None
        await self._conn.execute(
            """INSERT INTO images(sha, session, mime, bytes, width, height, created_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?)
               ON CONFLICT(sha) DO NOTHING""",
            (sha, session, mime, nbytes, width, height, int(time.time())),
        )
        await self._conn.commit()

    async def get_image(self, sha: str) -> dict | None:
        assert self._conn is not None
        async with self._conn.execute(
            """SELECT sha, session, mime, bytes, width, height, summary, visible_text,
                      tags_json, has_text, text_density, caption_model, caption_version,
                      created_at
                 FROM images WHERE sha = ?""",
            (sha,),
        ) as cur:
            row = await cur.fetchone()
        if not row:
            return None
        cols = ["sha", "session", "mime", "bytes", "width", "height", "summary",
                "visible_text", "tags_json", "has_text", "text_density",
                "caption_model", "caption_version", "created_at"]
        d = dict(zip(cols, row))
        d["tags"] = json.loads(d.pop("tags_json") or "[]")
        d["has_text"] = bool(d["has_text"])
        return d

    async def set_caption(
        self, sha: str, summary: str, visible_text: str, tags: list,
        has_text: bool, text_density: str, model: str, version: int,
    ) -> None:
        assert self._conn is not None
        await self._conn.execute(
            """UPDATE images SET summary = ?, visible_text = ?, tags_json = ?,
                      has_text = ?, text_density = ?, caption_model = ?, caption_version = ?
                WHERE sha = ?""",
            (summary, visible_text, json.dumps(tags), int(has_text),
             text_density, model, version, sha),
        )
        await self._conn.commit()

    async def images_for_session(self, session: str, limit: int = 100) -> list[dict]:
        assert self._conn is not None
        async with self._conn.execute(
            """SELECT sha FROM images WHERE session = ?
                ORDER BY created_at DESC LIMIT ?""",
            (session, limit),
        ) as cur:
            rows = await cur.fetchall()
        out = []
        for (sha,) in rows:
            rec = await self.get_image(sha)
            if rec:
                out.append(rec)
        return out

    async def image_shas_by_age(self) -> list[str]:
        """All image shas, oldest first — feeds housekeeping's orphan check
        (as a set) and LRU eviction (in order)."""
        assert self._conn is not None
        async with self._conn.execute(
            "SELECT sha FROM images ORDER BY created_at ASC"
        ) as cur:
            rows = await cur.fetchall()
        return [r[0] for r in rows]

    # ---- media (video) -------------------------------------------------------

    async def upsert_media(
        self, sha: str, session: str | None, mime: str, nbytes: int,
        duration_s: float = 0.0, width: int = 0, height: int = 0,
    ) -> None:
        """Register a video. Never clobbers an existing summary."""
        assert self._conn is not None
        await self._conn.execute(
            """INSERT INTO media(sha, session, mime, bytes, duration_s, width, height, created_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?)
               ON CONFLICT(sha) DO NOTHING""",
            (sha, session, mime, nbytes, duration_s, width, height, int(time.time())),
        )
        await self._conn.commit()

    async def get_media(self, sha: str) -> dict | None:
        assert self._conn is not None
        async with self._conn.execute(
            """SELECT sha, session, mime, bytes, duration_s, width, height, summary, created_at
                 FROM media WHERE sha = ?""",
            (sha,),
        ) as cur:
            row = await cur.fetchone()
        if not row:
            return None
        cols = ["sha", "session", "mime", "bytes", "duration_s", "width", "height",
                "summary", "created_at"]
        return dict(zip(cols, row))

    async def find_media(self, prefix: str) -> dict | None:
        """A video by full sha or unique prefix (the model sees short ids)."""
        assert self._conn is not None
        prefix = (prefix or "").lower().removeprefix("vid_")
        if len(prefix) < 6:
            return None
        async with self._conn.execute(
            "SELECT sha FROM media WHERE sha LIKE ? ORDER BY created_at DESC LIMIT 2",
            (prefix + "%",),
        ) as cur:
            rows = await cur.fetchall()
        return await self.get_media(rows[0][0]) if len(rows) == 1 else None

    async def set_media_summary(self, sha: str, summary: str) -> None:
        assert self._conn is not None
        await self._conn.execute("UPDATE media SET summary = ? WHERE sha = ?", (summary, sha))
        await self._conn.commit()

    async def media_shas_by_age(self) -> list[str]:
        assert self._conn is not None
        async with self._conn.execute("SELECT sha FROM media ORDER BY created_at ASC") as cur:
            rows = await cur.fetchall()
        return [r[0] for r in rows]

    async def get_image_answer(self, sha: str, question: str) -> str | None:
        assert self._conn is not None
        async with self._conn.execute(
            "SELECT answer FROM image_answers WHERE sha = ? AND question = ?",
            (sha, question),
        ) as cur:
            row = await cur.fetchone()
        return row[0] if row else None

    async def set_image_answer(self, sha: str, question: str, answer: str) -> None:
        assert self._conn is not None
        await self._conn.execute(
            """INSERT INTO image_answers(sha, question, answer, created_at)
                    VALUES (?, ?, ?, ?)
               ON CONFLICT(sha, question) DO UPDATE SET answer = excluded.answer""",
            (sha, question, answer, int(time.time())),
        )
        await self._conn.commit()

    # ---- diagnostics traces ------------------------------------------------

    async def insert_trace(self, trace, keep: int = 2000) -> None:
        """Persist a finished trace (summary columns + full payload JSON)
        and prune to the newest `keep` rows."""
        assert self._conn is not None
        await self._conn.execute(
            """INSERT OR REPLACE INTO traces
                    (id, created_at, trigger, model, session, status,
                     duration_ms, num_calls, num_ops, num_images, reply_preview,
                     resume_run_id, payload_json)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
            (
                trace.id, trace.created_at, trace.trigger, trace.model, trace.session,
                trace.status, trace.duration_ms, trace.num_calls, trace.num_ops,
                getattr(trace, "num_images", 0),
                trace.reply_preview, getattr(trace, "resume_run_id", None),
                json.dumps(trace.full(), default=str),
            ),
        )
        await self._conn.execute(
            "DELETE FROM traces WHERE id NOT IN "
            "(SELECT id FROM traces ORDER BY created_at DESC LIMIT ?)",
            (keep,),
        )
        await self._conn.commit()

    async def list_traces(self, limit: int = 200) -> list[dict]:
        assert self._conn is not None
        async with self._conn.execute(
            """SELECT id, created_at, trigger, model, session, status,
                      duration_ms, num_calls, num_ops, num_images, reply_preview
                 FROM traces ORDER BY created_at DESC LIMIT ?""",
            (limit,),
        ) as cur:
            rows = await cur.fetchall()
        cols = ["id", "created_at", "trigger", "model", "session", "status",
                "duration_ms", "num_calls", "num_ops", "num_images", "reply_preview"]
        return [dict(zip(cols, r)) for r in rows]

    async def get_trace(self, trace_id: str) -> dict | None:
        assert self._conn is not None
        async with self._conn.execute(
            "SELECT payload_json FROM traces WHERE id = ?", (trace_id,)
        ) as cur:
            row = await cur.fetchone()
        return json.loads(row[0]) if row else None

    # ---- scheduled-run history (traces keyed by trigger='schedule:<id>') ----

    _RUN_COLS = ("id", "created_at", "status", "duration_ms", "num_calls",
                 "reply_preview", "resume_run_id")

    def _run_row(self, row) -> dict:
        d = dict(zip(self._RUN_COLS, row))
        d["at"] = d.pop("created_at")
        return d

    async def list_schedule_runs(self, schedule_id: str, limit: int = 20) -> list[dict]:
        """Recent runs of one schedule, newest first — its trace history."""
        assert self._conn is not None
        async with self._conn.execute(
            f"SELECT {', '.join(self._RUN_COLS)} FROM traces "
            "WHERE trigger = ? ORDER BY created_at DESC LIMIT ?",
            (f"schedule:{schedule_id}", limit),
        ) as cur:
            rows = await cur.fetchall()
        return [self._run_row(r) for r in rows]

    async def latest_run_for(self, schedule_id: str) -> dict | None:
        """The most recent run of one schedule (for the list view's badge)."""
        runs = await self.list_schedule_runs(schedule_id, limit=1)
        return runs[0] if runs else None

    # ---- snapshot cache ----------------------------------------------------

    async def get_snapshot(self, session_id: str) -> list:
        assert self._conn is not None
        async with self._conn.execute(
            "SELECT lists_json FROM snapshots WHERE session_id = ?", (session_id,)
        ) as cur:
            row = await cur.fetchone()
        return json.loads(row[0]) if row else []

    async def get_notes(self, session_id: str) -> list:
        assert self._conn is not None
        async with self._conn.execute(
            "SELECT notes_json FROM snapshots WHERE session_id = ?", (session_id,)
        ) as cur:
            row = await cur.fetchone()
        return json.loads(row[0]) if row else []

    async def get_goals(self, session_id: str) -> list:
        assert self._conn is not None
        async with self._conn.execute(
            "SELECT goals_json FROM snapshots WHERE session_id = ?", (session_id,)
        ) as cur:
            row = await cur.fetchone()
        return json.loads(row[0]) if row else []

    async def set_snapshot(
        self,
        session_id: str,
        lists: list,
        notes: list | None = None,
        goals: list | None = None,
    ) -> None:
        """Upsert lists/notes/goals for a session. Notes and goals are
        optional — if omitted, preserve whatever's already cached (so a
        partial upload doesn't clobber the other fields)."""
        assert self._conn is not None
        if notes is None:
            notes = await self.get_notes(session_id)
        if goals is None:
            goals = await self.get_goals(session_id)
        await self._conn.execute(
            """INSERT INTO snapshots(session_id, lists_json, notes_json, goals_json, updated_at)
                    VALUES (?, ?, ?, ?, ?)
               ON CONFLICT(session_id) DO UPDATE SET
                    lists_json = excluded.lists_json,
                    notes_json = excluded.notes_json,
                    goals_json = excluded.goals_json,
                    updated_at = excluded.updated_at""",
            (session_id, json.dumps(lists), json.dumps(notes), json.dumps(goals), int(time.time())),
        )
        await self._conn.commit()

    # ---- delivery queue ----------------------------------------------------

    async def queue_delivery(
        self, session_id: str, title: str, body: str, ops: list,
        extras: dict | None = None,
    ) -> int:
        assert self._conn is not None
        cur = await self._conn.execute(
            """INSERT INTO pending_deliveries(session_id, title, body, ops_json, extras_json, created_at)
                    VALUES (?, ?, ?, ?, ?, ?)""",
            (session_id, title, body, json.dumps(ops),
             json.dumps(extras) if extras else None, int(time.time())),
        )
        await self._conn.commit()
        self._signal_delivery(session_id)
        return cur.lastrowid

    # Long-poll support: one Event per session, set (and replaced) whenever a
    # delivery is queued so every waiter on GET /v1/pending?wait= wakes at once.
    # Take the event BEFORE listing, then wait on it — a delivery queued in
    # between sets the event you already hold, so it can't be missed.
    def delivery_event(self, session_id: str) -> asyncio.Event:
        ev = self._delivery_events.get(session_id)
        if ev is None:
            ev = self._delivery_events[session_id] = asyncio.Event()
        return ev

    def _signal_delivery(self, session_id: str) -> None:
        ev = self._delivery_events.pop(session_id, None)
        if ev is not None:
            ev.set()

    @staticmethod
    async def wait_event(ev: asyncio.Event, timeout: float) -> bool:
        """Wait for [ev] up to [timeout] s. True if it fired."""
        try:
            await asyncio.wait_for(ev.wait(), timeout)
            return True
        except asyncio.TimeoutError:
            return False

    async def list_pending(self, session_id: str) -> list[dict]:
        assert self._conn is not None
        async with self._conn.execute(
            """SELECT id, title, body, ops_json, created_at, extras_json
                    FROM pending_deliveries
                   WHERE session_id = ? AND delivered_at IS NULL
                ORDER BY id""",
            (session_id,),
        ) as cur:
            rows = await cur.fetchall()
        out = []
        for r in rows:
            d = {
                "id": r[0],
                "title": r[1],
                "body": r[2],
                "ops": json.loads(r[3]),
                "created_at": r[4],
            }
            # Flatten the envelope extras (canvas / steps / artifacts) onto the
            # delivery so the client parses them the same way as a live reply.
            if r[5]:
                try:
                    d.update(json.loads(r[5]))
                except (ValueError, TypeError):
                    pass
            out.append(d)
        return out

    async def mark_delivered(self, delivery_id: int) -> bool:
        assert self._conn is not None
        cur = await self._conn.execute(
            """UPDATE pending_deliveries
                  SET delivered_at = ?
                WHERE id = ? AND delivered_at IS NULL""",
            (int(time.time()), delivery_id),
        )
        await self._conn.commit()
        return cur.rowcount > 0

    # ---- registry overlay (admin UI) --------------------------------------
    # JSON columns are parsed into Python on read; callers pass Python in.

    async def list_reg_agents(self) -> list[dict]:
        assert self._conn is not None
        async with self._conn.execute(
            "SELECT id, description, runtime_type, params_json, skill_ids_json, "
            "tool_ids_json, enabled, source, created_at, updated_at FROM reg_agents "
            "ORDER BY updated_at DESC"
        ) as cur:
            rows = await cur.fetchall()
        out = []
        for r in rows:
            out.append({
                "id": r[0], "description": r[1], "runtime_type": r[2],
                "params": json.loads(r[3] or "{}"),
                "skill_ids": json.loads(r[4] or "[]"),
                "tool_ids": json.loads(r[5] or "[]"),
                "enabled": bool(r[6]), "source": r[7],
                "created_at": r[8], "updated_at": r[9],
            })
        return out

    async def upsert_reg_agent(self, a: dict) -> None:
        assert self._conn is not None
        now = int(time.time())
        await self._conn.execute(
            """INSERT INTO reg_agents
                 (id, description, runtime_type, params_json, skill_ids_json,
                  tool_ids_json, enabled, source, created_at, updated_at)
               VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
               ON CONFLICT(id) DO UPDATE SET
                 description=excluded.description,
                 runtime_type=excluded.runtime_type,
                 params_json=excluded.params_json,
                 skill_ids_json=excluded.skill_ids_json,
                 tool_ids_json=excluded.tool_ids_json,
                 enabled=excluded.enabled,
                 updated_at=excluded.updated_at""",
            (a["id"], a.get("description", ""), a["runtime_type"],
             json.dumps(a.get("params", {})), json.dumps(a.get("skill_ids", [])),
             json.dumps(a.get("tool_ids", [])), 1 if a.get("enabled", True) else 0,
             a.get("source", "custom"), now, now),
        )
        await self._conn.commit()

    async def delete_reg_agent(self, agent_id: str) -> bool:
        assert self._conn is not None
        cur = await self._conn.execute("DELETE FROM reg_agents WHERE id = ?", (agent_id,))
        await self._conn.commit()
        return cur.rowcount > 0

    async def list_reg_skill_overlays(self) -> list[dict]:
        assert self._conn is not None
        async with self._conn.execute(
            "SELECT id, description, body, tool_ids_json, enabled, source, status, "
            "created_at, updated_at FROM reg_skill_overlay ORDER BY id"
        ) as cur:
            rows = await cur.fetchall()
        return [{
            "id": r[0], "description": r[1], "body": r[2],
            "tool_ids": json.loads(r[3] or "[]"), "enabled": bool(r[4]),
            "source": r[5], "status": r[6], "created_at": r[7], "updated_at": r[8],
        } for r in rows]

    async def upsert_reg_skill_overlay(self, s: dict) -> None:
        assert self._conn is not None
        now = int(time.time())
        await self._conn.execute(
            """INSERT INTO reg_skill_overlay
                 (id, description, body, tool_ids_json, enabled, source, status,
                  created_at, updated_at)
               VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
               ON CONFLICT(id) DO UPDATE SET
                 description=excluded.description, body=excluded.body,
                 tool_ids_json=excluded.tool_ids_json, enabled=excluded.enabled,
                 source=excluded.source, status=excluded.status,
                 updated_at=excluded.updated_at""",
            (s["id"], s.get("description"), s.get("body"),
             json.dumps(s.get("tool_ids", [])), 1 if s.get("enabled", True) else 0,
             s.get("source", "builtin-override"), s.get("status", "active"), now, now),
        )
        await self._conn.commit()

    async def delete_reg_skill_overlay(self, sid: str) -> bool:
        assert self._conn is not None
        cur = await self._conn.execute("DELETE FROM reg_skill_overlay WHERE id = ?", (sid,))
        await self._conn.commit()
        return cur.rowcount > 0

    async def list_reg_tool_meta(self) -> list[dict]:
        assert self._conn is not None
        async with self._conn.execute(
            "SELECT name, description_override, enabled, attachable, created_at, "
            "updated_at FROM reg_tool_meta ORDER BY name"
        ) as cur:
            rows = await cur.fetchall()
        return [{
            "name": r[0], "description_override": r[1], "enabled": bool(r[2]),
            "attachable": bool(r[3]), "created_at": r[4], "updated_at": r[5],
        } for r in rows]

    async def upsert_reg_tool_meta(self, t: dict) -> None:
        assert self._conn is not None
        now = int(time.time())
        await self._conn.execute(
            """INSERT INTO reg_tool_meta
                 (name, description_override, enabled, attachable, created_at, updated_at)
               VALUES (?, ?, ?, ?, ?, ?)
               ON CONFLICT(name) DO UPDATE SET
                 description_override=excluded.description_override,
                 enabled=excluded.enabled, attachable=excluded.attachable,
                 updated_at=excluded.updated_at""",
            (t["name"], t.get("description_override"),
             1 if t.get("enabled", True) else 0, 1 if t.get("attachable") else 0, now, now),
        )
        await self._conn.commit()
