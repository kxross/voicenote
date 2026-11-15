# agent-gateway architecture

A one-stop reference for the shape of this project. Every claim here
should point at real code — if you find a mismatch, the code is right
and this doc is stale.

---

## 1. Guiding principles

- **Client-agnostic surface.** Everything you can do from outside goes
  through OpenAI-compatible endpoints (`POST /v1/chat/completions`,
  `GET /v1/models`). Any OpenAI SDK works. The `model` field the client
  sends is an *agent id*, not a real LLM.
- **Provider-agnostic inference.** Which real LLM backs an agent is a
  server-side choice across three providers — local **Ollama** (default),
  local **vLLM** (optional; continuous batching for concurrency + a large
  context window), and **OpenRouter** (optional, key-gated). The client
  may *request* a specific LLM for a turn (see `GET /v1/llm-models`), but
  it never talks to a provider directly, and the `assistant` runtime
  *guards* the pick: a turn vLLM can't serve — an image attachment, or a
  preloaded context larger than vLLM's window — transparently falls back
  to the vision-capable, big-window local Ollama default (§3.1).
- **Framework-agnostic core.** There is exactly one abstraction agents
  implement: `BaseAgentRuntime.arun()` in
  [`app/agent_base.py`](../app/agent_base.py). Ollama, OpenAI-wire,
  LangGraph, Claude SDK — all sit behind the same interface.
- **Registry-driven extension.** Adding a new agent, a new skill, or a
  new push lane never touches `main.py`. You drop a file in the right
  place and re-import.
- **Two directions of traffic.** Request/response (client → gateway) is
  the OpenAI surface. Push (gateway → client) is the newer path — a
  DB-backed scheduler produces messages, a pluggable pusher fans them out
  to polling and (eventually) FCM.
- **State-locality: client owns, gateway caches.** The Android app's
  Room DB is authoritative for lists, notes, goals, threads, artifacts,
  and conversation history. The gateway holds only a *cached* snapshot of
  user state (for scheduled runs to reason about), a delivery queue, an
  image store, and the live `schedules` table. Anything the model wants
  to change on the phone is emitted as ops the client applies.

---

## 2. Registries

Two independent registries live at import time. Both are process-wide
singletons populated as a side effect of importing definition modules.

### 2.1 Agent registry — [`app/registry.py`](../app/registry.py)

```
id → AgentConfig{ runtime: BaseAgentRuntime, skills: list[str], description }
```

Populated by [`app/registry_service.py::rebuild()`](../app/registry_service.py),
which composes the live registry from the **code built-ins** in
[`app/agents/definitions.py::builtin_agents()`](../app/agents/definitions.py)
plus a **DB overlay** (custom agents / overrides edited through the `/admin`
UI), and atomically hot-swaps it — no restart. Built-ins stay in code (the
base that can't be bricked). Currently built-in:

| id | Runtime | Notes |
|---|---|---|
| `qwen-chat` | `OllamaRuntime` | plain chat, no tools |
| `qwen-tools` | `OllamaRuntime` | live Tavily `web_search` + a `code_interpreter` placeholder |
| `test_model` | `OllamaRuntime` | round-trip smoke test |
| `list_manager` | `ListManagerRuntime` | wraps output as `{reply, ops}`; kept for direct API testing, **not called by the Android app anymore** |
| `assistant` | `SkillfulOllamaRuntime` | progressive-disclosure skills (lists, notes, goals, images, websearch, today, canvas, artifact, schedules, vm, wiki) — **the Android app's single intake agent**; `think=True` overrides the global `OLLAMA_THINK` default because the multi-step skill+tool loop is measurably more reliable with thinking on. (Built-in sub-agent orchestration exists but is pinned off pending the vLLM delegation path.) |

`research-agent` (LangGraph) and `claude-json-agent` (Claude SDK) are
present in `definitions.py` but commented out.

### 2.2 Skill registry — [`app/skill_registry.py`](../app/skill_registry.py)

```
id → Skill{ description, body, build_tools }
```

Populated by [`app/skills/__init__.py::load_all_skills()`](../app/skills/__init__.py),
which scans `app/skills/*/SKILL.md`. Tool names are validated
globally-unique across skills at load — a collision fails startup.
Currently registered (all are in the `assistant`'s scope):

| id | folder | Purpose |
|---|---|---|
| `lists` | `lists/` | read + mutate the user's named lists (groceries, workout, etc.) |
| `notes` | `notes/` | read the user's voice-note transcripts (loaded on demand — notes can be many) |
| `goals` | `goals/` | extract goals from notes → draft first plan → refine from feedback. Goals are flat; decomposition = one level of nested plan subtasks. Plan approval == goal activation. Approved plans guarded against silent overwrite. |
| `images` | `images/` | escalation ladder over attached photos: cached caption text → targeted vision call |
| `websearch` | `websearch/` | live web search via Tavily (`TAVILY_API_KEY`); records every result as a numbered per-turn source the model cites inline as `[n]` (§6.3 `sources`); returns a clear "not configured" message when the key is unset |
| `today` | `today/` | current date / time / weekday / timezone, so relative dates ("next Friday") resolve |
| `canvas` | `canvas/` | render a visual React widget (chart, table, dashboard) in the app instead of plain text — writes to `state["canvas"]` |
| `artifact` | `artifact/` | deliver a long-form markdown document to the app's full-screen reader + library instead of a chat bubble — appends to `state["artifacts"]` |
| `schedules` | `scheduler/` | **approval-gated** create/edit of recurring scheduled runs. `list_schedules` reads; `propose_schedule` only *drafts* (`state["schedule_proposals"]`) — the write happens on the phone when the user taps Approve |
| `vm` | `vm/` | a real persistent Linux sandbox (the `vmm` daemon) to run and verify code, process files, and host long research runs. **Live** in the `assistant`'s scope |
| `wiki` | `wiki/` | a learned, git-backed knowledge base (markdown entries under `state/wiki/`): `wiki_search` retrieves approved entries; `propose_wiki_entry` drafts a new/amended entry into `state["wiki_proposals"]` for **user approval** on the phone — proposals never auto-activate |

Skills are **scoped per-agent** via `AgentConfig.skills=[...]` — an agent
only sees the skills in its scope. Load order matters:
`load_all_skills()` runs first in `main.py`, so agents can resolve
their skill scope at construction time.

---

## 3. Runtimes

All implement `BaseAgentRuntime.arun(messages, session_id) -> AgentResult`.

```
                       BaseAgentRuntime (abstract)
                              │
     ┌──────────────┬─────────┴───────┬──────────────────┐
     │              │                 │                  │
OllamaRuntime  OpenAICompatRuntime  LangGraphRuntime¹  ClaudeAgentSDKRuntime¹
     │              (OpenRouter)
     ├─(composes)─▶ ListManagerRuntime    (wraps → {reply, ops})
     └─(composes)─▶ SkillfulOllamaRuntime (load_skill loop + threading
                      tools + the delivery envelope + auto-continue)

¹ Commented out in definitions.py — code present, not registered.
```

Details:

- **[`OllamaRuntime`](../app/runtimes/ollama_runtime.py)** — hand-rolled
  tool loop over `/api/chat`. Each iteration sends the current
  `self.tools` schemas; `_resolve_tool_calls` runs any requested tool
  and appends its result to `convo`. Terminates when the model returns
  no `tool_calls`; on hitting `settings.max_tool_iterations` it returns
  `AgentResult(text, finish_reason="length")`. Per-instance `think` param
  overrides the global `OLLAMA_THINK` default. **Tool dispatch is
  fault-isolated**: `TypeError` from hallucinated arguments, malformed
  JSON, and generic tool exceptions all come back as recoverable
  `[error] ...` tool-result strings the model can read and retry — never
  as 500s.

- **[`OpenAICompatRuntime`](../app/runtimes/openai_runtime.py)** — the
  same tool-loop contract spoken over the OpenAI `/chat/completions` wire
  format, used for **both OpenRouter and the local vLLM** server. A
  `gate_provider` ctor arg (default `"openrouter"`) selects which
  concurrency gate and provider tag the instance uses, and
  `_reasoning_payload()` sends reasoning the way each provider wants it
  (OpenRouter nests `reasoning.effort`; vLLM takes a top-level
  `reasoning_effort`). Returns `finish_reason="length"` on cap
  (`openrouter_max_tool_iterations`, default 12), so everything layered on
  the Ollama path works identically on a remote/vLLM model. Two quirks it
  smooths over, both discovered against vLLM's Qwen3 template:
  - **Single system message.** vLLM 400s on more than one system message;
    `_build_conversation` collapses the catalog prompt + the state
    snapshot (+ memory) into one leading system message. (Ollama keeps its
    own builder and allows multiple.)
  - **Surfaced errors.** A ≥400 response raises with the provider's actual
    body (`"…context length…"`, `"System message must be at the beginning"`,
    etc.) instead of a bare status, so `/diag` shows the real reason.

  Remote/vLLM capacity is elastic/batched, so it is *not* behind the
  single-slot Ollama gate — vLLM has its own high gate (§3.1).

- **[`ListManagerRuntime`](../app/runtimes/list_manager_runtime.py)** —
  composes an OllamaRuntime per request. Parses `{prompt, lists, notes}`
  from the last user message, seeds per-request tool closures over an
  ops list, and re-wraps the final assistant text as
  `{"reply": "...", "ops": [...]}` so the client can apply mutations.

- **[`SkillfulOllamaRuntime`](../app/runtimes/skillful_ollama_runtime.py)** —
  the Android app's agent. Composes an inner runtime per request — Ollama
  (default), or `OpenAICompatRuntime` pointed at **vLLM** / **OpenRouter**
  when the client picked one for the turn — selected by the per-request
  provider guardrails in §3.1. Ships **three base tools** always available
  on top of loaded skills:
  - `load_skill(skill_id)` — closure extends the inner runtime's tools
    with the skill's tools and returns the SKILL.md body as the tool
    response.
  - `resolve_thread()` — sets `state["resolve"]=True`; the wrapped
    envelope carries `resolve_thread: true` so the client archives the
    thread. One-shot commands ("add milk") should self-resolve.
  - `no_response()` — sets `state["silent"]=True`; the wrapped envelope
    has `reply=null` and `no_response: true`. The transcript is still
    saved on the device; no message, no notification. Used for pure
    journaling / musings.

  It also owns two cross-cutting behaviours beyond the inner loop:
  **the delivery envelope** (§6.3 — it serializes `canvas`, `steps`,
  `artifacts`, `resume`, `schedule_proposals` that skills wrote into
  `state`), and **research auto-continue** (§4.9).

- **LangGraph / Claude SDK** — implemented in
  [`app/runtimes/`](../app/runtimes/) but not currently registered.
  Ready to enable when needed.

### 3.1 Three providers, one surface — [`app/model_catalog.py`](../app/model_catalog.py)

The gateway can drive three LLM providers. `GET /v1/llm-models` returns the
selectable *LLMs* (distinct from `/v1/models`, which lists *agents*),
each tagged with its `provider` (`ollama` / `vllm` / `openrouter`) and
capability flags (`tools`, `thinking`, `vision`). The Android client shows
this as a model picker — **provider-generic**, so a new provider's models
appear automatically — and echoes the chosen `{model, provider}` back in
the request envelope; the `assistant` runtime then routes that turn to the
matching inner runtime.

| provider | runtime | gate | when enabled | notes |
|---|---|---|---|---|
| `ollama` | `OllamaRuntime` | `OLLAMA_MAX_CONCURRENCY` (default 1 — a big local model serialises) | always (the default) | unknown model ⇒ *capable* (resident local model); vision-capable; large context window |
| `vllm` | `OpenAICompatRuntime(gate_provider="vllm")` | `VLLM_MAX_CONCURRENCY` (default 16 — it batches) | `VLLM_BASE_URL` set | **text-only** (served with `--limit-mm-per-prompt image:0`); fixed `VLLM_MAX_MODEL_LEN` window; `tools`+`thinking` true |
| `openrouter` | `OpenAICompatRuntime` | `OPENROUTER_MAX_CONCURRENCY` (default 4 — elastic) | `OPENROUTER_API_KEY` set | unknown model fails *closed* (a non-tool model would silently become a chatbot) |

A provider with no base-url/key is simply omitted from the catalog.

**Routing guardrails** (in `SkillfulOllamaRuntime`, next to the
tool-support fallback). The client's pick is honoured *unless* vLLM can't
serve the turn, in which case it transparently reverts to the local Ollama
default — never a hard error:

1. **Capability** — the picked model's `model_flags` say no `tools` ⇒
   fall back (the skill loop needs tool calls).
2. **Vision** — the turn carries an *uncaptioned* image ⇒ fall back, because
   vLLM is served text-only (the 27B's vision tower stays on Ollama/gemma3;
   enabling it crashes vLLM at startup on this stack). Captioned images are
   already plain text and stay on vLLM.
3. **Context** — the estimated prompt size (snapshot + goals + notes +
   prior thread, *not* double-counting the raw payload message) exceeds
   ~`vllm_max_model_len × 2.6` chars ⇒ fall back, because vLLM's window is
   fixed while Ollama runs a much larger one.

### 3.2 System One — typed decisions for agent middleware — [`app/systemone.py`](../app/systemone.py)

Separate from chat inference, the gateway exposes a **TypeSafe-conforming
System One API** (`POST /v1/systemone`): the caller sends a `state` plus named
`questions`, each typed `noul` (yes/no → P(yes)), `choice` (1–255 named
options → pick + calibrated probabilities) or `score` (1–255 ordered levels →
mean level + distribution), and gets **calibrated answers, no generated text**.
It is not a user-facing feature: it is the substrate for **middleware in agent
graphs**. In use: **smart approvals** — the sandbox daemon screens every
command the agent runs and holds only the flagged ones for a human (§7.6).
Planned next: *browser-control* decisions.

Two interchangeable backends speak the identical API:

| backend | what | where | when used |
|---|---|---|---|
| `kev` | open-source reproduction ([jaredpalmer/kev](https://github.com/jaredpalmer/kev), Apache-2.0; Kev-4B on Qwen3.5-4B) | self-hosted on the WSL host, `:8009` (§7.5) | default (`SYSTEMONE_PROVIDER=kev`) |
| `jev` | TypeSafe's hosted model | TypeSafe (or any System One-compatible router) | set `JEV_BASE_URL` + `JEV_API_KEY` |

**Routing:** a request `model` starting with `kev`/`jev` picks that backend for
the call; otherwise `SYSTEMONE_PROVIDER` decides. The gateway forwards the
body unchanged (defaulting only `model`), and relays the upstream status,
body and `x-typesafe-request-id` (plus `x-systemone-provider`), so any System
One client — including TypeSafe's SDK pointed at the gateway — works as-is.
Documented hard limits (`state` required; typed questions; 1–255 options /
levels) are validated up front for a fast, clear 422; the upstream stays
authoritative for token limits. An unreachable/unconfigured backend is a clean
503, a timeout a 504.

**Internal use:** gateway features call `systemone.decide(state, questions)` /
`systemone.noul(state, instructions)` directly (no HTTP hop). Every decision —
HTTP or internal — is recorded on the active `/diag` trace as a round-trip
tagged `kev`/`jev`, so you can see where it was routed and what came back.

---

## 4. End-to-end flows

### 4.1 Voice-triggered agent call (multi-turn threaded)

```
Android record ──▶ Whisper endpoint ──▶ transcript on Android
                                              │
                            (thread attachment: explicit / goal open / new)
                                              │
                            SnapshotSync.uploadSnapshot  ──▶ POST /v1/snapshot
                                              │
                                              ▼
                GatewayAgent.callListManager builds the messages array:
                    - prior thread turns as alternating assistant/user
                    - final user message content = JSON envelope
                      {prompt, lists, notes, goals, scoped_goal?, images?,
                       model?, provider?, resume_run_id?}
                                              │
                                              ▼
                    POST /v1/chat/completions  model=assistant
                                              │
                                              ▼
                    SkillfulOllamaRuntime.arun → skill loop →
                    {reply, ops, resolve_thread, no_response, canvas,
                     steps, artifacts, resume, schedule_proposals}
                                              │
                                              ▼
    if no_response: drop reply, keep note only.
    else:  persist agent message (with threadId) + any artifacts/canvas/
           resume/schedule-proposal rows, apply ops in a transaction, post
           notification (deep-links to thread),
           if resolve_thread: threadDao.resolve(threadId).
```

Code: [`GatewayAgent.callListManager`](../../VoiceNote/app/src/main/java/com/voicenote/app/GatewayAgent.kt),
[`SkillfulOllamaRuntime.arun`](../app/runtimes/skillful_ollama_runtime.py).

### 4.2 Geofence-triggered agent call

Same as voice-triggered, but the prompt is synthesized on the Android
side by `AgentQueryWorker` (`"I'm near <store>. What should I pick up?"`)
after a Play Services geofence transition. No Whisper hop.

### 4.3 Scheduled server-side run (DB-backed)

```
schedules table (state.db)  ─▶ APScheduler cron job (one per enabled row)
                        │          fires in the row's own IANA timezone
                        ▼
        scheduler._fire(id) → reload row fresh → _run_schedule(entry, …)
                        │
                        ├─ state.get_snapshot() + get_notes() + get_goals()
                        │
                        ├─ registry.get(entry.model).runtime.arun(msg)
                        │       (SAME runtimes as HTTP path — direct call)
                        │
                        ├─ parse {reply, ops, + extras} from the envelope
                        │
                        └─ for each pusher: await pusher.deliver(
                              session, title, reply, ops, extras)
                                │
                                ├─ PollingPusher → INSERT pending_deliveries
                                │                   (extras_json carries
                                │                    canvas/steps/artifacts/…)
                                └─ FcmPusher    → TODO stub (see docs/FCM_SETUP.md)
```

Schedules live in the **`schedules` table** (the source of truth) and are
editable at runtime via the CRUD API (§6.4) with no restart.
`schedules.yml` is used **only to seed an empty DB** on first boot.
Code: [`app/scheduler.py`](../app/scheduler.py),
[`app/push/pusher.py`](../app/push/pusher.py),
[`schedules.yml`](../schedules.yml).

### 4.4 Polling delivery drain

```
Android drains (PollWorker: 15-min periodic + one-shots on app open, boot,
and DeliveryAlarm; the async turn's own await loop long-polls)
        │
        ├─ GET /v1/pending/{session}[?wait=≤55]  ──▶ state.list_pending(session)
        │        (extras flattened; + busy_threads, scheduled_running, next_due)
        ▼
For each delivery — ONE Room transaction:
    ├─ applied_deliveries.claim(id)   (already claimed → skip to the ack)
    ├─ GatewayAgent.applyOpsJson(...)
    ├─ reopen / find-or-create the thread
    ├─ GatewayAgent.persistReply(...)  (message + widgets/docs)
    └─ resolve / touch the thread
then: postReplyNotification → AwaitedTurns.remove → DELETE /v1/pending/{id}
then: DeliveryFollowUp.reconcile(response)
```

**Exactly-once.** The queue is at-least-once: a delivery stays pending until
acked, and an ack can be lost. The claim row commits atomically with the
apply, so a re-fetched delivery (lost ack, or PollWorker racing the await
loop) is only re-acked. If the apply fails, everything rolls back, claim
included, and a later poll retries.

**Long-poll.** With `wait`, an empty response is held on a per-session
`asyncio.Event` (`State.delivery_event`, set by `queue_delivery`) until a
delivery is queued. The handler takes the event *before* listing, so a
delivery queued in between can't be missed. The async turn's await loop uses
`wait=50`, so a reply lands about a second after it's queued.

**Lost turns and follow-ups (no push).**
- The phone remembers its in-flight async turns (`AwaitedTurns`, thread →
  submit time).
- `busy_threads` is read from `_async_inflight` *before* listing. A turn
  queues its delivery before leaving that set, so a finished turn is always in
  one list or the other.
- An awaited thread that is neither busy nor pending was lost, usually to a
  gateway restart mid-turn. The phone posts an "interrupted — tap your
  message → Resend" reply and re-runs nothing.
- `reconcile` arms one `setAndAllowWhileIdle` alarm, which works in Doze with
  no extra permission. It fires a drain at 60 s / 2 min / 5 min while
  something is still running, otherwise at `next_due` + 90 s for the next
  scheduled check-in. The alarm enqueues an expedited `PollWorker`.
- FCM, when added, only needs to call `PollWorker.enqueueOneShot`, as a
  wake-up signal carrying no content.

Because the delivery flattens the envelope **extras**, a scheduled or
async turn surfaces the same widgets, artifacts, resume cards, and
schedule-proposal cards a live reply would. Code:
[`PollWorker`](../../VoiceNote/app/src/main/java/com/voicenote/app/PollWorker.kt),
[`DeliveryFollowUp`](../../VoiceNote/app/src/main/java/com/voicenote/app/DeliveryFollowUp.kt),
[`app/main.py` /v1/pending routes](../app/main.py).

### 4.5 Snapshot upload

After any mutation on Android (voice-triggered ops applied, manual
list/goal edit, poll-delivered ops) `SnapshotSync.uploadSnapshotAsync`
fires → `POST /v1/snapshot/{session}` with `{lists, notes, goals}`.
Gateway upserts into the `snapshots` table with **partial-preservation
semantics**: if a field is omitted, the cached value survives. Scheduled
runs read this cache and reason about fresh state without a round trip
to the phone.

### 4.6 Skill loading (progressive disclosure)

```
Client → POST /v1/chat/completions  model=assistant  (with thread history)
                    │
                    ▼
SkillfulOllamaRuntime.arun
    ├─ system message: base prompt + THREADS + SKILL CATALOG
    │       "  • lists — Read + mutate ..."  (+ notes, goals, images,
    │        websearch, today, canvas, artifact, schedules, vm)
    ├─ inject snapshot: lists + goals (with per-goal scoped notes) as
    │  a second system message
    ├─ prior thread turns pass through unchanged as user/assistant messages
    ├─ tools = [load_skill, resolve_thread, no_response]
    └─ inner runtime loop (Ollama, or OpenRouter if requested this turn)
            │  iteration N: model calls load_skill("goals")
            │       └─ closure extends inner.tools with goal tools
            │  iteration N+1: model calls create_goal(...) etc.
            │  iteration N+2: model calls resolve_thread() (if done)
            │  iteration N+3: model returns final assistant text
            ▼
Wrap: {reply, ops, resolve_thread, no_response, canvas, steps,
       artifacts, resume, schedule_proposals}
```

### 4.7 Thread lifecycle (client-owned)

Threads are entirely a client concept — the gateway has no thread
tables, no thread ids, no thread awareness beyond echoing back the
history it's given.

```
[open]  first engagement (voice with intent, geofence, or schedule)
   │
   ├── multi-turn: each new voice reply appends to the thread; client
   │   sends the full history each turn so the model has context.
   │
   ▼
[resolved]  agent calls resolve_thread(), or user swipes/taps Resolve.
   │
   ├── recording into a resolved thread reopens it.
   ▼
[open again]
```

**No-intent notes** never open a thread: the agent calls
`no_response()`, the client's `deliverReply` is skipped, and the
triggering note is left un-attached in Notes.

### 4.8 Photo attachments (three-layer image cache)

Up to 5 photos can be attached before recording a main-screen voice
note. The governing rule: **images never live in the assistant's
context window** — pixels enter a model exactly twice (turn 1, and
caption time), and everything after that is text.

```
Android: Photo Picker → downscale (1024px / JPEG q80) → Room NoteImageEntity
   │  UploadWorker, after Whisper:
   ▼
POST /v1/images/{session}  (multipart, content-addressed by SHA-256)
   │  dedup: same photo re-uploaded = no-op, returns existing id + caption
   ▼
turn 1: uncaptioned blobs ride the user message inline as base64
        (Ollama per-message `images:[b64]`) — the model actually SEES them
   │  after the reply is sent (fire-and-forget):
   ▼
caption_image(): ONE vision call per image, EVER → structured record
        {summary, visible_text (verbatim OCR), tags, has_text, text_density}
   │  next snapshot round trip:
   ▼
note text gains an index entry:  [img_a3f2 · "Receipt from Home Depot…" · has_text]
        (~20 tokens; advertises the id + that dense text is retrievable)
```

**Recall — the escalation ladder** (images skill,
[`app/skills/images/`](../app/skills/images/), tools in
[`app/tools/image_ops.py`](../app/tools/image_ops.py)), cheapest rung
first:

| question | resolved by | vision calls |
|---|---|---|
| "what's this note about?" | note text + summary already in context | 0 |
| "extract the text from this image" | `get_image_text(id)` — cached `visible_text` | 0 |
| "what colour is the car behind the sign?" | `describe_image(id, question)` — side-channel vision call, cached by `(sha, question)` | 1, then 0 |
| finding the right image first | `list_images()` — ids + summaries + has_text | 0 |

`describe_image` routes to a *separate* vision request rather than
injecting pixels into the conversation, so the assistant's own context
stays text-only. Short ids (`img_a3f2`) from note index entries resolve
by prefix. The three tools read the gateway's own store via
`state["app_state"]` / `state["session"]` (set at startup / per
request), unlike the list/goal tools which read the client-supplied
snapshot.

**Housekeeping** (`images.housekeep`, fired at startup and after each
upload): orphan blobs (file with no `images` row) are deleted; when the
store exceeds `IMAGE_STORE_MAX_MB` the oldest blobs are evicted (LRU by
`created_at`) — but caption records and cached answers are **kept**, so
text recall survives eviction forever.

### 4.8b Video attachments — frame inspection in the sandbox

```
phone: Photo Picker (ImageAndVideo) → VideoStore copies the original (≤300 MB,
       ≤5 min, 1 per note) + thumbnail → UploadWorker "Uploading video…"
POST /v1/media/{session}   (multipart, streamed to disk, sha256 while writing,
                            magic-byte check: MP4/MOV/3GP/WebM; duration/size from the phone)
  → state/media/<aa>/<sha>.<ext> + `media` row
turn envelope: videos:[{id, duration_s, summary?}]  → user turn lists "vid_xxxxxxxx (0:30)"
video guardrail: a video turn runs on a VISION-capable model (vLLM / non-vision
  OpenRouter picks are routed to the Ollama default); state["vision"] records it
`video` skill (app/skills/video/):
  video_probe     stream the file into the shared sandbox once (vmmd PUT /upload,
                  sha-checked) → `vn-video probe`: duration, fps, orientation,
                  scene cuts, motion profile, suggested sampling plan (text)
  video_overview  `vn-video sheet` → ONE contact-sheet JPEG (#n + timestamp tiles)
  video_frames    `vn-video frames` → ≤8 frames at times / fps / every_s in a range
  frames fetched with vmmd GET /raw → returned as ToolResult(text, images)
after the turn: a short summary of what the agent found → media.summary (phone
  mirrors it; later turns get "previously watched: …" instead of re-watching)
```

**Frames reach the model mid-loop** through [`app/tool_result.py`](../app/tool_result.py):
a tool may return `ToolResult(text, images, caption)`. Both tool loops
(`ollama_runtime`, `openai_runtime`) put the text in the `tool` message and the
images in **one follow-up user message** after that round's tool results
(Ollama `images`; OpenAI `image_url` parts). Only the newest batch stays
attached — earlier frame messages keep their caption but drop their pixels — so
frames are never re-sent on every later call. If the model can't see
(`state["vision"]` false), the video tools describe frames with the local vision
model (`images.vision_call_many`) and return text instead. Per-turn budget:
`VIDEO_MAX_FRAMES_PER_TURN` (48; a sheet tile counts ½) over 6 frame calls.
Frames are stripped from `/diag` like any image. No audio analysis yet.

### 4.9 Long research: run dirs, auto-continue, one-tap resume

Deep research outlives a single tool-loop budget. Three mechanisms chain
to let a run span many model passes and, when it still isn't done,
hand off to the phone as a one-tap card.

**Checkpointed run dirs** — the `vm` skill's
[`vm_new_run(title, goal)`](../app/tools/vm_ops.py) mints a run id
(`<timestamp>_<slug>`) and a directory `/workspace/runs/<run_id>/` in the
sandbox with `GOAL.md`, `PLAN.md`, and `STATE.json`
(`{run_id, status, step, total, plan, next, updated_at}`). `vm_checkpoint`
updates `STATE.json` after each step, re-renders `PLAN.md` checkboxes,
and publishes a `{phase:"plan", step, total, next, …}` progress event
(§4.10). The sandbox filesystem — not the chat transcript — is the run's
real memory.

**Auto-continue** (`SkillfulOllamaRuntime`, ceiling `MAX_AUTO_CONTINUE`,
default 2) — when a run that called `vm_new_run` hits the tool-loop cap
(`finish_reason == "length"`) without resolving, the wrapper doesn't
stop: it rebuilds a *lean* conversation that points the model back at
`STATE.json` and runs the inner loop again, up to the ceiling. Compaction
is "re-seed from the checkpoint," so context stays small no matter how
long the run goes. It relies only on both inner runtimes returning
`finish_reason="length"` on cap.

**One-tap resume** — if the run is *still* capped after the ceiling, the
envelope carries a `resume` handle `{run_id, run_dir, label}`. The app
stores it on the delivering message (`AgentMessageEntity.resumeJson`) and
renders a "Continue research" card; tapping it enqueues `ResumeWorker`,
which re-sends the turn with `resume_run_id` set so the model picks the
run back up from its checkpoint.

### 4.10 Live progress (SSE)

While a turn is in flight the app tails `GET /v1/progress/{session}`
([`ProgressStream.kt`](../../VoiceNote/app/src/main/java/com/voicenote/app/ProgressStream.kt)).
The runtime calls `diagnostics.progress_publish(session, event)` as each
skill/tool fires; events carry a `phase` (`plan`, `continue`, generic
step, start/done) and a goal-relative `label` that drives the working
bubble's live status line. The last event is retained for replay so a
late subscriber still sees current state.

### 4.11 Schedule proposals (approval-gated)

The `schedules` skill never writes a schedule. `propose_schedule(...)`
validates the cron, builds a plain-English draft, and appends it to
`state["schedule_proposals"]`; the envelope carries it to an in-thread
**Approve / Discard** card
([`item_schedule_proposal.xml`](../../VoiceNote/app/src/main/res/layout/item_schedule_proposal.xml)).
The actual create/update happens **only** when the user taps Approve,
which calls the CRUD API (§6.4) from the device. This is a *structural*
approval gate — independent of the sandbox's auto/manual mode, the model
cannot create a schedule on its own.

---

## 5. State

### 5.1 SQLite — [`app/state.py`](../app/state.py)

Path defaulted to `state/state.db`, override with `STATE_DB_PATH`. Mounted
persistent in Docker via `./state:/app/state`. All tables use
`CREATE TABLE IF NOT EXISTS`; new columns are added via idempotent
`ALTER TABLE ... ADD COLUMN` migrations, so upgrades preserve data.

**`snapshots`** — one row per session (lists/notes/goals cache).

| column | purpose |
|---|---|
| `session_id` (PK) | matches the client's OpenAI `user` field |
| `lists_json` | current lists snapshot (JSON array) |
| `notes_json` | current voice notes (JSON array, newest first) |
| `goals_json` | current goals snapshot (JSON array, two-level nested via `sub_goals`) |
| `updated_at` | epoch seconds |

`set_snapshot(session_id, lists, notes=None, goals=None)` preserves
whichever of `notes` / `goals` are omitted.

**`pending_deliveries`** — server-side queue of undelivered messages.

| column | purpose |
|---|---|
| `id` (PK autoinc) | delivery id, used in DELETE ack |
| `session_id` | target session |
| `title`, `body` | payload for the notification |
| `ops_json` | ops the client should apply on receipt |
| `extras_json` | the envelope extras (`canvas`, `steps`, `artifacts`, `resume`, `schedule_proposals`) so async deliveries don't drop them; flattened onto the delivery by `list_pending` |
| `created_at`, `delivered_at` | timestamps; `delivered_at IS NULL` == pending |

**`schedules`** — the source of truth for server-side cron runs (§4.3).

| column | purpose |
|---|---|
| `id` (PK) | schedule id (12-hex, minted on create) |
| `cron` | 5-field crontab expression |
| `model` | agent id to run (usually `assistant`) |
| `session_id` | target session (default `voicenote-default`) |
| `prompt` | the instruction fired on trigger |
| `notification_title` | delivery title |
| `enabled` | 0/1 — pauses without deleting |
| `timezone` | IANA zone the cron fires in; empty = container zone |
| `tz_mode` | `fixed` (pinned) or `device` (re-timed as the phone travels) |
| `created_at`, `updated_at` | epoch seconds |

**`images`** — one row per unique photo (PK = content SHA-256). Blobs
live beside the DB at `state/images/<aa>/<sha>.jpg`; the row carries the
durable caption record: `summary`, `visible_text` (verbatim OCR),
`tags_json`, `has_text`, `text_density`, `caption_model`,
`caption_version` (bump `CAPTION_PROMPT_VERSION` to invalidate + lazily
re-caption).

**`image_answers`** — `(sha, normalized_question) → answer` cache for
`describe_image`, so repeat questions are free.

**`traces`** — one row per model-driving request for the `/diag`
dashboard: summary columns (`trigger`, `model`, `session`, `status`,
`duration_ms`, `num_calls`, `num_ops`, `num_images`, `reply_preview`)
plus the full call-tree JSON in `payload_json`. Pruned to the newest
2000 rows on insert.

### 5.2 Per-request state

Runtimes that need mutable state (`ListManagerRuntime`,
`SkillfulOllamaRuntime`) build a fresh `state = {...}` dict inside every
`arun` call and pass it as a closure to their tools. Fields:

- `snapshot` / `notes` / `goals` — the client-supplied state (read-only
  for tools).
- `ops` — starts empty; mutating tools append ops here.
- `loaded` — set of skill ids the model has loaded this turn.
- `resolve`, `silent` — booleans set by `resolve_thread` / `no_response`.
- `canvas`, `artifacts`, `schedule_proposals` — written by the canvas /
  artifact / schedules skills; serialized onto the envelope at wrap time.
- `run_id`, `run_dir` — set by `vm_new_run`; drive auto-continue / resume.
- `session`, `app_state` — session id + the gateway's `State` instance,
  for tools (images) that read server-side stores instead of the
  client-supplied snapshot. `app_state` is injected onto skill-capable
  runtimes once at startup by `main.py`'s lifespan.

This avoids racing between concurrent requests hitting the same
registry-level singleton runtime instance.

### 5.3 Conversation memory — [`app/tools/memory.py`](../app/tools/memory.py)

Per-session `SessionState{ recent_turns, long_term_summary }`, in-memory
only, keyed by `session_id`. Used by the standalone `OllamaRuntime`
agents that opt in via `memory=memory_store`. Not used by list_manager
or assistant (their history is client-supplied per turn).

---

## 6. Wire contracts

### 6.1 OpenAI surface

`POST /v1/chat/completions` accepts a `ChatCompletionRequest`
([`app/schemas.py`](../app/schemas.py)) and returns either a
`ChatCompletionResponse` or a stream of `ChatCompletionChunk` SSE frames.
`model` is an agent id; `user` doubles as `session_id`. `GET /v1/models`
lists agents; `GET /v1/llm-models` lists the underlying LLMs across
providers (§3.1).

### 6.2 Inner payload — client → agent

For `list_manager` and `assistant`, the client sends prior thread
turns as ordinary `assistant`/`user` messages, and packs the current
request as a **JSON envelope inside the final user message's
`content`**:

```json
{
  "prompt": "add milk to groceries",
  "lists":  [{"name": "groceries", "items": [{"text": "eggs", "done": false}]}],
  "notes":  [{"transcript": "...", "created_at": 173..., "duration_seconds": 7, "goal": "Run a 10k"}],
  "goals":  [ { "title": "Run a 10k", "statement": "...", "status": "draft", "plan": {"tasks": [...]} } ],
  "scoped_goal": "Run a 10k",
  "images": [{"id": "<sha256>", "summary": null}],
  "model": "qwen3.8:latest",
  "provider": "ollama",
  "resume_run_id": "20260108-1012_ev-market"
}
```

`images` lists the attachments on this note. `model`/`provider` are the
LLM the user picked in the app (omitted → the gateway default).
`resume_run_id` is set only by `ResumeWorker` to pick a paused research
run back up (§4.9). The runtime:
1. Parses the envelope (falls back to raw content on non-JSON).
2. Injects `lists` and `goals` (with embedded scoped notes) as a
   preloaded system message.
3. Rewrites the final user turn to just `prompt` — or
   `"[About goal: <scoped_goal>] <prompt>"` when scoped.
4. Keeps `notes` in per-request state (`get_notes` fetches on demand).

### 6.3 Output envelope

`assistant` places a JSON string in `choices[0].message.content`.
`list_manager` emits the `{reply, ops}` subset; the `assistant` adds the
delivery extras:

```json
{
  "reply": "Added milk to groceries.",
  "ops":   [{"type": "add_item", "list": "groceries", "text": "milk"}],
  "resolve_thread": true,
  "no_response": false,
  "canvas": null,
  "steps": [],
  "artifacts": [],
  "resume": null,
  "schedule_proposals": [],
  "wiki_proposals": [],
  "sources": [{"n": 3, "url": "https://…", "title": "…", "domain": "iea.org",
               "snippet": "…", "via": "search", "cited": true}]
}
```

- `reply` is `null` when `no_response` is true (client suppresses the
  notification and persists no agent message).
- `canvas` — a React widget spec (canvas skill) rendered by
  [`CanvasRenderer.kt`](../../VoiceNote/app/src/main/java/com/voicenote/app/CanvasRenderer.kt).
- `steps` — completed-step labels accumulated this turn (working bubble).
- `artifacts` — `[{id, kind:"markdown", title, text}]`; each becomes a
  row in the full-screen reader + library
  ([`ArtifactReaderActivity`](../../VoiceNote/app/src/main/java/com/voicenote/app/ArtifactReaderActivity.kt)).
  The reader (top bar) and library (long-press) can **share** a document with
  people who don't have the app — as a PDF (print → Save as PDF), a
  self-contained script-free `.html` page snapshotted from the rendered view,
  or plain text ([`ArtifactShare.kt`](../../VoiceNote/app/src/main/java/com/voicenote/app/ArtifactShare.kt)) —
  or **delete** it (also removes its in-thread card). Artifacts are client-owned;
  the gateway keeps no copy.
  **Revisions in place:** the app sends the thread's recent documents on the
  request envelope (`artifacts: [{id, title, text, sources}]`, ≤6 / 160k chars,
  newest first). The gateway gives the model only an index (title, id, size,
  section headings) plus the artifact skill's `read_artifact(id)` and
  `edit_artifact(id, edits=[{find, replace}])` (exact, single-match,
  all-or-nothing) or `publish_artifact(..., replaces=id)` for a rewrite.
  Reading a document *adopts* its sources into the turn's registry and
  renumbers its `[n]` markers, so old citations and new search results share
  one numbering. The revision returns with the **same id** and
  `revised: true`; the app updates the row in place (revision + 1, moved
  under the revising reply, previous version kept for a one-tap
  "Show previous version" swap in the reader — Room v17).
  **Ask for changes (reader mic):** the reader's bottom mic records a voice
  request through the normal `UploadWorker` path, tagged with the document id
  (work tag `artifact-<id>`); the envelope carries `focus_artifact`, that
  document is always included in `artifacts`, and the runtime prefixes the
  turn "[The user is reading the document … — revise that document in place]".
  The reader observes the row, so the revision lands live, highlighted
  (`boot.js` `markChanges`): both versions are rendered off-screen and diffed
  per block (list item / table row / top-level block, citation markers
  ignored) — NEW and EDITED blocks get an amber bar, inserted words are
  marked, EDITED's tag reveals the old wording, removed blocks leave a
  collapsed stub, and a floating pill steps through them. Highlights are
  stripped from exports (`__cleanHtml`, print CSS). If the assistant answers
  instead of editing, the bar links to the thread.
  **Rendering** ([`assets/artifact/boot.js`](../../VoiceNote/app/src/main/assets/artifact/boot.js)):
  inline `$…$` is math only when it looks like math (pandoc rules — no space
  inside the delimiters, closing `$` not followed by a digit — plus currency /
  prose / table-cell / bold guards, and never inside code), so "$1.52B on
  $4.9B" stays text; invalid TeX falls back to its plain source. On phones a
  `flowchart LR` is laid out top-down, and a diagram still below 75% scale
  scrolls sideways at a readable size.
- `resume` — a one-tap "Continue research" handle (§4.9).
- `schedule_proposals` — drafted schedules awaiting approval (§4.11).
- `wiki_proposals` — drafted learned-wiki entries awaiting approval.
- `sources` — **structured citations** ([`app/sources.py`](../app/sources.py)).
  Every `web_search` result is recorded with a stable per-turn number and shown
  to the model as `[n]` (`via:"search"`). **Pages the sandbox fetched** are
  recorded too (`via:"browse"`): after each successful `vm_exec`, URLs on
  fetching lines of the command (curl/wget, requests/httpx/urlopen, Playwright
  `.goto(`, `read_csv`, `git clone`…) and every URL in a script written this
  turn that the command runs (if the script fetches at all) become sources, and
  the tool output ends with `sources recorded: [n] url` so the model cites them
  like search results. Package installs, registry/mirror hosts (pypi, npm, apt…),
  localhost/private IPs and templated URLs (`{…}`, `$VAR`) are skipped; cap 12
  per command. A browsed page starts with a path-derived title
  (`iea.org › reports › global-ev-outlook-2025`) that a later titled link
  replaces. Links the model writes (`[title](url)` or bare URLs, `via:"link"`)
  are recorded at wrap time. The app marks browsed sources "· browsed". Each
  source is `cited` if the text references its `[n]` or URL, else "also
  consulted"; cited first. The message carries the list relative to its reply +
  documents; **each artifact carries its own**, relative to its body. The app
  renders the message's list as a collapsible **Sources** sub-card (tap a row
  to open the page, long-press to copy; [`SourcesCard.kt`](../../VoiceNote/app/src/main/java/com/voicenote/app/SourcesCard.kt)),
  and the reader turns an artifact's inline `[n]` markers into links to a
  numbered Sources section at the end — which therefore travels into the PDF /
  web-page / text exports. Only http(s) URLs are kept, and the reader opens a
  link in the browser only on a real tap.

**Op families** (all reference records by name/text, never by id):

| family | examples | source |
|---|---|---|
| lists | `create_list`, `delete_list`, `rename_list`, `add_item`, `remove_item`, `set_item_done` | [`app/tools/list_ops.py`](../app/tools/list_ops.py) |
| goals | `create_goal`, `update_goal`, `add_goal_context`, `set_plan` (one level of `subtasks`; refused on approved plans), `set_plan_status` (**`approved` also activates the goal**), `add_task` (optional `parent_task`), `set_task_done`, `remove_task`, `delete_goal` | [`app/tools/goal_ops.py`](../app/tools/goal_ops.py) |

Goals are **flat**; decomposition happens inside the plan, capped at
**one level** of subtasks (server-side and client-side). The Android
client also has a **markdown edit mode** on the goal screen — statement,
context, plan, and scoped notes render as an editable markdown document
that is parsed back into entities and synced to the snapshot cache.

### 6.4 HTTP endpoints

All `/v1/*` routes are behind `require_api_key` (no-op when
`GATEWAY_API_KEY` is unset). `/diag*` is deliberately open on the tailnet.

| Method + path | Body / result |
|---|---|
| `GET /v1/models` | registered agents as model cards |
| `GET /v1/llm-models` | `{default, default_provider, models:[{provider,name,tools,thinking,vision}]}` |
| `POST /v1/chat/completions` | run an agent (stream or non-stream) |
| `POST /v1/snapshot/{session}` | `{lists, notes?, goals?}` → `{status:"ok"}` (omitted fields preserved) |
| `GET /v1/snapshot/{session}` | → `{lists, notes, goals}` |
| `GET /v1/pending/{session}?wait=` | → `{deliveries:[{id,title,body,ops,created_at, +extras}, …], busy_threads, scheduled_running, next_due}`; `wait` (≤55 s) long-polls |
| `DELETE /v1/pending/{id}` | ack a delivery (404 if unknown/delivered) |
| `GET /v1/schedules` | → `{schedules:[{…, next_run_time}]}` |
| `POST /v1/schedules` | create; validates cron (400) + model (404) → row w/ `next_run_time` |
| `PUT /v1/schedules/{id}` | update any field incl. `enabled`; re-registers the job |
| `DELETE /v1/schedules/{id}` | delete + unregister |
| `PUT /v1/schedules/device-tz` | `{tz}` → re-time every `tz_mode='device'` schedule |
| `POST /v1/schedule/{id}/run` | fire a schedule immediately |
| `GET /v1/progress/{session}` | SSE of live turn progress (§4.10) |
| `POST /v1/images/{session}` | multipart upload → `{images:[{id, existing, summary, has_text}]}` |
| `GET /v1/images/{sha}` | caption record; `?raw=1` serves the JPEG (api-key gated) |
| `POST /v1/media/{session}` | multipart video upload (streamed; `file` + `duration_s`/`width`/`height`) → `{media:{id, existing, mime, bytes, duration_s, summary}}` (§4.8b) |
| `GET /v1/media/{sha}` | video record incl. the post-watch `summary` |
| `GET/PUT /v1/sandbox/approval-mode` | read/set the vmm daemon's command-approval mode: `auto` / `smart` / `manual` (proxy) |
| `GET /v1/sandbox/approvals` | list pending sandbox commands, each with its smart-mode `review` (proxy) |
| `POST /v1/sandbox/approvals/{id}/approve`·`/deny` | act on a held command (proxy) |
| `POST /v1/systemone` | System One typed decision (§3.2) — body/status pass through from Kev/Jev |
| `POST /v1/systemone/permute`·`/separate` | the upstream's permutation / per-question variants (pass-through) |
| `GET /v1/systemone/models` | upstream model cards (`?model=jev-…` picks the backend) |
| `GET /v1/systemone/health` | `{enabled, provider, backends, reachable, models}` |
| `GET /healthz` | → `{status, agents, skills, schedules, ollama, providers}` |
| `GET /diag` | diagnostics dashboard (no auth — tailnet-trusted) |
| `GET /diag/traces`·`/traces/{id}`·`/stats`·`/stream` | trace list / call tree / gauge / SSE tail |

The sandbox routes are a **thin proxy** to the host-only `vmmd` daemon
(the app can't reach it directly); they fail fast with 503 when the
daemon is down. See §7.6.

---

## 7. Config & deployment

### 7.1 Env vars — [`app/config.py`](../app/config.py)

`pydantic-settings` with `env_file=".env"` and **no prefix**, so each env
var is the UPPERCASED field name. Highlights:

| name | default | purpose |
|---|---|---|
| `GATEWAY_API_KEY` | unset | if set, `Authorization: Bearer <key>` required on `/v1/*` |
| `OLLAMA_BASE_URL` | `http://localhost:11434` | override in Docker to `http://host.docker.internal:11434` |
| `DEFAULT_MODEL` | `qwen3.8:latest` | model id used by the Ollama-backed agents |
| `OLLAMA_THINK` | `true` | Ollama "thinking" mode default. `assistant` pins `think=True`. |
| `OPENROUTER_API_KEY` | empty | enables the OpenRouter provider; empty = Ollama-only |
| `OPENROUTER_BASE_URL` | `https://openrouter.ai/api/v1` | OpenRouter OpenAI-wire endpoint |
| `OPENROUTER_MAX_TOOL_ITERATIONS` | `12` | tool-loop cap on the remote path (cost ceiling) |
| `OPENROUTER_MAX_CONCURRENCY` | `4` | remote inference is elastic — no single-slot gate |
| `VLLM_BASE_URL` | empty | enables the vLLM provider (e.g. `http://host.docker.internal:8000/v1`); empty = no vLLM |
| `VLLM_MODEL` | `qwen3.8-27b` | the vLLM `--served-model-name` |
| `VLLM_API_KEY` | `EMPTY` | vLLM ignores it; any non-empty value satisfies the OpenAI client |
| `VLLM_MAX_CONCURRENCY` | `16` | vLLM batches — high gate, unlike Ollama's single slot |
| `VLLM_MAX_MODEL_LEN` | `131072` | must match the server's `--max-model-len`; drives the context guardrail (§3.1) |
| `SYSTEMONE_PROVIDER` | empty (compose: `kev`) | default System One backend: `kev` / `jev`; empty disables `/v1/systemone` (503) |
| `SYSTEMONE_TIMEOUT_SECONDS` | `60` | per-call upstream timeout (a scale-to-zero host cold-starts ~35 s) |
| `KEV_BASE_URL` | empty (compose: `http://host.docker.internal:8009`) | the self-hosted Kev server |
| `KEV_API_KEY` | empty | bearer key; must match the server's `KEV_API_KEY` (`~/.config/kev/kev.env`) |
| `KEV_MODEL` | `kev-latest` | model sent when a request omits one |
| `JEV_BASE_URL` / `JEV_API_KEY` | empty | TypeSafe's hosted System One endpoint + key; both needed to route to `jev` |
| `JEV_MODEL` | empty | model sent to Jev when a request omits one (empty = let upstream choose) |
| `VMM_APPROVAL_MODE` *(vmm service)* | `smart` | sandbox gate mode at daemon start: `auto` / `smart` / `manual` |
| `VMM_REVIEW_URL` *(vmm service)* | `http://gateway:5001/v1/systemone` | smart-mode reviewer; empty = every command held |
| `VMM_REVIEW_MALICIOUS_THRESHOLD` *(vmm service)* | `0.5` | hold when P(malicious) ≥ this (or verdict isn't `safe`) |
| `TAVILY_API_KEY` | empty | enables the `websearch` skill; empty = "not configured" |
| `TAVILY_SEARCH_DEPTH` | `basic` | `basic` (1 credit) or `advanced` |
| `MAX_TOOL_ITERATIONS` | `50` | Ollama tool-loop safety cap |
| `MAX_TOOL_RESULT_CHARS` | `8000` | per-tool-result truncation fed back to the model |
| `MAX_AUTO_CONTINUE` | `2` | extra research passes after a cap before one-tap resume (§4.9) |
| `OLLAMA_MAX_CONCURRENCY` | `1` | global cap on concurrent Ollama calls (semaphore + `/diag` gauge) |
| `VISION_MODEL` | empty → `DEFAULT_MODEL` | captioning + `describe_image` model |
| `CAPTION_PROMPT_VERSION` | `1` | bump to invalidate stored captions (lazy re-caption) |
| `IMAGE_STORE_MAX_MB` | `512` | blob-store cap; LRU-evicts pixels, keeps caption text. `0` disables |
| `STATE_DB_PATH` | `state/state.db` | SQLite file path |
| `STATE_DIR` | `state` | parent dir for the DB and `images/` blobs |
| `SCHEDULES_PATH` | `schedules.yml` | **seed-only** schedule file (§7.2) |
| `VMMD_BASE_URL` | `http://host.docker.internal:5002` | sandbox daemon URL |
| `SANDBOX_BACKEND` | empty | `docker` / `firecracker` / empty (daemon default) |
| `SANDBOX_SCOPE` | `shared` | `shared` (one box for all turns) or `session` (one per conversation) |
| `TZ` | container default | fallback zone for schedules with empty `timezone` |

### 7.2 Schedules — DB-backed, yml seeds once

Schedules live in the **`schedules` table** and are created/edited at
runtime through `POST`/`PUT`/`DELETE /v1/schedules` (§6.4) — no restart;
APScheduler jobs are added/replaced/removed live. On first boot, **if the
table is empty**, `schedules.yml` is imported once to carry over the
starter set; after that the DB is authoritative and the yml is ignored.
Each schedule carries its own IANA `timezone` and APScheduler fires it
there (correct across DST). `tz_mode='fixed'` pins the zone;
`tz_mode='device'` is re-timed by the app (`PUT /v1/schedules/device-tz`)
as the phone travels, so "8am" follows the traveler. Keep the yml file +
mount as the seed.

### 7.3 Docker

[`docker-compose.yml`](../docker-compose.yml) publishes port `5001`,
bind-mounts `./app`, `./schedules.yml`, and `./state`, and sets
`extra_hosts host.docker.internal:host-gateway` so the container can
reach Ollama and the `vmmd` daemon on the host. The container publishes
on the WSL host's `0.0.0.0`, so Tailscale peers hit it at
`http://<windows-tailscale-ip>:5001`. See
[`docs/FCM_SETUP.md`](FCM_SETUP.md) for the real-time push alternative to
WorkManager polling.

### 7.4 vLLM server (optional third provider)

vLLM runs **natively on the WSL host** (like Ollama), not in the gateway
container, as a **systemd service** (`/etc/systemd/system/vllm.service`)
that autostarts and restarts on failure. It serves `Qwen3.8-27B-NVFP4`
(Blackwell NVFP4 quant) on `:8000`, and the gateway reaches it at
`VLLM_BASE_URL` (`host.docker.internal:8000/v1` from the container).

Stack notes (hard-won — this is a bleeding-edge combo):
- Runs in a `uv` venv built with **CUDA 12.8** (`--torch-backend=cu128` →
  vLLM 0.24.0 / torch cu128). The newer `latest` image (CUDA 13) deadlocks
  at multimodal warmup on this box.
- FlashInfer's Blackwell (sm120) kernels come from the prebuilt
  `flashinfer-jit-cache` wheel (no runtime nvcc compile).
- Served **text-only** (`--limit-mm-per-prompt image:0`): the Qwen3-VL
  multimodal path crashes vLLM at startup on this stack, so vision stays on
  Ollama and image turns are routed there by the §3.1 guardrail.
- `--max-model-len` (128k) and `--gpu-memory-utilization` (0.50) are the
  context-window / KV-cache knobs; KV is 128 KB/token (fp8). Keep
  `VLLM_MAX_MODEL_LEN` in sync so the context guardrail matches.

Enable it by setting the `gateway` service's `VLLM_BASE_URL` (and friends)
in [`docker-compose.yml`](../docker-compose.yml) and recreating the
container; the catalog and picker pick it up automatically.

### 7.5 Kev server (System One backend)

Kev runs **natively on the WSL host** from `~/kev` (a `uv` venv; Python 3.13,
`torch<2.9`, plus `flash-linear-attention==0.5.2` — Kev's pinned version; without
it Qwen3.5's linear attention falls back to slow reference PyTorch), as a
systemd service (`/etc/systemd/system/kev.service`) serving
`jaredpalmer/kev-4b@v1.0` (~20 GB GPU loaded; `kev-9b` is the heavier, slightly
stronger drop-in) on `:8009`. It binds all interfaces so
the gateway container reaches it via `host.docker.internal`, with
`KEV_API_KEY` (from `~/.config/kev/kev.env`, mirrored into the gateway's
`.env`) required on every `/v1` route. To move to **Jev** instead, set
`JEV_BASE_URL` / `JEV_API_KEY` (and `SYSTEMONE_PROVIDER=jev`, or just send
`"model": "jev-…"` per call) — no code change.

### 7.6 Code interpreter — the `vmm` sandbox daemon

The `vm` skill ([`app/skills/vm/`](../app/skills/vm/), tools in
[`app/tools/vm_ops.py`](../app/tools/vm_ops.py)) gives agents a real Linux
box to run and verify code in, and hosts long research runs (§4.9). It is
**live in the `assistant`'s scope**. The tools are thin HTTP clients to a
separate daemon, **`vmmd`**, that runs on the WSL host (it needs
`/dev/kvm` + taps for Firecracker, or the docker socket) — see
[`vmm/README.md`](../vmm/README.md).

`vmmd` has **two interchangeable backends behind one interface** — Docker
containers (fast, sudoless, shares the host kernel) and Firecracker
microVMs (kernel-isolated) — and can run both at once. The gateway names
its choice via `sandbox_backend` and reaches the daemon at `vmmd_base_url`
(`host.docker.internal:5002` from the container). `sandbox_scope` picks
one shared box for every turn vs. one box per conversation.

The daemon owns the sandbox lifecycle, a **command-approval gate**, idle-TTL
reaping, and file transfer. The gate has three modes (`VMM_APPROVAL_MODE`,
switchable live from the app via the gateway's `/v1/sandbox/*` proxy):

| mode | behaviour |
|---|---|
| `auto` | run every command |
| **`smart`** (default) | screen each command with **System One** (§3.2) and hold **only the flagged ones** |
| `manual` | hold every command until a human approves |

**Smart approvals** ([`vmm/vmmd/review.py`](../vmm/vmmd/review.py)). Before an
`exec` runs, the daemon asks the gateway's `/v1/systemone` (reachable as
`http://gateway:5001` on the compose network) two typed questions about it — a
`choice` verdict `safe` / `suspicious` / `malicious` and a `noul` P(malicious) —
and holds it if the verdict isn't `safe` or P(malicious) ≥
`VMM_REVIEW_MALICIOUS_THRESHOLD` (0.5). The model sees context the gateway
attaches to each `vm_exec`: what the user asked for, the agent's stated purpose
(marked unverified), and the **source of any file written this turn that the
command runs** — so `python helper.py` is judged by what `helper.py` does. A
**deobfuscation layer** decodes embedded base64 into the state (a language model
can't read it), and *executing* encoded content (`exec(b64decode(…))`,
`… | base64 -d | bash`) is always held. A held command carries the review
(`verdict`, `p_malicious`, human-readable `reason`); the app's notification and
in-thread card lead with that reason, and a denied command returns the reason
to the agent in stderr. **Fails closed:** if Kev/Jev is down, slow, or answers
oddly, the command is held ("automatic review unavailable") — never run
unchecked. Each review is a `kev`/`jev` trace on `/diag`. Regression set:
[`vmm/smart_eval.py`](../vmm/smart_eval.py) (23 cases; 23/23 with Kev-4B,
median ~60 ms per review). A Textual TUI ([`vmm/tui.py`](../vmm/tui.py)) drives it
directly and includes an agent-loop harness.

**Image contents.** The `base` profile ([`vmm/profiles/base.pkgs`](../vmm/profiles/base.pkgs),
built by `bash vmm/build_image.sh base`) adds git/build tools plus **ffmpeg,
numpy, Pillow, OpenCV** and DejaVu fonts; every profile gets Python/pip, Node/TS,
Playwright+Chromium and **`vn-video`** ([`vmm/vn_video.py`](../vmm/vn_video.py)) —
the fixed probe / contact-sheet / frames CLI the `video` skill drives (§4.8b), so
frame work is a known command the reviewer sees verbatim, never model-written
code. **Binary I/O** besides `PUT/GET /file` (JSON+base64, small files):
`PUT /vms/{id}/upload?path=` streams a raw request body into the box (written to
`.part`, renamed on success; returns bytes + sha256; flat memory — a 250 MB file
streams in <1 s) and `GET /vms/{id}/raw?path=` returns one file's bytes (≤64 MB,
no tar). Neither is approval-gated (they move bytes, not commands). The shared
box keeps `/workspace` on the `vmm-ws-shared` volume; after an image rebuild,
[`vmm/migrate_shared_box.sh`](../vmm/migrate_shared_box.sh) backs the workspace
up to the host, recreates the box on the new image, and restores it.

---

## 8. Extension points

### Add a new agent

Edit [`app/agents/definitions.py`](../app/agents/definitions.py). Append
one `registry.register(AgentConfig(id=..., runtime=..., ...))` block. If
using `SkillfulOllamaRuntime`, populate `skills=[...]` and `skill_ids`.
Nothing else in the gateway needs to change.

### Add a new skill

Under [`app/skills/`](../app/skills/) create `<skill_id>/SKILL.md` (YAML
frontmatter with `id` + `description`, then markdown body) and `tools.py`
exporting `build_tools(state: dict) -> list[(schema, fn)]`. The loader
picks it up on next process start. Add its id to any agent's
`skills=[...]` scope. **Tool names are validated globally-unique across
skills** — factor shared logic into `app/tools/` and expose subsets.

### Add a new runtime

Subclass `BaseAgentRuntime` in [`app/agent_base.py`](../app/agent_base.py).
Implement `arun`; optionally `astream`. Register agents that use it in
`definitions.py`. (See `OpenAICompatRuntime` for the OpenAI-wire case.)

### Add a new push lane

Subclass `Pusher` in [`app/push/pusher.py`](../app/push/pusher.py) and
add an instance to the `pushers` list constructed in
[`app/main.py::lifespan`](../app/main.py). Every scheduled reply
automatically fans out to it. See [`docs/FCM_SETUP.md`](FCM_SETUP.md).

### Add a schedule

At runtime, `POST /v1/schedules` (or the app's Schedules screen) — no
restart. To change the *seed* set, edit [`schedules.yml`](../schedules.yml)
(only read when the DB is empty on first boot).

---

## 9. File map

| Path | Purpose |
|---|---|
| `app/main.py` | FastAPI app, lifespan (state + scheduler + pushers), all HTTP routes |
| `app/config.py` | pydantic-settings for env vars |
| `app/agent_base.py` | `BaseAgentRuntime` + `AgentResult` |
| `app/registry.py` | Agent registry + `AgentConfig` dataclass |
| `app/skill_registry.py` | Skill registry + `Skill` dataclass |
| `app/model_catalog.py` | Provider-neutral LLM catalog (Ollama + vLLM + OpenRouter) + capability flags |
| `app/registry_service.py` | Composes the live agent registry = code built-ins ⊕ DB overlay; hot-rebuild on admin edits |
| `app/sources.py` | Per-turn source registry: records search results, pages sandbox commands fetched (`fetched_urls`, called from `vm_ops.vm_exec`) + model-written links, numbers them, flags `cited`, emits the envelope `sources` |
| `app/systemone.py` | System One client: Kev/Jev routing, request validation, pass-through forwarder, `decide()`/`noul()` for internal middleware |
| `app/routes_systemone.py` | `/v1/systemone*` routes (decide, permute, separate, models, health) with `/diag` tracing |
| `app/ollama_models.py` | Ollama `/api/tags` + `/api/show` probe behind the catalog |
| `app/schemas.py` | OpenAI request/response Pydantic models |
| `app/response_adapter.py` | `AgentResult` → OpenAI response shape |
| `app/state.py` | SQLite tables + async accessors (aiosqlite) |
| `app/images.py` | Content-addressed blob store, structured captioning, `describe_image` cache, housekeeping, `vision_call_many` (multi-frame describer fallback) |
| `app/media.py` | Video store: streamed content-addressed upload with magic-byte check, LRU housekeeping (§4.8b) |
| `app/tool_result.py` | `ToolResult(text, images)` — tools that hand frames back to the model mid-loop; frame-message builders + retire-old-frames hygiene |
| `app/skills/video/` | `video` skill: `video_probe` / `video_overview` / `video_frames` over the sandbox's `vn-video` |
| `vmm/vn_video.py` | Sandbox video CLI (probe / sheet / frames), baked into the image as `vn-video` |
| `app/diagnostics.py` | Trace recorder, Ollama concurrency gate + gauge, progress + trace SSE pub/sub |
| `app/static/diag.html` | Self-contained `/diag` dashboard — per-call provider badges, collapsible (caret-controlled) JSON tree with nested-payload parsing, responsive mobile layout |
| `app/scheduler.py` | DB-backed APScheduler wrapper (`Scheduler`, `_run_schedule`, yml seed loader) |
| `app/push/pusher.py` | `Pusher` ABC + polling / FCM implementations |
| `app/agents/definitions.py` | Where you register agents |
| `app/runtimes/ollama_runtime.py` | Hand-rolled Ollama tool loop (fault-isolated, per-instance `think`) |
| `app/runtimes/openai_runtime.py` | OpenAI-wire tool loop (OpenRouter + vLLM via `gate_provider`); single-system merge; surfaced error bodies |
| `app/runtimes/list_manager_runtime.py` | list_manager composition on Ollama |
| `app/runtimes/skillful_ollama_runtime.py` | Progressive-disclosure runtime; delivery envelope + auto-continue; base tools `load_skill`, `resolve_thread`, `no_response` |
| `app/runtimes/langgraph_runtime.py` | LangGraph ReAct (unused) |
| `app/runtimes/claude_agent_sdk_runtime.py` | Claude SDK (unused) |
| `app/skills/__init__.py` | SKILL.md loader + tool-name collision check |
| `app/skills/{lists,notes,goals,images,websearch,today,canvas,artifact,scheduler,vm,wiki}/` | The registered skills (folder `scheduler/` = skill id `schedules`) |
| `app/tools/list_ops.py` | Schemas + closures for list/note ops |
| `app/tools/goal_ops.py` | Schemas + closure for goal ops (depth + approved-plan guards) |
| `app/tools/image_ops.py` | `list_images` / `get_image_text` / `describe_image` over the server store |
| `app/tools/vm_ops.py` | Sandbox HTTP client: `vm_new_run`, `vm_checkpoint`, exec/file tools |
| `app/tools/web_search.py` | Tavily-backed web search: `search()` + `format_results()`; the plain `web_search` tool for `qwen-tools`, while the `websearch` skill wraps it with source tracking |
| `app/tools/code_interpreter.py` | Placeholder code-runner schema for `qwen-tools` (the real sandbox is the `vm` skill) |
| `app/tools/memory.py` | In-memory session/summary store |
| `schedules.yml` | Seed-only cron schedules (imported when the DB is empty) |
| `docker-compose.yml` / `Dockerfile` | Container + volumes + env |
| `docs/ARCHITECTURE.md` | This document |
| `docs/FCM_SETUP.md` | How to enable the real-time push lane |
| `vmm/README.md` | The microVM sandbox daemon |
