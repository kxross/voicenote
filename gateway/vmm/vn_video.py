#!/usr/bin/env python3
"""vn-video — the sandbox's fixed video toolkit for the gateway's `video` skill.

Baked into the sandbox image (/usr/local/bin/vn-video) so frame work is a
known, deterministic command rather than model-written code: the smart-approval
reviewer sees the same short command every time, and nothing is piped through
base64. Every subcommand prints one JSON object on stdout (or {"error": ...}
with exit status 1).

    vn-video probe  FILE
        Duration, fps, size (as displayed, after rotation), codec, audio,
        scene cuts, and a per-bucket motion profile with a suggested plan.
    vn-video sheet  FILE --out OUT.jpg [--count N | --times T,T,..] [--scenes]
                         [--start S] [--end E] [--cell PX] [--cols C]
        One contact-sheet JPEG: N frames tiled with #index and timestamp labels.
    vn-video frames FILE --outdir DIR (--times T,T,.. |
                         --start S --end E (--fps F | --every S)) [--size PX] [--max N]
        Individual JPEG frames (long side PX) with a small timestamp label.

Needs ffmpeg/ffprobe, numpy and Pillow (all in the base sandbox image).
"""

from __future__ import annotations

import argparse
import json
import math
import os
import subprocess
import sys

FONT_PATHS = (
    "/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf",
    "/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf",
)


def fail(msg: str) -> None:
    print(json.dumps({"error": msg}))
    sys.exit(1)


def run(cmd: list[str], **kw) -> subprocess.CompletedProcess:
    return subprocess.run(cmd, capture_output=True, **kw)


def fmt_t(t: float) -> str:
    t = max(0.0, t)
    m, s = divmod(t, 60)
    h, m = divmod(int(m), 60)
    return (f"{h}:{m:02d}:{s:04.1f}" if h else f"{m:02d}:{s:04.1f}")


# ---- probe -------------------------------------------------------------------

def ffprobe(path: str) -> dict:
    p = run(["ffprobe", "-v", "error", "-print_format", "json",
             "-show_format", "-show_streams", path])
    if p.returncode != 0:
        fail(f"ffprobe failed: {p.stderr.decode(errors='replace')[:300]}")
    return json.loads(p.stdout or b"{}")


def _rate(r: str | None) -> float:
    try:
        n, d = (r or "0/1").split("/")
        return float(n) / float(d) if float(d) else 0.0
    except (ValueError, ZeroDivisionError):
        return 0.0


def meta(path: str) -> dict:
    info = ffprobe(path)
    streams = info.get("streams") or []
    v = next((s for s in streams if s.get("codec_type") == "video"), None)
    if v is None:
        fail("no video stream")
    a = any(s.get("codec_type") == "audio" for s in streams)
    rot = 0
    for sd in v.get("side_data_list") or []:
        if "rotation" in sd:
            rot = int(float(sd["rotation"]))
    rot = rot or int(float((v.get("tags") or {}).get("rotate", 0) or 0))
    w, h = int(v.get("width") or 0), int(v.get("height") or 0)
    if abs(rot) % 180 == 90:
        w, h = h, w
    dur = float(v.get("duration") or (info.get("format") or {}).get("duration") or 0)
    fps = _rate(v.get("avg_frame_rate")) or _rate(v.get("r_frame_rate"))
    return {
        "duration_s": round(dur, 3),
        "fps": round(fps, 3),
        "frames": int(v.get("nb_frames") or (dur * fps if fps else 0)),
        "width": w, "height": h, "rotation": rot,
        "codec": v.get("codec_name"),
        "has_audio": a,
        "size_bytes": int((info.get("format") or {}).get("size") or os.path.getsize(path)),
    }


def analyze(path: str, duration: float) -> dict:
    """One cheap decode pass at low fps / tiny size: per-sample motion (mean
    abs pixel change) and scene cuts (histogram jumps)."""
    import numpy as np

    sample_fps = 4 if duration <= 600 else 2
    W = 96
    p = subprocess.Popen(
        ["ffmpeg", "-v", "error", "-i", path, "-an",
         "-vf", f"fps={sample_fps},scale={W}:-2,format=gray",
         "-f", "rawvideo", "-"],
        stdout=subprocess.PIPE, stderr=subprocess.DEVNULL,
    )
    raw = p.stdout.read()
    p.wait()
    # Frame height after scale=-2 (even), from the stream size.
    m = meta_cache.get(path)
    if not m or not m["width"]:
        return {"scene_cuts": [], "motion": {"bucket_s": 1, "values": []}}
    H = int(round(W * m["height"] / m["width"] / 2) * 2) or 2
    n = len(raw) // (W * H)
    if n < 2:
        return {"scene_cuts": [], "motion": {"bucket_s": 1, "values": []}}
    frames = np.frombuffer(raw[: n * W * H], dtype=np.uint8).reshape(n, H, W).astype(np.int16)
    diffs = np.abs(np.diff(frames, axis=0)).mean(axis=(1, 2))            # 0..255
    hists = np.stack([np.histogram(f, bins=32, range=(0, 256))[0] for f in frames]).astype(np.float64)
    hists /= hists.sum(axis=1, keepdims=True) + 1e-9
    hjump = 0.5 * np.abs(np.diff(hists, axis=0)).sum(axis=1)             # 0..1
    med = float(np.median(diffs)) if len(diffs) else 0.0
    cuts = []
    for i, (d, hj) in enumerate(zip(diffs, hjump)):
        t = (i + 1) / sample_fps
        if hj > 0.35 or (d > max(18.0, 4 * med) and hj > 0.15):
            if not cuts or t - cuts[-1] >= 0.75:
                cuts.append(round(t, 2))
    # Motion per bucket, scaled 0-100, at most ~60 buckets.
    bucket_s = max(1, math.ceil(duration / 60)) if duration else 1
    per = max(1, int(bucket_s * sample_fps))
    vals = [round(float(min(100.0, diffs[i:i + per].mean() * 4)), 1) for i in range(0, len(diffs), per)]
    return {"scene_cuts": cuts[:200], "motion": {"bucket_s": bucket_s, "values": vals}}


def segments(motion: dict) -> list[dict]:
    """Collapse the motion profile into runs: static / moderate / high."""
    b, vals = motion["bucket_s"], motion["values"]
    def kind(v: float) -> str:
        return "static" if v < 3 else ("moderate" if v < 12 else "high")
    out: list[dict] = []
    for i, v in enumerate(vals):
        k = kind(v)
        if out and out[-1]["kind"] == k:
            out[-1]["end_s"] = (i + 1) * b
        else:
            out.append({"start_s": i * b, "end_s": (i + 1) * b, "kind": k})
    return out


def suggest(m: dict, a: dict, segs: list[dict]) -> dict:
    """A starting sampling plan the model can follow or override."""
    dur = m["duration_s"]
    n_cuts = len(a["scene_cuts"])
    overview = 16 if dur > 90 else 12
    rates = {"static": "1 frame per segment", "moderate": "1-2 fps", "high": "4-8 fps"}
    busy = [s for s in segs if s["kind"] == "high"][:5]
    return {
        "overview": {
            "count": overview,
            "use_scenes": n_cuts >= 4,
            "why": (f"{n_cuts} scene cuts — sample at cuts" if n_cuts >= 4
                    else "few cuts — sample evenly"),
        },
        "dense_rates": rates,
        "busiest_segments": [{"start_s": s["start_s"], "end_s": s["end_s"]} for s in busy],
    }


meta_cache: dict[str, dict] = {}


def cmd_probe(args) -> dict:
    m = meta(args.file)
    meta_cache[args.file] = m
    a = analyze(args.file, m["duration_s"])
    segs = segments(a["motion"])
    return {**m, **a, "segments": segs, "suggested": suggest(m, a, segs)}


# ---- frame extraction --------------------------------------------------------

def grab(path: str, t: float, out: str, long_side: int) -> bool:
    vf = (f"scale='if(gt(iw,ih),{long_side},-2)':'if(gt(iw,ih),-2,{long_side})'")
    p = run(["ffmpeg", "-v", "error", "-y", "-ss", f"{max(0.0, t):.3f}", "-i", path,
             "-frames:v", "1", "-vf", vf, "-q:v", "3", out])
    return p.returncode == 0 and os.path.exists(out) and os.path.getsize(out) > 0


def font(size: int):
    from PIL import ImageFont
    for fp in FONT_PATHS:
        if os.path.exists(fp):
            try:
                return ImageFont.truetype(fp, size)
            except OSError:
                pass
    return ImageFont.load_default()


def label(img, text: str, size: int) -> None:
    from PIL import ImageDraw
    d = ImageDraw.Draw(img)
    f = font(size)
    try:
        x0, y0, x1, y1 = d.textbbox((0, 0), text, font=f)
        tw, th = x1 - x0, y1 - y0
    except AttributeError:
        tw, th = d.textsize(text, font=f)
    pad = max(3, size // 4)
    d.rectangle([0, 0, tw + 2 * pad, th + 2 * pad], fill=(0, 0, 0))
    d.text((pad, pad - 1), text, fill=(255, 255, 255), font=f)


def parse_times(s: str | None) -> list[float]:
    if not s:
        return []
    out = []
    for part in s.split(","):
        part = part.strip()
        if not part:
            continue
        if ":" in part:
            secs = 0.0
            for x in part.split(":"):
                secs = secs * 60 + float(x)
            out.append(secs)
        else:
            out.append(float(part))
    return out


def even(start: float, end: float, n: int) -> list[float]:
    span = max(0.0, end - start)
    return [start + (i + 0.5) * span / n for i in range(n)] if n > 0 else []


def pick(ts: list[float], n: int) -> list[float]:
    """At most n of ts, spread evenly."""
    if len(ts) <= n:
        return ts
    step = len(ts) / n
    return [ts[int(i * step)] for i in range(n)]


def cmd_sheet(args) -> dict:
    from PIL import Image
    m = meta(args.file)
    meta_cache[args.file] = m
    dur = m["duration_s"]
    start = max(0.0, args.start or 0.0)
    end = min(dur, args.end) if args.end else dur
    count = max(1, min(args.count, 25))
    if args.times:
        ts = pick([t for t in parse_times(args.times) if 0 <= t <= dur], count)
    elif args.scenes:
        cuts = [c for c in analyze(args.file, dur)["scene_cuts"] if start <= c < end]
        ts = pick([start] + cuts, count)
        if len(ts) < count:   # fill quiet stretches evenly
            ts = sorted(set(ts) | set(even(start, end, count - len(ts))))
            ts = pick(ts, count)
    else:
        ts = even(start, end, count)
    ts = sorted(round(min(max(t, 0.0), max(0.0, dur - 0.05)), 2) for t in ts)

    tmp = args.out + ".parts"
    os.makedirs(tmp, exist_ok=True)
    tiles = []
    for i, t in enumerate(ts):
        fp = os.path.join(tmp, f"{i:03d}.jpg")
        if grab(args.file, t, fp, args.cell):
            tiles.append((i + 1, t, Image.open(fp).convert("RGB")))
    if not tiles:
        fail("could not extract any frames")
    cols = args.cols or min(4, len(tiles))
    rows = math.ceil(len(tiles) / cols)
    cw = max(im.width for _, _, im in tiles)
    ch = max(im.height for _, _, im in tiles)
    gap = 4
    sheet = Image.new("RGB", (cols * cw + (cols - 1) * gap, rows * ch + (rows - 1) * gap), (24, 24, 24))
    cells = []
    for k, (n, t, im) in enumerate(tiles):
        label(im, f"#{n} {fmt_t(t)}", max(12, cw // 18))
        r, c = divmod(k, cols)
        sheet.paste(im, (c * (cw + gap), r * (ch + gap)))
        cells.append({"n": n, "t": t, "label": fmt_t(t)})
    os.makedirs(os.path.dirname(os.path.abspath(args.out)), exist_ok=True)
    sheet.save(args.out, "JPEG", quality=82)
    for f in os.listdir(tmp):
        os.remove(os.path.join(tmp, f))
    os.rmdir(tmp)
    return {"sheet": args.out, "cols": cols, "rows": rows, "cells": cells,
            "width": sheet.width, "height": sheet.height}


def cmd_frames(args) -> dict:
    from PIL import Image
    m = meta(args.file)
    dur = m["duration_s"]
    limit = max(1, min(args.max, 16))
    if args.times:
        ts = [t for t in parse_times(args.times) if 0 <= t <= dur]
    else:
        if args.start is None or args.end is None:
            fail("give --times, or --start and --end with --fps or --every")
        start, end = max(0.0, args.start), min(dur, args.end)
        if end <= start:
            fail("--end must be after --start")
        step = (1.0 / args.fps) if args.fps else (args.every or (end - start) / limit)
        ts, t = [], start
        while t < end - 1e-6 and len(ts) < 10_000:
            ts.append(t)
            t += step
    requested = len(ts)
    ts = pick(ts, limit)
    ts = [round(min(max(t, 0.0), max(0.0, dur - 0.05)), 3) for t in ts]
    os.makedirs(args.outdir, exist_ok=True)
    out = []
    for i, t in enumerate(ts):
        fp = os.path.join(args.outdir, f"f{i:03d}_{t:08.3f}.jpg")
        if grab(args.file, t, fp, args.size):
            im = Image.open(fp).convert("RGB")
            label(im, fmt_t(t), max(12, im.width // 40))
            im.save(fp, "JPEG", quality=85)
            out.append({"n": i + 1, "t": t, "label": fmt_t(t), "path": fp})
    if not out:
        fail("could not extract any frames")
    return {"frames": out, "requested": requested, "returned": len(out),
            "capped": requested > len(out)}


def main() -> None:
    ap = argparse.ArgumentParser(prog="vn-video")
    sub = ap.add_subparsers(dest="cmd", required=True)
    p = sub.add_parser("probe"); p.add_argument("file")
    s = sub.add_parser("sheet")
    s.add_argument("file"); s.add_argument("--out", required=True)
    s.add_argument("--count", type=int, default=12); s.add_argument("--times")
    s.add_argument("--scenes", action="store_true")
    s.add_argument("--start", type=float); s.add_argument("--end", type=float)
    s.add_argument("--cell", type=int, default=320); s.add_argument("--cols", type=int)
    f = sub.add_parser("frames")
    f.add_argument("file"); f.add_argument("--outdir", required=True)
    f.add_argument("--times"); f.add_argument("--start", type=float); f.add_argument("--end", type=float)
    f.add_argument("--fps", type=float); f.add_argument("--every", type=float)
    f.add_argument("--size", type=int, default=768); f.add_argument("--max", type=int, default=8)
    args = ap.parse_args()
    if not os.path.isfile(args.file):
        fail(f"no such file: {args.file}")
    try:
        res = {"probe": cmd_probe, "sheet": cmd_sheet, "frames": cmd_frames}[args.cmd](args)
    except SystemExit:
        raise
    except Exception as e:  # noqa: BLE001 — report, don't traceback
        fail(f"{type(e).__name__}: {e}")
    print(json.dumps(res))


if __name__ == "__main__":
    main()
