#!/usr/bin/env bash
# Build a guest profile as a plain Docker image for the docker backend —
# NO SUDO. Reuses the SAME Dockerfile.rootfs that build_rootfs.sh turns into
# an ext4, so a profile is defined once and feeds both backends. The docker
# backend runs the image with `sleep infinity` as PID 1 and drives it via
# `docker exec`, so the baked systemd/sshd go unused (harmless).
#
#     bash vmm/build_image.sh [profile]      # default: base
set -euo pipefail
source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/env.sh"

PROFILE="${1:-base}"
PKGS_FILE="$PROFILES_DIR/$PROFILE.pkgs"
[[ -f "$PKGS_FILE" ]] || { echo "no such profile: $PROFILE (missing $PKGS_FILE)" >&2; exit 1; }
command -v docker >/dev/null || { echo "docker not found" >&2; exit 1; }

# Keep in sync with config.yml -> docker.image_prefix
IMAGE_PREFIX="vmm-sandbox"
TAG="$IMAGE_PREFIX:$PROFILE"

ensure_keys
PUBKEY="$(cat "$SSH_PUBKEY")"
PACKAGES="$(grep -vE '^\s*(#|$)' "$PKGS_FILE" | tr '\n' ' ')"

echo "==> building $TAG (packages: $PACKAGES)"
docker build \
  -f "$VMM_DIR/Dockerfile.rootfs" \
  --build-arg PACKAGES="$PACKAGES" \
  --build-arg SSH_PUBKEY="$PUBKEY" \
  -t "$TAG" \
  "$VMM_DIR"

echo "==> built $TAG"
docker image inspect "$TAG" --format '    size: {{.Size}} bytes, id: {{.Id}}' 2>/dev/null || true
