"""Backend-agnostic VM lifecycle. The Manager owns everything that's the
same regardless of *how* a sandbox is realized — id/index allocation,
resource-limit guards, state/vms.json persistence + reconciliation, TTL
bookkeeping, and the exec/file passthrough the API routes call — and
dispatches the actual sandbox primitives to a pluggable Backend.

A single Manager can hold several backends at once (e.g. firecracker and
docker), each gated by its own preflight; every VMRecord remembers which
backend made it, so reconcile/destroy/exec dispatch correctly.
"""

from __future__ import annotations

import asyncio
import json
import time
import uuid
from dataclasses import asdict, dataclass, field

from . import config as C
from .backends.base import Backend, ExecResult, VMSpec
from .util import SandboxError

# Kept as the API-facing alias so existing callers (main.py) are unaffected.
VMError = SandboxError


@dataclass
class VMRecord:
    id: str
    idx: int
    profile: str
    backend: str
    vcpus: int
    mem_mib: int
    disk_gb: int
    ttl_minutes: int
    state: str = "starting"        # starting|running|error|stopping|dead
    ip: str = ""
    handle: dict = field(default_factory=dict)   # backend-private addressing
    created_at: float = field(default_factory=time.time)
    last_activity: float = field(default_factory=time.time)
    error: str | None = None
    # Optional caller-chosen scope key. When set, create() is idempotent on it
    # (ensure-or-reuse) — the gateway uses one key ("shared") for a single
    # machine shared by every turn, and later a per-conversation key, without
    # any daemon change.
    key: str = ""

    def public(self) -> dict:
        # handle is backend-internal; don't leak it to API/TUI clients.
        return {
            "id": self.id, "idx": self.idx, "profile": self.profile,
            "backend": self.backend, "vcpus": self.vcpus, "mem_mib": self.mem_mib,
            "disk_gb": self.disk_gb, "ttl_minutes": self.ttl_minutes,
            "state": self.state, "ip": self.ip, "error": self.error,
            "key": self.key, "created_at": self.created_at,
            "idle_seconds": int(time.time() - self.last_activity),
        }


class Manager:
    def __init__(self, cfg: C.Config, backends: dict[str, Backend], default_backend: str):
        self.cfg = cfg
        self.backends = backends
        self.default_backend = default_backend
        self.vms: dict[str, VMRecord] = {}
        self._lock = asyncio.Lock()
        C.STATE_DIR.mkdir(parents=True, exist_ok=True)
        self._state_file = C.STATE_DIR / "vms.json"

    # ---- persistence + reconciliation ------------------------------------

    def _save(self) -> None:
        self._state_file.write_text(json.dumps([asdict(r) for r in self.vms.values()], indent=2))

    async def reconcile(self) -> None:
        """On startup: adopt sandboxes still alive, clean up dead ones. Each
        record names its backend, so we dispatch is_alive/destroy correctly
        even in a mixed firecracker+docker daemon."""
        if not self._state_file.exists():
            return
        try:
            data = json.loads(self._state_file.read_text())
        except (ValueError, OSError):
            return
        for d in data:
            rec = VMRecord(**{k: d[k] for k in d if k in VMRecord.__annotations__})
            backend = self.backends.get(rec.backend)
            alive = bool(backend) and await backend.is_alive(rec.handle)
            if alive:
                rec.state = "running"
                self.vms[rec.id] = rec
            elif backend is not None:
                try:
                    await backend.destroy(rec.handle)  # best-effort residue cleanup
                except Exception:  # noqa: BLE001
                    pass
        self._save()

    # ---- allocation -------------------------------------------------------

    def _alloc_index(self) -> int:
        used = {r.idx for r in self.vms.values()}
        for idx in range(self.cfg.index_min, self.cfg.index_max + 1):
            if idx in self.cfg.reserved or idx in used:
                continue
            return idx
        raise VMError("no free VM index in the configured pool")

    # ---- lifecycle --------------------------------------------------------

    def _find_by_key(self, key: str) -> VMRecord | None:
        if not key:
            return None
        for r in self.vms.values():
            if r.key == key and r.state in ("starting", "running"):
                return r
        return None

    async def create(
        self,
        *,
        backend: str | None = None,
        profile: str | None = None,
        vcpus: int | None = None,
        mem_mib: int | None = None,
        disk_gb: int | None = None,
        ttl_minutes: int | None = None,
        key: str = "",
    ) -> VMRecord:
        d = self.cfg.defaults
        backend_name = backend or self.default_backend
        be = self.backends.get(backend_name)
        if be is None:
            avail = ", ".join(self.backends) or "(none ready)"
            raise VMError(f"backend '{backend_name}' not available. Ready: {avail}")

        profile = profile or d.profile
        vcpus = min(vcpus or d.vcpus, self.cfg.limits.max_vcpus_per_vm)
        mem_mib = min(mem_mib or d.mem_mib, self.cfg.limits.max_mem_mib_per_vm)
        disk_gb = disk_gb or d.disk_gb
        ttl_minutes = ttl_minutes if ttl_minutes is not None else d.ttl_minutes

        async with self._lock:
            # Ensure-by-key: reuse the live sandbox for this scope if one exists
            # (what makes "one shared machine" and, later, per-conversation work).
            existing = self._find_by_key(key)
            if existing is not None:
                return existing
            if len(self.vms) >= self.cfg.limits.max_vms:
                raise VMError(f"VM limit reached ({self.cfg.limits.max_vms})")
            if sum(r.mem_mib for r in self.vms.values()) + mem_mib > self.cfg.limits.max_total_mem_mib:
                raise VMError("would exceed max_total_mem_mib across live VMs")
            idx = self._alloc_index()
            vm_id = uuid.uuid4().hex[:10]
            rec = VMRecord(
                id=vm_id, idx=idx, profile=profile, backend=backend_name,
                vcpus=vcpus, mem_mib=mem_mib, disk_gb=disk_gb, ttl_minutes=ttl_minutes,
                key=key,
            )
            self.vms[vm_id] = rec
            self._save()

        try:
            spec = VMSpec(id=vm_id, idx=idx, profile=profile,
                          vcpus=vcpus, mem_mib=mem_mib, disk_gb=disk_gb, key=key)
            rec.handle = await be.start(spec)
            rec.ip = be.guest_ip(rec.handle) or ""
            rec.state = "running"
            rec.last_activity = time.time()
            self._save()
            return rec
        except Exception as e:  # noqa: BLE001 — failed start must clean up
            rec.state = "error"
            rec.error = str(e)
            self._save()
            self.vms.pop(vm_id, None)
            self._save()
            raise

    async def destroy(self, vm_id: str) -> None:
        rec = self.vms.get(vm_id)
        if rec is None:
            raise VMError(f"no such vm: {vm_id}")
        rec.state = "stopping"
        self._save()
        be = self.backends.get(rec.backend)
        if be is not None:
            try:
                await be.destroy(rec.handle)
            except Exception:  # noqa: BLE001 — always drop the record
                pass
        self.vms.pop(vm_id, None)
        self._save()

    # ---- exec / files passthrough (used by the API routes) ----------------

    async def exec(self, vm_id: str, command: str, timeout: int) -> ExecResult:
        rec, be = self._resolve(vm_id)
        rec.last_activity = time.time()
        return await be.exec(rec.handle, command, timeout)

    async def put_file(self, vm_id: str, dest_path: str, data: bytes) -> None:
        rec, be = self._resolve(vm_id)
        rec.last_activity = time.time()
        await be.put_file(rec.handle, dest_path, data)

    async def get_file(self, vm_id: str, path: str) -> bytes:
        rec, be = self._resolve(vm_id)
        rec.last_activity = time.time()
        return await be.get_file(rec.handle, path)

    async def put_stream(self, vm_id: str, dest_path: str, chunks) -> int:
        rec, be = self._resolve(vm_id)
        rec.last_activity = time.time()
        return await be.put_stream(rec.handle, dest_path, chunks)

    async def get_raw(self, vm_id: str, path: str, max_bytes: int) -> bytes:
        rec, be = self._resolve(vm_id)
        rec.last_activity = time.time()
        return await be.get_raw(rec.handle, path, max_bytes)

    def _resolve(self, vm_id: str) -> tuple[VMRecord, Backend]:
        rec = self.vms.get(vm_id)
        if rec is None:
            raise VMError(f"no such vm: {vm_id}")
        be = self.backends.get(rec.backend)
        if be is None:
            raise VMError(f"backend '{rec.backend}' for vm {vm_id} is not loaded")
        return rec, be

    # ---- queries ----------------------------------------------------------

    def get(self, vm_id: str) -> VMRecord | None:
        return self.vms.get(vm_id)

    def list(self) -> list[VMRecord]:
        return list(self.vms.values())

    def touch(self, vm_id: str) -> None:
        rec = self.vms.get(vm_id)
        if rec:
            rec.last_activity = time.time()

    async def shutdown(self) -> None:
        # Detach: leave sandboxes running across daemon restarts; reconcile()
        # re-adopts them. Just persist current state.
        self._save()
