#!/usr/bin/env bash
# Step 1 of 2 — host-level services (systemd). Run as your normal user; it
# calls sudo where needed. Idempotent: safe to re-run.
#
#     bash setup/01-host.sh                    # Ollama + default model
#     bash setup/01-host.sh --with-kev         # + Kev (smart sandbox approvals)
#     bash setup/01-host.sh --with-vllm        # + vLLM (fast local text model)
#     bash setup/01-host.sh --with-kev --with-vllm
#
# Assumes Ubuntu 22.04+/24.04 (native or WSL2 with systemd enabled), an NVIDIA
# GPU with a working driver (`nvidia-smi`), and Docker (Docker Desktop with WSL
# integration, or docker-ce + nvidia-container-toolkit on native Linux).
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
WITH_KEV=0; WITH_VLLM=0
for a in "$@"; do
  case "$a" in
    --with-kev) WITH_KEV=1 ;;
    --with-vllm) WITH_VLLM=1 ;;
    -h|--help) sed -n 2,14p "$0"; exit 0 ;;
    *) echo "unknown option: $a" >&2; exit 1 ;;
  esac
done

say()  { printf '\n\033[1;36m==> %s\033[0m\n' "$*"; }
warn() { printf '\033[1;33m!!  %s\033[0m\n' "$*"; }
die()  { printf '\033[1;31mxx  %s\033[0m\n' "$*" >&2; exit 1; }

render_unit() {  # template → /etc/systemd/system/<name>
  local tmpl="$1" name="$2"
  sed -e "s#@USER@#$USER#g" -e "s#@HOME@#$HOME#g" "$tmpl" | sudo tee "/etc/systemd/system/$name" >/dev/null
}

# --- prerequisites -----------------------------------------------------------
say "Checking prerequisites"
command -v nvidia-smi >/dev/null && nvidia-smi --query-gpu=name,memory.total --format=csv,noheader \
  || warn "nvidia-smi not found — GPU services (Parakeet, vLLM, Kev) will not work"
command -v docker >/dev/null || die "docker not found (install Docker Desktop + WSL integration, or docker-ce)"
docker info >/dev/null 2>&1 || die "docker daemon not reachable — start Docker and re-run"
[[ "$(ps -p 1 -o comm=)" == "systemd" ]] || die "systemd is not PID 1 (on WSL: add [boot] systemd=true to /etc/wsl.conf, then 'wsl --shutdown')"
sudo apt-get update -qq
sudo apt-get install -y -qq curl git python3 python3-venv rsync >/dev/null

if ! command -v uv >/dev/null && [[ ! -x "$HOME/.local/bin/uv" ]]; then
  say "Installing uv (Python package manager)"
  curl -LsSf https://astral.sh/uv/install.sh | sh
fi
UV="$(command -v uv || echo "$HOME/.local/bin/uv")"

# --- Ollama ------------------------------------------------------------------
say "Ollama"
if ! command -v ollama >/dev/null; then
  curl -fsSL https://ollama.com/install.sh | sh
fi
sudo mkdir -p /etc/systemd/system/ollama.service.d
sudo cp "$ROOT/setup/systemd/ollama-override.conf" /etc/systemd/system/ollama.service.d/override.conf
sudo systemctl daemon-reload
sudo systemctl enable --now ollama
sudo systemctl restart ollama
for i in $(seq 1 30); do curl -sf localhost:11434/api/tags >/dev/null && break; sleep 2; done
DEFAULT_MODEL="$(grep -E '^DEFAULT_MODEL=' "$ROOT/gateway/.env" 2>/dev/null | cut -d= -f2 || true)"
DEFAULT_MODEL="${DEFAULT_MODEL:-qwen3.8:latest}"
say "Pulling $DEFAULT_MODEL (large download on first run)"
ollama pull "$DEFAULT_MODEL"

# --- Kev (optional) ----------------------------------------------------------
if (( WITH_KEV )); then
  say "Kev (System One) — jaredpalmer/kev-4b"
  [[ -d "$HOME/kev/.git" ]] || git clone https://github.com/jaredpalmer/kev.git "$HOME/kev"
  ( cd "$HOME/kev"
    [[ -x .venv/bin/python ]] || "$UV" venv --python 3.13 .venv
    "$UV" pip install --python .venv/bin/python -e . --torch-backend=cu128
    # Kev's pinned linear-attention kernels; without them it falls back to slow PyTorch.
    "$UV" pip install --python .venv/bin/python "flash-linear-attention==0.5.2" )
  mkdir -p "$HOME/.config/kev"
  if [[ ! -f "$HOME/.config/kev/kev.env" ]]; then
    umask 077
    printf 'KEV_API_KEY=%s\nHF_HOME=%s\n' "$(openssl rand -hex 24)" "$HOME/.cache/huggingface" > "$HOME/.config/kev/kev.env"
    echo "   wrote ~/.config/kev/kev.env (new random KEV_API_KEY)"
  fi
  render_unit "$ROOT/setup/systemd/kev.service.tmpl" kev.service
  sudo systemctl daemon-reload
  sudo systemctl enable --now kev
  echo "   kev.service started — first start downloads the model (several minutes)."
  echo "   02-services.sh copies KEV_API_KEY into gateway/.env."
fi

# --- vLLM (optional) ---------------------------------------------------------
if (( WITH_VLLM )); then
  say "vLLM — Qwen3.8-27B NVFP4 (text-only, port 8000)"
  # Pinned: the cu128 build is the one that works on Blackwell under WSL
  # (the vllm Docker image / cu130 builds hang at the first forward pass).
  V="$HOME/vllm-venv-cu128"
  [[ -x "$V/bin/python" ]] || "$UV" venv "$V" --python 3.12 --seed
  "$UV" pip install --python "$V/bin/python" "vllm==0.24.0" --torch-backend=cu128
  # Prebuilt FlashInfer kernels — JIT-compiling sm_120 at startup crashes.
  "$UV" pip install --python "$V/bin/python" "flashinfer-jit-cache==0.6.12" --index-url https://flashinfer.ai/whl/cu128
  mkdir -p "$HOME/.cache/huggingface"
  render_unit "$ROOT/setup/systemd/vllm.service.tmpl" vllm.service
  sudo systemctl daemon-reload
  sudo systemctl enable --now vllm
  echo "   vllm.service started — first start downloads ~20 GB of weights, then ~3 min to load."
  echo "   Stop it any time to free VRAM: sudo systemctl stop vllm  (the gateway falls back to Ollama)"
fi

say "Host setup done. Next: bash setup/02-services.sh"
