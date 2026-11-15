"""vmmd — the Firecracker microVM manager daemon.

Runs on the WSL host (not in the gateway container) because it needs
/dev/kvm, tap devices, and iptables. Exposes an HTTP API the gateway's
vm_ops tools (and the TUI) drive. See vmm/README.md.
"""
