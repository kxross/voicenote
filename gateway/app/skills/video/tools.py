"""Tools exported by the `video` skill: watch a video the user attached.

All decoding happens in the sandbox. The first call streams the stored video
(app/media.py) into the shared sandbox at /workspace/media/<sha>.<ext> through
vmmd's raw upload, then every tool runs the image's fixed `vn-video` CLI
(probe / sheet / frames) and fetches the resulting JPEGs back with vmmd's raw
read. Frames reach the model as images via `ToolResult` (app/tool_result.py) —
or, if the turn's model can't see, as text from the local vision model.

A per-turn budget keeps context bounded: settings.video_max_frames_per_turn
frames (a contact-sheet tile counts as half a frame) and 6 frame calls.
"""

from __future__ import annotations

import base64
import json
import math
import shlex

import httpx

from app import images as image_store
from app import media
from app.config import settings
from app.tool_result import ToolResult

ToolSpec = tuple[dict, object]

_TIMEOUT = httpx.Timeout(connect=10, read=600, write=600, pool=10)
_MAX_CALLS = 6
_MAX_PER_CALL = 8

_VID = {"type": "string", "description": "The video id from the message, e.g. 'vid_ab12cd34'."}

PROBE_SCHEMA = {
    "type": "function",
    "function": {
        "name": "video_probe",
        "description": (
            "Start here for any attached video. Loads it into your sandbox and reports "
            "duration, fps, size/orientation, scene cuts and a motion profile, plus a "
            "suggested sampling plan. Cheap — no frames."
        ),
        "parameters": {"type": "object", "properties": {"video_id": _VID}, "required": ["video_id"]},
    },
}

OVERVIEW_SCHEMA = {
    "type": "function",
    "function": {
        "name": "video_overview",
        "description": (
            "Look at a contact sheet: `count` frames from the video (or from a time "
            "range) tiled into ONE image, each labelled #n and its timestamp. Use first "
            "to see the whole video at a glance, then zoom in with video_frames."
        ),
        "parameters": {
            "type": "object",
            "properties": {
                "video_id": _VID,
                "count": {"type": "integer", "description": "Frames on the sheet, 4-20 (default 12)."},
                "use_scenes": {"type": "boolean",
                               "description": "Sample at scene cuts instead of evenly (default: probe's suggestion)."},
                "start_s": {"type": "number", "description": "Optional range start, seconds."},
                "end_s": {"type": "number", "description": "Optional range end, seconds."},
            },
            "required": ["video_id"],
        },
    },
}

FRAMES_SCHEMA = {
    "type": "function",
    "function": {
        "name": "video_frames",
        "description": (
            "Look closely at specific moments: up to 8 full-size frames, either at "
            "explicit `times`, or across start_s..end_s at `fps` (or one every "
            "`every_s` seconds). Pick the rate from the motion: 1-2 fps for slow "
            "action, 4-8 fps for fast action in a short window."
        ),
        "parameters": {
            "type": "object",
            "properties": {
                "video_id": _VID,
                "times": {"type": "array", "items": {"type": "number"},
                          "description": "Exact timestamps in seconds."},
                "start_s": {"type": "number"},
                "end_s": {"type": "number"},
                "fps": {"type": "number", "description": "Frames per second within the range."},
                "every_s": {"type": "number", "description": "Seconds between frames within the range."},
            },
            "required": ["video_id"],
        },
    },
}


def _fmt_t(t: float) -> str:
    t = max(0.0, float(t or 0))
    m, s = divmod(t, 60)
    return f"{int(m):02d}:{s:04.1f}"


def build_tools(state: dict) -> list[ToolSpec]:
    base_url = state.get("vm_base_url") or settings.vmmd_base_url
    app_state = state.get("app_state")
    vm: dict = {"id": None}
    budget = state.setdefault("video_budget", {
        "frames": float(settings.video_max_frames_per_turn), "calls": _MAX_CALLS,
    })
    probes: dict = state.setdefault("video_probes", {})
    in_vm: dict = state.setdefault("video_in_vm", {})       # sha -> vm path

    def _client() -> httpx.AsyncClient:
        return httpx.AsyncClient(base_url=base_url, timeout=_TIMEOUT)

    async def _vm_id() -> str:
        if vm["id"]:
            return vm["id"]
        body: dict = {"key": state.get("vm_key") or "shared", "profile": "base"}
        if state.get("default_backend"):
            body["backend"] = state["default_backend"]
        if state.get("vm_ttl_minutes") is not None:
            body["ttl_minutes"] = state["vm_ttl_minutes"]
        async with _client() as c:
            r = await c.post("/vms", json=body)
            r.raise_for_status()
            vm["id"] = r.json()["id"]
        return vm["id"]

    async def _exec(command: str, timeout: int, purpose: str) -> dict:
        ctx = {"purpose": purpose}
        if state.get("user_request"):
            ctx["user_request"] = str(state["user_request"])[:2000]
        async with _client() as c:
            r = await c.post(f"/vms/{await _vm_id()}/exec",
                             json={"command": command, "timeout": timeout, "context": ctx})
            r.raise_for_status()
            return r.json()

    async def _json_exec(command: str, timeout: int, purpose: str) -> dict:
        res = await _exec(command, timeout, purpose)
        out = (res.get("stdout") or "").strip()
        try:
            data = json.loads(out.splitlines()[-1]) if out else {}
        except json.JSONDecodeError:
            data = {}
        if res.get("exit_code") != 0 or not data or "error" in data:
            why = data.get("error") or (res.get("stderr") or out or "no output").strip()[:300]
            raise RuntimeError(why)
        return data

    async def _raw(path: str) -> bytes:
        async with _client() as c:
            r = await c.get(f"/vms/{await _vm_id()}/raw", params={"path": path})
            r.raise_for_status()
            return r.content

    async def _resolve(video_id: str) -> dict | None:
        vid = (video_id or "").strip().lower().removeprefix("vid_")
        for v in state.get("videos") or []:
            if v["id"].startswith(vid):
                rec = await app_state.get_media(v["id"]) if app_state else None
                return rec or {"sha": v["id"], "duration_s": v["duration_s"]}
        return await app_state.find_media(vid) if app_state else None

    async def _ensure_in_vm(sha: str) -> str:
        if sha in in_vm:
            return in_vm[sha]
        local = media.find(sha)
        if local is None:
            raise RuntimeError("the video file is no longer stored on the gateway "
                               "(evicted) — ask the user to send it again")
        path = f"/workspace/media/{sha}{local.suffix}"
        res = await _exec(f"test -f {shlex.quote(path)} && stat -c %s {shlex.quote(path)}",
                          30, "Check whether the video is already in the sandbox")
        if res.get("exit_code") != 0 or (res.get("stdout") or "").strip() != str(local.stat().st_size):
            async def chunks():
                with open(local, "rb") as fh:
                    while True:
                        b = fh.read(1 << 20)
                        if not b:
                            break
                        yield b
            async with _client() as c:
                r = await c.put(f"/vms/{await _vm_id()}/upload", params={"path": path},
                                content=chunks())
                r.raise_for_status()
                if r.json().get("sha256") != sha:
                    raise RuntimeError("video upload to the sandbox was corrupted")
        in_vm[sha] = path
        return path

    def _workdir(sha: str) -> str:
        return f"/workspace/media/{sha[:12]}"

    def _seq() -> int:
        """A per-turn counter so each sheet / frame batch gets its own output."""
        state["video_seq"] = state.get("video_seq", 0) + 1
        return state["video_seq"]

    async def _show(sha: str, imgs: list[bytes], text: str, caption: str, times: list[float]):
        """Frames to the model — as pixels if it can see, else described."""
        b64s = [base64.b64encode(b).decode() for b in imgs]
        state.setdefault("videos_watched", set()).add(sha)
        if state.get("vision", True):
            return ToolResult(text=text, images=b64s, caption=caption)
        stamps = ", ".join(_fmt_t(t) for t in times)
        prompt = (
            f"These images are frames from a video ({caption}); timestamps: {stamps}. "
            "Describe what is visible and what changes between them, citing timestamps. "
            "Be concrete and brief."
            + (f" The user asked: {state['user_request'][:500]}" if state.get("user_request") else "")
        )
        try:
            described = await image_store.vision_call_many(b64s, prompt)
        except Exception as e:  # noqa: BLE001
            described = f"(couldn't describe the frames: {e})"
        return f"{text}\n\nWhat the frames show (described by the vision model, since you can't see images):\n{described}"

    def _spend(frames: float) -> str | None:
        if budget["calls"] <= 0 or budget["frames"] < min(frames, 1):
            return ("[video budget for this turn is used up] Answer from what you've already "
                    "seen; the user can ask a follow-up to look further.")
        return None

    # ---- tools ----------------------------------------------------------------

    async def video_probe(video_id: str) -> str:
        rec = await _resolve(video_id)
        if rec is None:
            return f"[error] no video matching '{video_id}'."
        sha = rec["sha"]
        try:
            path = await _ensure_in_vm(sha)
            p = await _json_exec(f"vn-video probe {shlex.quote(path)}", 600, "Analyze the attached video (probe)")
        except Exception as e:  # noqa: BLE001
            return f"[error] couldn't load the video: {e}"
        probes[sha] = p
        state.setdefault("videos_watched", set()).add(sha)
        w, h = p.get("width"), p.get("height")
        orient = "portrait" if h and w and h > w else ("landscape" if w and h and w > h else "square")
        cuts = p.get("scene_cuts") or []
        segs = p.get("segments") or []
        seg_txt = " · ".join(f"{s['kind']} {_fmt_t(s['start_s'])}–{_fmt_t(s['end_s'])}" for s in segs[:14])
        sug = p.get("suggested") or {}
        ov = sug.get("overview") or {}
        busy = sug.get("busiest_segments") or []
        lines = [
            f"vid_{sha[:8]}: {_fmt_t(p.get('duration_s', 0))} ({p.get('duration_s')} s), {w}×{h} {orient}, "
            f"{p.get('fps')} fps, {p.get('codec')}, audio: {'yes' if p.get('has_audio') else 'no'}.",
            f"Scene cuts ({len(cuts)}): " + (", ".join(_fmt_t(c) for c in cuts[:30]) or "none"),
            f"Motion (buckets of {(p.get('motion') or {}).get('bucket_s', 1)} s): {seg_txt or 'n/a'}",
            f"Suggested: an overview sheet of {ov.get('count', 12)} frames "
            f"{'at scene cuts' if ov.get('use_scenes') else 'evenly spaced'} ({ov.get('why', '')}); "
            + ("then dense frames in the busiest stretches: "
               + ", ".join(f"{_fmt_t(b['start_s'])}–{_fmt_t(b['end_s'])}" for b in busy)
               + " (4-8 fps)." if busy else "then zoom into whatever the sheet shows matters."),
            f"Budget this turn: {int(budget['frames'])} frames over {budget['calls']} calls.",
        ]
        return "\n".join(lines)

    async def video_overview(video_id: str, count: int = 12, use_scenes: bool | None = None,
                             start_s: float | None = None, end_s: float | None = None):
        rec = await _resolve(video_id)
        if rec is None:
            return f"[error] no video matching '{video_id}'."
        sha = rec["sha"]
        count = max(4, min(int(count or 12), 20))
        over = _spend(math.ceil(count / 2))
        if over:
            return over
        try:
            path = await _ensure_in_vm(sha)
            probe = probes.get(sha)
            dur = float((probe or {}).get("duration_s") or rec.get("duration_s") or 0)
            lo = max(0.0, float(start_s or 0))
            hi = min(dur, float(end_s)) if (end_s and dur) else (float(end_s) if end_s else dur)
            if use_scenes is None:
                use_scenes = bool(((probe or {}).get("suggested") or {}).get("overview", {}).get("use_scenes"))
            args = ["--count", str(count), "--cell", "320"]
            if use_scenes and probe and probe.get("scene_cuts"):
                # Pick the cut times here (from the cached probe) so the sheet
                # doesn't re-decode the whole video to find them again.
                cuts = [lo] + [c for c in probe["scene_cuts"] if lo < c < (hi or 1e9)]
                if len(cuts) < count and hi:
                    span = hi - lo
                    cuts += [lo + (i + 0.5) * span / (count - len(cuts)) for i in range(count - len(cuts))]
                cuts = sorted(set(round(c, 2) for c in cuts))
                if len(cuts) > count:
                    step = len(cuts) / count
                    cuts = [cuts[int(i * step)] for i in range(count)]
                args += ["--times", ",".join(f"{c:.2f}" for c in cuts)]
            else:
                if start_s is not None:
                    args += ["--start", f"{lo:.2f}"]
                if end_s is not None:
                    args += ["--end", f"{float(end_s):.2f}"]
            out = f"{_workdir(sha)}/sheet-{_seq()}.jpg"
            res = await _json_exec(
                "vn-video sheet " + shlex.quote(path) + " --out " + shlex.quote(out) + " "
                + " ".join(shlex.quote(a) for a in args),
                600, "Make a contact sheet of video frames",
            )
            img = await _raw(res["sheet"])
        except Exception as e:  # noqa: BLE001
            return f"[error] couldn't make the overview: {e}"
        cells = res.get("cells") or []
        budget["calls"] -= 1
        budget["frames"] -= math.ceil(len(cells) / 2)
        times = [c["t"] for c in cells]
        span = f"{_fmt_t(times[0])}–{_fmt_t(times[-1])}" if times else ""
        text = (f"Contact sheet of vid_{sha[:8]}: {len(cells)} frames ({res.get('cols')}×{res.get('rows')} grid, "
                f"read left→right, top→bottom): "
                + ", ".join(f"#{c['n']} {c['label']}" for c in cells)
                + f". Budget left: {int(budget['frames'])} frames, {budget['calls']} calls.")
        return await _show(sha, [img], text, f"contact sheet, {len(cells)} frames {span} of vid_{sha[:8]}", times)

    async def video_frames(video_id: str, times: list | None = None, start_s: float | None = None,
                           end_s: float | None = None, fps: float | None = None,
                           every_s: float | None = None):
        rec = await _resolve(video_id)
        if rec is None:
            return f"[error] no video matching '{video_id}'."
        sha = rec["sha"]
        if not times and (start_s is None or end_s is None):
            return "[error] give `times`, or `start_s` and `end_s` (with `fps` or `every_s`)."
        cap = int(min(_MAX_PER_CALL, budget["frames"]))
        over = _spend(1)
        if over:
            return over
        try:
            path = await _ensure_in_vm(sha)
            outdir = f"{_workdir(sha)}/frames-{_seq()}"
            args = ["--outdir", outdir, "--size", "768", "--max", str(cap)]
            if times:
                ts = times if isinstance(times, list) else str(times).split(",")
                args += ["--times", ",".join(f"{float(t):.3f}" for t in ts)]
            else:
                args += ["--start", f"{float(start_s):.3f}", "--end", f"{float(end_s):.3f}"]
                if fps:
                    args += ["--fps", f"{float(fps):g}"]
                elif every_s:
                    args += ["--every", f"{float(every_s):g}"]
            res = await _json_exec(
                "vn-video frames " + shlex.quote(path) + " " + " ".join(shlex.quote(a) for a in args),
                600, "Extract video frames to look at",
            )
            frames = res.get("frames") or []
            imgs = [await _raw(f["path"]) for f in frames]
        except Exception as e:  # noqa: BLE001
            return f"[error] couldn't extract frames: {e}"
        budget["calls"] -= 1
        budget["frames"] -= len(frames)
        ts = [f["t"] for f in frames]
        span = f"{_fmt_t(ts[0])}–{_fmt_t(ts[-1])}" if ts else ""
        note = (f" (asked for {res.get('requested')}, showing {len(frames)} spread across the range — "
                "narrow the window or lower the rate for consecutive frames)") if res.get("capped") else ""
        text = (f"{len(frames)} frames of vid_{sha[:8]} at "
                + ", ".join(f["label"] for f in frames) + note
                + f". Budget left: {int(budget['frames'])} frames, {budget['calls']} calls.")
        return await _show(sha, imgs, text, f"{len(frames)} frames, {span} of vid_{sha[:8]}", ts)

    return [
        (PROBE_SCHEMA, video_probe),
        (OVERVIEW_SCHEMA, video_overview),
        (FRAMES_SCHEMA, video_frames),
    ]


__all__ = ["build_tools"]
