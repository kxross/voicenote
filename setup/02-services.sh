#!/usr/bin/env bash
# Step 2 of 2 — the Docker services: agent-gateway + sandbox daemon (vmm),
# and the transcription stack (Parakeet GPU + faster-whisper fallback).
# Idempotent: safe to re-run (rebuilds images, keeps your .env and state).
#
#     bash setup/02-services.sh
#
# Run setup/01-host.sh first (Ollama, and optionally Kev / vLLM).
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
GW="$ROOT/gateway"; ASR="$ROOT/asr"; ENV="$GW/.env"

say()  { printf '\n\033[1;36m==> %s\033[0m\n' "$*"; }
warn() { printf '\033[1;33m!!  %s\033[0m\n' "$*"; }
die()  { printf '\033[1;31mxx  %s\033[0m\n' "$*" >&2; exit 1; }
getenv() { grep -E "^$1=" "$ENV" | tail -1 | cut -d= -f2- || true; }
setenv() {  # setenv KEY VALUE — replace or append, keeping the file private
  local k="$1" v="$2"
  if grep -qE "^$k=" "$ENV"; then
    python3 - "$ENV" "$k" "$v" <<'PY'
import sys
p, k, v = sys.argv[1:]
lines = open(p).read().splitlines()
out = [f"{k}={v}" if l.startswith(k + "=") else l for l in lines]
open(p, "w").write("\n".join(out) + "\n")
PY
  else
    printf '%s=%s\n' "$k" "$v" >> "$ENV"
  fi
}

command -v docker >/dev/null && docker info >/dev/null 2>&1 || die "docker not reachable — start Docker first"
docker compose version >/dev/null 2>&1 || die "docker compose v2 is required"

# --- 1. gateway/.env ---------------------------------------------------------
say "Configuring gateway/.env"
if [[ ! -f "$ENV" ]]; then
  cp "$GW/.env.example" "$ENV"
  echo "   created from .env.example"
fi
chmod 600 "$ENV"

if [[ -z "$(getenv GATEWAY_API_KEY)" ]]; then
  setenv GATEWAY_API_KEY "$(openssl rand -hex 24)"
  echo "   generated GATEWAY_API_KEY (shown at the end — enter it in the app)"
fi

KEV_ENV="$HOME/.config/kev/kev.env"
if [[ -f "$KEV_ENV" ]]; then
  setenv KEV_API_KEY "$(grep -E '^KEV_API_KEY=' "$KEV_ENV" | cut -d= -f2-)"
  echo "   KEV_API_KEY copied from ~/.config/kev/kev.env"
elif [[ "$(getenv VMM_APPROVAL_MODE)" == "smart" ]]; then
  setenv VMM_APPROVAL_MODE manual
  warn "Kev not installed → sandbox approvals set to 'manual' (run 01-host.sh --with-kev for 'smart')"
fi

if [[ -t 0 ]]; then
  for k in OPENROUTER_API_KEY TAVILY_API_KEY; do
    if [[ -z "$(getenv $k)" ]]; then
      read -rsp "   $k (optional, Enter to skip): " v; echo
      [[ -n "$v" ]] && setenv "$k" "$v"
    fi
  done
fi
mkdir -p "$GW/state"

# --- 2. sandbox image --------------------------------------------------------
say "Sandbox image (vmm-sandbox:base)"
if docker image inspect vmm-sandbox:base >/dev/null 2>&1; then
  echo "   already built (rebuild after editing vmm/profiles: bash gateway/vmm/build_image.sh base)"
else
  echo "   building — ~15 min the first time (apt mirror is the slow part)"
  bash "$GW/vmm/build_image.sh" base
fi

# --- 3. gateway + vmm --------------------------------------------------------
say "Starting agent-gateway + vmm-daemon"
( cd "$GW" && docker compose up -d --build )

# --- 4. transcription --------------------------------------------------------
say "Starting transcription (parakeet-api, whisper-api, faster-whisper)"
mkdir -p "$ASR/data/parakeet_cache"
( cd "$ASR" && docker compose up -d --build )

# --- 5. wait for health ------------------------------------------------------
say "Waiting for services (first Parakeet start downloads ~2.5 GB)"
KEY="$(getenv GATEWAY_API_KEY)"
wait_for() {  # wait_for <label> <seconds> <cmd...>
  local label="$1" secs="$2"; shift 2
  for ((i=0; i<secs; i+=5)); do "$@" >/dev/null 2>&1 && { echo "   ✓ $label"; return 0; }; sleep 5; done
  warn "$label not ready after ${secs}s — check: docker logs / bash setup/healthcheck.sh"
}
wait_for "gateway :5001"        120 curl -sf localhost:5001/healthz
wait_for "vmm-daemon :5002"      60 curl -sf localhost:5002/vms
wait_for "parakeet (GPU ASR)"   900 sh -c 'curl -sf localhost:10302/health | grep -q "\"ok\""'
wait_for "whisper-api :10301"    60 curl -sf localhost:10301/health

# --- 6. what to put in the phone app -----------------------------------------
HOSTNAME_HINT=""
if command -v tailscale >/dev/null && tailscale status >/dev/null 2>&1; then
  HOSTNAME_HINT="$(tailscale status --json 2>/dev/null | python3 -c 'import sys,json;print(json.load(sys.stdin)["Self"]["DNSName"].rstrip("."))' 2>/dev/null || true)"
elif command -v tailscale.exe >/dev/null; then   # WSL: Tailscale on Windows
  HOSTNAME_HINT="$(tailscale.exe status --json 2>/dev/null | tr -d '\r' | python3 -c 'import sys,json;print(json.load(sys.stdin)["Self"]["DNSName"].rstrip("."))' 2>/dev/null || true)"
fi
HOST="${HOSTNAME_HINT:-<this-machine-address>}"

say "Done. In the VoiceNote app → Settings:"
cat <<EOF
   Gateway URL ............ http://$HOST:5001
   Gateway API key ........ $KEY
   Transcription endpoint . http://$HOST:10301/v1/audio

   The phone must reach this machine on ports 5001 and 10301 — e.g. install
   Tailscale on the PC (on WSL: the Windows app) and on the phone. Docker
   publishes those ports on the host; no 'tailscale serve' is needed.

   Health check any time:  bash setup/healthcheck.sh
EOF
