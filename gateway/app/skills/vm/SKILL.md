---
id: vm
description: Run code and shell commands on a real, persistent Linux machine — process files, analyze images/video, write and build software, or anything a computer with a terminal and internet can do. Load whenever the request needs actual execution or verification rather than just an answer — phrases like run, build me, analyze this file, extract, convert, test, does this work, or write a script that…, or any task where you'd otherwise be guessing at output you could instead produce.
---

You have a **persistent Linux machine** (a sandbox) you drive with bash. Use it
to *do* the work and *verify* it, not to describe what you would do.

It's one machine, always there — you don't start, pick, or manage it. Just run
commands; it spins up on first use. `/workspace`, installed packages, and files
**persist between commands and across turns**, so build on what's already there.

## The tools

- **`vm_exec("…")`** — run a bash command; get stdout, stderr, exit code. Your
  main tool. Chain with `&&` / `;`. No `vm_id` needed. Pass a short `purpose`
  ("Installing dependencies", "Running the test suite", "Fetching the SEC
  filing") on non-trivial commands — it becomes the live status the user sees on
  their phone, which is far clearer than a bare "Running a command".
- **`vm_write_file(path, content)`** — write a source file or script (cleaner
  than echo/heredoc).
- **`vm_read_file(path)`** — read a file's contents back.
- **`vm_install(packages, manager?)`** — install deps when something's missing
  (the box has internet): `pip` (default), `apt`, or `npm`.

The machine already has python3/pip/venv, node/npm, and typescript — so most
Python and JS/TS work needs no setup.

**Media tools are pre-installed** — `ffmpeg`/`ffprobe`, numpy, Pillow and OpenCV
(`cv2`), so image/video processing needs no installs. For a video the user
attached, use the `video` skill (it loads the file here and shows you frames)
rather than processing it by hand.

**Headless browser is pre-installed** — Playwright + Chromium, ready to use (do
NOT install it). Use it to render JS-heavy pages, screenshot, scrape, export
PDFs, or automate forms. It runs as root, so launch Chromium with
`--no-sandbox`:

```python
from playwright.sync_api import sync_playwright
with sync_playwright() as p:
    b = p.chromium.launch(args=["--no-sandbox"])   # required (root)
    pg = b.new_page(); pg.goto("https://example.com")
    print(pg.title()); pg.screenshot(path="/workspace/shot.png")
    b.close()
```

For a plain fetch (no JS), prefer `requests`/`httpx` — faster than a browser.
To **find** pages, use the `web_search` tool first (load the websearch skill) —
don't navigate to Google/Bing, they block automation. Search finds the URL; the
browser is for reading, scraping, or screenshotting a page you already have.

## Working directory discipline

Keep a clean, persistent artifact trail: every task gets its own run folder.
**Start a task by calling `vm_new_run(title, goal)`** — it creates the folder and
returns its path. (Skip it only for a trivial one-off like evaluating an
expression; use it for anything that produces files or takes multiple steps.)

```
/workspace/runs/<run_id>/
  GOAL.md     — the goal, the outcome, and the file list (update when done)
  PLAN.md     — the step checklist (written by vm_checkpoint)
  STATE.json  — the machine-readable checkpoint (written by vm_checkpoint)
  work/       — scripts, scratch files, intermediate output
  output/     — final deliverables only
```

- Write source/scripts and intermediate artifacts under `work/`.
- Put only finished deliverables in `output/`.
- **Before you finish, update `GOAL.md`**: fill in the Outcome, and list each
  file in `output/` with a one-line description.
- Reference files by full path (e.g. `<run>/output/report.csv`) and tell the
  user what you produced and where.

### Checkpoint as you go (so a long run can resume)

Long or research-heavy runs may hit a tool-call limit mid-task. The run **resumes
from its checkpoint**, so keep the checkpoint current:

- **First action of any turn on an existing run:** read `STATE.json` (and
  `PLAN.md`) and continue from its `next`. Do **not** call `vm_new_run` again,
  and don't redo completed steps — trust the checkpoint and the files already in
  `work/`/`output/`.
- **After each meaningful step:** call
  `vm_checkpoint(step, next_step, plan=[...])`. Pass the full ordered `plan` on
  the first checkpoint (and whenever it changes); afterwards just advance `step`
  (the count of steps now done) and set `next_step`. This also shows live
  progress on the user's phone.
- **On the final step:** `vm_checkpoint(step=<total>, next_step="", status="complete")`,
  update `GOAL.md`, then (if the whole request is satisfied) call `resolve_thread`.
- If you are told you are **resuming run `<run_id>`**, `cat` its `STATE.json`
  first and pick up at `next`.

## Keep context small — save, then extract

Page HTML and command output can be huge; dumping it into your reply or reading
it all back wastes the context window. Treat the machine's filesystem as your
working memory:

- **Save raw output to a file in `work/`, don't print it.** Fetching a page →
  write the HTML/JSON to `work/page.html`, not stdout. Screenshots and big
  artifacts go to files, never into context.
- **Extract only what you need** from the file with shell tools — `grep`, `sed`,
  `awk`, `jq` (JSON), or Python (BeautifulSoup / CSS selectors) — and print just
  the matched lines or parsed values.
- **Bound every `vm_exec` output**: pipe through `head`, `tail`, `wc -l`, or
  `grep` so stdout stays small (you see that output — keep it to what matters).
- With the browser, grab targeted text (`page.inner_text(selector)`,
  `locator(...).all_text_contents()`) or save `page.content()` to a file and
  parse it — don't return the whole DOM.
- **Strip boilerplate before reading** a page — run saved HTML through
  readability/BeautifulSoup to get the main text, dropping nav/scripts/styles.
- **Paginate deliberately**: fetch → extract → decide next, one page at a time;
  write multi-row scrapes straight to a CSV in `output/`, not into context.
- **Summarize, don't echo**: report the conclusion and the file path; never paste
  a large result back into your reply.
- **Cite the pages you read.** Pages a command fetches (curl, requests,
  Playwright `page.goto`, `read_csv` of a URL…) are recorded as sources
  automatically: the command's output ends with "sources recorded: [n] url".
  Cite a finding from one by that `[n]`, exactly like a `web_search` result —
  "sales rose 12% [7]". Put the page's URL literally in the command or script
  (not only built up at runtime) so it can be captured; a page that wasn't
  captured can still be cited as a markdown link — "per
  [the vendor's pricing page](https://example.com/pricing)". Either way it lands
  in the user's linked **Sources** list; don't write your own sources list.
- **Recall past work by grepping the trail** (`/workspace/runs/**`) instead of
  re-fetching or re-deriving something you already did.
- Re-use saved files across steps instead of re-fetching (the machine persists).

Example: `curl -s https://… > work/page.html` → inspect structure with
`grep -oE '<h[12][^>]*>[^<]+' work/page.html | head` → refine the selector →
extract with Python into `output/data.csv`. You pulled a big page in once and
only the few values you needed ever touched your context.

## Rules that matter

- **Verify before you claim success.** "It builds" means you ran the build and
  saw exit_code 0. "It works" means you ran it. Never report a result you
  didn't produce — the whole point of the machine is that you can check.
- **Read the exit code and stderr.** A non-zero exit is a failure to fix, not
  to narrate past. Iterate: read the error, adjust, re-run.
- **Discover what you need.** If a command isn't found or an import fails,
  `vm_install` it and continue — don't ask the user to.
- **The user can't see the machine.** Bring results back into your reply: paste
  the key output, the final code, or a summary of what you verified.
- **A command may pause for approval.** If approvals are on, `vm_exec` just
  takes longer while the user approves — that's normal, wait for the result.

## Example — "does this Python sort correctly?"

`vm_write_file("/workspace/s.py", "<their code + a few asserts>")` →
`vm_exec("python3 /workspace/s.py")` → read exit_code/stderr → if it fails, fix
and re-run; only then report, pasting the output you actually saw.
