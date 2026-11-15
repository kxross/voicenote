"""Central settings. Everything here is overridable via environment variables
or a local .env file (copy .env.example -> .env to start)."""

from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    model_config = SettingsConfigDict(env_file=".env", extra="ignore")

    # Gateway auth — optional. If unset, the gateway accepts unauthenticated
    # requests, which is fine for local dev and nothing else.
    gateway_api_key: str | None = None

    # Local Ollama server backing the OllamaRuntime and LangGraphRuntime agents.
    ollama_base_url: str = "http://localhost:11434"
    default_model: str = "qwen3.8:latest"

    # Reasoning/"thinking" mode for thinking-capable models (e.g. qwen3).
    # Default OFF: these models think by default, so we send think=false to
    # actively disable it (faster, cheaper, no reasoning preamble). Set
    # OLLAMA_THINK=true to turn it on. No effect on non-thinking models.
    ollama_think: bool = True

    # Context window. num_ctx is sized to this fraction of the model's trained
    # max context (queried once from Ollama's /api/show), so a long multi-tool
    # research loop can't overrun the small default window and 500 the model.
    # 0.8 keeps headroom under the trained max. Lower OLLAMA_CTX_FRACTION if the
    # resulting KV cache pushes VRAM. The fallback is used only when /api/show
    # can't report a context length for the model.
    ollama_ctx_fraction: float = 0.8
    ollama_num_ctx_fallback: int = 8192
    # Absolute ceiling on the computed num_ctx, as a guard against a model that
    # advertises an enormous positional window (e.g. qwen3 reports 262144) whose
    # KV cache would OOM a smaller GPU. On a big card this should be high enough
    # that the 0.8× fraction governs: the 95 GiB RTX PRO 6000 here runs the full
    # 262144 window comfortably (Ollama's own vram-based default), so the cap is
    # set to match. Lower OLLAMA_NUM_CTX_MAX on a VRAM-constrained machine.
    ollama_num_ctx_max: int = 262144

    # Claude Agent SDK reads ANTHROPIC_API_KEY itself; kept here for visibility.
    anthropic_api_key: str | None = None

    # --- OpenRouter (second model provider) ------------------------------
    # Empty key = provider disabled; the model catalog then lists Ollama only
    # and nothing else changes. OpenRouter is OpenAI-wire-compatible, so it's
    # served by OpenAICompatRuntime rather than the Ollama tool loop.
    openrouter_api_key: str = ""
    openrouter_base_url: str = "https://openrouter.ai/api/v1"
    # Attribution headers OpenRouter shows on its dashboard/leaderboards.
    openrouter_referer: str = "https://github.com/voicenote/agent-gateway"
    openrouter_title: str = "VoiceNote assistant"
    # Cost ceiling. 50 round trips of full snapshots is fine when inference is
    # free and local; at per-token prices it is not.
    openrouter_max_tool_iterations: int = 12
    # Remote capacity is elastic and latency-bound, so it does NOT want the
    # single-slot gate that keeps one big local model from thrashing.
    openrouter_max_concurrency: int = 4

    # --- vLLM (third provider: local, OpenAI-compatible) ----------------
    # A local vLLM server (continuous batching) alongside Ollama + OpenRouter.
    # Empty base_url = provider disabled (the model catalog then omits it, like
    # OpenRouter with no key). Served via OpenAICompatRuntime with gate_provider
    # "vllm". Unlike Ollama (serialises), vLLM batches, so its gate is HIGH.
    vllm_base_url: str = ""               # e.g. http://host.docker.internal:8000/v1
    vllm_model: str = "qwen3.8-27b"       # --served-model-name on the vLLM server
    vllm_api_key: str = "EMPTY"           # vLLM ignores it; any non-empty value
    vllm_max_concurrency: int = 16
    # Must match the vLLM server's --max-model-len. The skillful runtime's
    # context guardrail uses this to decide when a turn's preloaded context is
    # too big for vLLM and must fall back to the big-window Ollama default.
    vllm_max_model_len: int = 131072

    # --- System One (typed decisions: noul / choice / score) ------------
    # One TypeSafe-conforming /v1/systemone surface over two interchangeable
    # backends. "kev" = the open-source reproduction (jaredpalmer/kev) served
    # on the WSL host; "jev" = TypeSafe's hosted model (same API). Empty =
    # endpoint disabled (503). A request `model` starting with "kev"/"jev"
    # routes that call to that backend regardless of the default.
    systemone_provider: str = ""            # "kev" | "jev" | ""
    systemone_timeout_seconds: float = 60   # a scale-to-zero host cold-starts ~35s
    kev_base_url: str = ""                  # e.g. http://host.docker.internal:8009
    kev_api_key: str = ""                   # must match the server's KEV_API_KEY
    kev_model: str = "kev-latest"
    jev_base_url: str = ""                  # TypeSafe's System One base URL (from your TypeSafe account)
    jev_api_key: str = ""
    jev_model: str = ""                     # empty = let the caller / upstream choose

    # --- Tavily (web search backend) ------------------------------------
    # Empty key = web_search is disabled and returns a clear "not configured"
    # message instead of crashing. Add TAVILY_API_KEY to .env (same place as
    # OPENROUTER_API_KEY) to turn it on. search_depth "basic" is one credit
    # per call; "advanced" is deeper (more credits).
    tavily_api_key: str = ""
    tavily_base_url: str = "https://api.tavily.com"
    tavily_max_results: int = 5
    tavily_search_depth: str = "basic"   # "basic" | "advanced"

    # Tool-loop safety valve (Ollama runtime): max round trips through the
    # model before we give up and return whatever text we have. With
    # progressive-disclosure skills, indexed goals fetched via get_goal, and
    # multi-op edits within a single request, 5 is far too tight — the agent
    # can burn 3-4 iterations on skill loading + fetches alone.
    max_tool_iterations: int = 50

    # Hard cap on how much of a tool's result is fed back to the model (chars),
    # so one big output (a scraped page printed to stdout, a huge file cat'd)
    # can't blow the local model's context. The truncation note tells the model
    # to save the full output to a file and grep/extract instead. ~8 KB ≈ 2k
    # tokens; generous for normal results, protective against accidents.
    max_tool_result_chars: int = 8000

    # Research auto-continue: when a checkpointed run (one that called
    # vm_new_run) hits max_tool_iterations mid-task, the runtime re-seeds a
    # fresh context from the run's STATE.json and keeps going, up to this many
    # extra passes. After that it hands off to one-tap resume on the phone.
    # Effective budget ≈ (per-pass iterations) × (1 + max_auto_continue).
    # 0 disables auto-continue (cap -> straight to one-tap resume).
    max_auto_continue: int = 2

    # Global cap on concurrent Ollama /api/chat calls across all agents and
    # triggers. A large local model serialises inference anyway; 1 keeps
    # requests from swamping it. The /diag dashboard shows the live gauge.
    ollama_max_concurrency: int = 1

    # --- sub-agents (orchestration) -------------------------------------
    # An orchestrator agent (enable_subagents) can delegate to up to this many
    # agents at once. Their Ollama calls run under a DEDICATED gate (provider
    # "ollama-sub") sized here, so fan-out truly runs in parallel on the GPU
    # while the main gate stays at ollama_max_concurrency (1) — the rest of the
    # system's concurrency is unchanged. NOTE: for two LOCAL calls to overlap,
    # the Ollama server also needs OLLAMA_NUM_PARALLEL >= 2; two resident
    # contexts double the KV cache, so lower ollama_num_ctx_max if VRAM is tight.
    subagent_concurrency: int = 2
    # Hard cap on sub-agents fanned out per orchestrator turn.
    subagent_max_parallel: int = 2
    # Per-sub-agent wall-clock budget; a research sub-agent can run minutes.
    subagent_timeout_seconds: int = 600
    # num_ctx ceiling for SUB-AGENT model calls only (top-level turns keep the
    # full ollama_num_ctx_max window). Lowering this lets TWO sub-agent contexts
    # fit in VRAM at once, which is the prerequisite for real parallel fan-out.
    # BUT: Ollama 0.40.14 refuses parallel requests for the qwen3 architecture
    # ("model architecture does not currently support parallel requests"), so on
    # the current local model two sub-agents serialise regardless — and a value
    # BELOW the main window would only thrash the model (reload on every 209k↔sub
    # switch) for no gain. So it defaults to the full window (no-op); lower it
    # (e.g. 32768) only once sub-agents run on a parallel-capable model/Ollama,
    # or on OpenRouter (which is already parallel via its own gate).
    subagent_num_ctx: int = 262144

    # --- images ---------------------------------------------------------
    # Model used for captioning and describe_image. Empty -> default_model,
    # which already reports vision capability, so there's no second model
    # resident and no VRAM eviction thrash. Point at a smaller vision model
    # (e.g. gemma3:12b-it-qat) to trade VRAM for captioning speed.
    vision_model: str = ""
    # Bump to invalidate every stored caption and re-caption lazily.
    caption_prompt_version: int = 1
    # Where blobs live; also the parent of state.db.
    state_dir: str = "state"
    # Blob-store size cap. When exceeded, the oldest blobs are evicted
    # (LRU by created_at) — but their caption records stay, so text recall
    # via get_image_text survives eviction forever. 0 disables the cap.
    image_store_max_mb: int = 512
    # Video attachments (app/media.py): per-upload cap, and the on-disk store
    # cap (LRU; summaries survive eviction). Frames are only ever extracted in
    # the sandbox, and a turn may show the model at most this many of them.
    media_max_mb: int = 300
    media_store_max_mb: int = 4096
    video_max_frames_per_turn: int = 48

    # --- sandbox / code interpreter (vmm daemon) -----------------------
    # The vmm daemon runs on the WSL host (it needs /dev/kvm, taps, iptables
    # for firecracker, or the docker socket), so from inside the gateway
    # container it's reached at host.docker.internal; on the host directly
    # it's localhost. The daemon selects firecracker vs docker per its own
    # config; `sandbox_backend` names which one this gateway asks for (empty
    # = let the daemon use its default_backend).
    vmmd_base_url: str = "http://host.docker.internal:5002"
    sandbox_backend: str = ""     # "docker" | "firecracker" | "" (daemon default)
    # How sandboxes are scoped. "shared" = one machine for every turn (pinned,
    # never idle-reaped). "session" = one machine per conversation (session id),
    # idle-reaped on the daemon's default TTL. Flipping this is the whole switch
    # from a single shared box to per-conversation — no other code changes.
    sandbox_scope: str = "shared"   # "shared" | "session"


settings = Settings()
