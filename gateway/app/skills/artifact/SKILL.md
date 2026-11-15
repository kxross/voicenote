---
id: artifact
description: Deliver a long-form document ("artifact") to the app's full-screen reader instead of a chat reply — research, writeups, reports, structured breakdowns, step-by-step guides, anything beyond a few short sentences. Load whenever the answer is long-form prose or a structured document ("write up", "research", "report", "summary of…", "guide", "explain in depth", "draft", "notes on…", a document the user will read rather than glance at).
---

You can deliver a **long-form document** ("artifact") that shows in the app as a
rendered, full-screen reader — not a chat bubble. Use it whenever your answer is
substantial: research, a writeup, a report, a structured breakdown, a how-to,
notes, a draft. For short, glanceable answers, just reply in text and do NOT
load or use this.

Call `publish_artifact(title, text)`, then write a **one or two sentence
`reply`** that acts as a lead-in — e.g. "Here's the research on X — I've opened
the full writeup below." The body lives **only** in the artifact; do **not**
repeat it in the reply. The chat stays clean; the document goes to the reader.

You may publish more than one artifact in a turn (e.g. two separate reports);
each call adds a document.

## Revising an existing document

When the user asks to change a document already in the thread ("add a section
on…", "fix the table", "make it shorter", "update the numbers", "add sources
for…"), **revise it in place** — the user wants their document updated, not a
second copy. The DOCUMENTS index in your context lists the thread's documents
with their ids.

1. `read_artifact(id)` — get the exact current markdown.
2. `edit_artifact(id, edits)` — targeted `{find, replace}` edits. Copy each
   `find` exactly from what you read, with enough context to match one place.
   To add a section, `find` the heading that should follow it (or use an empty
   `find` to append at the end) and include the new text in `replace`.
3. For a wholesale rewrite (restructure, change of angle), call
   `publish_artifact(title, text, replaces=id)` with the full new body.

Keep what the user didn't ask to change, including its `[n]` citations — they
stay valid (`read_artifact` gives you the current numbering, and new
`web_search` results continue it). Do only the new research the change needs.
Then reply in one sentence with what changed. Publish a **new** document only
when the user wants a separate one.

## When to use it

- **Use** for anything the user will *read*: multi-paragraph explanations,
  research findings, comparisons with reasoning, reports, summaries of gathered
  material, structured guides, drafts of documents.
- **Don't use** for quick answers, confirmations, a single fact, a short list,
  or a status update — those belong in the plain `reply`.
- If you used the `websearch` or `vm` skills to gather material, the synthesized
  writeup is almost always an artifact, not a wall of text in the reply.

## The dialect — GitHub-flavored markdown

Write the body as **GitHub-flavored markdown**. Supported and encouraged:

- **Headings** (`#`, `##`, `###`) to structure the document. Start at `#` for
  the document's own sections; the title is shown separately, so you don't need
  to repeat it as an `#` heading.
- **Tables** (pipe syntax) — great for comparisons and structured data.
- **Fenced code blocks** with a language tag for syntax highlighting.
- **Lists** (ordered/unordered, nested), **blockquotes**, **bold/italic**,
  **links**.
- **Mermaid diagrams** — a fenced block tagged `mermaid`:
  ````
  ```mermaid
  flowchart TD
    A[Start] --> B{Decision}
    B -->|yes| C[Do it]
    B -->|no| D[Skip]
  ```
  ````
  Use for flowcharts, sequence diagrams, timelines, simple graphs. The reader
  is a **phone**: prefer top-down (`flowchart TD`) layouts, keep node labels
  short (a few words; put detail in the prose), and split a big diagram into
  two small ones rather than one wide one.
- **Math** via KaTeX: inline `$…$` and display `$$…$$`, e.g.
  `$E = mc^2$` or `$$\int_0^1 x^2\,dx = \tfrac{1}{3}$$`. No space just inside
  the dollar signs (`$x$`, not `$ x $`).
- **Money** is written normally — "$4.9B revenue", "$1,200/mo". The reader
  tells currency from math, so you don't need to escape it.

## Before you publish — validate the markdown

The body is rendered as markdown in the reader, so malformed syntax shows up as
broken output (a half-rendered table, a code block that swallows the rest of the
document, raw `$` or `|` characters). **Before calling `publish_artifact`, re-read
the body you wrote and confirm every one of these, fixing any that fail:**

- **Code fences are balanced** — every opening ``` has a matching closing ```.
  For a fenced block that itself contains ``` (e.g. you're showing a markdown or
  mermaid snippet), wrap the OUTER block in four backticks ````. Mismatched
  fences are the most common failure — they make everything after them render as
  code.
- **Tables are well-formed** — a header row, a separator row of `|---|---|` with
  the same number of columns, and every data row with that same column count.
  Escape any literal `|` inside a cell as `\|`.
- **Math is closed** — every math `$…$` is paired and every `$$` block is
  closed. (Dollar amounts are fine as-is.)
- **Mermaid parses** — the diagram type is on the first line (`flowchart LR`,
  `sequenceDiagram`, …), node ids are simple, and labels with special characters
  are quoted. A broken diagram renders as an error box, so keep it simple.
- **Headings** start a line with `#` + a space; **lists** use consistent markers
  with a space after; nested items are indented in multiples of two spaces.
- No stray unescaped `<`, `>`, or `&` meant as literal text (use `\<` etc. or a
  code span), and no half-finished link `[text](` syntax.

If anything is off, correct the body and only then publish. A clean render is
part of the deliverable — don't ship markdown you haven't checked.

## Writing style

- Lead with the point. Structure with headings so the reader can skim. Prefer
  tables over long comma-separated sentences when comparing things.
- **Cite your sources** — it's how the reader checks the research:
  - Results from `web_search` are numbered; cite them inline right after the
    claim: "The market reached $4.2B in 2025 [3]." Several: "[2, 5]".
  - For a page you read some other way (the sandbox browser, `curl`), cite it
    as a markdown link: "according to [the EIA's 2026 outlook](https://…)".
  - **Don't write your own Sources / References list** — the reader renders
    one automatically from your citations (numbered, linked, with the domain),
    and the inline `[n]` markers become tappable links to it.
  - If a figure came from your own computation, say so; never invent a
    citation or a number.
- The reader renders in a clean, monochrome theme (dark and light); don't try to
  set colors or styles — just write good markdown and it will be styled for you.
- Keep images out for now (Phase 1 is text/diagrams/math only); describe a chart
  in a `mermaid` diagram or a table instead of embedding an image.
