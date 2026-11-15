"""The backend seam. Everything the daemon needs from a sandbox
implementation, and nothing about *how* it's implemented — a Firecracker
microVM and a Docker container both satisfy this, so the Manager code
above it is identical for either.

A `BackendHandle` is whatever the backend needs to find the sandbox again
later (firecracker: pid/idx/ip/sock; docker: container name). It's a plain
JSON-able dict so it round-trips through the daemon's state/vms.json.
"""

from __future__ import annotations

from abc import ABC, abstractmethod
from dataclasses import dataclass, field


@dataclass
class VMSpec:
    """A resource/profile request — the backend-neutral inputs to `start`."""
    id: str                     # daemon-assigned short id (also names the sandbox)
    idx: int                    # pool index (firecracker uses it for the /30; docker ignores)
    profile: str
    vcpus: int
    mem_mib: int
    disk_gb: int
    # Stable scope key (e.g. "shared" or a session id). Unlike `id` (a fresh
    # uuid each create), this is constant for a scope across reap/recreate, so a
    # backend can persist the workspace against it. Empty = a one-off, unkeyed
    # sandbox whose workspace need not survive the container.
    key: str = ""


@dataclass
class ExecResult:
    stdout: str
    exit_code: int
    stderr: str = ""
    truncated: bool = False

    def as_dict(self) -> dict:
        return {
            "stdout": self.stdout,
            "stderr": self.stderr,
            "exit_code": self.exit_code,
            "truncated": self.truncated,
        }


# A backend handle is just a JSON-serializable dict; aliased for readability.
BackendHandle = dict


class Backend(ABC):
    """One sandbox implementation. Instances are constructed once at daemon
    startup and shared across requests, so methods must be safe to call
    concurrently for *different* handles (the Manager serialises per-VM work
    where needed)."""

    #: short stable name used in config, VMRecord.backend, and the API
    name: str = "base"

    @abstractmethod
    async def preflight(self) -> list[str]:
        """Human-readable missing prerequisites; empty list == ready. The
        Manager only enables backends whose preflight passes."""

    @abstractmethod
    async def start(self, spec: VMSpec) -> BackendHandle:
        """Create and boot the sandbox; return a handle addressing it. Must
        clean up after itself on failure (raise, leave nothing running)."""

    @abstractmethod
    async def exec(self, handle: BackendHandle, command: str, timeout: int) -> ExecResult:
        """Run a bash command inside the sandbox."""

    @abstractmethod
    async def put_file(self, handle: BackendHandle, dest_path: str, data: bytes) -> None:
        """Write `data` to `dest_path` inside the sandbox."""

    @abstractmethod
    async def get_file(self, handle: BackendHandle, path: str) -> bytes:
        """Read a file (or a directory, returned as a tar stream) out of the
        sandbox."""

    async def put_stream(self, handle: BackendHandle, dest_path: str, chunks) -> int:
        """Stream an async iterator of byte chunks into `dest_path` without
        holding the whole file in memory (videos). Returns bytes written.
        Optional: backends that can't stream raise SandboxError."""
        from ..util import SandboxError
        raise SandboxError(f"streamed upload isn't supported by the {self.name} backend")

    async def get_raw(self, handle: BackendHandle, path: str, max_bytes: int) -> bytes:
        """One file's raw bytes (not a tar) — e.g. an extracted frame.
        Optional: backends that can't do it raise SandboxError."""
        from ..util import SandboxError
        raise SandboxError(f"raw file reads aren't supported by the {self.name} backend")

    @abstractmethod
    async def is_alive(self, handle: BackendHandle) -> bool:
        """True if the sandbox is still running — used by reconcile()."""

    @abstractmethod
    async def destroy(self, handle: BackendHandle) -> None:
        """Stop and remove the sandbox and any per-VM host resources. Must be
        idempotent and best-effort (called during failed-start cleanup too)."""

    def guest_ip(self, handle: BackendHandle) -> str | None:
        """Optional: an address the daemon/TUI can show. Docker returns None
        (files/exec go through the CLI, not an IP)."""
        return handle.get("ip")
