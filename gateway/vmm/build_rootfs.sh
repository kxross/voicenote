#!/usr/bin/env bash
# Build a guest rootfs image for one profile — NO SUDO REQUIRED.
#
#     bash vmm/build_rootfs.sh [profile]     # default: base
#
# Docker assembles a Debian tree and runs apt (cached, reproducible); we then
# `docker export` it and turn the tree into an ext4 image with
# `fakeroot mkfs.ext4 -d`. fakeroot makes the export's root-owned files read
# back as uid 0 without mounting or real privilege, so the microVM sees a
# correctly-owned root filesystem.
set -euo pipefail
source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/env.sh"

PROFILE="${1:-base}"
PKGS_FILE="$PROFILES_DIR/$PROFILE.pkgs"
[[ -f "$PKGS_FILE" ]] || { echo "no such profile: $PROFILE (missing $PKGS_FILE)" >&2; exit 1; }

for bin in docker fakeroot mkfs.ext4; do
  command -v "$bin" >/dev/null || { echo "missing required tool: $bin" >&2; exit 1; }
done

ensure_keys
PUBKEY="$(cat "$SSH_PUBKEY")"

# profiles list: strip comments + blanks, join to one space-separated string
PACKAGES="$(grep -vE '^\s*(#|$)' "$PKGS_FILE" | tr '\n' ' ')"
echo "==> profile '$PROFILE' packages: $PACKAGES"

IMAGE_TAG="vmm-rootfs:$PROFILE"
OUT="$IMAGES_DIR/$PROFILE.ext4"
BUILD_DIR="$STATE_DIR/build-$PROFILE"
ROOTTREE="$BUILD_DIR/root"

mkdir -p "$IMAGES_DIR" "$BUILD_DIR"
rm -rf "$ROOTTREE"; mkdir -p "$ROOTTREE"

echo "==> docker build $IMAGE_TAG"
docker build \
  -f "$VMM_DIR/Dockerfile.rootfs" \
  --build-arg PACKAGES="$PACKAGES" \
  --build-arg SSH_PUBKEY="$PUBKEY" \
  -t "$IMAGE_TAG" \
  "$VMM_DIR"

CID="$(docker create "$IMAGE_TAG" /bin/true)"
trap 'docker rm -f "$CID" >/dev/null 2>&1 || true' EXIT

echo "==> exporting rootfs tree (under fakeroot for correct uid 0)"
# Extract the export and build the ext4 in ONE fakeroot session so the faked
# ownership is consistent between tar's chown and mkfs's stat.
docker export "$CID" > "$BUILD_DIR/export.tar"

# size the image: exported tree + 2 GiB slack for runtime apt/pip installs
docker rm -f "$CID" >/dev/null 2>&1 || true; trap - EXIT
TREE_MB=$(( $(stat -c %s "$BUILD_DIR/export.tar") / 1024 / 1024 ))
SIZE_MB=$(( TREE_MB + 2048 ))
echo "==> tree ~${TREE_MB}MB -> image ${SIZE_MB}MB"

fakeroot bash -c "
  set -e
  tar -xpf '$BUILD_DIR/export.tar' -C '$ROOTTREE'
  # marker so we can confirm which profile a mounted image is
  echo '$PROFILE' > '$ROOTTREE/etc/vmm-profile'
  mkfs.ext4 -q -F -b 4096 -d '$ROOTTREE' '$OUT.tmp' ${SIZE_MB}M
"
mv -f "$OUT.tmp" "$OUT"

rm -rf "$BUILD_DIR"
echo "==> built $OUT ($(du -h "$OUT" | cut -f1) apparent, $(du -h --apparent-size "$OUT" | cut -f1) allocated)"
echo "==> sanity: /sbin/init present?"
debugfs -R "stat /sbin/init" "$OUT" 2>/dev/null | grep -E 'Type|User' | head -2 || true
