#!/usr/bin/env bash
# One-screen status of every service. Safe to run any time.
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
KEY="$(grep -E '^GATEWAY_API_KEY=' "$ROOT/gateway/.env" 2>/dev/null | cut -d= -f2-)"
AUTH=(); [[ -n "$KEY" ]] && AUTH=(-H "Authorization: Bearer $KEY")

echo "--- containers"; docker ps --format '{{.Names}}\t{{.Status}}' | grep -E 'agent-gateway|vmm-|whisper|parakeet' || echo "(none running)"
echo "--- systemd";    for s in ollama kev vllm; do printf '%-7s %s\n' "$s" "$(systemctl is-active $s 2>/dev/null)"; done
echo "--- gateway";    curl -s -m 5 localhost:5001/healthz | head -c 160; echo
echo "--- delivery";   curl -s -m 5 "${AUTH[@]}" localhost:5001/v1/pending/voicenote-default | python3 -c 'import sys,json;d=json.load(sys.stdin);print("queued",len(d["deliveries"]),"busy",d["busy_threads"],"next_due",d["next_due"])' 2>/dev/null || echo "(unreachable)"
echo "--- vmm";        curl -s -m 5 localhost:5002/vms | head -c 160; echo
echo "--- ollama";     curl -s -m 5 localhost:11434/api/tags | python3 -c 'import sys,json;print([m["name"] for m in json.load(sys.stdin)["models"]][:5])' 2>/dev/null || echo "(down)"
echo "--- vllm";       curl -s -m 5 localhost:8000/v1/models | head -c 120 || true; echo
echo "--- kev";        curl -s -m 5 "${AUTH[@]}" localhost:5001/v1/systemone/health | head -c 200; echo
echo "--- asr";        curl -s -m 5 localhost:10301/health; echo     # want "default_engine":"parakeet"
echo "--- gpu";        nvidia-smi --query-gpu=memory.used,memory.total --format=csv,noheader 2>/dev/null || echo "(no nvidia-smi)"
