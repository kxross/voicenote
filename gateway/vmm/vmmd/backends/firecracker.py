"""Firecracker backend — the original vmm lifecycle, now behind the Backend
interface. Boots exactly the way smoke.sh proved out: net-helper brings up a
tap + NAT, a sparse rootfs copy becomes the disk, firecracker boots it with
an `ip=` kernel arg, and we wait for SSH. exec/file transfer go over SSH
(asyncssh, imported lazily so the daemon runs without it until firecracker
is actually used)."""

from __future__ import annotations

import asyncio
import json
import time

from .. import config as C
from ..util import SandboxError, kill_pid, pid_alive, rm, run
from .base import Backend, BackendHandle, ExecResult, VMSpec


class FirecrackerBackend(Backend):
    name = "firecracker"

    def __init__(self, cfg: C.Config):
        self.cfg = cfg
        self._procs: dict[str, asyncio.subprocess.Process] = {}

    async def preflight(self) -> list[str]:
        import os
        problems = []
        if not C.FIRECRACKER.exists():
            problems.append(f"firecracker missing ({C.FIRECRACKER}) — run vmm/setup.sh")
        if not C.KERNEL.exists():
            problems.append(f"guest kernel missing ({C.KERNEL}) — run vmm/setup.sh")
        if not C.SSH_KEY.exists():
            problems.append(f"guest ssh key missing ({C.SSH_KEY}) — run vmm/setup.sh")
        if not os.access("/dev/kvm", os.R_OK | os.W_OK):
            problems.append("/dev/kvm not accessible — add user to kvm group + restart WSL")
        return problems

    # ---- lifecycle --------------------------------------------------------

    async def start(self, spec: VMSpec) -> BackendHandle:
        rootfs = C.IMAGES_DIR / f"{spec.profile}.ext4"
        if not rootfs.exists():
            raise SandboxError(
                f"profile '{spec.profile}' not built — run: bash vmm/build_rootfs.sh {spec.profile}"
            )
        ip = C.guest_ip(spec.idx)
        handle: BackendHandle = {
            "id": spec.id, "idx": spec.idx, "ip": ip,
            "sock": str(C.STATE_DIR / f"{spec.id}.sock"), "pid": None,
        }
        try:
            await self._net_up(spec.idx)
            await self._make_disk(spec.id, rootfs, spec.disk_gb)
            handle["pid"] = await self._launch(spec, ip)
            await self._wait_ssh(spec.id, ip, timeout=45)
            return handle
        except Exception:
            await self.destroy(handle)
            raise

    async def is_alive(self, handle: BackendHandle) -> bool:
        return pid_alive(handle.get("pid"))

    async def destroy(self, handle: BackendHandle) -> None:
        vm_id = handle.get("id")
        idx = handle.get("idx")
        proc = self._procs.pop(vm_id, None)
        if proc and proc.returncode is None:
            try:
                proc.terminate()
                await asyncio.wait_for(proc.wait(), timeout=5)
            except (asyncio.TimeoutError, ProcessLookupError):
                try:
                    proc.kill()
                except ProcessLookupError:
                    pass
        elif pid_alive(handle.get("pid")):
            kill_pid(handle["pid"])  # adopted after a daemon restart
        if idx is not None:
            await self._net_down(idx)
        rm(C.STATE_DIR / f"{vm_id}.ext4")
        rm(C.STATE_DIR / f"{vm_id}.sock")
        rm(C.STATE_DIR / f"{vm_id}.json")

    # ---- exec + files over SSH (lazy asyncssh) ----------------------------

    async def _conn(self, handle: BackendHandle):
        try:
            import asyncssh
        except ImportError as e:  # pragma: no cover
            raise SandboxError(
                "asyncssh not installed — `pip install asyncssh` to use the firecracker backend"
            ) from e
        return await asyncssh.connect(
            handle["ip"], username="root", client_keys=[str(C.SSH_KEY)],
            known_hosts=None, connect_timeout=10,
        )

    async def exec(self, handle: BackendHandle, command: str, timeout: int) -> ExecResult:
        conn = await self._conn(handle)
        try:
            res = await conn.run(command, timeout=timeout, check=False)
            return ExecResult(
                stdout=res.stdout or "", stderr=res.stderr or "",
                exit_code=res.exit_status if res.exit_status is not None else -1,
            )
        finally:
            conn.close()

    async def put_file(self, handle: BackendHandle, dest_path: str, data: bytes) -> None:
        conn = await self._conn(handle)
        try:
            async with conn.start_sftp_client() as sftp:
                async with sftp.open(dest_path, "wb") as f:
                    await f.write(data)
        finally:
            conn.close()

    async def get_file(self, handle: BackendHandle, path: str) -> bytes:
        # tar so a file or a whole directory comes back the same way
        res = await self.exec(handle, f"tar -C / -cf - '{path.lstrip('/')}'", timeout=120)
        if res.exit_code != 0:
            raise SandboxError(f"get_file failed: {res.stderr[:200]}")
        return res.stdout.encode("latin-1", errors="ignore")

    # ---- building blocks (moved verbatim from the old Manager) -------------

    async def _net_up(self, idx: int) -> None:
        await run("sudo", str(C.NET_HELPER), "up", str(idx), what="net-helper up")

    async def _net_down(self, idx: int) -> None:
        try:
            await run("sudo", str(C.NET_HELPER), "down", str(idx), what="net-helper down")
        except SandboxError:
            pass  # teardown is best-effort

    async def _make_disk(self, vm_id: str, rootfs, disk_gb: int) -> None:
        disk = C.STATE_DIR / f"{vm_id}.ext4"
        await run("cp", "--sparse=always", str(rootfs), str(disk), what="disk copy")
        want = disk_gb * 1024 * 1024 * 1024
        if disk.stat().st_size < want:
            await run("truncate", "-s", f"{disk_gb}G", str(disk), what="disk grow")
            await run("e2fsck", "-fy", str(disk), what="e2fsck", ok_codes=(0, 1))
            await run("resize2fs", str(disk), what="resize2fs")

    async def _launch(self, spec: VMSpec, ip: str) -> int:
        disk = C.STATE_DIR / f"{spec.id}.ext4"
        sock = C.STATE_DIR / f"{spec.id}.sock"
        cfg = C.STATE_DIR / f"{spec.id}.json"
        log = C.STATE_DIR / f"{spec.id}.log"
        rm(sock)
        boot_args = (
            "console=ttyS0 reboot=k panic=1 pci=off net.ifnames=0 random.trust_cpu=on "
            f"ip={ip}::{C.host_ip(spec.idx)}:255.255.255.252::eth0:off root=/dev/vda rw"
        )
        cfg.write_text(json.dumps({
            "boot-source": {"kernel_image_path": str(C.KERNEL), "boot_args": boot_args},
            "drives": [{"drive_id": "rootfs", "path_on_host": str(disk),
                        "is_root_device": True, "is_read_only": False}],
            "machine-config": {"vcpu_count": spec.vcpus, "mem_size_mib": spec.mem_mib},
            "network-interfaces": [{"iface_id": "eth0",
                                    "host_dev_name": C.tap_name(spec.idx),
                                    "guest_mac": C.guest_mac(spec.idx)}],
        }))
        logf = open(log, "wb")
        # start_new_session detaches firecracker so a daemon restart doesn't
        # kill running VMs — reconcile() re-adopts them by pid.
        proc = await asyncio.create_subprocess_exec(
            str(C.FIRECRACKER), "--api-sock", str(sock), "--config-file", str(cfg),
            stdout=logf, stderr=logf, stdin=asyncio.subprocess.DEVNULL,
            start_new_session=True,
        )
        self._procs[spec.id] = proc
        return proc.pid

    async def _wait_ssh(self, vm_id: str, ip: str, timeout: int) -> None:
        deadline = time.time() + timeout
        while time.time() < deadline:
            proc = self._procs.get(vm_id)
            if proc and proc.returncode is not None:
                log = C.STATE_DIR / f"{vm_id}.log"
                tail = log.read_text(errors="replace")[-800:] if log.exists() else ""
                raise SandboxError(f"firecracker exited early (rc={proc.returncode}):\n{tail}")
            try:
                fut = asyncio.open_connection(ip, 22)
                _, writer = await asyncio.wait_for(fut, timeout=2)
                writer.close()
                try:
                    await writer.wait_closed()
                except Exception:  # noqa: BLE001
                    pass
                return
            except (OSError, asyncio.TimeoutError):
                await asyncio.sleep(1)
        raise SandboxError(f"VM {vm_id} did not open ssh within {timeout}s")
