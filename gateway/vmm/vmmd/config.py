"""Daemon config: runtime knobs from config.yml + filesystem paths that
mirror env.sh (single source of truth for "where things live" is env.sh;
this re-derives the same layout from the vmm/ directory so the Python side
doesn't shell out to read it)."""

from __future__ import annotations

import os
from dataclasses import dataclass, field
from pathlib import Path

import yaml

VMM_DIR = Path(__file__).resolve().parent.parent  # .../vmm
BIN_DIR = VMM_DIR / "bin"
IMAGES_DIR = VMM_DIR / "images"
KEYS_DIR = VMM_DIR / "keys"
STATE_DIR = VMM_DIR / "state"
PROFILES_DIR = VMM_DIR / "profiles"

FIRECRACKER = BIN_DIR / "firecracker"
KERNEL = IMAGES_DIR / "vmlinux"
SSH_KEY = KEYS_DIR / "id_ed25519"
NET_HELPER = VMM_DIR / "net-helper.sh"

NET_PREFIX = "172.30"
TAP_PREFIX = "fc-tap"


def guest_ip(idx: int) -> str:
    return f"{NET_PREFIX}.{idx}.2"


def host_ip(idx: int) -> str:
    return f"{NET_PREFIX}.{idx}.1"


def tap_name(idx: int) -> str:
    return f"{TAP_PREFIX}{idx}"


def guest_mac(idx: int) -> str:
    # Locally-administered (0x02 bit set), unique per index.
    return f"AA:FC:00:00:{(idx >> 8) & 0xFF:02X}:{idx & 0xFF:02X}"


@dataclass
class Limits:
    max_vms: int = 8
    max_total_mem_mib: int = 24576
    max_vcpus_per_vm: int = 8
    max_mem_mib_per_vm: int = 8192
    exec_timeout_default: int = 120
    exec_timeout_max: int = 900
    approval_timeout: int = 300     # manual mode: auto-deny if unanswered this long


@dataclass
class Defaults:
    profile: str = "base"
    vcpus: int = 2
    mem_mib: int = 2048
    disk_gb: int = 8
    ttl_minutes: int = 30


@dataclass
class DockerCfg:
    image_prefix: str = "vmm-sandbox"      # image = <prefix>:<profile>
    network: str = "vmm-sandbox-net"       # dedicated bridge for isolation
    isolate_egress: bool = True            # block sandbox -> RFC1918/tailnet
    name_prefix: str = "vmm"               # container = <name_prefix>-<id>


APPROVAL_MODES = ("auto", "smart", "manual")


@dataclass
class ReviewCfg:
    """Smart-mode screening (see vmmd/review.py). The reviewer is the
    agent-gateway's System One endpoint; empty url = every command is held
    (fail closed)."""
    url: str = ""
    api_key: str = ""
    timeout: float = 30.0
    malicious_threshold: float = 0.5       # hold if P(malicious) >= this
    flag_verdicts: tuple[str, ...] = ("suspicious", "malicious")

    @classmethod
    def from_env(cls) -> "ReviewCfg":
        return cls(
            url=os.environ.get("VMM_REVIEW_URL", ""),
            api_key=os.environ.get("VMM_REVIEW_API_KEY", ""),
            timeout=float(os.environ.get("VMM_REVIEW_TIMEOUT", "30")),
            malicious_threshold=float(os.environ.get("VMM_REVIEW_MALICIOUS_THRESHOLD", "0.5")),
        )


@dataclass
class Config:
    defaults: Defaults = field(default_factory=Defaults)
    limits: Limits = field(default_factory=Limits)
    docker: DockerCfg = field(default_factory=DockerCfg)
    review: ReviewCfg = field(default_factory=ReviewCfg)
    # "auto" runs everything; "smart" screens each command with System One and
    # holds only flagged ones; "manual" holds every command.
    approval_mode: str = "manual"
    default_backend: str = "docker"        # what the gateway gets unless it names one
    index_min: int = 0
    index_max: int = 200
    reserved: tuple[int, ...] = (99,)
    host: str = "0.0.0.0"
    port: int = 5002

    @classmethod
    def load(cls, path: Path | None = None) -> "Config":
        path = path or (VMM_DIR / "config.yml")
        raw = yaml.safe_load(path.read_text()) if path.exists() else {}
        d = raw.get("defaults", {})
        lim = raw.get("limits", {})
        net = raw.get("network", {})
        srv = raw.get("server", {})
        dock = raw.get("docker", {})
        return cls(
            defaults=Defaults(**{k: d[k] for k in d if k in Defaults.__annotations__}),
            limits=Limits(**{k: lim[k] for k in lim if k in Limits.__annotations__}),
            docker=DockerCfg(**{k: dock[k] for k in dock if k in DockerCfg.__annotations__}),
            review=ReviewCfg.from_env(),
            # Env wins so the gateway stack's .env (VMM_APPROVAL_MODE) is the
            # single config entry; then config.yml; then manual.
            approval_mode=os.environ.get("VMM_APPROVAL_MODE") or raw.get("approval_mode", "manual"),
            default_backend=raw.get("default_backend", "docker"),
            index_min=net.get("index_min", 0),
            index_max=net.get("index_max", 200),
            reserved=tuple(net.get("reserved", [99])),
            host=srv.get("host", "0.0.0.0"),
            port=int(os.environ.get("VMMD_PORT", srv.get("port", 5002))),
        )
