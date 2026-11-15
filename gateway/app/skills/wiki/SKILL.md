---
id: wiki
description: Your learned wiki — reusable formats, procedures, and standing preferences distilled from past chats and APPROVED by the user. Consult it before re-deriving something you may have established before; propose saving new reusable knowledge when a chat produces it. Distinct from built-in skills; grows over time.
---

You have a **learned wiki**: a user-owned library of reusable knowledge — a
deliverable's format, a multi-step procedure you worked out together, a standing
preference ("always use tables, not prose"). It is built up over time, and every
entry in it was **approved by the user**. It is separate from your built-in skills.

## Use it — don't re-derive what you've learned

- Before doing work that might repeat something established before (a report you've
  produced, a format you've agreed on), check the **WIKI INDEX** in your context and
  call **`wiki_search(query)`** to pull the relevant entry, then apply it directly.
  This is the whole point: skip the back-and-forth you already had once.

## Propose new knowledge — never save silently

When a conversation **establishes something reusable** the user will want again — a
format you settled on, a procedure you refined over several turns, a preference they
stated — call **`propose_wiki_entry(...)`**. This does NOT save it: it drafts a
proposal the user approves on their phone (they see it as a card). Tell them what you
captured and that they can approve it.

- `title` — a short, searchable name ("Weekly ops report format").
- `when_to_use` — one line on when this entry applies ("producing the weekly ops
  report"). This is what future-you matches against.
- `body` — the actual knowledge/procedure, in markdown. Be concrete and complete so
  it stands alone next time.
- `kind` — "procedure" (a how-to / workflow) or "knowledge" (facts / preferences).

## Evolve existing entries, don't duplicate

If `wiki_search` shows an entry that **already covers this** but is incomplete or
out of date — e.g. you applied it and the user wanted something added — propose an
**amendment**: call `propose_wiki_entry` with that entry's `entry_id` (the id shown
in the WIKI INDEX / search results) and the improved `body`. The user sees a **diff**
of just the change. Prefer amending over creating a near-duplicate.

Only propose genuinely reusable things — not one-off answers or trivia.
