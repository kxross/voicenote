"""Docker backend — a container per sandbox. Much simpler than firecracker:
no tap/kernel/disk, and exec/file transfer go through `docker exec`/`docker
cp` rather than SSH. Shares the host kernel (weaker isolation than a
microVM) but starts in well under a second and needs no privileged setup in
open mode. cgroup v2 makes --cpus/--memory real limits; per-container disk
limits aren't available on overlay2/extfs, so disk_gb is advisory.

Egress isolation (block sandbox -> RFC1918/tailnet/host, allow public internet)
is applied per-sandbox: the daemon runs a privileged sidecar that shares the
sandbox's network namespace and installs OUTPUT drops (vmm-egress-lockdown),
while the sandbox itself has no NET_ADMIN so the agent can't undo them. No host
iptables and no sudo, so it works on Docker Desktop and native Docker alike;
with docker.isolate_egress on, start() fails closed if the lockdown can't be
applied.
"""

from __future__ import annotations

import asyncio
import re

from .. import config as C
from ..util import SandboxError, run_io
from .base import Backend, BackendHandle, ExecResult, VMSpec


class DockerBackend(Backend):
    name = "docker"

    def __init__(self, cfg: C.Config):
        self.cfg = cfg
        self.d = cfg.docker

    def _container(self, vm_id: str) -> str:
        return f"{self.d.name_prefix}-{vm_id}"

    def _image(self, profile: str) -> str:
        return f"{self.d.image_prefix}:{profile}"

    def _workspace_volume(self, key: str) -> str | None:
        """Named volume that persists /workspace for a scope. Keyed by the
        stable scope key (NOT the ephemeral vm id), so the workspace survives
        container reap/recreate and daemon restarts. None for unkeyed one-off
        sandboxes, which keep an ephemeral in-container workspace."""
        if not key:
            return None
        slug = re.sub(r"[^a-zA-Z0-9_.-]", "-", key)[:200] or "default"
        return f"{self.d.name_prefix}-ws-{slug}"

    async def preflight(self) -> list[str]:
        rc, out, err = await run_io("docker", "version", "--format", "{{.Server.Version}}")
        if rc != 0:
            return [f"docker CLI not usable: {(err or out).decode(errors='replace').strip()[:200]}"]
        return []

    # ---- lifecycle --------------------------------------------------------

    async def start(self, spec: VMSpec) -> BackendHandle:
        image = self._image(spec.profile)
        rc, _, err = await run_io("docker", "image", "inspect", image)
        if rc != 0:
            raise SandboxError(
                f"image '{image}' not built — run: bash vmm/build_image.sh {spec.profile}"
            )
        name = self._container(spec.id)
        # Clean any stale container with this name (e.g. from a crash).
        await run_io("docker", "rm", "-f", name)

        args = [
            "docker", "run", "-d", "--name", name,
            "--hostname", spec.profile,
            "--cpus", str(spec.vcpus), "--memory", f"{spec.mem_mib}m",
            "--label", "vmm=1", "--label", f"vmm-id={spec.id}",
            "-w", "/workspace",
        ]
        # Persist /workspace across container reap/recreate: a named volume keyed
        # by the stable scope key, created on first use (idempotent). Without it,
        # /workspace lives only in the container layer and is lost when the box is
        # reaped or the daemon/host restarts. Unkeyed sandboxes stay ephemeral.
        ws_volume = self._workspace_volume(spec.key)
        if ws_volume:
            await self._ensure_volume(ws_volume)
            args += ["--label", f"vmm-ws={ws_volume}", "-v", f"{ws_volume}:/workspace"]
        # Egress isolation: attach to the dedicated sandbox network (the daemon
        # creates it; no sudo). The real blocking is applied per-sandbox below,
        # inside its netns — portable across Docker Desktop and native Docker.
        if self.d.isolate_egress:
            await self._ensure_network()
            args += ["--network", self.d.network]
        args += [image, "sleep", "infinity"]

        rc, out, err = await run_io(*args)
        if rc != 0:
            raise SandboxError(f"docker run failed: {(err or out).decode(errors='replace').strip()[:300]}")
        cid = out.decode(errors="replace").strip()[:12]
        handle: BackendHandle = {"id": spec.id, "container": name, "cid": cid, "ip": None}
        # Confirm it actually stayed up (image with a broken entrypoint, etc.)
        if not await self.is_alive(handle):
            logs = await self._logs(name)
            await self.destroy(handle)
            raise SandboxError(f"container exited immediately:\n{logs[:400]}")

        # Egress lockdown: a privileged sidecar shares this sandbox's network
        # namespace and installs OUTPUT drops for private ranges; the sandbox
        # itself has NO NET_ADMIN, so the agent can't undo them. Fail CLOSED —
        # tear the sandbox down if the lockdown can't be applied.
        if self.d.isolate_egress:
            rc, lout, lerr = await run_io(
                "docker", "run", "--rm",
                "--network", f"container:{name}",
                "--cap-add", "NET_ADMIN",
                image, "vmm-egress-lockdown",
            )
            if rc != 0:
                detail = (lerr or lout).decode(errors="replace").strip()[:300]
                await self.destroy(handle)
                raise SandboxError(f"egress lockdown failed, sandbox refused: {detail}")
        return handle

    # Dedicated sandbox network — the daemon creates it (no sudo). Isolation
    # comes from the per-sandbox netns lockdown, not host iptables, so a known
    # subnet isn't load-bearing; it just keeps sandboxes off other networks.
    _SUBNET = "10.200.7.0/24"
    _GATEWAY = "10.200.7.1"

    async def _ensure_network(self) -> None:
        rc, _, _ = await run_io("docker", "network", "inspect", self.d.network)
        if rc == 0:
            return
        rc, out, err = await run_io(
            "docker", "network", "create", "--driver", "bridge",
            "--subnet", self._SUBNET, "--gateway", self._GATEWAY,
            "--label", "vmm-isolated=1", self.d.network,
        )
        if rc != 0:
            # A concurrent create may have won the race; re-check before failing.
            rc2, _, _ = await run_io("docker", "network", "inspect", self.d.network)
            if rc2 != 0:
                raise SandboxError(
                    f"could not create sandbox network '{self.d.network}': "
                    f"{(err or out).decode(errors='replace').strip()[:200]}"
                )

    async def _ensure_volume(self, name: str) -> None:
        """Create the workspace volume if absent (idempotent). Never removed on
        destroy, so the workspace outlives the container."""
        rc, _, _ = await run_io("docker", "volume", "inspect", name)
        if rc == 0:
            return
        rc, out, err = await run_io("docker", "volume", "create", "--label", "vmm-ws=1", name)
        if rc != 0:
            # A concurrent create may have won the race; re-check before failing.
            rc2, _, _ = await run_io("docker", "volume", "inspect", name)
            if rc2 != 0:
                raise SandboxError(
                    f"could not create workspace volume '{name}': "
                    f"{(err or out).decode(errors='replace').strip()[:200]}"
                )

    async def is_alive(self, handle: BackendHandle) -> bool:
        name = handle.get("container")
        rc, out, _ = await run_io("docker", "inspect", "-f", "{{.State.Running}}", name)
        return rc == 0 and out.decode(errors="replace").strip() == "true"

    async def destroy(self, handle: BackendHandle) -> None:
        name = handle.get("container")
        if name:
            await run_io("docker", "rm", "-f", name)  # ignore errors — idempotent

    # ---- exec + files -----------------------------------------------------

    async def exec(self, handle: BackendHandle, command: str, timeout: int) -> ExecResult:
        name = handle["container"]
        # -l gives a login shell so PATH picks up node/npm/etc.
        proc = await asyncio.create_subprocess_exec(
            "docker", "exec", name, "bash", "-lc", command,
            stdout=asyncio.subprocess.PIPE, stderr=asyncio.subprocess.PIPE,
        )
        try:
            out, err = await asyncio.wait_for(proc.communicate(), timeout=timeout)
        except asyncio.TimeoutError:
            proc.kill()
            await proc.wait()
            return ExecResult(stdout="", stderr=f"[timed out after {timeout}s]",
                              exit_code=124, truncated=True)
        return ExecResult(
            stdout=out.decode(errors="replace"),
            stderr=err.decode(errors="replace"),
            exit_code=proc.returncode if proc.returncode is not None else -1,
        )

    async def put_file(self, handle: BackendHandle, dest_path: str, data: bytes) -> None:
        name = handle["container"]
        # ensure the parent dir exists, then stream bytes straight into the file
        import os
        parent = os.path.dirname(dest_path) or "/"
        await run_io("docker", "exec", name, "mkdir", "-p", parent)
        rc, _, err = await run_io(
            "docker", "exec", "-i", name, "sh", "-c", 'cat > "$1"', "--", dest_path,
            stdin=data,
        )
        if rc != 0:
            raise SandboxError(f"put_file failed: {err.decode(errors='replace')[:200]}")

    async def get_file(self, handle: BackendHandle, path: str) -> bytes:
        name = handle["container"]
        # `docker cp <c>:<path> -` emits a tar stream (file or dir) on stdout.
        rc, out, err = await run_io("docker", "cp", f"{name}:{path}", "-")
        if rc != 0:
            raise SandboxError(f"get_file failed: {err.decode(errors='replace')[:200]}")
        return out

    async def put_stream(self, handle: BackendHandle, dest_path: str, chunks) -> int:
        """Pipe chunks straight into `docker exec -i … cat`, written to a
        .part file and renamed on success, so a 300 MB video never sits in
        memory and a broken upload never leaves a truncated file in place."""
        import os
        name = handle["container"]
        parent = os.path.dirname(dest_path) or "/"
        await run_io("docker", "exec", name, "mkdir", "-p", parent)
        proc = await asyncio.create_subprocess_exec(
            "docker", "exec", "-i", name, "sh", "-c",
            'cat > "$1.part" && mv -f "$1.part" "$1"', "--", dest_path,
            stdin=asyncio.subprocess.PIPE,
            stdout=asyncio.subprocess.PIPE, stderr=asyncio.subprocess.PIPE,
        )
        total = 0
        try:
            async for chunk in chunks:
                if not chunk:
                    continue
                proc.stdin.write(chunk)
                await proc.stdin.drain()
                total += len(chunk)
        except BaseException:
            proc.kill()
            await proc.wait()
            await run_io("docker", "exec", name, "rm", "-f", f"{dest_path}.part")
            raise
        proc.stdin.close()
        _, err = await proc.communicate()
        if proc.returncode != 0:
            raise SandboxError(f"upload failed: {err.decode(errors='replace')[:200]}")
        return total

    async def get_raw(self, handle: BackendHandle, path: str, max_bytes: int) -> bytes:
        name = handle["container"]
        # head -c caps what a runaway path could return; one byte over = too big.
        rc, out, err = await run_io(
            "docker", "exec", name, "sh", "-c", 'test -f "$1" && head -c "$2" -- "$1"',
            "--", path, str(max_bytes + 1),
        )
        if rc != 0:
            raise SandboxError(f"no such file: {path}" if not err else err.decode(errors="replace")[:200])
        if len(out) > max_bytes:
            raise SandboxError(f"{path} is larger than {max_bytes} bytes")
        return out

    # ---- helpers ----------------------------------------------------------

    async def _logs(self, name: str) -> str:
        _, out, err = await run_io("docker", "logs", "--tail", "20", name)
        return (out + err).decode(errors="replace")
