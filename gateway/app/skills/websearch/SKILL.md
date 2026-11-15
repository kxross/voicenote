---
id: websearch
description: FIND current or factual information on the web and locate the right sources — news, events, prices, documentation, "what is", "look up", "search for", "find online", or anything that may have changed since training. Fast, cheap, read-only discovery. Load when the request needs external/current info you're not confident about. To then READ a specific page in full (JS-rendered sites, scraping, screenshots, forms/login), use the vm skill's headless browser — search finds the page, the browser reads it.
---

Web search is for **discovery** — finding relevant pages and quick current
facts. Call `web_search(query)` with a focused query (optionally `max_results`,
1–10). It returns the top results as title + URL + snippet, plus a synthesized
answer when available, via Tavily.

## When to use this vs. the browser

- **Use `web_search`** to find things or get a quick current fact — it's fast,
  needs no sandbox, and is the right default for "look up / find / what is".
- **Escalate to the `vm` skill's headless browser** when you need the *full*
  content of a specific page, a JS-rendered site, structured scraping, a
  screenshot, or to interact (forms, login, multi-step). Typical flow: **search
  here to find the URL, then open it in the browser.**
- Do **not** try to "search" by opening Google/Bing in the browser — they block
  automation. Searching is this tool's job.

## Guidance

- Search only when the user actually needs external or current information.
  Small talk, opinions, and things already in your context get no search.
- Prefer one well-formed query over several. Refine and retry only if the first
  result set is empty or clearly off-target.
- If a snippet already answers the question, you're done — don't open a browser.
- Summarize the findings in one or two short sentences suitable for a phone
  notification. Never paste the raw result block back to the user.

## Citing sources

Every result comes back numbered — `[3] Title / URL / snippet`. Those numbers
are stable for the whole turn (a repeat result keeps its number), and every
result is shown to the user as a linked **Sources** list under your reply.

- **Cite inline with the number** right after the claim it supports:
  "Revenue grew 12% in Q3 [3]." Several at once: "[2, 5]".
- Cite only sources you actually used; uncited results still appear, labelled
  "also consulted".
- Don't paste raw URLs into the reply — the user gets the links from the list.
  Never invent a number or a source.
- Read-only: it fetches from the web and changes nothing on the user's device.
