# vmm — sandbox daemon for the agent's code interpreter

`vmm` gives agents a real Linux box they drive with bash: run code, process
files, analyze images/video, build and verify software, install packages.
It's a small HTTP daemon (`vmmd`) plus a TUI, and it plugs into the gateway
as a skill (`vm`).

Two **backends** implement the same interface, chosen by config:

| | **docker** | **firecracker** |
|---|---|---|
| Isolation | shares host kernel (container) | kernel-isolated microVM (stronger) |
| Start time | sub-second | ~1–2 s |
| Privileged setup | **none** (open) | kvm group + one sudoers rule |
| Disk limit | advisory (overlay2) | enforced (ext4 sized to `disk_gb`) |
| Best for | fast, everyday sandboxing | untrusted code, hard isolation |

A **single daemon runs both at once**, each enabled only if its preflight
passes — so on a box without `/dev/kvm` you still get docker. Every sandbox
records which backend made it; the TUI shows and creates both.

## Layout

```
vmm/
├── vmmd/                 the daemon (FastAPI on :5002)
│   ├── main.py           HTTP API (lifecycle, exec, files, approvals)
│   ├── manager.py        backend-agnostic lifecycle, state, reconcile, limits
│   ├── approvals.py      manual-approval gate (+ SSE)
│   ├── reaper.py         idle-TTL destroy
│   ├── config.py         config.yml loader + paths
│   ├── util.py           subprocess / process helpers
│   └── backends/
│       ├── base.py       the Backend interface (start/exec/files/destroy/…)
│       ├── docker.py     container per sandbox (docker run/exec/cp)
│       └── firecracker.py microVM per sandbox (tap+NAT, ext4, SSH)
├── tui.py                Textual cockpit: Sandboxes + Agent tabs
├── config.yml            runtime config (default_backend, approval, limits, docker)
├── Dockerfile.rootfs     ONE profile definition → feeds both backends
├── profiles/*.pkgs       base / python-cv / node-web package lists
├── build_image.sh        build a profile as a docker image  (docker backend)
├── build_rootfs.sh       build a profile as an ext4 image   (firecracker backend)
├── setup.sh              one-time firecracker host setup (sudo)
├── net-helper.sh         firecracker tap+NAT (the only fc root surface)
└── smoke.sh              boot one microVM end to end (firecracker)
```

## Quick start — docker backend (no sudo)

Run everything with the project venv's interpreter — `textual`, `uvicorn`,
`fastapi`, `asyncssh` live in `.venv`, not the system Python. All commands are
from the repo root (`agent-gateway/`).

```bash
# 0. one-time: install the vmm deps into the venv
.venv/bin/pip install -r vmm/requirements-vmm.txt

# 1. build a profile image (reuses Dockerfile.rootfs; ~500MB, cached)
bash vmm/build_image.sh base
# (also: python-cv for opencv/ffmpeg, node-web for web-app tooling)
# Every profile includes python3/pip/venv, nodejs/npm, and typescript/ts-node
# from the Dockerfile base layer.

# CV profile — build + verify pipeline (ffmpeg, opencv, tesseract OCR):
#   bash vmm/build_cv.sh                 # docker image + smoke tests
#   bash vmm/build_cv.sh --firecracker   # also build the firecracker ext4

# 2. run the daemon (default_backend: docker)
PYTHONPATH=vmm .venv/bin/uvicorn vmmd.main:app --host 0.0.0.0 --port 5002

# 3. in another terminal, drive it with the TUI
.venv/bin/python vmm/tui.py
```

(Or `source .venv/bin/activate` once, then use bare `uvicorn` / `python`.)
That's the whole setup for docker: no kvm, no sudo, no privileged steps.

## Quick start — firecracker backend

```bash
sudo bash vmm/setup.sh          # firecracker + guest kernel + kvm group + sudoers
#   then restart WSL so kvm-group membership applies:  wsl --shutdown
bash vmm/build_rootfs.sh base   # debian rootfs -> ext4 (no sudo; docker + fakeroot)
bash vmm/smoke.sh               # boot one microVM, ssh in, check egress
```

Set `default_backend: firecracker` in `config.yml` (or pick it per-VM in the
TUI / per-request from the gateway).

## config.yml

```yaml
default_backend: docker      # what the gateway gets unless it names one
docker:
  image_prefix: vmm-sandbox  # image = <prefix>:<profile>
  isolate_egress: true       # block sandbox -> LAN/tailnet (see Egress below)
approval_mode: manual        # auto | manual (runtime-switchable)
defaults: { profile: base, vcpus: 2, mem_mib: 2048, disk_gb: 8, ttl_minutes: 30 }
limits:  { max_vms: 8, max_total_mem_mib: 24576, ... }
```

## The TUI

`python3 vmm/tui.py [--url http://localhost:5002]` — two tabs:

- **Sandboxes** — list every sandbox (both backends, with a backend column),
  run bash in the selected one, upload/download files, toggle approval mode,
  approve/deny held commands. `n` new (profile + backend picker, gated by
  readiness), `d` destroy, `a` approval mode, `y`/`x` approve/deny.
- **Agent** — type a prompt and watch a full cycle: it runs the gateway's
  **actual** `OllamaRuntime` + `build_vm_tools` + the `vm` skill body, so each
  tool call and the final answer render live. A green run here is a green run
  in the gateway.

## Approval gate

`approval_mode: manual` holds every `exec` in a pending queue; the `exec` HTTP
call blocks until a human approves (or it times out → auto-deny). Denied
commands return exit 126 and never run. `auto` bypasses the gate. It gates the
*command*, not the backend, so it behaves identically on docker and
firecracker.

## Gateway integration (the config toggle)

The gateway talks to `vmmd` over HTTP via `app/tools/vm_ops.py`
(`build_vm_tools`), exposed as the `vm` skill (`app/skills/vm/`). Backend
choice is a config line:

- `app/config.py` → `vmmd_base_url` (default `http://host.docker.internal:5002`,
  since the gateway runs in a container), `sandbox_backend` (`docker` |
  `firecracker` | empty = daemon default), and `sandbox_scope` (`shared` = one
  machine for every turn, pinned; `session` = one per conversation).
- The `vm` skill is scoped to the live `assistant` and the runtime threads the
  sandbox wiring into per-request state, so the assistant uses it automatically.

### Starting the daemon

By default `vmmd` runs **as a compose service** — it comes up with the gateway,
no separate start:

```bash
docker compose up -d            # starts gateway + vmm (COMPOSE_PROFILES=vmm in .env)
```

The `vmm` service (see `docker-compose.yml`, built from `vmm/Dockerfile.vmmd`)
drives the **host** docker via the mounted socket, so sandboxes are host
siblings of the stack — docker backend only. To turn it off, clear
`COMPOSE_PROFILES` in `.env`. For the **firecracker** backend, run `vmmd` on the
host instead (it needs kvm + privileged networking a container can't give):

```bash
PYTHONPATH=vmm .venv/bin/uvicorn vmmd.main:app --host 0.0.0.0 --port 5002
```

Either way the gateway reaches it at `host.docker.internal:5002`. Egress
isolation needs no setup — the daemon applies it per-sandbox (see below).

## API surface

```
POST   /vms                     {backend?, profile?, vcpus?, mem_mib?, disk_gb?, ttl_minutes?}
GET    /vms   GET /vms/{id}   DELETE /vms/{id}   POST /vms/{id}/touch
POST   /vms/{id}/exec           {command, timeout?}   (blocks on approval in manual mode)
PUT    /vms/{id}/file           {path, content_b64}
GET    /vms/{id}/file?path=     -> {content_b64}  (tar stream)
GET    /approvals   POST /approvals/{id}/approve|deny   GET /approvals/stream (SSE)
GET/PUT /config/approval-mode   GET /healthz
```

## Egress isolation

Both backends block sandbox→RFC1918/tailnet/host while allowing the public
internet.

- **Firecracker** does it per-VM via `net-helper.sh` (per-tap iptables chain),
  always.
- **Docker** does it **per-sandbox, automatically — no sudo, no host setup**.
  On start (with `docker.isolate_egress: true`, the default) the daemon:
  1. creates the dedicated bridge network `vmm-sandbox-net` (`10.200.7.0/24`) if
     needed and attaches the sandbox to it;
  2. runs a one-shot privileged sidecar
     (`docker run --network container:<sandbox> --cap-add NET_ADMIN <image>
     vmm-egress-lockdown`) that installs `OUTPUT` DROP rules for
     {RFC1918, CGNAT 100.64/10, link-local} inside the **sandbox's own netns**.

  The sandbox itself runs with **no `NET_ADMIN`**, so the agent (even as root)
  can't flush the rules. Because the filtering lives in the container's netns —
  not host iptables / `DOCKER-USER` — this works identically on **Docker Desktop
  (WSL2)** and native Docker. `vm_start` **fails closed**: if the lockdown can't
  be applied, the sandbox is torn down rather than run open. Set
  `docker.isolate_egress: false` only for trusted/dev code.

  **Verify** (inside a running sandbox): `curl https://example.com` → 200;
  `curl http://10.200.7.1` → blocked; `iptables -F` → "Permission denied".

## Notes

- Sandboxes persist across daemon restarts (firecracker detached; docker
  containers survive) — `reconcile()` re-adopts live ones and cleans up dead
  records on startup.
- Idle sandboxes are auto-destroyed after `ttl_minutes` (0 = never).
- Disk limits are enforced on firecracker (ext4) but advisory on docker
  (overlay2/extfs has no per-container quota).
