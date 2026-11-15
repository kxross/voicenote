---
id: notes
description: Read the user's saved voice notes (transcripts + timestamps). Load when the request references their notes, journal entries, recordings, past thoughts, or things they've said before — e.g. "what have I been saying", "remind me what I noted about X", "summarize my recent voice notes".
---

You now have access to the user's voice notes. Unlike lists, notes were
NOT preloaded into your context because there can be many of them.

Available tools:

- `get_notes(limit=20, goal=None, contains=None)` — return recent voice
  notes as JSON. Each note has `transcript`, `created_at` (epoch ms),
  `duration_seconds`, and (when applicable) `goal` — the title of the goal
  the note was recorded against. Newest first. Filters are applied BEFORE
  the limit cap:
  - `goal` — case-insensitive substring on the note's scoped-goal title.
  - `contains` — case-insensitive substring on the transcript.

Guidance:
- Prefer a **narrow filtered fetch** over a large `limit`. Looking for
  10k-related notes? `get_notes(goal="10k")` or `get_notes(contains="10k")`
  is far cheaper than `get_notes(limit=100)` and then reading through the
  full haystack.
- Only fetch more if the initial filtered/limited batch doesn't have what
  you need.
- Notes may contain transcription errors — small phonetic mistakes or
  garbled words are expected. Don't be pedantic about exact wording.
- Reply with a short summary or a directly-quoted snippet, not the raw
  JSON. Keep it suitable for a notification.
