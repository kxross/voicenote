"""Tools for the `vm` skill — a code interpreter the agent drives with bash,
backed by the vmm daemon (firecracker microVMs OR docker containers, selected
daemon-side). Every tool is a thin async HTTP call to the daemon, so this
module has no sandbox dependency and works identically whichever backend the
daemon runs. Which backend a new VM uses is `state["default_backend"]` (from
gateway config) unless the model names one.

The daemon's approval gate is transparent here: in manual mode a `vm_exec`
call blocks server-side until a human approves, so it just looks like a slow
tool — the long httpx timeout accommodates it. In smart mode the daemon first
screens each command with System One and holds only flagged ones; to make that
judgment it gets context from here — the user's request, the model's stated
purpose, and the source of any file written this turn that the command runs
(so `python script.py` is reviewed by its contents, not just its name).
"""

from __future__ import annotations

import base64
import json
import re
import shlex
import time
from collections.abc import Callable
from datetime import datetime

import httpx

from app import sources

ToolSpec = tuple[dict, Callable]

# exec can wait on a human approval and on long builds (npm/pip), so give the
# HTTP call plenty of room; the daemon enforces the real per-command timeout.
_TIMEOUT = httpx.Timeout(connect=10, read=600, write=60, pool=10)


# vm_id is optional on every command: omit it and the tool uses "your machine"
# — the persistent sandbox the gateway ensures for this scope. Pass an explicit
# id only to target a different sandbox you got from vm_list.
_VM_ID_PROP = {
    "type": "string",
    "description": "Optional — omit to use your machine. Only set it to target a specific sandbox id.",
}

VM_START_SCHEMA = {
    "type": "function",
    "function": {
        "name": "vm_start",
        "description": (
            "Ensure your machine is ready and return its info. You normally do "
            "NOT need to call this — just use vm_exec and the machine starts on "
            "first use. Call it only if you want to confirm the box or pick a "
            "non-default profile the first time it's created."
        ),
        "parameters": {
            "type": "object",
            "properties": {
                "profile": {"type": "string", "description": "base | python-cv | node-web (only applied when the machine is first created; default base, which already has python + node)"},
                "backend": {"type": "string", "description": "Optional: 'docker' or 'firecracker'. Omit to use the configured default."},
            },
            "required": [],
        },
    },
}

VM_NEW_RUN_SCHEMA = {
    "type": "function",
    "function": {
        "name": "vm_new_run",
        "description": (
            "Start a new task run and get its id + folder path. Call this ONCE at "
            "the start of any task that creates files, takes multiple steps, or is "
            "research (skip it for a trivial one-off). It creates "
            "/workspace/runs/<run_id>/ with work/ and output/ subfolders plus "
            "GOAL.md, PLAN.md and STATE.json. Put scratch/intermediate files in "
            "work/, final deliverables in output/. Then drive the run with "
            "vm_checkpoint after each step, and update GOAL.md's Outcome + Files "
            "before you finish. If you are RESUMING a run, do NOT call this — read "
            "the run's STATE.json and continue from its `next`."
        ),
        "parameters": {
            "type": "object",
            "properties": {
                "title": {
                    "type": "string",
                    "description": "Short name for the task, e.g. 'Scrape BBC headlines' (used in the folder name).",
                },
                "goal": {
                    "type": "string",
                    "description": "A sentence or two describing what the user wants; recorded in GOAL.md.",
                },
            },
            "required": ["title", "goal"],
        },
    },
}

VM_CHECKPOINT_SCHEMA = {
    "type": "function",
    "function": {
        "name": "vm_checkpoint",
        "description": (
            "Record progress on the current run so it can resume cleanly if it is "
            "paused or hits a limit. Call this after each meaningful step. It "
            "rewrites the run's STATE.json and PLAN.md and shows live progress on "
            "the user's phone. Pass the full ordered `plan` on your first "
            "checkpoint (and whenever the plan changes); afterwards just advance "
            "`step`. Set status='complete' on the final checkpoint (then also call "
            "resolve_thread if the request is fully done)."
        ),
        "parameters": {
            "type": "object",
            "properties": {
                "step": {
                    "type": "integer",
                    "description": "How many plan steps are now DONE (0-based count completed).",
                },
                "next_step": {
                    "type": "string",
                    "description": "One short line: what you will do next (empty if complete).",
                },
                "status": {
                    "type": "string",
                    "description": "running | paused | complete (default running).",
                },
                "plan": {
                    "type": "array",
                    "items": {"type": "string"},
                    "description": "The full ordered list of plan steps. Pass on the first checkpoint and whenever the plan changes; omit to keep the existing plan.",
                },
                "note": {
                    "type": "string",
                    "description": "Optional short note / finding to carry across a resume.",
                },
            },
            "required": ["step"],
        },
    },
}

VM_EXEC_SCHEMA = {
    "type": "function",
    "function": {
        "name": "vm_exec",
        "description": (
            "Run a bash command on your machine and get back stdout, stderr, and "
            "the exit code. This is your main tool — chain commands with && or ; "
            "and inspect the exit code. The machine is persistent: /workspace and "
            "installed packages carry over between commands. ALWAYS verify results "
            "by running them (build, execute, test) before telling the user "
            "something works."
        ),
        "parameters": {
            "type": "object",
            "properties": {
                "command": {"type": "string", "description": "bash command line"},
                "vm_id": _VM_ID_PROP,
                "timeout": {"type": "integer", "description": "seconds (optional)"},
                "purpose": {
                    "type": "string",
                    "description": (
                        "Optional but encouraged: one short sentence (≤12 words, "
                        "plain language) on what this command is for — shown to "
                        "the user as the live status, e.g. 'Checking the Q4 "
                        "revenue figures'. Leave off for trivial commands."
                    ),
                },
            },
            "required": ["command"],
        },
    },
}

VM_WRITE_FILE_SCHEMA = {
    "type": "function",
    "function": {
        "name": "vm_write_file",
        "description": (
            "Write text content to a file on your machine (creating parent "
            "dirs). Use this for source files or scripts instead of fragile "
            "echo/heredoc via vm_exec."
        ),
        "parameters": {
            "type": "object",
            "properties": {
                "path": {"type": "string", "description": "absolute path on the machine, e.g. /workspace/app.py"},
                "content": {"type": "string"},
                "vm_id": _VM_ID_PROP,
            },
            "required": ["path", "content"],
        },
    },
}

VM_READ_FILE_SCHEMA = {
    "type": "function",
    "function": {
        "name": "vm_read_file",
        "description": "Read a text file off your machine and return its contents.",
        "parameters": {
            "type": "object",
            "properties": {
                "path": {"type": "string"},
                "vm_id": _VM_ID_PROP,
            },
            "required": ["path"],
        },
    },
}

VM_INSTALL_SCHEMA = {
    "type": "function",
    "function": {
        "name": "vm_install",
        "description": (
            "Install packages on your machine. Use when an import/command is "
            "missing — the box has internet. manager: 'pip' (default), 'apt', or "
            "'npm'. This is a convenience wrapper over vm_exec."
        ),
        "parameters": {
            "type": "object",
            "properties": {
                "packages": {"type": "string", "description": "space-separated package names"},
                "manager": {"type": "string", "description": "pip | apt | npm (default pip)"},
                "vm_id": _VM_ID_PROP,
            },
            "required": ["packages"],
        },
    },
}

VM_LIST_SCHEMA = {
    "type": "function",
    "function": {
        "name": "vm_list",
        "description": "List the sandboxes currently running (id, backend, profile, idle time).",
        "parameters": {"type": "object", "properties": {}, "required": []},
    },
}

VM_DESTROY_SCHEMA = {
    "type": "function",
    "function": {
        "name": "vm_destroy",
        "description": (
            "Destroy a sandbox when done with it, freeing its resources. Idle "
            "sandboxes are also auto-destroyed after a while, so this is optional "
            "but tidy."
        ),
        "parameters": {
            "type": "object",
            "properties": {"vm_id": {"type": "string"}},
            "required": ["vm_id"],
        },
    },
}


def build_vm_tools(state: dict) -> list[ToolSpec]:
    """`state` needs "vm_base_url" (the daemon URL). Optional:
    "default_backend" (which backend new VMs use), "vm_key" (scope key — the
    gateway ensures one sandbox per key; "shared" = one machine for everything),
    and "vm_ttl_minutes" (0 pins the box, None uses the daemon default)."""
    base_url = state.get("vm_base_url") or "http://localhost:5002"
    default_backend = state.get("default_backend") or ""
    vm_key = state.get("vm_key") or "shared"
    vm_ttl = state.get("vm_ttl_minutes", 0)

    # Caches this scope's sandbox id for the turn. The daemon keys by vm_key so
    # the SAME box is returned across turns — persistence is daemon-side.
    ensured: dict[str, str | None] = {"id": None}

    # Files written this turn (path -> source), so smart-mode review can see the
    # code a command actually runs. Bounded: newest 20 files.
    written: dict[str, str] = {}

    def _review_context(command: str, purpose: str | None) -> dict:
        ctx: dict = {}
        if state.get("user_request"):
            ctx["user_request"] = str(state["user_request"])[:2000]
        if purpose:
            ctx["purpose"] = purpose
        runs = {
            p: src for p, src in written.items()
            if p in command or (p.rsplit("/", 1)[-1] and p.rsplit("/", 1)[-1] in command)
        }
        if runs:
            ctx["files"] = runs
        return ctx

    def _client() -> httpx.AsyncClient:
        return httpx.AsyncClient(base_url=base_url, timeout=_TIMEOUT)

    async def _err(prefix: str, e: Exception) -> str:
        if isinstance(e, httpx.HTTPStatusError):
            try:
                detail = e.response.json().get("detail", e.response.text)
            except (ValueError, AttributeError):
                detail = e.response.text
            return f"[error] {prefix}: {detail}"
        if isinstance(e, httpx.ConnectError):
            return (f"[error] {prefix}: can't reach the sandbox daemon at {base_url}. "
                    "Is vmmd running?")
        return f"[error] {prefix}: {e}"

    async def _ensure_vm(profile: str | None = None, backend: str | None = None) -> dict:
        """Ensure this scope's machine exists and return its record. Idempotent
        on the daemon via `key`, so repeated calls (and later turns) get the
        same box. Raises on failure; callers format via _err."""
        if ensured["id"]:
            return {"id": ensured["id"], "reused": True}
        body: dict = {"key": vm_key, "profile": profile or "base"}
        chosen = backend or default_backend
        if chosen:
            body["backend"] = chosen
        if vm_ttl is not None:
            body["ttl_minutes"] = vm_ttl
        async with _client() as c:
            r = await c.post("/vms", json=body)
            r.raise_for_status()
            vm = r.json()
        ensured["id"] = vm["id"]
        return vm

    async def _vm_id(explicit: str | None) -> str:
        """Resolve the sandbox to act on: an explicit id if given, else the
        scope's ensured machine."""
        if explicit:
            return explicit
        vm = await _ensure_vm()
        return vm["id"]

    async def vm_start(profile: str | None = None, backend: str | None = None) -> str:
        try:
            vm = await _ensure_vm(profile=profile, backend=backend)
        except Exception as e:  # noqa: BLE001
            return await _err("vm_start failed", e)
        verb = "Ready — reusing" if vm.get("reused") else "Started"
        extra = "" if vm.get("reused") else f" (backend={vm.get('backend')}, profile={vm.get('profile')})"
        return (f"{verb} your machine{extra}. Just run commands with vm_exec — "
                "no vm_id needed.")

    async def _exec_raw(vm_id: str, command: str, timeout: int | None = None,
                        context: dict | None = None) -> dict:
        body: dict = {"command": command}
        if timeout:
            body["timeout"] = timeout
        if context:
            body["context"] = context
        async with _client() as c:
            r = await c.post(f"/vms/{vm_id}/exec", json=body)
            r.raise_for_status()
            return r.json()

    async def vm_exec(
        command: str, vm_id: str | None = None, timeout: int | None = None,
        purpose: str | None = None,   # live status + context for smart-mode review
    ) -> str:
        ctx = _review_context(command, purpose)
        try:
            target = await _vm_id(vm_id)
            res = await _exec_raw(target, command, timeout, ctx)
        except Exception as e:  # noqa: BLE001
            return await _err("vm_exec failed", e)
        return _fmt_exec(res) + _record_browsed(command, ctx.get("files"), res)

    def _record_browsed(command: str, files: dict | None, res: dict) -> str:
        """Pages a successful command fetched become numbered "browse" sources;
        tell the model their numbers so it can cite them like search results."""
        if res.get("exit_code") != 0:
            return ""
        try:
            urls = sources.fetched_urls(command, list((files or {}).values()))
        except Exception:  # noqa: BLE001 — never break a command over bookkeeping
            return ""
        got = []
        for u in urls:
            n = sources.record(state, u, via="browse")
            if n is not None:
                got.append(f"[{n}] {u}")
        if not got:
            return ""
        return ("\n\nsources recorded (pages this command fetched — cite as [n] if you "
                "use what they say):\n" + "\n".join(got))

    async def vm_new_run(title: str, goal: str) -> str:
        title = (title or "").strip() or "run"
        goal = (goal or "").strip() or "(not specified)"
        slug = re.sub(r"[^a-z0-9]+", "-", title.lower()).strip("-")[:40] or "run"
        run_id = f"{datetime.now():%Y%m%d-%H%M%S}_{slug}"
        run_dir = f"/workspace/runs/{run_id}"
        try:
            vm_id = await _vm_id(None)
            res = await _exec_raw(
                vm_id, f"mkdir -p {shlex.quote(run_dir)}/work {shlex.quote(run_dir)}/output"
            )
        except Exception as e:  # noqa: BLE001
            return await _err("vm_new_run failed", e)
        if res.get("exit_code") != 0:
            return f"[error] vm_new_run: couldn't create {run_dir}: {(res.get('stderr') or '').strip()[:200]}"
        # Record the active run on the shared per-request state so the runtime's
        # continuation controller (auto-continue / resume) knows where the
        # checkpoint lives without the model having to tell it.
        state["run_id"] = run_id
        state["run_dir"] = run_dir
        goal_md = f"# {title}\n\n## Goal\n{goal}\n\n## Outcome\n\n## Files\n"
        await vm_write_file(f"{run_dir}/GOAL.md", goal_md, vm_id=vm_id)
        state0 = {
            "run_id": run_id, "title": title, "status": "running",
            "step": 0, "total": 0, "plan": [], "next": "",
            "updated_at": int(time.time()),
        }
        await vm_write_file(f"{run_dir}/STATE.json", json.dumps(state0, indent=2), vm_id=vm_id)
        await vm_write_file(
            f"{run_dir}/PLAN.md",
            f"# Plan — {title}\n\n_(call vm_checkpoint with the plan steps)_\n", vm_id=vm_id,
        )
        return (
            f"Run {run_id} ready at {run_dir}\n"
            f"- scratch & intermediate files -> {run_dir}/work\n"
            f"- final deliverables -> {run_dir}/output\n"
            f"- call vm_checkpoint(step, next_step, plan=[...]) after each step; "
            f"set status='complete' when done.\n"
            f"- update {run_dir}/GOAL.md (Outcome + Files) before you finish.\n"
            f"If you ever resume, read {run_dir}/STATE.json first and continue from its `next`."
        )

    async def vm_checkpoint(
        step: int, next_step: str = "", status: str = "running",
        plan: list | None = None, note: str = "",
    ) -> str:
        run_dir = state.get("run_dir")
        if not run_dir:
            return "[error] vm_checkpoint: no active run — call vm_new_run first."
        # Merge onto the prior STATE.json so the plan/title survive even when the
        # model omits them on later checkpoints.
        prior: dict = {}
        try:
            target = await _vm_id(None)
            res = await _exec_raw(target, f"cat -- {json.dumps(run_dir + '/STATE.json')}")
            if res.get("exit_code") == 0 and (res.get("stdout") or "").strip():
                prior = json.loads(res["stdout"])
        except Exception:  # noqa: BLE001 - a missing/corrupt checkpoint just starts fresh
            prior = {}
        cur = dict(prior)
        cur["run_id"] = state.get("run_id") or cur.get("run_id", "")
        if plan is not None:
            cur["plan"] = [str(p) for p in plan]
            cur["total"] = len(cur["plan"])
        cur["step"] = int(step)
        cur["status"] = (status or "running").strip()
        cur["next"] = (next_step or "").strip()
        if note:
            cur["note"] = note
        cur["updated_at"] = int(time.time())
        try:
            await vm_write_file(f"{run_dir}/STATE.json", json.dumps(cur, indent=2))
            planlist = cur.get("plan") or []
            lines = [f"# Plan — {cur.get('title', 'run')}", ""]
            for i, item in enumerate(planlist):
                lines.append(f"- [{'x' if i < cur['step'] else ' '}] {item}")
            if cur.get("next"):
                lines += ["", f"**Next:** {cur['next']}"]
            await vm_write_file(f"{run_dir}/PLAN.md", "\n".join(lines) + "\n")
        except Exception as e:  # noqa: BLE001
            return await _err("vm_checkpoint failed", e)
        # Live progress to the phone (best-effort; never fails the tool).
        try:
            from app.diagnostics import progress_publish
            planlist = cur.get("plan") or []
            progress_publish(state.get("session"), {
                "type": "progress", "phase": "plan",
                "steps": [{"text": it, "done": i < cur["step"]} for i, it in enumerate(planlist)],
                "step": cur["step"], "total": cur.get("total", 0),
                "next": cur.get("next", ""),
                "label": cur.get("next") or f"step {cur['step']}/{cur.get('total', 0)}",
            })
        except Exception:  # noqa: BLE001
            pass
        return (
            f"Checkpoint saved: {cur['step']}/{cur.get('total', 0)} done, "
            f"status={cur['status']}, next={cur.get('next') or '—'}."
        )

    async def vm_write_file(path: str, content: str, vm_id: str | None = None) -> str:
        try:
            target = await _vm_id(vm_id)
        except Exception as e:  # noqa: BLE001
            return await _err("vm_write_file failed", e)
        b64 = base64.b64encode(content.encode()).decode()
        try:
            async with _client() as c:
                r = await c.put(f"/vms/{target}/file", json={"path": path, "content_b64": b64})
                r.raise_for_status()
                out = r.json()
        except Exception as e:  # noqa: BLE001
            return await _err("vm_write_file failed", e)
        written.pop(path, None)
        written[path] = content
        while len(written) > 20:
            written.pop(next(iter(written)))
        return f"Wrote {out.get('bytes', len(content))} bytes to {path}."

    async def vm_read_file(path: str, vm_id: str | None = None) -> str:
        # Read as text via cat (the daemon's file GET returns a tar; cat is
        # what the model actually wants for source/output files).
        return await vm_exec(f"cat -- {json.dumps(path)}", vm_id=vm_id)

    async def vm_install(packages: str, manager: str | None = None, vm_id: str | None = None) -> str:
        mgr = (manager or "pip").lower()
        if mgr == "pip":
            cmd = f"pip install {packages}"
        elif mgr == "apt":
            cmd = f"apt-get update && apt-get install -y {packages}"
        elif mgr == "npm":
            cmd = f"npm install {packages}"
        else:
            return f"[error] unknown package manager '{manager}' (use pip, apt, or npm)"
        return await vm_exec(cmd, vm_id=vm_id, timeout=300)

    async def vm_list() -> str:
        try:
            async with _client() as c:
                r = await c.get("/vms")
                r.raise_for_status()
                vms = r.json().get("vms", [])
        except Exception as e:  # noqa: BLE001
            return await _err("vm_list failed", e)
        if not vms:
            return "No sandboxes running."
        lines = [f"- {v['id']} [{v['backend']}] {v['profile']} "
                 f"({v['state']}, idle {v['idle_seconds']}s)" for v in vms]
        return "\n".join(lines)

    async def vm_destroy(vm_id: str) -> str:
        try:
            async with _client() as c:
                r = await c.delete(f"/vms/{vm_id}")
                r.raise_for_status()
        except Exception as e:  # noqa: BLE001
            return await _err("vm_destroy failed", e)
        return f"Destroyed sandbox {vm_id}."

    return [
        (VM_START_SCHEMA, vm_start),
        (VM_NEW_RUN_SCHEMA, vm_new_run),
        (VM_CHECKPOINT_SCHEMA, vm_checkpoint),
        (VM_EXEC_SCHEMA, vm_exec),
        (VM_WRITE_FILE_SCHEMA, vm_write_file),
        (VM_READ_FILE_SCHEMA, vm_read_file),
        (VM_INSTALL_SCHEMA, vm_install),
        (VM_LIST_SCHEMA, vm_list),
        # (VM_DESTROY_SCHEMA, vm_destroy),
    ]


def _fmt_exec(res: dict) -> str:
    """Render an exec result compactly for the model."""
    rc = res.get("exit_code")
    out = (res.get("stdout") or "").rstrip()
    err = (res.get("stderr") or "").rstrip()
    parts = [f"exit_code: {rc}"]
    if out:
        parts.append(f"stdout:\n{out}")
    if err:
        parts.append(f"stderr:\n{err}")
    if res.get("truncated"):
        parts.append("(output truncated)")
    if not out and not err:
        parts.append("(no output)")
    return "\n".join(parts)
