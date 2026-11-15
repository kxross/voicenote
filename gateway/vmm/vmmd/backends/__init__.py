"""Sandbox backends. Each implements the same `Backend` interface so the
daemon's Manager (routes, approval gate, reaper, limits, state) is written
once and works over any of them. A single daemon can hold several at once,
gated by each backend's preflight.
"""

from __future__ import annotations


def build_all(cfg) -> list:
    """Instantiate every known backend (unfiltered). main.py runs preflight
    on each and keeps only the ready ones."""
    from .docker import DockerBackend
    from .firecracker import FirecrackerBackend
    return [DockerBackend(cfg), FirecrackerBackend(cfg)]
