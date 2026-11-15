# VoiceNote ecosystem: runbook

How to bring up, check, and fix every service behind the VoiceNote app, and how
the pieces fit together. It's for someone new to the stack, or the author six
months from now.

For *how the code works*, see [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md).
This doc is about *running it*.

> Snapshot as of 2026-11-15. Ports, paths and model names were checked against
> the live box that day. If something here disagrees with reality, trust
> `docker ps`, `systemctl status`, and the compose files, and then fix this doc.

---

## 1. The 30-second picture

```
 Android phone (VoiceNote app)
   │  Tailscale tailnet → Windows host  "<your-pc>.<your-tailnet>.ts.net"
   │  (Tailscale runs on WINDOWS; ports are published to Windows by Docker Desktop)
   │
   ├──:10301  whisper-api ──▶ parakeet-api (GPU, NeMo)            speech → text
   │                       └─▶ faster-whisper (Wyoming :10300)   fallback
   │
   └──:5001   agent-gateway (FastAPI)          the brain the app talks to
                 │
                 ├─▶ Ollama          :11434  local models (default qwen3.8, has vision)
                 ├─▶ vLLM            :8000   local Qwen3.8-27B, text-only, fast (optional)
                 ├─▶ OpenRouter      (cloud) hosted models (Anthropic, OpenAI…)
                 ├─▶ Tavily          (cloud) web search
                 ├─▶ Kev             :8009   "System One" typed decisions (smart approvals)
                 └─▶ vmm-daemon      :5002   sandbox manager
                        └─▶ vmm-<id> container  the agent's Linux box (/workspace persists)
                               ▲
                               └── vmm-daemon asks the gateway's /v1/systemone (→ Kev)
                                   "is this command safe?" before running it
```

**Where things physically run:** one Windows 11 PC with an RTX PRO 6000 (96 GB).
- **Windows:** Docker Desktop and Tailscale.
- **WSL2 Ubuntu:** everything else.
  - **systemd services:** Ollama, vLLM, Kev.
  - **Docker containers:** gateway, vmm, sandboxes, whisper.

The phone only ever talks to two ports: **5001** (the gateway) and **10301**
(transcription).

---

## 2. Inventory

| Service | Runs as | Port | Source / config | Auto-starts? | Health check |
|---|---|---|---|---|---|
| **agent-gateway** | Docker container `agent-gateway` | 5001 | `gateway/` (`docker-compose.yml`, `.env`; `app/` is bind-mounted) | yes (`restart: unless-stopped`) | `curl localhost:5001/healthz` |
| **vmm-daemon** | Docker container `vmm-daemon` (compose profile `vmm`) | 5002 | same repo, `vmm/` (`vmm/config.yml`) | yes | `curl localhost:5002/vms` |
| **sandbox** | Docker container `vmm-<id>`, image `vmm-sandbox:base` | none | `vmm/Dockerfile.rootfs`, `vmm/profiles/base.pkgs` | created on first use; `/workspace` is on volume `vmm-ws-shared` | `docker ps \| grep vmm-` |
| **Ollama** | systemd `ollama.service` | 11434 | `/usr/local/bin/ollama`, models in `~/.ollama/models` | yes (enabled) | `curl localhost:11434/api/tags` |
| **vLLM** | systemd `vllm.service` | 8000 | venv `~/vllm-venv-cu128`; unit `/etc/systemd/system/vllm.service` (copy staged at `~/vllm.service`) | enabled, but **often stopped on purpose** to free VRAM | `curl localhost:8000/v1/models` |
| **Kev** (System One) | systemd `kev.service` | 8009 | `~/kev` (uv venv); key in `~/.config/kev/kev.env` | yes (enabled) | `curl localhost:8009/health` (with bearer) or the gateway's `GET /v1/systemone/health` |
| **whisper-api** (transcription router) | Docker container `whisper-api` | 10301 | `asr/` (`docker-compose.yml`, `whisper_http_wrapper.py`) | yes | `curl localhost:10301/health` → `"default_engine":"parakeet"` |
| **parakeet-api** (GPU ASR, default engine) | Docker container `parakeet-api` | 10302 (debug) | same compose (`Dockerfile.parakeet`, `parakeet_server.py`) | yes | `curl localhost:10302/health` → `"status":"ok"` |
| **faster-whisper** (Wyoming ASR, fallback) | Docker container `faster-whisper` | 10300 | same compose | yes | used through whisper-api |
| **Tailscale** | **Windows** app | none | Windows tray | Windows startup | phone can open `http://<your-pc>.<your-tailnet>.ts.net:5001/diag` |
| **Docker Engine** | Docker Desktop (Windows, WSL integration) | none | Windows | Windows startup | `docker ps` in WSL |

**Cloud dependencies:** OpenRouter (hosted models) and Tavily (web search).
Their keys live only in the gateway's `.env`.

**GPU budget (96 GB card):** about what each takes when loaded.

| Service | VRAM |
|---|---|
| Ollama, qwen3.8 | ~17–20 GB, unloaded after 30 min idle (`OLLAMA_KEEP_ALIVE=30m`) |
| vLLM, `--gpu-memory-utilization 0.50` | ~44 GB |
| Kev-4B | ~20 GB |
| Parakeet (0.6B) | ~2–3 GB, always resident |

All three fit together. When you need room for something else, stop vLLM first.

---

## 3. Cold start (after a reboot)

Almost everything comes back by itself. Order matters only for the first few
seconds.

1. **Windows boots** and starts **Tailscale** and **Docker Desktop**.
   - Check that Docker Desktop has *WSL integration* on for Ubuntu.
2. **Open a WSL shell** (`wsl -d ubuntu`). This boots the distro and its
   systemd, which starts **ollama**, **kev** and **vllm**.
   - vLLM is enabled, but if it was stopped on purpose it stays stopped. Start
     it only if you want it (§4.4).
3. **Docker containers** with `restart: unless-stopped` come back once Docker
   Desktop's engine is up: gateway, vmm-daemon, whisper-api and faster-whisper.
4. **The sandbox container** (`vmm-…`) may be gone (the reaper, or a reboot).
   That's fine: the gateway recreates it on the next `vm_exec`, and
   `/workspace` survives on the `vmm-ws-shared` volume.
5. **Run the health check** (§5).

> WSL only runs while something holds it open. If the phone can't reach anything
> after a reboot, open a WSL terminal once, or set WSL/Docker Desktop to start
> at login.

From scratch, if the containers don't exist yet:

```bash
cd gateway && docker compose up -d --build        # gateway + vmm
```

```bash
cd asr && docker compose up -d --build        # parakeet-api + whisper-api + faster-whisper
```

```bash
sudo systemctl enable --now ollama kev        # vllm optional: sudo systemctl enable --now vllm
```

The first sandbox use needs the image `vmm-sandbox:base`. Build it once (about
15 min, because the Debian mirror is slow):

```bash
cd gateway && bash vmm/build_image.sh base
```

---

## 4. Each service

### 4.1 agent-gateway (port 5001)

The FastAPI app the phone talks to. It holds:
- the agent loop and skills;
- the delivery queue;
- schedules;
- the state DB;
- the media and image stores;
- the `/diag` and `/admin` web pages.

- **Start / restart:** `docker compose up -d gateway` (also re-applies `.env`
  and compose changes), or `docker restart agent-gateway`.
- **Logs:** `docker logs -f agent-gateway`.
- **Code edits:** `app/` is bind-mounted and uvicorn runs with `--reload`, so
  Python edits apply on save. **Each reload kills any turn in flight**; the
  phone then shows "interrupted — Resend".
  - **SKILL.md, config or table changes:** need `docker restart agent-gateway`.
  - **`.env` or compose changes:** need `docker compose up -d gateway`, which
    recreates the container.
- **Web pages:**
  - `http://<host>:5001/diag`: live traces, concurrency, the SSE tail. It is
    **unauthenticated**; keep it on the tailnet.
  - `http://<host>:5001/admin`: the agent and skill registry, the wiki, and
    proposals.
- **State:** `./state/` on the host. This holds:
  - `state.db`, the SQLite database: deliveries, schedules, traces, media
    index, registry overlay;
  - `images/` and `media/`, content-addressed blobs;
  - `wiki/`, a git repo.

  **Back this folder up.** Nothing else holds this data.
- **Config:** `.env` holds the key *names* below; never commit or paste their
  values.
  - `OPENROUTER_API_KEY`, `TAVILY_API_KEY`, `KEV_API_KEY`.
  - `COMPOSE_PROFILES=vmm`, `VMM_APPROVAL_MODE`.
  - Optional: `GATEWAY_API_KEY`, `ANTHROPIC_API_KEY`, `JEV_*`.
  - Defaults such as `DEFAULT_MODEL=qwen3.8:latest`, `VLLM_*`, `KEV_BASE_URL`
    and `TZ=Pacific/Honolulu` are in `docker-compose.yml`.
- **Auth:** if `GATEWAY_API_KEY` is set, every client must send
  `Authorization: Bearer <key>`; set the same key in the app's Settings.
  **It is currently unset, so the API is open to anything on the tailnet.**
- **Testing code:** run scripts *inside* the container, because the host
  Python lacks the deps:

  ```bash
  MSYS_NO_PATHCONV=1 wsl -e docker exec -e PYTHONPATH=/app -w /app agent-gateway python /app/app/<test_file>.py
  ```

  Drop `MSYS_NO_PATHCONV=1 wsl -e` when running from inside WSL. Put test files
  under `app/`, which is bind-mounted. Example: `app/test_pending_longpoll.py`.

### 4.2 vmm-daemon and the sandbox (port 5002)

`vmmd` creates and drives sandbox containers through the host's docker socket.
The gateway's `vm`/`video` skills call it.

- **Restart:** `docker restart vmm-daemon`. It has **no `--reload`**, so source
  edits under `vmm/vmmd/` need this restart. Env changes need
  `docker compose up -d vmm`. Sandboxes survive a daemon restart.
- **Approval modes:**
  - `VMM_APPROVAL_MODE` in `.env` wins over `vmm/config.yml`, and the app can
    flip it live.
  - The modes are `smart` (default: Kev screens each command and only flagged
    ones are held), `manual` (hold everything) and `auto`.
  - Smart mode **fails closed**: if the gateway or Kev is down, commands are
    held, not run.
- **The shared box:** one sandbox keyed `shared`.
  - `/workspace` is on the named volume `vmm-ws-shared`, so destroying or
    recreating the container keeps the files.
  - The box has internet access, but private-network egress is blocked
    (`vmm/egress-lockdown.sh`). It **cannot** reach the gateway.
- **Image changes** (adding packages):
  1. Edit `vmm/profiles/base.pkgs` and/or `vmm/Dockerfile.rootfs`.
  2. Rebuild: `bash vmm/build_image.sh base`.
  3. Recreate the box, keeping `/workspace`: `bash vmm/migrate_shared_box.sh`.

  An existing box ignores image changes until it is recreated.
- **What's baked in:**
  - python3/pip, node/npm/typescript;
  - Playwright and Chromium (launch with `--no-sandbox`);
  - ffmpeg, numpy, Pillow, OpenCV;
  - the `vn-video` CLI.
- **Smoke tests:** `vmm/smoke.sh`, and `vmm/smart_eval.py`, the smart-approval
  regression set (23/23 expected).

### 4.3 Ollama (port 11434)

The default model host. `qwen3.8:latest` handles everything that needs
**vision** (images, video frames), plus any turn vLLM can't take.

- **Manage:** `sudo systemctl restart ollama`; logs with
  `journalctl -u ollama -f`. Models: `ollama list` / `ollama pull <tag>`.
- **Unit env:** `OLLAMA_HOST=0.0.0.0` (so containers reach it through
  `host.docker.internal`), `OLLAMA_KEEP_ALIVE=30m`, `OLLAMA_NUM_PARALLEL=2`.
- **Gotcha:** Ollama 0.40 runs qwen3 requests one at a time even with
  `NUM_PARALLEL=2`, so local sub-agents don't actually run in parallel.

### 4.4 vLLM (port 8000), optional and fast

A native WSL install, not Docker: the Docker image deadlocks on this Blackwell
GPU. It serves `nvidia/Qwen3.8-27B-NVFP4` as `qwen3.8-27b`, **text only**, with
a 128k context.

- **Start / stop:** `sudo systemctl start vllm` / `sudo systemctl stop vllm`.
  Startup takes **about 3 minutes**. Logs: `journalctl -u vllm -f`.
- **When it's down:** you lose nothing but speed. The gateway sends vLLM-picked
  turns that need vision, an over-long context or video to Ollama, and the app
  shows vLLM models as unavailable.
- **Unit changes:**
  1. Edit `~/vllm.service`.
  2. Install it: `sudo bash -c 'cp $HOME/vllm.service /etc/systemd/system/ && systemctl daemon-reload && systemctl restart vllm'`
- **Fragile bits** (see the comments in the install scripts in `~`):
  - It must be the **cu128** build: vllm 0.24 with torch cu128. The cu130 build
    hangs.
  - It needs the prebuilt `flashinfer-jit-cache==0.6.12` wheel.
  - `~/.cache/huggingface` must be owned by your user.

### 4.5 Kev, "System One" (port 8009)

A small model (`jaredpalmer/kev-4b@v1.0`) that answers typed questions (yes/no,
choice, score). The user never sees it. Its job is **smart approvals** for
sandbox commands.

- **Manage:** `sudo systemctl restart kev`; logs with `journalctl -u kev -f`.
  Its bearer key is in `~/.config/kev/kev.env`, mirrored into the gateway's
  `.env` as `KEV_API_KEY`.
- **Path:** vmm-daemon calls `http://gateway:5001/v1/systemone`, and the gateway
  forwards to Kev at `host.docker.internal:8009`. vmm has no
  `host.docker.internal`, which is why it goes through the gateway.
- **Switching to hosted Jev:** set `JEV_BASE_URL` and `JEV_API_KEY`, then
  `SYSTEMONE_PROVIDER=jev`. No code change.

### 4.6 Transcription: whisper-api (10301), Parakeet (10302), faster-whisper (10300)

A separate compose project in `asr/`, with three
containers:
- **`whisper-api`**: the router the phone calls. It takes `POST /v1/audio`
  (multipart `file` plus `model`) and returns `{"text": …, "engine": …}`.
- **`parakeet-api`**: the **default engine**. NVIDIA NeMo
  `parakeet-tdt-0.6b-v2` on the GPU, kept resident, about 0.1–0.3 s per short
  clip.
- **`faster-whisper`**: a Wyoming CPU engine, about 1 s or more per short clip.

**Routing.** `model=faster-whisper-*` goes to faster-whisper; anything else,
including the app's `parakeet-tdt-0.6b-v2`, goes to Parakeet. If Parakeet is
down, loading, or errors, the router **falls back to faster-whisper**, so a
note still gets transcribed. The response's `engine` field says which engine
answered (`parakeet` or `faster-whisper (fallback)`).

- **Rebuild / restart** (after editing either `.py` file):

  ```bash
  cd asr && docker compose up -d --build parakeet-api whisper-api
  ```

  Parakeet is healthy about 20 s after start, once its model is cached in
  `data/parakeet_cache`. The first ever start downloads about 2.5 GB.
- **Health:**
  - `curl localhost:10301/health` should show
    `"parakeet":"ok","default_engine":"parakeet"`.
  - Parakeet only reports `ok` after a **warm-up transcription** succeeds at
    startup, so a broken GPU path shows as `error`, not `ok`.
- **Logs:** `docker logs -f whisper-api`. Each request logs
  `Transcription [parakeet] …` or a `falling back` warning.
- **App setting:** "Transcription endpoint" = `http://<your-pc>.<your-tailnet>.ts.net:10301/v1/audio`.
  The app sends `model=parakeet-tdt-0.6b-v2`.
- **No server needed:** the app also has an on-device transcription option in
  Settings. It's off by default.
- **WSL gotcha (fixed in `parakeet_server.py`):**
  - **What NeMo does:** its data loader force-enables PyTorch's
    `expandable_segments` allocator mode at `transcribe()` time, and it does
    this even over `PYTORCH_CUDA_ALLOC_CONF`.
  - **Why that breaks here:** WSL2 doesn't handle that mode's GPU memory
    mapping. The first convolution dies with `CUDA driver error: unknown error`,
    and later calls fail with a `CUDACachingAllocator … handles_` assert.
  - **The fix:** the server turns NeMo's switch into a no-op
    (`_disable_expandable_segments`), and compose sets
    `PYTORCH_CUDA_ALLOC_CONF=expandable_segments:False`.

  If you ever upgrade NeMo and see those errors again, check that the patched
  function still exists.
- **Test with real speech:** a short sample is at `/tmp/asr_sample/jfk.wav`
  (from the whisper.cpp repo); re-download it if `/tmp` was cleared.

  ```bash
  curl -s -F file=@/tmp/asr_sample/jfk.wav -F model=parakeet-tdt-0.6b-v2 localhost:10301/v1/audio
  ```

### 4.7 Tailscale (Windows)

The phone reaches the PC over the tailnet as **`<your-pc>.<your-tailnet>.ts.net`**.
Docker Desktop publishes container ports on Windows (`0.0.0.0:5001`, `:10301`),
so Tailscale on Windows exposes them without any `tailscale serve` setup.

- The WSL distro also has a `tailscaled` installed, but it's **logged out and
  unused**; ignore it.
- **Phone dozing:** while the phone is in Doze, tailnet DNS may not resolve,
  so gateway calls fail until it wakes. Expected.

### 4.8 The Android app (VoiceNote)

- **Repo:** `android/` (Kotlin, Room,
  WorkManager; no Gradle wrapper).
- **Build (PowerShell):**

  ```powershell
  $env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
  $gradle = (Get-ChildItem "%USERPROFILE%\.gradle\wrapper\dists\gradle-8.2-bin\*\gradle-8.2\bin\gradle.bat")[0].FullName
  & $gradle :app:assembleDebug        # → app\build\outputs\apk\debug\app-debug.apk
  ```

- **Install:**

  ```bash
  adb devices        # USB: <device-serial>; wireless: adb-<device-serial>-…_adb-tls-connect._tcp
  ```

  ```bash
  adb -s <serial> install -r app/build/outputs/apk/debug/app-debug.apk
  ```

  adb lives at `%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe`. The package
  id is `com.voicenote.app.debug`.
- **Settings to fill in:**
  - **Gateway URL:** `http://<your-pc>.<your-tailnet>.ts.net:5001`.
  - **Gateway API key:** only if `GATEWAY_API_KEY` is set.
  - **Transcription endpoint:** see §4.6.
  - **Assistant model:** the picker is grouped endpoint › maker › model.
  - **Sandbox approvals:** Smart, Manual or Off.
- **Database upgrades:** Room migrations are explicit; there's no destructive
  fallback. Before each upgrade, the old DB is copied to
  `databases/voicenote.db.v<N>.bak`.
- **Inspecting the DB:**
  1. Pull it with `adb exec-out run-as com.voicenote.app.debug cat databases/voicenote.db > out.db`.
  2. Pull `-wal` and `-shm` the same way.
  3. Read it with Python's `sqlite3`.

---

## 5. Health check (paste into a WSL shell)

```bash
echo "--- containers"; docker ps --format '{{.Names}}\t{{.Status}}' | grep -E 'agent-gateway|vmm-|whisper|parakeet'
echo "--- systemd";    systemctl is-active ollama kev vllm | paste - - - | sed 's/^/ollama kev vllm: /'
echo "--- gateway";    curl -s -m 5 localhost:5001/healthz | head -c 200; echo
echo "--- pending";    curl -s -m 5 localhost:5001/v1/pending/voicenote-default | python3 -c 'import sys,json;d=json.load(sys.stdin);print("queued",len(d["deliveries"]),"busy",d["busy_threads"],"next_due",d["next_due"])'
echo "--- vmm";        curl -s -m 5 localhost:5002/vms | head -c 200; echo
echo "--- ollama";     curl -s -m 5 localhost:11434/api/tags | python3 -c 'import sys,json;print([m["name"] for m in json.load(sys.stdin)["models"]][:5])'
echo "--- vllm";       curl -s -m 5 localhost:8000/v1/models | head -c 120 || true; echo
echo "--- kev";        curl -s -m 5 localhost:5001/v1/systemone/health | head -c 200; echo
echo "--- asr";        curl -s -m 5 localhost:10301/health; echo        # want "default_engine":"parakeet"
echo "--- gpu";        nvidia-smi --query-gpu=memory.used,memory.total --format=csv,noheader
```

**What healthy looks like:**
- the containers are `Up`: agent-gateway, vmm-daemon, whisper-api,
  parakeet-api, faster-whisper, and the sandbox if it has been used;
- ollama and kev are `active` (vllm may be `inactive` by choice);
- each curl returns JSON.

Then, from the phone, open `http://<your-pc>.<your-tailnet>.ts.net:5001/diag`. If it
loads, the tailnet path works.

---

## 6. How a request flows

1. **Voice note.**
   - The app records audio and posts it to `:10301/v1/audio`, which returns the
     text. Or the app transcribes on-device.
   - Any photos and videos are uploaded first: `POST /v1/images/...` and
     `POST /v1/media/{session}`.
2. **Turn.** The app sends `POST :5001/v1/chat/completions` with `X-Async: 1`
   and the thread id. The gateway answers **202** right away and runs the turn
   in the background.
3. **Agent loop.**
   - The `assistant` agent picks a provider: Ollama, vLLM or OpenRouter, with
     guardrails for vision, context length and video.
   - It loads skills as needed and calls tools:
     - web search (Tavily);
     - the sandbox (vmm → Kev screening → `vmm-<id>`);
     - video frames, documents, lists, schedules and so on.
   - Live progress streams over SSE at `/v1/progress/{session}`.
4. **Delivery.**
   - The reply is written to the SQLite delivery queue. The phone long-polls
     `GET /v1/pending/{session}?wait=50`, applies the reply **exactly once**,
     and acks it.
   - If the gateway restarted mid-turn, the phone sees the turn is no longer
     running and posts "interrupted — Resend".
5. **Scheduled check-ins.**
   - APScheduler in the gateway runs the agent on cron and queues the reply
     the same way.
   - The phone sets an alarm for just after the next due time.
   - It also checks every 15 min as a backstop, and on app open.

There is no push notification service yet. FCM is stubbed; see
`docs/FCM_SETUP.md`.

---

## 7. Troubleshooting

| Symptom | Likely cause | Fix |
|---|---|---|
| Phone: "Unable to resolve host" / can't connect | Phone dozing, tailnet down, or WSL/Docker not running | Wake the phone; check Tailscale on Windows; open a WSL shell; `docker ps` |
| Thread stuck on "Thinking…" | Turn still running (check `/diag`), or old app build | `/diag` shows live traces. New builds post "interrupted" for lost turns within about a minute |
| "This reply was interrupted" | Gateway restarted mid-turn (a code edit with `--reload`, or a restart) | Tap the message → Resend. Avoid editing gateway code during long turns |
| Sandbox commands never run / always "held" | Smart mode and Kev or the gateway are down (fails closed), or Manual mode | `systemctl status kev`; `/v1/systemone/health`; approve from the notification; or switch mode in Settings |
| Model picker shows vLLM models greyed out / turns re-routed | vLLM stopped | `sudo systemctl start vllm` (~3 min), or ignore it, since Ollama takes over |
| Video or image turn ignores the picked model | Expected: video and uncaptioned images need vision → Ollama `qwen3.8` | none |
| vLLM hangs at startup, GPU idle | Wrong build (cu130 / Docker) | Use the `~/vllm-venv-cu128` venv; see the `~/vllm_*` scripts |
| vLLM PermissionError on `.locks` | HF cache owned by root | `sudo chown -R $USER:$USER ~/.cache/huggingface` |
| Gateway reload "Waiting for connections to close" forever | Old compose command | `docker restart agent-gateway`; the compose command has `--timeout-graceful-shutdown 3` |
| `docker exec … /app/...` fails from Git Bash with odd paths | MSYS path conversion | Prefix `MSYS_NO_PATHCONV=1` |
| Sandbox lacks a package after editing `base.pkgs` | Box predates the image | `bash vmm/build_image.sh base && bash vmm/migrate_shared_box.sh` |
| Transcription returns empty text | Silent or too-short audio | `docker logs whisper-api` shows which engine ran and what it heard |
| Transcription slow (~1 s+) / `engine: faster-whisper (fallback)` | Parakeet down, loading or erroring | `curl localhost:10302/health`; `docker logs parakeet-api`; `docker compose up -d parakeet-api` |
| Parakeet `CUDA driver error: unknown error` / `handles_` assert | NeMo re-enabled expandable segments (WSL) | See the WSL gotcha in §4.6 |
| App crashes on launch after an update | Room migration failed | `adb logcat`; the pre-migration copy is `databases/voicenote.db.v<N>.bak` |

---

## 8. Known gaps and to-dos

- **`GATEWAY_API_KEY` is unset:** every endpoint, including uploads, is open to
  the tailnet.
- **No automated backups** of `agent-gateway/state/` or the `vmm-ws-shared`
  volume.
- **Turns run in gateway memory:** a restart loses in-flight turns. The phone
  says so, but nothing resumes them.
- **FCM isn't wired:** delivery relies on long-polls, alarms and the 15-min
  backstop.
- **Old sandbox backup:** a host copy of the pre-migration sandbox workspace
  sits at `~/vmm-workspace-backup-shared-20261114-223335`. Delete it once
  you're sure.

---

## 9. Where to read more

| Topic | Doc |
|---|---|
| Gateway internals, endpoints, tables, delivery, video, artifacts | [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md) |
| Working on the gateway code (for AI agents and humans) | [`AGENTS.md`](AGENTS.md) |
| Sandbox daemon, backends, profiles | [`vmm/README.md`](vmm/README.md) |
| Enabling real-time push | [`docs/FCM_SETUP.md`](docs/FCM_SETUP.md) |
