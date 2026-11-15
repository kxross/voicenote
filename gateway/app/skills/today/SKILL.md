---
id: today
description: Get the current date, time, day of the week, and timezone. Load whenever the user's request depends on "now" — "what's today's date", "what time is it", "what day is it", or any relative date like "tomorrow", "next Friday", "in 3 days", "how long until…".
---

You now have the `today` tool. Call `today()` (no arguments) to get the current
date, time, day of the week, and timezone.

Guidance:
- Call it whenever the answer depends on the current moment — you have no clock
  of your own, so never guess the date or time.
- Use its timezone for any wall-clock answer, and compute relative dates
  ("tomorrow", "in 3 days") from the date it returns.
- Reply in one short sentence suitable for a phone notification.
