#!/usr/bin/env bash
# One-time host setup for the Firecracker VM manager. Run once:
#
#     sudo bash vmm/setup.sh
#
# Idempotent — safe to re-run. Downloads happen unprivileged; sudo is used
# only for the two genuinely privileged, persistent changes: adding you to
# the `kvm` group and installing a narrow NOPASSWD sudoers rule so the
# unprivileged daemon can create tap devices via net-helper.sh (and nothing
# else). After this you must restart WSL once for the group to take effect.

set -euo pipefail
source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/env.sh"

# Re-exec note: env.sh sets VMM_USER from SUDO_USER when run under sudo, so
# downloads/keys land owned by you, not root.
if [[ "${VMM_USER}" == "root" ]]; then
  echo "Run with sudo as your normal user (got VMM_USER=root). Try: sudo bash vmm/setup.sh" >&2
  exit 1
fi

run_as_user() { sudo -u "$VMM_USER" "$@"; }

echo "==> vmm setup (user: $VMM_USER)"
mkdir -p "$BIN_DIR" "$IMAGES_DIR" "$STATE_DIR"
chown "$VMM_USER":"$VMM_USER" "$BIN_DIR" "$IMAGES_DIR" "$STATE_DIR"

# --- 1. guest-access keypair (unprivileged) --------------------------------
run_as_user bash -c "source '$VMM_DIR/env.sh'; ensure_keys"

# --- 2. firecracker binary (unprivileged) ----------------------------------
if [[ -x "$FIRECRACKER" ]]; then
  echo "==> firecracker present: $("$FIRECRACKER" --version | head -1)"
else
  echo "==> downloading firecracker $FC_VERSION"
  tmp="$(mktemp -d)"
  run_as_user curl -fSL "$FC_URL" -o "$tmp/fc.tgz"
  run_as_user tar -xzf "$tmp/fc.tgz" -C "$tmp"
  # archive lays out release-<ver>-<arch>/firecracker-<ver>-<arch>
  found="$(find "$tmp" -name "firecracker-${FC_VERSION}-${FC_ARCH}" -type f | head -1)"
  install -o "$VMM_USER" -g "$VMM_USER" -m 0755 "$found" "$FIRECRACKER"
  rm -rf "$tmp"
  echo "==> installed $("$FIRECRACKER" --version | head -1)"
fi

# --- 3. guest kernel (unprivileged) ----------------------------------------
if [[ -f "$KERNEL" ]]; then
  echo "==> kernel present: $KERNEL ($(du -h "$KERNEL" | cut -f1))"
else
  echo "==> downloading guest kernel"
  run_as_user curl -fSL "$KERNEL_URL" -o "$KERNEL"
  echo "==> kernel at $KERNEL ($(du -h "$KERNEL" | cut -f1))"
fi

# --- 4. kvm group (privileged, persistent) ---------------------------------
if id -nG "$VMM_USER" | tr ' ' '\n' | grep -qx kvm; then
  echo "==> $VMM_USER already in kvm group"
else
  echo "==> adding $VMM_USER to kvm group (needs a WSL restart to apply)"
  usermod -aG kvm "$VMM_USER"
fi

# --- 5. NOPASSWD sudoers rule for net-helper only (privileged) -------------
# The daemon runs unprivileged; only tap/iptables setup needs root, and only
# through this one script whose args it strictly validates.
SUDOERS_FILE="/etc/sudoers.d/vmm-net"
SUDOERS_LINE="$VMM_USER ALL=(root) NOPASSWD: $VMM_DIR/net-helper.sh"
if [[ -f "$SUDOERS_FILE" ]] && grep -qF "$SUDOERS_LINE" "$SUDOERS_FILE"; then
  echo "==> sudoers rule already installed"
else
  echo "$SUDOERS_LINE" > "$SUDOERS_FILE"
  chmod 0440 "$SUDOERS_FILE"
  # Validate before it can wedge sudo; remove if malformed.
  if ! visudo -cf "$SUDOERS_FILE" >/dev/null; then
    rm -f "$SUDOERS_FILE"
    echo "ERROR: generated sudoers rule failed validation; removed." >&2
    exit 1
  fi
  echo "==> installed $SUDOERS_FILE"
fi
chmod 0755 "$VMM_DIR/net-helper.sh" 2>/dev/null || true

cat <<EOF

==> setup complete.

Next steps:
  1. Restart WSL so kvm-group membership applies:
       (from Windows)  wsl --shutdown
     then reopen the distro.
  2. Build a rootfs (no sudo needed):
       bash vmm/build_rootfs.sh base
  3. Smoke-test one VM end to end:
       bash vmm/smoke.sh
EOF
