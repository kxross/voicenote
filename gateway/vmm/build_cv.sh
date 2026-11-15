#!/usr/bin/env bash
# Build + verify pipeline for the python-cv sandbox image.
#
# Stages:
#   1. Build the docker image (reuses build_image.sh python-cv).
#   2. Spin up a throwaway container from that image and smoke-verify the
#      full CV + OCR toolchain end to end:
#        - ffmpeg, tesseract present
#        - python imports: cv2, numpy, PIL, scipy, pytesseract
#        - OCR round-trip: render text with PIL -> OCR with pytesseract
#        - Video decode: synthesize a 2s testsrc clip with ffmpeg -> decode
#          with cv2.VideoCapture -> assert frames extracted
#   3. Fail loudly (non-zero exit) if any stage breaks.
#
# Usage:
#     bash vmm/build_cv.sh                 # docker image only (default)
#     bash vmm/build_cv.sh --firecracker   # also build the firecracker ext4
#
set -euo pipefail

VMM_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROFILE="python-cv"
IMAGE="vmm-sandbox:${PROFILE}"
BUILD_FC=0

for arg in "$@"; do
  case "$arg" in
    --firecracker|-f) BUILD_FC=1 ;;
    *) echo "unknown arg: $arg" >&2; exit 2 ;;
  esac
done

echo "==> [1/3] building ${IMAGE}"
bash "${VMM_DIR}/build_image.sh" "${PROFILE}"

echo "==> [2/3] smoke-verifying toolchain inside ${IMAGE}"
CONTAINER="vmm-cv-smoke-$$"
cleanup() { docker rm -f "${CONTAINER}" >/dev/null 2>&1 || true; }
trap cleanup EXIT

docker run -d --name "${CONTAINER}" "${IMAGE}" sleep infinity >/dev/null

# --- presence checks ---------------------------------------------------------
check() {
  local label="$1"; shift
  echo "  - ${label}..."
  if ! docker exec "${CONTAINER}" "$@" >/dev/null 2>&1; then
    echo "    FAIL: ${label}" >&2
    exit 1
  fi
  echo "    ok"
}

check "ffmpeg present"        ffmpeg -version
check "tesseract present"     tesseract --version

# --- python imports -----------------------------------------------------------
echo "  - python imports (cv2, numpy, PIL, scipy, pytesseract)..."
docker exec "${CONTAINER}" python3 -c '
import cv2, numpy, PIL, scipy, pytesseract
print("    ok (cv2={}, numpy={}, PIL={})".format(cv2.__version__, numpy.__version__, PIL.__version__))
'

# --- OCR round-trip test ------------------------------------------------------
echo "  - OCR round-trip (PIL render -> pytesseract read)..."
docker exec "${CONTAINER}" python3 -c '
from PIL import Image, ImageDraw, ImageFont
import pytesseract, sys

img = Image.new("RGB", (400, 64), "white")
d = ImageDraw.Draw(img)
d.text((10, 20), "Hello CV World 42", fill="black")
text = pytesseract.image_to_string(img).strip()
assert "Hello" in text and "42" in text, f"OCR round-trip failed: got {text!r}"
print("    ok (read: {!r})".format(text))
'

# --- video decode test --------------------------------------------------------
echo "  - video decode (ffmpeg testsrc -> cv2.VideoCapture)..."
docker exec "${CONTAINER}" bash -lc '
set -e
cd /tmp
# Synthesize a 2-second color test pattern at 10 fps (20 frames).
ffmpeg -hide_banner -loglevel error -f lavfi -i testsrc=duration=2:size=320x240:rate=10 test_clip.mp4 -y
python3 -c "
import cv2, sys
cap = cv2.VideoCapture(\"test_clip.mp4\")
assert cap.isOpened(), \"VideoCapture failed to open\"
count = 0
while True:
    ret, frame = cap.read()
    if not ret:
        break
    count += 1
    assert frame.shape == (240, 320, 3), f\"unexpected frame shape: {frame.shape}\"
cap.release()
assert count == 20, f\"expected 20 frames, got {count}\"
print(\"    ok ({} frames decoded)\".format(count))
"
rm -f test_clip.mp4
'

echo "==> [3/3] ${IMAGE} verified"

# --- optional firecracker rootfs ---------------------------------------------
if [ "${BUILD_FC}" -eq 1 ]; then
  echo "==> building firecracker rootfs for ${PROFILE}"
  bash "${VMM_DIR}/build_rootfs.sh" "${PROFILE}"
  echo "==> firecracker rootfs built"
fi

echo "==> done. image: ${IMAGE}"