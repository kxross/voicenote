---
id: schedules
description: Set up or change the user's recurring scheduled runs — a cron job that fires an agent with a prompt on a repeating schedule (e.g. "every weekday at 8am summarize my unread", "remind me every Sunday evening", "each morning check the news"). Load when the user asks to schedule, automate, or recur something, or to see/change what's scheduled. Proposing a change always needs the user's approval.
---

You can set up **recurring schedules** for the user: a cron job that fires an
agent with a prompt on a repeating cadence. Use this when the user asks to
schedule, automate, repeat, or get a recurring reminder/digest — or to review
or change what they already have scheduled.

## Two tools

- **`list_schedules()`** — read-only; shows what's set up (with each schedule's
  id). Call it to answer "what do I have scheduled?" and ALWAYS before proposing
  an **edit**, so you have the id.
- **`propose_schedule(action, title, cron, prompt, …)`** — drafts a create or
  edit. **This does not create anything** — it sends a draft to the user's phone
  for approval, and the schedule only takes effect when they tap Approve.

## Always: draft, describe, and let the user approve

Every create/edit goes through approval. After calling `propose_schedule`:

1. **Describe the draft in your reply** — the plain-English time and what it will
   do, e.g. *"I've drafted a schedule: every weekday at 8:00 AM the assistant
   will summarize your unread. Approve it on your phone to turn it on."*
2. Make clear it is **not active yet** and needs their approval. Don't claim it's
   scheduled or done.

## Writing the cron

5 fields: `minute hour day-of-month month day-of-week` (day-of-week 0=Sun … 6=Sat).

- Every weekday at 8:00 → `0 8 * * 1-5`
- Every day at 9:30 PM → `30 21 * * *`
- Every Sunday at 9:00 AM → `0 9 * * 0`
- 1st of each month at 7:00 → `0 7 1 * *`
- Every hour at :15 → `15 * * * *`

## Which agent

- `assistant` (default) — the general skill-routing assistant; use for most things.
- `list_manager` — focused on list/todo upkeep.

## Timezone

- Default `tz_mode="device"` — the time follows the user as they travel ("8am"
  stays 8am wherever they are). Use `tz_mode="fixed"` with an IANA `timezone`
  (e.g. `America/New_York`) only if they want it pinned to one zone.
- Omit `timezone` to use their device's current zone.

## When NOT to use this

- A one-off ("remind me at 3pm today") is not a recurring schedule — handle it
  in the moment, don't create a cron job.
- Don't propose a schedule the user didn't ask for.
