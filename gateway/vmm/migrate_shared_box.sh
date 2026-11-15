#!/usr/bin/env bash
# One-off: move the shared sandbox onto a freshly built image (e.g. after
# adding ffmpeg/vn-video to the base profile) WITHOUT losing its /workspace.
#
# Older shared boxes were created before the persistent workspace volume, so
# their /workspace lives in the container layer and would vanish on recreate.
# This backs /workspace up to the host, destroys the box, lets vmmd recreate it
# (now with the `vmm-ws-shared` volume), and copies the workspace back in.
# The host backup is kept; delete it once you're happy.
#
#     bash vmm/migrate_shared_box.sh
set -euo pipefail

VMMD="${VMMD:-http://localhost:5002}"
KEY="${KEY:-shared}"

ID=$(curl -fsS "$VMMD/vms" | python3 -c '
import sys, json
key = sys.argv[1]
vms = [v for v in json.load(sys.stdin)["vms"] if v.get("key") == key]
print(vms[0]["id"] if vms else "")' "$KEY")

BK="$HOME/vmm-workspace-backup-$KEY-$(date +%Y%m%d-%H%M%S)"
if [[ -n "$ID" ]]; then
  OLD="vmm-$ID"
  mkdir -p "$BK"
  docker cp "$OLD:/workspace/." "$BK/"
  echo "==> backed up $(du -sh "$BK" | cut -f1) of /workspace to $BK"
  curl -fsS -X DELETE "$VMMD/vms/$ID" >/dev/null
  echo "==> destroyed $OLD"
else
  echo "==> no '$KEY' sandbox running — creating a fresh one"
fi

NEW=$(curl -fsS -X POST "$VMMD/vms" -H 'Content-Type: application/json' \
  -d "{\"key\":\"$KEY\",\"profile\":\"base\",\"ttl_minutes\":0}" |
  python3 -c 'import sys, json; print(json.load(sys.stdin)["id"])')
echo "==> recreated as vmm-$NEW"

if [[ -d "$BK" ]]; then
  docker cp "$BK/." "vmm-$NEW:/workspace/"
  echo "==> restored /workspace"
fi

docker exec "vmm-$NEW" sh -c '
  echo "--- check"
  command -v ffmpeg vn-video
  python3 -c "import numpy, PIL, cv2; print(\"numpy\", numpy.__version__, \"| cv2\", cv2.__version__)"
  ls /workspace'
docker inspect "vmm-$NEW" --format '--- workspace mount: {{range .Mounts}}{{.Name}} -> {{.Destination}}{{end}}'
