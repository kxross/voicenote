# VoiceNote

A voice-first personal assistant for Android. Hold to talk; the app
transcribes your speech and hands it to an AI assistant that manages your
lists, goals, and notes, answers questions, runs research, draws charts,
writes long-form documents, and runs on schedules — all backed by your
own self-hosted **agent-gateway**, not a cloud vendor.

> The brain lives in the paired gateway service
> (`../gateway` in this monorepo). This
> repo is the Android client. See that project's `docs/ARCHITECTURE.md`
> for the end-to-end picture and wire contracts.

---

## What it does

- **Talk to it.** Hold-to-record (main screen or a home-screen widget) →
  Whisper transcription → the gateway's `assistant` agent replies. A
  conversation is a **thread**; recording a follow-up continues it.
- **Lists.** "Add milk to groceries," "what's on my packing list" — the
  assistant edits your named lists; you also edit them by hand.
- **Goals.** Ambitions mined from your notes become goals with multi-step
  **plans**; approve a plan to activate it. A markdown edit mode lets you
  rewrite a goal's statement, context, and plan directly.
- **Notes.** Every transcript is saved and searchable; pure musings can be
  journaled with no reply.
- **Photos.** Attach up to 5 photos to a note; the assistant reads text
  from them (receipts, signs, whiteboards) and answers visual questions.
- **Canvas widgets.** Quantitative answers come back as rendered charts,
  tables, and dashboards instead of walls of text.
- **Artifacts.** Long-form research and write-ups are delivered to a
  full-screen reader with a browsable library — rendered markdown with
  mermaid diagrams and math — instead of a cramped chat bubble.
- **Schedules.** Recurring runs ("every weekday 8am, summarize my unread")
  fire server-side and push results to the phone. The assistant can also
  **propose** a schedule in chat; it only takes effect when you tap
  Approve.
- **Research that resumes.** Long research checkpoints itself and
  auto-continues; if it's still going when it pauses, you get a one-tap
  **Continue** card.
- **Proactive triggers.** Geofence arrival ("you're near the store…"),
  an SMS sweep, and scheduled check-ins can all open a thread on their own.

Everything is stored locally in a **Room** database (the source of truth);
the gateway keeps only a cached snapshot so scheduled runs have current
state to reason about.

---

## Architecture (client side)

```
Hold-to-record ─▶ AudioRecorder (16kHz AAC .m4a) ─▶ UploadWorker
      │                                                   │
      │                               POST /v1/chat/completions (model=assistant)
      │                                                   │
      ▼                                                   ▼
 Room DB  ◀── apply ops / persist messages ──  GatewayAgent parses the envelope
 (lists, goals, notes,                          {reply, ops, canvas, steps,
  threads, messages,                             artifacts, resume,
  artifacts, images)                             schedule_proposals, …}
      │                                                   │
      └── SnapshotSync.uploadSnapshot ─▶ POST /v1/snapshot (gateway cache)

Background lanes (WorkManager):
  UploadWorker      upload audio + run the turn
  PollWorker        drain POST-delivered results (15-min periodic)
  ResendWorker      retry failed gateway calls
  AgentQueryWorker  geofence-triggered query
  SmsSweepWorker    periodic SMS triage
  ResumeWorker      one-tap research resume
  WidgetRelockWorker home-screen widget state
```

Live turn status is streamed from the gateway over SSE
(`ProgressStream.kt` → `GET /v1/progress/{session}`) and shown as the
"working" bubble's status line.

### Key components

| Area | Files |
|---|---|
| Recording | `AudioRecorder.kt`, `RecordButton.kt`, `WaveformView.kt`, `RecordingService.kt`, `VoiceCapture.kt` |
| Gateway client | `GatewayAgent.kt` (OkHttp singleton, envelope parse, `deliverReply`), `Prefs.kt`, `SnapshotSync.kt` |
| Workers | `UploadWorker.kt`, `PollWorker.kt`, `ResendWorker.kt`, `AgentQueryWorker.kt`, `SmsSweepWorker.kt`, `ResumeWorker.kt` |
| Database | `NoteDatabase.kt` (Room, versioned migrations under `app/schemas/`), `*Entity.kt`, `*Dao.kt` |
| Lists | `ListsActivity.kt`, `ListDetailActivity.kt`, `List*Adapter.kt`, `ListViewModel.kt` |
| Goals | `GoalsActivity.kt`, `GoalDetailActivity.kt`, `GoalMarkdown.kt`, `GoalViewModel.kt` |
| Notes | `NotesActivity.kt`, `NotesAdapter.kt`, `NoteViewModel.kt` |
| Assistant threads | `AssistantActivity.kt`, `ThreadDetailActivity.kt`, `AssistantThreadAdapter.kt`, `ThreadDetailViewModel.kt` |
| Canvas widgets | `CanvasRenderer.kt` (WebView harness) |
| Artifacts | `ArtifactReaderActivity.kt`, `ArtifactsActivity.kt`, `ArtifactRenderer.kt`, `Artifact*.kt` |
| Schedules | `SchedulesActivity.kt`, `ScheduleEditActivity.kt`, `CronBuilder.kt`, `TimeZoneReceiver.kt` |
| Images | `ImageStore.kt`, `ImageViewerActivity.kt`, `NoteImage*.kt` |
| Proactive | `GeofenceRegistrar.kt`, `GeofenceReceiver.kt`, `SmsReader.kt`, `BootReceiver.kt` |
| Home-screen widget | `RecordWidgetProvider.kt`, `WidgetRecordActivity.kt`, `WidgetScopePickerActivity.kt` |
| Settings | `SettingsActivity.kt` (gateway URL, API key, model picker, sandbox approval mode) |

---

## Build & run

**Stack:** Kotlin + XML views (ViewBinding, no Compose), Room, WorkManager,
OkHttp (+ SSE), WebKit (`WebViewAssetLoader` for the sandboxed widget/
artifact harness), Markwon, Play Services Location. `minSdk 26`,
`compile/targetSdk 34`, `applicationId com.voicenote.app` (debug builds get
a `.debug` suffix).

### Prerequisites

- Android Studio (recent) with JDK 17 (its bundled JBR is fine)
- Android SDK 34

> There is **no Gradle wrapper** checked in. Build from Android Studio, or
> point a local Gradle 8.2 at the project.

### Steps

1. Open the `VoiceNote/` folder in Android Studio.
2. **Build → Build APK(s)** (or `assembleDebug` via a local Gradle 8.2).
   Output: `app/build/outputs/apk/debug/app-debug.apk`.
3. Install to a connected phone:

```bash
adb install app/build/outputs/apk/debug/app-debug.apk
```

### Point it at your gateway

Run the app → **Settings (⚙)** and set:

| Setting | Value |
|---|---|
| Gateway URL | your agent-gateway base, e.g. `http://<tailscale-ip>:5001` |
| API key | the gateway's `GATEWAY_API_KEY` (leave blank if the gateway has none) |
| Model | pick the LLM the assistant uses (populated from `GET /v1/llm-models`) |
| Sandbox approval | auto, or manual (hold each sandbox command for your approval) |

The gateway must be reachable from the phone (the project runs it in
Docker on a WSL host, published on the Tailscale network). Audio is
recorded as **M4A / AAC, 16kHz mono** (~6 KB/s, ideal for speech-to-text).

---

## Permissions

Microphone (recording), notifications (replies + check-ins), fine location
+ background location (geofence triggers), and SMS read (the SMS sweep) —
each requested when the feature is first used. All optional except the mic.

---

## Related

- **agent-gateway** — the FastAPI backend that runs the assistant, skills,
  schedules, and image pipeline. Its `docs/ARCHITECTURE.md` is the system
  reference; `AGENTS.md` is the quick map.
</content>
