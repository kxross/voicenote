# VoiceNote

A voice-first personal assistant: an **Android app** you talk to, backed by a
self-hosted **agent gateway** running on your own GPU box. The app records
speech, a local GPU model transcribes it, and the gateway's assistant agent
handles the rest:
- manages lists, goals, notes and schedules;
- searches the web;
- writes documents;
- runs code in a sandboxed Linux box;
- watches videos;
- replies in the app.

```
 Android app (VoiceNote)
   │  your private network (e.g. Tailscale)
   ├──:10301 ─ asr/      whisper-api router ─▶ parakeet-api (GPU)  ─╮ speech → text
   │                                         └▶ faster-whisper (CPU) ╯ fallback
   └──:5001 ── gateway/  agent-gateway (FastAPI, Docker)
                  ├─▶ Ollama   :11434  local models (default; has vision)   [host, systemd]
                  ├─▶ vLLM     :8000   fast local text model (optional)     [host, systemd]
                  ├─▶ Kev      :8009   typed decisions → smart approvals (optional) [host, systemd]
                  ├─▶ OpenRouter / Tavily (cloud, optional keys)
                  └─▶ vmm-daemon :5002 ─▶ sandbox container (the agent's Linux box) [Docker]
```

| Folder | What it is |
|---|---|
| [`android/`](android/) | The Android app (Kotlin, Room, WorkManager). |
| [`gateway/`](gateway/) | agent-gateway (FastAPI) plus `vmm/`, the sandbox daemon and image. |
| [`asr/`](asr/) | Transcription stack: Parakeet (NVIDIA NeMo, GPU), a faster-whisper fallback, and the router. |
| [`setup/`](setup/) | The setup scripts, systemd unit templates, and the health check. |

Deeper docs:
- [`gateway/RUNBOOK.md`](gateway/RUNBOOK.md): operating every service day to day, plus troubleshooting.
- [`gateway/docs/ARCHITECTURE.md`](gateway/docs/ARCHITECTURE.md): how the code works.
- [`gateway/AGENTS.md`](gateway/AGENTS.md): guidance for working on the gateway.
- [`gateway/vmm/README.md`](gateway/vmm/README.md): the sandbox.

---

## Requirements

- **OS:** Linux with **systemd**. Either Ubuntu 22.04/24.04, or **WSL2 Ubuntu
  on Windows 11** with `[boot] systemd=true` in `/etc/wsl.conf`.
- **GPU:** an **NVIDIA GPU** and a working driver (`nvidia-smi` works).
  - Ollama's default model plus Parakeet need about 24 GB of VRAM.
  - Adding Kev (+20 GB) and vLLM (+44 GB) needs a 96 GB-class card. Both are
    optional.
- **Docker** with GPU access:
  - Windows: **Docker Desktop** with WSL integration on.
  - Native Linux: docker-ce plus the NVIDIA Container Toolkit.
  - Compose v2 either way.
- **Disk:** about 60 GB for images and models, more with vLLM.
- **Network:** a way for the phone to reach the box on ports **5001** and
  **10301**. Tailscale on both is easiest. On WSL, install the *Windows*
  Tailscale app; Docker Desktop publishes the ports on Windows.
- **For the app:** Android Studio, or JDK 17 and the Android SDK, and an
  Android 8.0+ phone.

## Quick start

```bash
git clone <this repo> voicenote && cd voicenote
```

**1. Host services** (Ollama, plus optional Kev and vLLM, as systemd units;
uses sudo):

```bash
bash setup/01-host.sh --with-kev        # add --with-vllm on a big GPU
```

**2. Containers** (gateway, sandbox and transcription; prints the app settings
at the end):

```bash
bash setup/02-services.sh
```

**3. Check everything:**

```bash
bash setup/healthcheck.sh
```

**4. Build and install the app** (from `android/`):

```bash
cd android && ./gradlew :app:assembleDebug        # Windows: gradlew.bat :app:assembleDebug
```

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

The first build needs the Android SDK: either open the folder once in Android
Studio, or create `android/local.properties` with `sdk.dir=/path/to/Android/Sdk`.

**5. In the app → Settings,** enter what `02-services.sh` printed:
- **Gateway URL:** `http://<your-pc>:5001`.
- **Gateway API key:** generated into `gateway/.env`.
- **Transcription endpoint:** `http://<your-pc>:10301/v1/audio`.

Then pick an assistant model; the picker is grouped endpoint › maker › model.
Record a note, and the reply arrives in the thread.

### What the scripts do

**`setup/01-host.sh`** runs as you and uses sudo where needed:
1. Checks prerequisites, and installs `uv`.
2. Installs **Ollama**. A systemd drop-in sets `OLLAMA_HOST=0.0.0.0`, so
   containers can reach it, plus keep-alive and parallel settings.
3. Pulls `DEFAULT_MODEL` (`qwen3.8:latest`).
4. With `--with-kev`: clones `jaredpalmer/kev` into `~/kev`, builds its venv
   (torch cu128 plus the pinned `flash-linear-attention`), writes
   `~/.config/kev/kev.env` with a random bearer key, and installs and starts
   `kev.service` on :8009.
5. With `--with-vllm`: builds `~/vllm-venv-cu128` (vllm 0.24.0, cu128, and the
   prebuilt FlashInfer kernels), then installs and starts `vllm.service` on
   :8000. It serves `nvidia/Qwen3.8-27B-NVFP4`, text only, with a 128k context.

**`setup/02-services.sh`:**
1. Creates `gateway/.env` from `.env.example` (mode 600) and generates
   `GATEWAY_API_KEY`.
2. Copies Kev's key in, or falls back to *manual* sandbox approvals if Kev
   isn't installed.
3. Optionally asks for OpenRouter and Tavily keys.
4. Builds the sandbox image `vmm-sandbox:base` (about 15 min the first time).
5. Starts the gateway and vmm: `docker compose up -d --build` in `gateway/`.
6. Starts transcription the same way in `asr/`. The first Parakeet start
   downloads about 2.5 GB.
7. Waits for health, then prints the app settings.

Both scripts are **idempotent**: re-run them after pulling changes.

## Configuration

Everything lives in **`gateway/.env`**; see
[`gateway/.env.example`](gateway/.env.example) for every key, with comments.

| Key | Required | Purpose |
|---|---|---|
| `GATEWAY_API_KEY` | recommended | Bearer token for the app and other clients |
| `DEFAULT_MODEL` | yes (default set) | Ollama model; must support vision for images and video |
| `OPENROUTER_API_KEY` | optional | Hosted models (Anthropic, OpenAI, etc.) in the model picker |
| `TAVILY_API_KEY` | optional | Web search for the agent |
| `KEV_API_KEY` | with Kev | Must match `~/.config/kev/kev.env` |
| `VMM_APPROVAL_MODE` | yes (default set) | `smart` needs Kev; otherwise `manual` or `auto` |
| `VLLM_BASE_URL` / `VLLM_MODEL` | with vLLM | Defaults match `01-host.sh` |

After editing `.env`, run `cd gateway && docker compose up -d`.

## Day-to-day

| Task | Command |
|---|---|
| Status of everything | `bash setup/healthcheck.sh` |
| Gateway logs / restart | `docker logs -f agent-gateway` / `docker restart agent-gateway` |
| Live traces | `http://<your-pc>:5001/diag` (keep it on your private network) |
| Agent and skill registry UI | `http://<your-pc>:5001/admin` |
| Free GPU memory | `sudo systemctl stop vllm` (turns fall back to Ollama) |
| Rebuild the sandbox after editing `gateway/vmm/profiles/base.pkgs` | `bash gateway/vmm/build_image.sh base && bash gateway/vmm/migrate_shared_box.sh` |
| Rebuild transcription | `cd asr && docker compose up -d --build` |

**Back up:**
- `gateway/state/`: the SQLite DB, uploaded media and the wiki;
- the `vmm-ws-shared` Docker volume: the sandbox's `/workspace`.

Neither is in git.

## What's *not* in this repo

The following are left out on purpose; the setup scripts recreate or download
them:
- **Secrets:** `.env`, `kev.env`, `local.properties` and signing keys.
- **Runtime state:** `gateway/state/`, sandbox keys and images, `asr/data/`.
- **Model weights.**
- **Network-specific details:** hostnames and device IDs. Docs use
  placeholders like `<your-pc>`.
