#!/usr/bin/env python3
"""vmm TUI — a terminal cockpit for the sandbox daemon, with two tabs:

  Sandboxes  direct control: list VMs (both backends), run bash, upload/
             download files, toggle approval mode, and approve/deny commands.
  Agent      a full prompt->tool->result harness that runs the gateway's
             ACTUAL OllamaRuntime + build_vm_tools + the vm SKILL body, so a
             green run here is a green run in the gateway. Pick the backend a
             new sandbox uses; Firecracker and Docker sandboxes show together.

Run on the host (talks to vmmd over HTTP):
    cd vmm && python3 tui.py           # or: python3 tui.py --url http://localhost:5002
"""

from __future__ import annotations

import argparse
import asyncio
import sys
from pathlib import Path

import httpx

# Make the gateway package importable for the agent-loop tab.
sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from textual import on, work
from textual.app import App, ComposeResult
from textual.containers import Horizontal, Vertical
from textual.screen import ModalScreen
from textual.widgets import (
    Button, DataTable, Footer, Header, Input, Label, RichLog, Select, Static,
    TabbedContent, TabPane,
)

DEFAULT_URL = "http://localhost:5002"
PROFILES = ["base", "python-cv", "node-web"]


# --------------------------------------------------------------------------
# modal screens
# --------------------------------------------------------------------------

class NewVMScreen(ModalScreen):
    """Pick a profile + backend for a new sandbox. Only backends whose
    preflight passed are offered."""

    def __init__(self, ready_backends: list[str], default_backend: str):
        super().__init__()
        self.ready = ready_backends
        self.default_backend = default_backend

    def compose(self) -> ComposeResult:
        with Vertical(id="dialog"):
            yield Label("New sandbox", id="dtitle")
            yield Select([(p, p) for p in PROFILES], value="base", id="profile", prompt="profile")
            if self.ready:
                yield Select(
                    [(b, b) for b in self.ready],
                    value=self.default_backend if self.default_backend in self.ready else self.ready[0],
                    id="backend", prompt="backend",
                )
            else:
                yield Label("No backends ready — check daemon /healthz", id="nobackend")
            with Horizontal(id="dbtns"):
                yield Button("Start", variant="primary", id="start", disabled=not self.ready)
                yield Button("Cancel", id="cancel")

    @on(Button.Pressed, "#cancel")
    def _cancel(self) -> None:
        self.dismiss(None)

    @on(Button.Pressed, "#start")
    def _start(self) -> None:
        profile = self.query_one("#profile", Select).value
        backend = self.query_one("#backend", Select).value
        self.dismiss({"profile": profile, "backend": backend})


class PromptScreen(ModalScreen):
    """One-line text prompt (used for upload/download paths)."""

    def __init__(self, title: str, placeholder: str = ""):
        super().__init__()
        self.title_text = title
        self.placeholder = placeholder

    def compose(self) -> ComposeResult:
        with Vertical(id="dialog"):
            yield Label(self.title_text, id="dtitle")
            yield Input(placeholder=self.placeholder, id="value")
            with Horizontal(id="dbtns"):
                yield Button("OK", variant="primary", id="ok")
                yield Button("Cancel", id="cancel")

    @on(Button.Pressed, "#cancel")
    def _cancel(self) -> None:
        self.dismiss(None)

    @on(Button.Pressed, "#ok")
    @on(Input.Submitted, "#value")
    def _ok(self) -> None:
        self.dismiss(self.query_one("#value", Input).value.strip() or None)


# --------------------------------------------------------------------------
# main app
# --------------------------------------------------------------------------

class VmmTUI(App):
    CSS = """
    #dialog { width: 60; height: auto; padding: 1 2; background: $panel;
              border: thick $primary; }
    #dtitle { text-style: bold; margin-bottom: 1; }
    #dbtns { height: auto; margin-top: 1; }
    #dbtns Button { margin-right: 2; }
    #banner { height: auto; padding: 0 1; background: $warning-darken-2; color: $text;
              align: left middle; }
    #banner.hidden { display: none; }
    #banner-text { width: 1fr; content-align: left middle; }
    #banner Button { min-width: 14; margin-left: 1; height: 3; }
    DataTable { width: 45%; }
    #out, #transcript { border: solid $primary; }
    """
    BINDINGS = [
        ("n", "new_vm", "New"),
        ("d", "destroy_vm", "Destroy"),
        ("a", "toggle_approval", "Appr.mode"),
        ("y", "approve", "Approve"),
        ("x", "deny", "Deny"),
        ("u", "upload", "Upload"),
        ("g", "download", "Download"),
        ("q", "quit", "Quit"),
    ]

    def __init__(self, base_url: str):
        super().__init__()
        self.base_url = base_url
        self.active_vm: str | None = None
        self.health: dict = {}
        self.pending: list[dict] = []
        # Per-sandbox output history (rich-markup lines). The #out pane shows
        # only the active sandbox's buffer; other sandboxes' output is kept
        # here and replayed when you switch to them.
        self.logs: dict[str, list[str]] = {}

    def compose(self) -> ComposeResult:
        yield Header(show_clock=True)
        with TabbedContent():
            with TabPane("Sandboxes", id="direct"):
                with Horizontal(id="banner", classes="hidden"):
                    yield Label("", id="banner-text")
                    yield Button("Approve (y)", id="approve", variant="success")
                    yield Button("Deny (x)", id="deny", variant="error")
                with Horizontal():
                    yield DataTable(id="vms", cursor_type="row")
                    with Vertical():
                        yield RichLog(id="out", wrap=True, highlight=True, markup=True)
                        yield Input(placeholder="bash in selected sandbox…  (Enter to run)", id="cmd")
            with TabPane("Agent", id="agent"):
                yield RichLog(id="transcript", wrap=True, highlight=True, markup=True)
                yield Input(placeholder="prompt the agent (runs the real gateway runtime)…", id="prompt")
        yield Footer()

    def on_mount(self) -> None:
        t = self.query_one("#vms", DataTable)
        t.add_columns("id", "backend", "profile", "state", "idle")
        self._show_active_log()
        self.set_interval(2.0, self.poll_daemon)
        self.call_after_refresh(self.poll_daemon)

    # ---- per-sandbox output ----------------------------------------------

    def _log(self, vm_id: str, markup: str) -> None:
        """Record a line for a sandbox; render it only if that sandbox is the
        one currently selected."""
        self.logs.setdefault(vm_id, []).append(markup)
        if vm_id == self.active_vm:
            self.query_one("#out", RichLog).write(markup)

    def _show_active_log(self) -> None:
        """Repaint the output pane with just the active sandbox's history."""
        out = self.query_one("#out", RichLog)
        out.clear()
        lines = self.logs.get(self.active_vm) if self.active_vm else None
        if not lines:
            out.write("[dim]Select a sandbox on the left, or press [b]n[/b] to start one.[/]")
            return
        for m in lines:
            out.write(m)

    def _set_active(self, vm_id: str | None) -> None:
        if vm_id != self.active_vm:
            self.active_vm = vm_id
            self._show_active_log()

    def _pending_priority(self) -> list[dict]:
        """Pending approvals with the active sandbox's first, so y/x act on
        the command you just typed before an agent's background request."""
        return sorted(self.pending, key=lambda a: 0 if a.get("vm_id") == self.active_vm else 1)

    # ---- polling / rendering ---------------------------------------------

    async def _get(self, path: str) -> dict | None:
        try:
            async with httpx.AsyncClient(base_url=self.base_url, timeout=5) as c:
                r = await c.get(path)
                r.raise_for_status()
                return r.json()
        except Exception:  # noqa: BLE001 — a transient daemon blip shouldn't crash the UI
            return None

    async def poll_daemon(self) -> None:
        self.health = await self._get("/healthz") or {}
        vms = (await self._get("/vms") or {}).get("vms", [])
        appr = (await self._get("/approvals") or {}).get("pending", [])
        self.pending = appr
        self._render_header()
        self._render_table(vms)
        self._render_banner()

    def _render_header(self) -> None:
        if not self.health:
            self.sub_title = f"⚠ can't reach daemon at {self.base_url}"
            return
        backends = self.health.get("backends", {})
        chips = " ".join(
            f"{'●' if v.get('ready') else '○'}{name}" for name, v in backends.items()
        )
        self.sub_title = (
            f"{self.base_url}  |  backends {chips}  |  default={self.health.get('default_backend')}"
            f"  |  approval={self.health.get('approval_mode')}"
        )

    def _render_table(self, vms: list[dict]) -> None:
        t = self.query_one("#vms", DataTable)
        t.clear()
        for v in vms:
            t.add_row(v["id"], v["backend"], v["profile"], v["state"],
                      f"{v['idle_seconds']}s", key=v["id"])
        ids = {v["id"] for v in vms}
        # forget output buffers for sandboxes that no longer exist
        for gone in [k for k in self.logs if k not in ids]:
            del self.logs[gone]
        # keep the active selection valid; repaint the pane if it changed
        if self.active_vm not in ids:
            self._set_active(vms[0]["id"] if vms else None)

    def _render_banner(self) -> None:
        banner = self.query_one("#banner", Horizontal)
        prioritized = self._pending_priority()
        if prioritized:
            a = prioritized[0]
            self.query_one("#banner-text", Label).update(
                f"⏳ APPROVAL NEEDED  vm={a['vm_id']}  →  {a['command']}   ({len(prioritized)} pending)"
            )
            banner.remove_class("hidden")
        else:
            banner.add_class("hidden")

    @on(Button.Pressed, "#approve")
    async def _btn_approve(self) -> None:
        await self._decide(True)

    @on(Button.Pressed, "#deny")
    async def _btn_deny(self) -> None:
        await self._decide(False)

    @on(DataTable.RowSelected, "#vms")
    def _row_selected(self, event: DataTable.RowSelected) -> None:
        if event.row_key is not None:
            self._set_active(event.row_key.value)

    @on(DataTable.RowHighlighted, "#vms")
    def _row_highlighted(self, event: DataTable.RowHighlighted) -> None:
        if event.row_key is not None:
            self._set_active(event.row_key.value)

    # ---- direct-tab actions ----------------------------------------------

    @on(Input.Submitted, "#cmd")
    def _run_cmd(self, event: Input.Submitted) -> None:
        # Sync handler: dispatch to a worker and return immediately. In manual
        # mode the exec blocks server-side until approved, so it MUST NOT run
        # on the message pump — otherwise the y/x keys that approve it can't be
        # processed and the whole TUI deadlocks.
        cmd = event.value.strip()
        if not cmd:
            return
        event.input.value = ""
        if not self.active_vm:
            self._show_active_log()
            self.query_one("#out", RichLog).write("[red]No sandbox selected. Press n to start one.[/]")
            return
        self._exec_worker(cmd, self.active_vm)

    @work
    async def _exec_worker(self, cmd: str, vm_id: str) -> None:
        self._log(vm_id, f"[bold cyan]$ {cmd}[/]")
        res = await self._post(f"/vms/{vm_id}/exec", {"command": cmd})
        if res is None:
            self._log(vm_id, "[red](exec failed — daemon unreachable)[/]")
            return
        if res.get("stdout"):
            self._log(vm_id, res["stdout"].rstrip())
        if res.get("stderr"):
            self._log(vm_id, f"[red]{res['stderr'].rstrip()}[/]")
        self._log(vm_id, f"[dim]exit {res.get('exit_code')}[/]")

    async def _post(self, path: str, body: dict, timeout: int = 620) -> dict | None:
        try:
            async with httpx.AsyncClient(base_url=self.base_url, timeout=timeout) as c:
                r = await c.post(path, json=body)
                r.raise_for_status()
                return r.json()
        except Exception:  # noqa: BLE001 — caller reports; never write to a shared pane
            return None

    @work
    async def action_new_vm(self) -> None:
        # @work: push_screen_wait requires a worker context.
        ready = [n for n, v in self.health.get("backends", {}).items() if v.get("ready")]
        default = self.health.get("default_backend", "")
        choice = await self.push_screen_wait(NewVMScreen(ready, default))
        if not choice:
            return
        self.notify(f"starting {choice['profile']} on {choice['backend']}…")
        res = await self._post("/vms", choice, timeout=120)
        if res:
            self._set_active(res["id"])
            self._log(res["id"], f"[green]started {res['id']} ({res['backend']}, {res['profile']})[/]")
        else:
            self.notify("failed to start sandbox", severity="error")
        await self.poll_daemon()

    async def action_destroy_vm(self) -> None:
        if not self.active_vm:
            return
        vm = self.active_vm
        try:
            async with httpx.AsyncClient(base_url=self.base_url, timeout=30) as c:
                await c.delete(f"/vms/{vm}")
        except Exception:  # noqa: BLE001
            pass
        self.query_one("#out", RichLog).write(f"[dim]destroyed {vm}[/]")
        await self.poll_daemon()

    async def action_toggle_approval(self) -> None:
        # Cycle auto -> smart (System One screens, holds only flagged) -> manual.
        order = ["auto", "smart", "manual"]
        cur = self.health.get("approval_mode", "auto")
        new = order[(order.index(cur) + 1) % len(order)] if cur in order else "smart"
        try:
            async with httpx.AsyncClient(base_url=self.base_url, timeout=5) as c:
                await c.put("/config/approval-mode", json={"mode": new})
        except Exception:  # noqa: BLE001
            pass
        await self.poll_daemon()

    async def action_approve(self) -> None:
        await self._decide(True)

    async def action_deny(self) -> None:
        await self._decide(False)

    async def _decide(self, approve: bool) -> None:
        prioritized = self._pending_priority()
        if not prioritized:
            return
        aid = prioritized[0]["id"]
        verb = "approve" if approve else "deny"
        try:
            async with httpx.AsyncClient(base_url=self.base_url, timeout=5) as c:
                await c.post(f"/approvals/{aid}/{verb}")
        except Exception:  # noqa: BLE001
            pass
        await self.poll_daemon()

    @work
    async def action_upload(self) -> None:
        if not self.active_vm:
            return
        host_path = await self.push_screen_wait(PromptScreen("Upload — host file path", "/path/on/host"))
        if not host_path:
            return
        dest = await self.push_screen_wait(PromptScreen("Destination in sandbox", "/workspace/file"))
        if not dest:
            return
        out = self.query_one("#out", RichLog)
        try:
            import base64
            data = Path(host_path).read_bytes()
            async with httpx.AsyncClient(base_url=self.base_url, timeout=120) as c:
                r = await c.put(f"/vms/{self.active_vm}/file",
                                json={"path": dest, "content_b64": base64.b64encode(data).decode()})
                r.raise_for_status()
            out.write(f"[green]uploaded {len(data)} bytes → {dest}[/]")
        except Exception as e:  # noqa: BLE001
            out.write(f"[red]upload failed: {e}[/]")

    @work
    async def action_download(self) -> None:
        if not self.active_vm:
            return
        path = await self.push_screen_wait(PromptScreen("Download — path in sandbox", "/workspace/file"))
        if not path:
            return
        dest = await self.push_screen_wait(PromptScreen("Save to host path", str(Path.cwd() / "download.tar")))
        if not dest:
            return
        out = self.query_one("#out", RichLog)
        try:
            import base64
            async with httpx.AsyncClient(base_url=self.base_url, timeout=120) as c:
                r = await c.get(f"/vms/{self.active_vm}/file", params={"path": path})
                r.raise_for_status()
                data = base64.b64decode(r.json()["content_b64"])
            Path(dest).write_bytes(data)
            out.write(f"[green]downloaded {len(data)} bytes → {dest} (tar)[/]")
        except Exception as e:  # noqa: BLE001
            out.write(f"[red]download failed: {e}[/]")

    # ---- agent tab --------------------------------------------------------

    @on(Input.Submitted, "#prompt")
    def _agent_prompt(self, event: Input.Submitted) -> None:
        prompt = event.value.strip()
        if not prompt:
            return
        event.input.value = ""
        self.run_agent(prompt)

    @work(exclusive=True, group="agent")
    async def run_agent(self, prompt: str) -> None:
        # group="agent": exclusive only cancels a prior agent run — it must NOT
        # cancel in-flight direct commands or modal workers (different groups).
        log = self.query_one("#transcript", RichLog)
        log.write(f"[bold green]▸ you:[/] {prompt}")
        try:
            from app.config import settings
            from app.runtimes.ollama_runtime import OllamaRuntime
            from app.schemas import ChatMessage
            from app.skills.vm import SKILL_BODY
            from app.tools.vm_ops import build_vm_tools
        except Exception as e:  # noqa: BLE001
            log.write(f"[red]cannot import gateway runtime: {e}[/]")
            return

        state = {
            "vm_base_url": self.base_url,
            "default_backend": self.health.get("default_backend", ""),
            "session": "tui-agent",
        }
        tools = self._wrap_tools(build_vm_tools(state), log)
        runtime = OllamaRuntime(
            model=settings.default_model,
            system_prompt=SKILL_BODY,
            tools=tools,
            think=True,
        )
        try:
            result = await runtime.arun([ChatMessage(role="user", content=prompt)])
            log.write(f"[bold blue]◂ agent:[/] {result.text}")
        except Exception as e:  # noqa: BLE001
            log.write(f"[red]agent error: {e}  (is Ollama running?)[/]")

    def _wrap_tools(self, tools, log: RichLog):
        """Wrap each tool so its call + result stream into the transcript live
        — the point of the agent tab is watching the tool cycle."""
        wrapped = []
        for schema, fn in tools:
            name = schema["function"]["name"]

            def mk(fn, name):
                async def w(**kw):
                    shown = ", ".join(f"{k}={_short(v)}" for k, v in kw.items())
                    log.write(f"[cyan]  → {name}([/][dim]{shown}[/][cyan])[/]")
                    res = await fn(**kw)
                    log.write(f"[dim]    {_short(res, 300)}[/]")
                    return res
                return w

            wrapped.append((schema, mk(fn, name)))
        return wrapped


def _short(v, n: int = 80) -> str:
    s = str(v).replace("\n", " ⏎ ")
    return s if len(s) <= n else s[:n] + "…"


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--url", default=DEFAULT_URL, help="vmmd base URL")
    args = ap.parse_args()
    VmmTUI(args.url).run()


if __name__ == "__main__":
    main()
