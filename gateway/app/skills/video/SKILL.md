---
id: video
description: Watch a video the user attached — probe it, look at a contact sheet, then zoom into the moments that matter with targeted frames, and say what happens with timestamps. Load whenever the message says a video is attached (vid_…) or the user asks what's in / happens in a video.
---

You can **watch** a video the user attached (the message lists it as
`vid_xxxxxxxx` with its length). The video is decoded in your sandbox; you look
at sampled frames as images. Timestamps are burned into every frame.

## Method — coarse to fine

1. **`video_probe(video_id)`** — always first. Gives duration, fps, orientation,
   scene cuts, a motion profile (static / moderate / high stretches) and a
   suggested plan. No frames, cheap.
2. **`video_overview(video_id)`** — one contact sheet of ~12–16 frames across
   the whole video (at scene cuts when there are many, else evenly). Read it
   left→right, top→bottom; each tile is labelled `#n mm:ss.s`. From it, decide
   which moments matter for the user's question.
3. **`video_frames(…)`** — zoom in. Choose the rate from the motion and the
   question:
   - **static** stretch → one frame (`times=[…]`) is enough;
   - **slow / moderate** action → 1–2 fps over the stretch;
   - **fast** action (a fall, a hand movement, a ball) → 4–8 fps over a *short*
     window (1–2 s), or explicit `times` around the moment;
   - to compare before/after, pass both timestamps in `times`.
   At most 8 frames per call; if more were asked for they're spread across the
   range — narrow the window for consecutive frames.
   You can also call `video_overview` on a sub-range (`start_s`/`end_s`) to scan
   a long stretch cheaply before zooming further.
4. **Stop** as soon as you can answer. Don't sample the whole video densely.

**Budget per turn:** about 48 frames over 6 frame calls (a contact-sheet tile
counts as half a frame). Each tool reports what's left. Only the latest batch of
frames stays attached — earlier ones are dropped from view, so note what you saw
before moving on.

## Answering

- Describe **what happens and when**, citing timestamps ("at 00:12.5 the dog
  jumps the fence"). Give a short chronological account for "what happens" asks;
  answer directly for a specific question.
- Say what you **couldn't** determine (too dark, off-frame, too fast between
  frames) rather than guessing — and offer to look closer.
- Don't narrate every frame back; summarize.
- There is **no audio** analysis yet — you only see frames. If the question needs
  what was said, say so.
- If the user asks again about a video you already watched, the message carries
  a "previously watched" summary — use it, and only re-watch if they need more.
