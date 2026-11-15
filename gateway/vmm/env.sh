#!/usr/bin/env bash
# Shared build-time constants + paths for the vmm shell scripts (setup.sh,
# build_rootfs.sh, net-helper.sh, smoke.sh). The Python daemon reads
# config.yml for *runtime* tunables; this file is only "where things live"
# and "which versions", so the scripts agree without duplicating strings.
#
# Source it: `source "$(dirname "$0")/env.sh"`

set -euo pipefail

# Resolve the vmm/ directory regardless of caller's cwd.
VMM_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

BIN_DIR="$VMM_DIR/bin"          # firecracker binary
IMAGES_DIR="$VMM_DIR/images"    # guest kernel + per-profile rootfs .ext4
KEYS_DIR="$VMM_DIR/keys"        # vmmd's ssh keypair (guest access)
STATE_DIR="$VMM_DIR/state"      # per-VM runtime: sockets, overlay disks, exports
PROFILES_DIR="$VMM_DIR/profiles"

FIRECRACKER="$BIN_DIR/firecracker"
KERNEL="$IMAGES_DIR/vmlinux"
SSH_KEY="$KEYS_DIR/id_ed25519"
SSH_PUBKEY="$KEYS_DIR/id_ed25519.pub"

# --- pinned versions --------------------------------------------------------
FC_VERSION="v1.16.1"
FC_ARCH="x86_64"
FC_URL="https://github.com/firecracker-microvm/firecracker/releases/download/${FC_VERSION}/firecracker-${FC_VERSION}-${FC_ARCH}.tgz"
# Firecracker-CI published guest kernel (6.1, config known-good for microVMs).
KERNEL_URL="https://s3.amazonaws.com/spec.ccfc.min/firecracker-ci/v1.12/${FC_ARCH}/vmlinux-6.1.128"

# --- networking -------------------------------------------------------------
# Each VM index N gets the /30 172.30.N.0/30: host tap = .1, guest = .2.
# Keep in sync with vmmd/manager.py and net-helper.sh.
NET_PREFIX="172.30"
TAP_PREFIX="fc-tap"

# The Linux user vmmd/smoke run as (owns the tap devices). net-helper grants
# tap ownership to this user so an unprivileged daemon can drive the VM.
VMM_USER="${SUDO_USER:-$(id -un)}"

# --- shared helpers ---------------------------------------------------------

# Generate the guest-access keypair once; both setup.sh and build_rootfs.sh
# depend on it (the public half is baked into every rootfs). Sudoless.
ensure_keys() {
  mkdir -p "$KEYS_DIR"
  chmod 700 "$KEYS_DIR"
  if [[ ! -f "$SSH_KEY" ]]; then
    ssh-keygen -t ed25519 -N "" -C "vmmd-guest-access" -f "$SSH_KEY" >/dev/null
    echo "[env] generated guest-access keypair at $SSH_KEY"
  fi
}

# guest IP for a VM index
guest_ip()  { echo "${NET_PREFIX}.${1}.2"; }
host_ip()   { echo "${NET_PREFIX}.${1}.1"; }
tap_name()  { echo "${TAP_PREFIX}${1}"; }
