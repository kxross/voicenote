#!/usr/bin/env bash
# End-to-end smoke test for the VM stack, independent of the daemon. Boots
# one Firecracker microVM (reserved index 99) from the `base` rootfs, waits
# for SSH, runs a command inside the guest, then tears everything down.
#
#     bash vmm/smoke.sh
#
# Requires setup.sh to have run AND a WSL restart (for kvm-group membership),
# plus `build_rootfs.sh base`.
set -euo pipefail
source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/env.sh"

IDX=99
PROFILE="${1:-base}"
DISK_SRC="$IMAGES_DIR/$PROFILE.ext4"
DISK="$STATE_DIR/smoke-$IDX.ext4"
SOCK="$STATE_DIR/smoke-$IDX.sock"
CFG="$STATE_DIR/smoke-$IDX.json"
LOG="$STATE_DIR/smoke-$IDX.log"
GUEST="$(guest_ip "$IDX")"
GW="$(host_ip "$IDX")"
TAP="$(tap_name "$IDX")"

# ---- preflight ------------------------------------------------------------
[[ -x "$FIRECRACKER" ]] || { echo "firecracker missing — run setup.sh" >&2; exit 1; }
[[ -f "$KERNEL" ]]      || { echo "kernel missing — run setup.sh" >&2; exit 1; }
[[ -f "$DISK_SRC" ]]    || { echo "rootfs missing — run: bash vmm/build_rootfs.sh $PROFILE" >&2; exit 1; }
if ! { : < /dev/kvm; } 2>/dev/null; then
  echo "cannot open /dev/kvm — is $VMM_USER in the kvm group? (needs WSL restart after setup.sh)" >&2
  exit 1
fi

FC_PID=""
cleanup() {
  [[ -n "$FC_PID" ]] && kill "$FC_PID" 2>/dev/null || true
  sudo "$VMM_DIR/net-helper.sh" down "$IDX" >/dev/null 2>&1 || true
  rm -f "$DISK" "$SOCK" "$CFG"
}
trap cleanup EXIT

echo "==> [1/5] network up (idx $IDX)"
sudo "$VMM_DIR/net-helper.sh" up "$IDX"

echo "==> [2/5] throwaway disk copy"
cp --sparse=always "$DISK_SRC" "$DISK"

echo "==> [3/5] firecracker config"
cat > "$CFG" <<JSON
{
  "boot-source": {
    "kernel_image_path": "$KERNEL",
    "boot_args": "console=ttyS0 reboot=k panic=1 pci=off net.ifnames=0 random.trust_cpu=on ip=${GUEST}::${GW}:255.255.255.252::eth0:off root=/dev/vda rw"
  },
  "drives": [
    { "drive_id": "rootfs", "path_on_host": "$DISK", "is_root_device": true, "is_read_only": false }
  ],
  "machine-config": { "vcpu_count": 2, "mem_size_mib": 1024 },
  "network-interfaces": [
    { "iface_id": "eth0", "host_dev_name": "$TAP", "guest_mac": "AA:FC:00:00:00:63" }
  ]
}
JSON

echo "==> [4/5] boot"
rm -f "$SOCK"
"$FIRECRACKER" --api-sock "$SOCK" --config-file "$CFG" >"$LOG" 2>&1 &
FC_PID=$!

SSH_OPTS=(-o ConnectTimeout=2 -o StrictHostKeyChecking=no -o UserKnownHostsFile=/dev/null -o LogLevel=ERROR -i "$SSH_KEY")
echo "==> [5/5] waiting for ssh on $GUEST ..."
ok=""
for i in $(seq 1 40); do
  if ! kill -0 "$FC_PID" 2>/dev/null; then
    echo "!! firecracker exited early; console log:" >&2; tail -30 "$LOG" >&2; exit 1
  fi
  if ssh "${SSH_OPTS[@]}" "root@$GUEST" 'true' 2>/dev/null; then ok=1; break; fi
  sleep 1
done
if [[ -z "$ok" ]]; then
  echo "!! ssh never came up; console log:" >&2; tail -40 "$LOG" >&2; exit 1
fi

echo "==> guest reachable — running checks:"
ssh "${SSH_OPTS[@]}" "root@$GUEST" '
  echo "  uname : $(uname -sr)"
  echo "  os    : $(. /etc/os-release; echo $PRETTY_NAME)"
  echo "  prof  : $(cat /etc/vmm-profile 2>/dev/null)"
  echo "  ip    : $(ip -4 addr show eth0 | awk "/inet /{print \$2}")"
  echo -n "  net   : "; (curl -sS -m 8 -o /dev/null -w "pypi reachable (%{http_code})\n" https://pypi.org/simple/ || echo "no egress")
'
echo "==> SMOKE PASSED"
