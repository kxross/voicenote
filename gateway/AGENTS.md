# AGENTS.md

> Guidance for AI coding agents (and humans) working in this repository.

## What this project is

`agent-gateway` is a FastAPI service that exposes an **OpenAI-compatible
chat completions API** backed by local LLM agents instead of OpenAI. A
client sends `POST /v1/chat/completions` with `model` set to an *agent
id*; the gateway looks up that agent in a registry, runs it through
whatever runtime/framework backs it, and returns a standard
`chat.completion` (or streamed `chat.completion.chunk`) response.

Beyond request/response, the gateway also:

- Runs agents on **cron schedules**, DB-backed and editable at runtime
  (`schedules` table + CRUD API + APScheduler; `schedules.yml` only seeds
  an empty DB), and pushes replies to a client-visible delivery queue.
- Caches client state snapshots (lists, notes, goals) so scheduled runs
  can reason about current state without a round trip to the phone.
- Accepts **content-addressed image uploads**, captions them lazily with
  a vision model, and serves cached text recall — pixels enter a model
  at most twice, ever.
- Carries a richer **delivery envelope** — besides `{reply, ops}`, agents
  emit `canvas` widgets, long-form `artifacts`, step logs, one-tap
  research `resume` handles, and approval-gated `schedule_proposals`.
- Streams **live turn progress** over SSE (`GET /v1/progress/{session}`)
  and proxies the sandbox's **command-approval** API (`/v1/sandbox/*`).
- Provides a **`/diag` dashboard** with live request traces, Ollama
  concurrency gauge, and SSE tail.

The primary backing LLM server is a local **Ollama** instance (default
model `qwen3.8:latest`); **OpenRouter** is an optional second provider,
enabled when `OPENROUTER_API_KEY` is set, so the app can pick a remote
model per turn (`GET /v1/llm-models`). Claude Agent SDK and LangGraph
runtimes exist but are currently commented out in
`app/agents/definitions.py`.

## Architecture in one paragraph

```
Client (any OpenAI SDK)
  → POST /v1/chat/completions  (model = agent id)
  → AgentRegistry.get(model) → AgentConfig{ runtime, skills }
  → runtime.arun(messages, session_id) → AgentResult
  → response_adapter → chat.completion JSON back to client
```

Two registries (both process-wide singletons populated at import time):

1. **Agent registry** (`app/registry.py`) — `id → AgentConfig{runtime, skills, description}`.
   Populated by `app/agents/definitions.py`.
2. **Skill registry** (`app/skill_registry.py`) — `id → Skill{description, body, build_tools}`.
   Populated by `app/skills/__init__.py::load_all_skills()` scanning `app/skills/*/SKILL.md`.

All runtimes implement `BaseAgentRuntime.arun()` from `app/agent_base.py`.
The gateway never sees past that interface — Ollama, LangGraph, or Claude
SDK are all interchangeable behind it.

## Key directories

| Path | Purpose |
|---|---|
| `app/main.py` | FastAPI app, lifespan, all HTTP routes |
| `app/config.py` | Pydantic settings (env vars) |
| `app/agent_base.py` | `BaseAgentRuntime` ABC + `AgentResult` |
| `app/registry.py` | Agent registry + `AgentConfig` |
| `app/skill_registry.py` | Skill registry + `Skill` dataclass |
| `app/schemas.py` | OpenAI-shaped Pydantic request/response models |
| `app/response_adapter.py` | `AgentResult` → OpenAI wire format |
| `app/model_catalog.py` | Provider-neutral LLM catalog (Ollama + OpenRouter) + capability flags |
| `app/state.py` | SQLite (aiosqlite): snapshots, deliveries, schedules, traces, images |
| `app/images.py` | Blob store, captioning, housekeeping |
| `app/diagnostics.py` | Trace recorder, Ollama concurrency gate, progress + trace SSE pub/sub |
| `app/scheduler.py` | DB-backed APScheduler wrapper (dynamic add/update/remove) |
| `app/push/pusher.py` | `Pusher` ABC + polling/FCM implementations |
| `app/agents/definitions.py` | **Where you register agents** |
| `app/runtimes/` | Runtime implementations (ollama, openai/OpenRouter, skillful, list_manager, langgraph, claude) |
| `app/skills/` | Progressive-disclosure skills (lists, notes, goals, images, websearch, today, canvas, artifact, scheduler, vm) |
| `app/tools/` | Tool implementations (list_ops, goal_ops, image_ops, memory, web_search, code_interpreter, vm_ops) |
| `vmm/` | Separate microVM sandbox daemon for the `vm` skill |
| `docs/ARCHITECTURE.md` | Detailed architecture reference |

## Registered agents

| id | Runtime | Purpose |
|---|---|---|
| `qwen-chat` | `OllamaRuntime` | Plain chat, no tools |
| `qwen-tools` | `OllamaRuntime` | web_search + code_interpreter placeholders |
| `test_model` | `OllamaRuntime` | Round-trip smoke test |
| `list_manager` | `ListManagerRuntime` | `{reply, ops}` envelope; direct API testing |
| `assistant` | `SkillfulOllamaRuntime` | **Primary agent** — all ten progressive-disclosure skills; `think=True` |

## Registered skills

All ten are in the `assistant`'s scope.

| id | folder | Purpose |
|---|---|---|
| `lists` | `lists/` | Read + mutate named lists and items |
| `notes` | `notes/` | Read voice-note transcripts |
| `goals` | `goals/` | Extract goals → draft plan → refine from feedback |
| `images` | `images/` | Escalation ladder: cached text → targeted vision |
| `websearch` | `websearch/` | Live web search via Tavily (`TAVILY_API_KEY`) |
| `today` | `today/` | Current date / time / weekday / timezone |
| `canvas` | `canvas/` | Render a React widget (chart/table/dashboard) in the app |
| `artifact` | `artifact/` | Deliver a long-form markdown doc to the full-screen reader + library |
| `schedules` | `scheduler/` | Approval-gated create/edit of scheduled runs (drafts only; user taps Approve) |
| `vm` | `vm/` | Persistent Linux sandbox — run/verify code, host long research runs. **Live** |

## How to run

```bash
# Local dev
uvicorn app.main:app --reload --port 8000

# Docker
docker-compose up
```

Requires a running Ollama server at `OLLAMA_BASE_URL` (default
`http://localhost:11434`). Copy `.env.example` to `.env` for local
configuration.

## Extension patterns

- **Add an agent:** edit `app/agents/definitions.py`, append a
  `registry.register(AgentConfig(...))` block. Nothing else changes.
- **Add a skill:** create `app/skills/<id>/SKILL.md` (YAML frontmatter +
  markdown body) and `tools.py` exporting `build_tools(state) -> list[(schema, fn)]`.
  Add the id to an agent's `skills=[...]` scope. Tool names must be
  globally unique across skills.
- **Add a runtime:** subclass `BaseAgentRuntime` in `app/agent_base.py`,
  implement `arun()` (and optionally `astream()`).
- **Add a push lane:** subclass `Pusher` in `app/push/pusher.py`, add an
  instance to the `pushers` list in `app/main.py::lifespan`.
- **Add a schedule:** at runtime via `POST /v1/schedules` (or the app's
  Schedules screen) — no restart. `schedules.yml` is read **only** to seed
  an empty DB on first boot.

## Important conventions

- **Tool calls are server-side only.** The client never sees OpenAI
  `tool_calls` deltas — the gateway runs the tool loop internally and
  returns a plain assistant message. This avoids replicating OpenAI's
  tool-call streaming wire format.
- **State locality: client owns, gateway caches.** The Android app's Room
  DB is authoritative. The gateway holds cached snapshots for scheduled
  runs and a delivery queue. Mutations are emitted as `ops` the client
  applies.
- **Output envelope.** `list_manager` returns `{reply, ops}` in
  `choices[0].message.content`; `assistant` adds
  `resolve_thread`, `no_response`, and the delivery extras `canvas`,
  `steps`, `artifacts`, `resume`, `schedule_proposals`. `reply` is
  `null` when `no_response` is true.
- **Per-request state.** Runtimes that need mutable state build a fresh
  `state = {...}` dict inside each `arun()` call — never mutate the
  singleton runtime instance, which is shared across concurrent requests.
- **Fault isolation.** Tool dispatch errors (bad args, missing tools,
  exceptions) return as recoverable `[error] ...` tool-result strings,
  never as 500s.
- **Load order matters.** `load_all_skills()` runs before
  `app/agents.definitions` is imported, so agents can resolve skill ids
  at construction time.
- **Diagnostics are fire-and-forget.** Trace recording never breaks a
  request — if no trace is active, all recorders are no-ops.

## Env vars

| name | default | purpose |
|---|---|---|
| `GATEWAY_API_KEY` | unset | Auth (Bearer token); unset = no auth |
| `OLLAMA_BASE_URL` | `http://localhost:11434` | Ollama server |
| `DEFAULT_MODEL` | `qwen3.8:latest` | Model for Ollama-backed agents |
| `OLLAMA_THINK` | `true` | Thinking mode default; `assistant` pins `think=True` |
| `OPENROUTER_API_KEY` | empty | Enables the OpenRouter provider; empty = Ollama-only |
| `OPENROUTER_MAX_TOOL_ITERATIONS` | `12` | Tool-loop cap on the remote path |
| `TAVILY_API_KEY` | empty | Enables the `websearch` skill; empty = "not configured" |
| `MAX_TOOL_ITERATIONS` | `50` | Ollama tool loop safety cap |
| `MAX_AUTO_CONTINUE` | `2` | Extra research passes after a cap before one-tap resume |
| `OLLAMA_MAX_CONCURRENCY` | `1` | Global concurrent Ollama call cap |
| `VISION_MODEL` | empty → `DEFAULT_MODEL` | Model for captioning |
| `IMAGE_STORE_MAX_MB` | `512` | Blob cap; LRU-evicts pixels, keeps captions |
| `STATE_DB_PATH` | `state/state.db` | SQLite path |
| `SCHEDULES_PATH` | `schedules.yml` | Seed-only schedule file (DB is the source of truth) |
| `VMMD_BASE_URL` | `http://host.docker.internal:5002` | Sandbox daemon URL |
| `SANDBOX_SCOPE` | `shared` | `shared` (one box for all turns) / `session` (per conversation) |

## HTTP endpoints

| Method + path | Purpose |
|---|---|
| `GET /v1/models` | List registered agents as model cards |
| `GET /v1/llm-models` | List selectable LLMs across providers (Ollama + OpenRouter) |
| `POST /v1/chat/completions` | Run an agent (stream or non-stream) |
| `POST /v1/snapshot/{session}` | Upload client state snapshot |
| `GET /v1/snapshot/{session}` | Read cached snapshot |
| `GET /v1/pending/{session}` | Drain delivery queue |
| `DELETE /v1/pending/{id}` | Ack a delivery |
| `GET·POST /v1/schedules` | List / create schedules |
| `PUT·DELETE /v1/schedules/{id}` | Update / delete a schedule |
| `PUT /v1/schedules/device-tz` | Re-time every `device`-mode schedule |
| `POST /v1/schedule/{id}/run` | Fire a schedule immediately |
| `GET /v1/progress/{session}` | SSE of live turn progress |
| `POST /v1/images/{session}` | Upload images (multipart, content-addressed) |
| `GET /v1/images/{sha}` | Image metadata (`?raw=1` for JPEG) |
| `GET·PUT /v1/sandbox/approval-mode` | Read/set sandbox command-approval mode (proxy) |
| `GET /v1/sandbox/approvals` + `…/{id}/approve`·`/deny` | Act on held sandbox commands (proxy) |
| `GET /healthz` | Health check |
| `GET /diag` | Diagnostics dashboard |
| `GET /diag/traces` | Trace list |
| `GET /diag/traces/{id}` | Full trace call tree |
| `GET /diag/stats` | Ollama concurrency gauge |
| `GET /diag/stream` | SSE live tail |

## Further reading

- `docs/ARCHITECTURE.md` — comprehensive architecture reference with
  end-to-end flow diagrams, wire contracts, and state schema details.
- `docs/FCM_SETUP.md` — how to wire up real-time FCM push.
- `vmm/README.md` — the microVM sandbox daemon.