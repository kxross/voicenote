#!/bin/bash
# Quick smoke test for the Wyoming whisper wrapper.
# Run this from any machine that can reach the server to confirm
# the endpoint accepts requests before blaming the Android app.
#
# Usage:
#   chmod +x test_endpoint.sh
#   ./test_endpoint.sh                              # uses defaults
#   ./test_endpoint.sh http://192.168.1.50:5000     # custom host

HOST="${1:-http://localhost:5000}"
ENDPOINT="$HOST/v1/audio"

echo "=== Testing: $ENDPOINT ==="
echo ""

# 1. Health check
echo "--- /health ---"
curl -s "$HOST/health" | python3 -m json.tool
echo ""

# 2. Generate a minimal valid WAV (1 second of silence, 16kHz mono PCM)
#    so we have something real to send without needing an actual recording
WAV=$(mktemp /tmp/test_XXXXXX.wav)
python3 - "$WAV" << 'PYEOF'
import sys, struct, wave
path = sys.argv[1]
with wave.open(path, 'w') as w:
    w.setnchannels(1)
    w.setsampwidth(2)
    w.setframerate(16000)
    w.writeframes(b'\x00\x00' * 16000)   # 1 second of silence
print(f"Generated test WAV: {path}")
PYEOF

echo ""
echo "--- POST /v1/audio (WAV) ---"
curl -v \
  -F "file=@$WAV;type=audio/wav" \
  -F "model=faster-whisper-base" \
  "$ENDPOINT"
echo ""
echo ""

# 3. Also try with m4a content-type to mirror exactly what the Android app sends
echo "--- POST /v1/audio (m4a content-type, same WAV bytes) ---"
curl -v \
  -F "file=@$WAV;type=audio/mp4" \
  -F "model=faster-whisper-base" \
  "$ENDPOINT"
echo ""

rm -f "$WAV"
echo "=== Done ==="
