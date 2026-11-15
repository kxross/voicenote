"""Shared helpers for the manager and backends: subprocess running, process
liveness, file removal, and the common error type."""

from __future__ import annotations

import asyncio
import os
import signal
import time


class SandboxError(RuntimeError):
    """Any recoverable sandbox failure. Surfaced to the API as a 4xx and to
    the model as a readable tool error — never a 500."""


async def run(*args: str, what: str, ok_codes: tuple[int, ...] = (0,)) -> str:
    """Run a command to completion, capturing stdout+stderr. Raises
    SandboxError on an unexpected exit code."""
    proc = await asyncio.create_subprocess_exec(
        *args, stdout=asyncio.subprocess.PIPE, stderr=asyncio.subprocess.STDOUT,
    )
    out, _ = await proc.communicate()
    text = out.decode(errors="replace")
    if proc.returncode not in ok_codes:
        raise SandboxError(f"{what} failed (rc={proc.returncode}): {text.strip()[:400]}")
    return text


async def run_io(*args: str, stdin: bytes | None = None) -> tuple[int, bytes, bytes]:
    """Run a command, feeding stdin and returning (rc, stdout, stderr) as raw
    bytes — for binary transfers like `docker cp -`."""
    proc = await asyncio.create_subprocess_exec(
        *args,
        stdin=asyncio.subprocess.PIPE if stdin is not None else None,
        stdout=asyncio.subprocess.PIPE,
        stderr=asyncio.subprocess.PIPE,
    )
    out, err = await proc.communicate(input=stdin)
    return proc.returncode or 0, out, err


def proc_exists(pid: int) -> bool:
    return os.path.exists(f"/proc/{pid}")


def pid_alive(pid: int | None) -> bool:
    # sandbox processes run as our own user, so /proc presence is authoritative.
    return bool(pid) and proc_exists(pid)


def kill_pid(pid: int) -> None:
    for sig in (signal.SIGTERM, signal.SIGKILL):
        try:
            os.kill(pid, sig)
        except ProcessLookupError:
            return
        time.sleep(0.3)
        if not proc_exists(pid):
            return


def rm(path) -> None:
    try:
        os.remove(path)
    except FileNotFoundError:
        pass
