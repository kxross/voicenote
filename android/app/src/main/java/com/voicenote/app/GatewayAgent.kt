package com.voicenote.app

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.room.withTransaction
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Shared plumbing for talking to the gateway. Everyone routes through
 * here — UploadWorker (voice), AgentQueryWorker (geofence), SnapshotSync
 * (state upload), and PollWorker (scheduled-delivery drain) — so the
 * OkHttp client, request/response contract, op semantics, and
 * notification look-and-feel are defined in exactly one place.
 */
object GatewayAgent {

    private const val TAG = "GatewayAgent"

    /** Thread documents sent with each turn (for in-place revision): at most
     *  this many, newest first, within a total character budget. */
    private const val THREAD_DOCS_MAX = 6
    private const val THREAD_DOCS_MAX_CHARS = 160_000

    const val SESSION_ID = "voicenote-default"

    const val REPLY_CHANNEL_ID = "agent_reply_channel"
    private const val REPLY_CHANNEL_NAME = "Agent replies"
    private const val REPLY_NOTIFICATION_ID = 4242

    val JSON_MEDIA_TYPE: MediaType = "application/json; charset=utf-8".toMediaType()

    // Read timeout is generous on purpose: an assistant run is 3-6
    // sequential Ollama round trips (skill catalog -> load_skill -> tools ->
    // reply) on a large local model, which can legitimately take minutes.
    // These calls run inside WorkManager workers, never on the UI thread.
    val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .readTimeout(300, TimeUnit.SECONDS)
        .build()

    /**
     * Attaches `Authorization: Bearer <key>` to the builder if key is
     * present. Accepts either raw tokens or already-prefixed values.
     */
    fun addAuth(builder: Request.Builder, apiKey: String?) {
        if (apiKey.isNullOrBlank()) return
        val value = if (apiKey.startsWith("Bearer ")) apiKey else "Bearer $apiKey"
        builder.addHeader("Authorization", value)
    }

    /** One selectable model. `provider` is "ollama" or "openrouter";
     *  `tools`/`thinking` come from the gateway so Settings can flag models
     *  that won't drive the assistant's skills. */
    data class ModelInfo(
        val name: String,
        val provider: String,
        val thinking: Boolean,
        val tools: Boolean,
    )

    /**
     * Fetch the assistant-selectable Ollama models from the gateway. Returns
     * an empty list if the gateway is unset/unreachable — the caller then
     * shows just the "Gateway default" option. Runs on a background thread.
     */
    fun fetchAssistantModels(gatewayBaseUrl: String, gatewayApiKey: String?): List<ModelInfo> {
        if (gatewayBaseUrl.isBlank()) return emptyList()
        return try {
            val builder = Request.Builder().url("$gatewayBaseUrl/v1/llm-models").get()
            addAuth(builder, gatewayApiKey)
            httpClient.newCall(builder.build()).execute().use { response ->
                if (!response.isSuccessful) return emptyList()
                val arr = JSONObject(response.body?.string() ?: "")
                    .optJSONArray("models") ?: return emptyList()
                (0 until arr.length()).map { i ->
                    val o = arr.getJSONObject(i)
                    ModelInfo(
                        name = o.getString("name"),
                        provider = o.optString("provider", "ollama"),
                        thinking = o.optBoolean("thinking", false),
                        tools = o.optBoolean("tools", false),
                    )
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "fetchAssistantModels failed: ${e.message}")
            emptyList()
        }
    }

    /** Result of one agent round trip. `silent=true` means the agent chose
     *  not to respond (no message, no notification, no thread). */
    data class AgentCallResult(
        val reply: String?,
        val threadId: Long?,
        val silent: Boolean,
        /** True when the turn was submitted async: there is no inline [reply]
         *  (it arrives through the delivery queue), but [threadId] is set. */
        val queued: Boolean = false,
    )

    /** A one-tap resume handle for a paused research run (envelope `resume`). */
    data class ResumeInfo(val runId: String, val label: String) {
        companion object {
            fun parse(json: String?): ResumeInfo? {
                if (json.isNullOrBlank()) return null
                return try {
                    val o = org.json.JSONObject(json)
                    val id = o.optString("run_id")
                    if (id.isBlank()) null
                    else ResumeInfo(id, o.optString("label").ifBlank { "Continue research" })
                } catch (e: Exception) { null }
            }
        }
    }

    /** A drafted schedule create/edit awaiting the user's approval (envelope
     *  `schedule_proposals`). The `id` is the proposal's id (not the schedule's;
     *  `scheduleId` is the target schedule for an edit). */
    data class ScheduleProposal(
        val id: String,
        val action: String,       // "create" | "update"
        val scheduleId: String,   // target schedule id for an edit; "" for create
        val title: String,
        val cron: String,
        val agent: String,
        val prompt: String,
        val timezone: String,
        val tzMode: String,
        val enabled: Boolean,
        val summary: String,
    ) {
        companion object {
            fun parseList(json: String?): List<ScheduleProposal> {
                if (json.isNullOrBlank()) return emptyList()
                return try {
                    val arr = org.json.JSONArray(json)
                    (0 until arr.length()).mapNotNull { i ->
                        val o = arr.optJSONObject(i) ?: return@mapNotNull null
                        val id = o.optString("id")
                        if (id.isBlank()) null else ScheduleProposal(
                            id = id,
                            action = o.optString("action", "create"),
                            scheduleId = o.optString("schedule_id"),
                            title = o.optString("title").ifBlank { "(untitled)" },
                            cron = o.optString("cron"),
                            agent = o.optString("agent").ifBlank { "assistant" },
                            prompt = o.optString("prompt"),
                            timezone = o.optString("timezone"),
                            tzMode = o.optString("tz_mode").ifBlank { "device" },
                            enabled = o.optBoolean("enabled", true),
                            summary = o.optString("summary"),
                        )
                    }
                } catch (e: Exception) { emptyList() }
            }
        }
    }

    /** A drafted learned-wiki entry awaiting approval (envelope `wiki_proposals`).
     *  `op` is "create" or "amend"; for an amend the card can fetch a diff. */
    data class WikiProposal(
        val proposalId: String,
        val op: String,
        val targetId: String,
        val title: String,
        val whenToUse: String,
        val kind: String,
        val body: String,
    ) {
        companion object {
            fun parseList(json: String?): List<WikiProposal> {
                if (json.isNullOrBlank()) return emptyList()
                return try {
                    val arr = org.json.JSONArray(json)
                    (0 until arr.length()).mapNotNull { i ->
                        val o = arr.optJSONObject(i) ?: return@mapNotNull null
                        val id = o.optString("proposal_id")
                        if (id.isBlank()) null else WikiProposal(
                            proposalId = id,
                            op = o.optString("op", "create"),
                            targetId = o.optString("target_id"),
                            title = o.optString("title").ifBlank { "(untitled)" },
                            whenToUse = o.optString("when_to_use"),
                            kind = o.optString("kind", "knowledge"),
                            body = o.optString("body"),
                        )
                    }
                } catch (e: Exception) { emptyList() }
            }
        }
    }

    /** Fire a resume turn for a paused run. Reuses the normal thread turn with
     *  `resume_run_id` set, so the gateway re-seeds from the run's checkpoint. */
    suspend fun resumeRun(
        context: Context,
        gatewayBaseUrl: String,
        gatewayApiKey: String?,
        runId: String,
        threadId: Long?,
        notificationTitle: String = "assistant",
    ): AgentCallResult = callListManager(
        context = context,
        prompt = "Continue the research from where it paused.",
        gatewayBaseUrl = gatewayBaseUrl,
        gatewayApiKey = gatewayApiKey,
        notificationTitle = notificationTitle,
        source = "resume",
        threadId = threadId,
        resumeRunId = runId,
        async = true,
    )

    /**
     * Runs one turn of a thread with the skill-routing `assistant` agent:
     * loads snapshots, sends prior thread turns as conversation history
     * with the `{prompt, lists, notes, goals}` envelope on the final user
     * message, applies returned ops, and handles the thread lifecycle —
     * deferred creation (a thread exists only once the agent engages),
     * agent-side resolution, and the silence contract (`no_response`).
     */
    suspend fun callListManager(
        context: Context,
        prompt: String,
        gatewayBaseUrl: String,
        gatewayApiKey: String?,
        notificationTitle: String = "assistant",
        scopedGoalId: Long? = null,
        scopedGoalTitle: String? = null,
        scopedListId: Long? = null,
        scopedListTitle: String? = null,
        source: String = "voice",
        threadId: Long? = null,
        currentNoteId: Long? = null,
        noteId: Long? = null,
        modelOverride: String? = null,
        providerOverride: String? = null,
        resumeRunId: String? = null,
        /** When true, submit the turn and return immediately; the reply is
         *  delivered through the pending queue instead of this HTTP response.
         *  Used for long turns (voice, resume) that would otherwise blow the
         *  client's read timeout. Short local turns (SMS/query) keep it false
         *  so their caller still gets the reply inline. */
        async: Boolean = false,
        /** Spoken from a document's reader: the request is about this doc. */
        focusArtifactId: String? = null,
    ): AgentCallResult {
        val db = NoteDatabase.get(context)
        val threadDao = db.threadDao()
        return try {
            val listDao = db.listDao()
            val goalDao = db.goalDao()
            val noteDao = db.noteDao()
            val snapshot = listDao.snapshot()
            // Cap the general note history on live calls — prefill time scales
            // with payload size and this path blocks a user-facing round trip.
            val notes = noteDao.snapshotWithGoal(limit = 40)
            val goals = goalDao.snapshot()
            val notesByGoal = notes
                .filter { it.note.goalId != null }
                .groupBy { it.note.goalId!! }
                .mapValues { (_, v) -> v.map { it.note } }
            val imagesByNote = db.noteImageDao().recent().groupBy { it.noteId }

            // Attachments for THIS note only. Uncaptioned ones are inlined
            // by the gateway on this turn; captioned ones contribute text.
            val attachments = noteId?.let { db.noteImageDao().forNote(it) }.orEmpty()
            val imagesArr = JSONArray()
            for (img in attachments) {
                val rid = img.remoteId ?: continue
                imagesArr.put(
                    JSONObject().put("id", rid).apply {
                        img.summary?.let { put("summary", it) }
                    }
                )
            }

            val userPayload = JSONObject().apply {
                put("prompt", prompt)
                put("lists", snapshotToJson(snapshot))
                put("notes", notesToJson(notes, imagesByNote))
                put("goals", goalsToJson(goals, notesByGoal))
                if (!scopedGoalTitle.isNullOrBlank()) put("scoped_goal", scopedGoalTitle)
                if (!scopedListTitle.isNullOrBlank()) put("scoped_list", scopedListTitle)
                if (imagesArr.length() > 0) put("images", imagesArr)
                // This note's videos (uploaded ones only): the agent watches them
                // through the `video` skill; a summary means it already has.
                val videosArr = JSONArray()
                for (v in noteId?.let { db.noteMediaDao().forNote(it) }.orEmpty()) {
                    val rid = v.remoteId ?: continue
                    videosArr.put(JSONObject().put("id", rid).put("duration_s", v.durationMs / 1000.0).apply {
                        v.summary?.let { put("summary", it) }
                    })
                }
                if (videosArr.length() > 0) put("videos", videosArr)
                // Client-picked model + its provider; the gateway overrides for
                // this turn only. Absent = gateway default.
                if (!modelOverride.isNullOrBlank()) put("model", modelOverride)
                if (!providerOverride.isNullOrBlank()) put("provider", providerOverride)
                // Resuming a paused research run: the gateway re-seeds from the
                // run's on-disk checkpoint instead of starting fresh.
                if (!resumeRunId.isNullOrBlank()) put("resume_run_id", resumeRunId)
                // Display-only activity prefs: let the gateway omit what the
                // user has toggled off from the steps trace (keeps payloads
                // lean; the model always reasons regardless).
                val uiPrefs = Prefs(context)
                put("show_thinking", uiPrefs.showActivity)
                put("show_tools", uiPrefs.showActivity)
                // The thread's documents, so "add a section", "fix the table"
                // etc. revise the existing document instead of producing a new
                // one. The gateway shows the model only an index; bodies are
                // read on demand.
                val docs = threadDocumentsJson(db.artifactDao(), threadId, focusArtifactId)
                if (docs.length() > 0) put("artifacts", docs)
                if (!focusArtifactId.isNullOrBlank()) put("focus_artifact", focusArtifactId)
            }

            // Multi-turn: prior thread turns ride as plain messages; the
            // envelope goes only on the final user message. The runtime
            // passes messages[:-1] through to the model untouched.
            val messagesArr = JSONArray()
            if (threadId != null) {
                for (msg in buildThreadHistory(threadDao, threadId, currentNoteId)) {
                    messagesArr.put(msg)
                }
            }
            messagesArr.put(
                JSONObject().put("role", "user").put("content", userPayload.toString())
            )

            val body = JSONObject().apply {
                put("model", "assistant")
                put("stream", false)
                put("user", SESSION_ID)
                put("messages", messagesArr)
            }

            // Async lane: submit the turn, return at once, let the reply arrive
            // through the delivery queue. A long turn no longer holds the socket
            // (the 5-min read timeout never trips) and a mid-run retry attaches
            // to the running turn instead of spawning a competitor.
            if (async) {
                val tid = ensureThread(threadDao, threadId, notificationTitle, prompt, scopedGoalId, scopedListId)
                val submit = Request.Builder()
                    .url("$gatewayBaseUrl/v1/chat/completions")
                    .post(body.toString().toRequestBody(JSON_MEDIA_TYPE))
                    .addHeader("X-Trigger", source)
                    .addHeader("X-Async", "1")
                    .addHeader("X-Thread-Id", tid.toString())
                    // The title can carry non-ASCII (the "…" truncation marker,
                    // emoji, accents); HTTP header values are ASCII-only, so
                    // percent-encode it and let the gateway decode.
                    .addHeader(
                        "X-Notification-Title",
                        java.net.URLEncoder.encode(notificationTitle, "UTF-8").replace("+", "%20"),
                    )
                addAuth(submit, gatewayApiKey)
                val resp = httpClient.newCall(submit.build()).execute()
                val respStr = resp.body?.string() ?: ""
                if (!resp.isSuccessful) {
                    Log.w(TAG, "Async submit error ${resp.code}: ${respStr.take(200)}")
                    val err = "[assistant error: ${resp.code}]"
                    deliverReply(context, notificationTitle, err, scopedGoalId, source, tid)
                    return AgentCallResult(err, tid, silent = false)
                }
                val status = try { JSONObject(respStr).optString("status") } catch (e: Exception) { "" }
                Log.i(TAG, "Turn submitted (thread=$tid, status=$status) — awaiting delivery")
                // Remembered until its reply is applied, so a later drain can
                // tell a still-running turn from one the gateway lost.
                AwaitedTurns.add(context, tid)
                // Keep this worker alive until the queued reply lands, so the
                // live "working" indicator stays up and (in manual mode) sandbox
                // approvals still surface. The delivery path renders the reply.
                withApprovalPolling(context, gatewayBaseUrl, gatewayApiKey) {
                    awaitDelivery(context, gatewayBaseUrl, gatewayApiKey, tid)
                }
                return AgentCallResult(null, tid, silent = false, queued = true)
            }

            val requestBuilder = Request.Builder()
                .url("$gatewayBaseUrl/v1/chat/completions")
                .post(body.toString().toRequestBody(JSON_MEDIA_TYPE))
                // Labels the /diag trace by trigger (voice / geofence / sms).
                // The gateway also uses this to redact sensitive payloads —
                // sms-triggered turns get their message bodies stripped.
                .addHeader("X-Trigger", source)
            addAuth(requestBuilder, gatewayApiKey)

            // While the turn is in flight, a sandbox command may be held for
            // approval (manual mode blocks server-side); poll for it and surface
            // an actionable notification. No-op in auto mode / when no daemon.
            val response = withApprovalPolling(context, gatewayBaseUrl, gatewayApiKey) {
                httpClient.newCall(requestBuilder.build()).execute()
            }
            val bodyStr = response.body?.string() ?: ""

            if (!response.isSuccessful) {
                Log.w(TAG, "Gateway error ${response.code}: $bodyStr")
                val err = "[assistant error: ${response.code}]"
                val tid = ensureThread(threadDao, threadId, notificationTitle, prompt, scopedGoalId, scopedListId)
                deliverReply(context, notificationTitle, err, scopedGoalId, source, tid)
                return AgentCallResult(err, tid, silent = false)
            }

            val rawContent = JSONObject(bodyStr)
                .getJSONArray("choices")
                .getJSONObject(0)
                .getJSONObject("message")
                .getString("content")

            val parsed = try {
                JSONObject(rawContent)
            } catch (e: Exception) {
                Log.w(TAG, "Reply was not JSON, treating as plain text: ${rawContent.take(120)}")
                null
            }

            val ops = parsed?.optJSONArray("ops") ?: JSONArray()
            if (ops.length() > 0) {
                db.withTransaction { applyOpsJson(db, ops) }
                Log.i(TAG, "Applied ${ops.length()} op(s)")
            }

            val silent = parsed?.optBoolean("no_response", false) ?: false
            if (silent) {
                Log.i(TAG, "Agent chose no_response — note saved, nothing delivered")
                return AgentCallResult(null, threadId, silent = true)
            }

            val reply = (parsed?.optString("reply", rawContent.trim()) ?: rawContent.trim()).trim()
            val resolve = parsed?.optBoolean("resolve_thread", false) ?: false
            // An AI-generated React widget, if the assistant rendered one this
            // turn. Stored verbatim as its {title, jsx} JSON string; the thread
            // adapter renders it in a sandboxed WebView.
            val canvas = parsed?.optJSONObject("canvas")?.toString()
            // The assistant's activity trace (skills/tools/thinking) for the
            // collapsible accordion. Null/absent => no activity section.
            val steps = parsed?.optJSONArray("steps")
                ?.takeIf { it.length() > 0 }?.toString()
            // Long-form documents the assistant published this turn, shown in
            // the reader/library rather than inline. Null/empty => none.
            val artifacts = parsed?.optJSONArray("artifacts")?.takeIf { it.length() > 0 }
            // One-tap resume handle, if a checkpointed run paused at the ceiling.
            val resume = parsed?.optJSONObject("resume")?.toString()
            // Drafted schedule proposals awaiting approval (array). Null => none.
            val scheduleProposals = parsed?.optJSONArray("schedule_proposals")
                ?.takeIf { it.length() > 0 }?.toString()
            val wikiProposals = parsed?.optJSONArray("wiki_proposals")
                ?.takeIf { it.length() > 0 }?.toString()
            // Structured citations behind the reply (the "Sources" sub-card).
            val sources = parsed?.optJSONArray("sources")
                ?.takeIf { it.length() > 0 }?.toString()

            val tid = ensureThread(threadDao, threadId, notificationTitle, prompt, scopedGoalId, scopedListId)
            Log.i(TAG, "Gateway reply (thread=$tid, resolve=$resolve, canvas=${canvas != null}, artifacts=${artifacts?.length() ?: 0}, resume=${resume != null}): \"${reply.take(80)}…\"")
            deliverReply(context, notificationTitle, reply, scopedGoalId, source, tid, canvas, steps, artifacts, resume, scheduleProposals, wikiProposals, sources)
            if (resolve) threadDao.resolve(tid) else threadDao.touch(tid)
            AgentCallResult(reply, tid, silent = false)
        } catch (e: Exception) {
            Log.e(TAG, "Gateway call failed: ${e.message}")
            val err = "[assistant error: ${e.message ?: "unknown"}]"
            val tid = try {
                ensureThread(threadDao, threadId, notificationTitle, prompt, scopedGoalId, scopedListId)
            } catch (inner: Exception) {
                null
            }
            deliverReply(context, notificationTitle, err, scopedGoalId, source, tid)
            AgentCallResult(err, tid, silent = false)
        }
    }

    /** Turn a thread's persisted history into role-tagged wire messages,
     *  excluding the note currently being processed (it becomes the
     *  envelope turn). */
    private suspend fun buildThreadHistory(
        threadDao: ThreadDao,
        threadId: Long,
        excludeNoteId: Long?,
    ): List<JSONObject> {
        data class Turn(val ts: Long, val role: String, val text: String)

        val turns =
            threadDao.messagesForThread(threadId).map { Turn(it.createdAt, "assistant", it.body) } +
            threadDao.notesForThread(threadId)
                .filter { it.id != excludeNoteId }
                .map { Turn(it.createdAt, "user", it.transcript) }

        return turns.sortedBy { it.ts }.map {
            JSONObject().put("role", it.role).put("content", it.text)
        }
    }

    /** Deferred thread creation: reuse the existing id, or open one now
     *  that the agent has engaged. */
    private suspend fun ensureThread(
        threadDao: ThreadDao,
        threadId: Long?,
        notificationTitle: String,
        prompt: String,
        goalId: Long?,
        listId: Long? = null,
    ): Long {
        if (threadId != null) return threadId
        return threadDao.insert(
            ThreadEntity(
                title = threadTitleFor(notificationTitle, prompt),
                goalId = goalId,
                listId = listId,
            )
        )
    }

    /** Thread title derived from the triggering turn: a scoped title (goal or
     *  list name) when present, otherwise the user's first utterance,
     *  truncated. Shared by deferred creation here and the eager creation in
     *  [UploadWorker] so a thread reads the same however it was opened. */
    fun threadTitleFor(notificationTitle: String, prompt: String): String = when {
        notificationTitle != "assistant" -> notificationTitle
        prompt.length > 48 -> prompt.take(45).trimEnd() + "…"
        else -> prompt
    }

    /**
     * The single delivery choke point: persist the agent's message to the
     * in-app thread AND surface it as a (dismissable) notification. Every
     * agent reply — voice-triggered, geofence-triggered, or scheduled —
     * routes through here so nothing is lost when a notification is swiped.
     */
    suspend fun deliverReply(
        context: Context,
        title: String,
        body: String,
        goalId: Long? = null,
        source: String = "voice",
        threadId: Long? = null,
        canvas: String? = null,
        steps: String? = null,
        artifacts: JSONArray? = null,
        resume: String? = null,
        scheduleProposals: String? = null,
        wikiProposals: String? = null,
        sources: String? = null,
    ) {
        try {
            persistReply(
                NoteDatabase.get(context), title, body, goalId, source, threadId, canvas,
                steps, artifacts, resume, scheduleProposals, wikiProposals, sources,
            )
        } catch (e: Exception) {
            Log.w(TAG, "Failed to persist agent message: ${e.message}")
        }
        postReplyNotification(context, title, body, threadId, canvas != null)
    }

    /** The DB half of [deliverReply]: the agent message row plus its documents.
     *  Throws on failure so a caller's transaction can roll back. */
    suspend fun persistReply(
        db: NoteDatabase,
        title: String,
        body: String,
        goalId: Long? = null,
        source: String = "voice",
        threadId: Long? = null,
        canvas: String? = null,
        steps: String? = null,
        artifacts: JSONArray? = null,
        resume: String? = null,
        scheduleProposals: String? = null,
        wikiProposals: String? = null,
        sources: String? = null,
    ) {
        val messageId = db.agentMessageDao().insert(
            AgentMessageEntity(
                title = title,
                body = body,
                goalId = goalId,
                threadId = threadId,
                source = source,
                canvas = canvas,
                steps = steps,
                resumeJson = resume,
                scheduleProposalJson = scheduleProposals,
                wikiProposalJson = wikiProposals,
                sourcesJson = sources,
            )
        )
        if (artifacts != null && messageId > 0) persistArtifacts(db, messageId, threadId, artifacts)
    }

    /** The thread's recent markdown documents as `[{id, title, text, sources}]`,
     *  newest first, bounded so a long research thread can't bloat the request.
     *  A focus document (the one open in the reader) always comes first. */
    private suspend fun threadDocumentsJson(
        dao: ArtifactDao,
        threadId: Long?,
        focusId: String?,
    ): JSONArray {
        val out = JSONArray()
        var budget = THREAD_DOCS_MAX_CHARS
        val focus = focusId?.takeIf { it.isNotBlank() }?.let { dao.get(it) }
        val recent = threadId?.let { dao.recentForThread(it, THREAD_DOCS_MAX) }.orEmpty()
        val docs = listOfNotNull(focus) + recent.filter { it.id != focus?.id }
        for (a in docs) {
            val text = a.text ?: continue
            if (text.length > budget && a !== focus) break
            budget -= text.length
            out.put(JSONObject().apply {
                put("id", a.id)
                put("title", a.title)
                put("text", text)
                a.sourcesJson?.let { s ->
                    try { put("sources", JSONArray(s)) } catch (e: Exception) { /* skip */ }
                }
            })
        }
        return out
    }

    /** Persist the envelope's published documents as child rows of the agent
     *  message that delivered them. Markdown rides inline (`text`); binary
     *  kinds (Phase 2) will carry `ref`/`mime` instead.
     *
     *  A document the assistant REVISED arrives with the id of an existing row:
     *  that row is updated in place — new text, revision + 1, moved to the
     *  delivering message (so its card sits under the "updated it" reply) —
     *  keeping the prior version for undo. An identical redelivery is a no-op. */
    private suspend fun persistArtifacts(
        db: NoteDatabase,
        messageId: Long,
        threadId: Long?,
        artifacts: JSONArray,
    ) {
        val dao = db.artifactDao()
        for (i in 0 until artifacts.length()) {
            val a = artifacts.optJSONObject(i) ?: continue
            val id = a.optString("id").ifBlank {
                java.util.UUID.randomUUID().toString().replace("-", "").take(12)
            }
            try {
                val existing = dao.get(id)
                if (existing != null) {
                    val text = if (a.isNull("text")) null else a.optString("text").ifBlank { null }
                    val title = a.optString("title").ifBlank { existing.title }
                    if (text == existing.text && title == existing.title) continue  // redelivery
                    dao.insert(
                        existing.copy(
                            messageId = messageId,
                            threadId = threadId ?: existing.threadId,
                            title = title,
                            text = text,
                            sourcesJson = a.optJSONArray("sources")?.takeIf { it.length() > 0 }?.toString(),
                            revision = existing.revision + 1,
                            updatedAt = System.currentTimeMillis(),
                            readAt = null,
                            previousText = existing.text,
                            previousTitle = existing.title,
                            previousSourcesJson = existing.sourcesJson,
                            previousRevision = existing.revision,
                        )
                    )
                    continue
                }
                dao.insert(
                    ArtifactEntity(
                        id = id,
                        messageId = messageId,
                        threadId = threadId,
                        kind = a.optString("kind", "markdown"),
                        title = a.optString("title").ifBlank { "Document" },
                        text = if (a.isNull("text")) null else a.optString("text").ifBlank { null },
                        ref = if (a.isNull("ref")) null else a.optString("ref").ifBlank { null },
                        mime = if (a.isNull("mime")) null else a.optString("mime").ifBlank { null },
                        sourcesJson = a.optJSONArray("sources")?.takeIf { it.length() > 0 }?.toString(),
                    )
                )
            } catch (e: Exception) {
                Log.w(TAG, "Failed to persist artifact $id: ${e.message}")
            }
        }
    }

    fun postReplyNotification(
        context: Context,
        title: String,
        reply: String,
        threadId: Long? = null,
        hasCanvas: Boolean = false,
    ) {
        val nm = NotificationManagerCompat.from(context)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (nm.getNotificationChannel(REPLY_CHANNEL_ID) == null) {
                nm.createNotificationChannel(
                    NotificationChannel(
                        REPLY_CHANNEL_ID,
                        REPLY_CHANNEL_NAME,
                        NotificationManager.IMPORTANCE_DEFAULT,
                    )
                )
            }
        }

        // Tapping the notification deep-links into the thread when known,
        // otherwise the inbox — the notification is an entry point, not the
        // record. Request code varies by thread so PendingIntents don't
        // collide across threads.
        val target = if (threadId != null) {
            Intent(context, ThreadDetailActivity::class.java)
                .putExtra(ThreadDetailActivity.EXTRA_THREAD_ID, threadId)
        } else {
            Intent(context, AssistantActivity::class.java)
        }
        val contentIntent = PendingIntent.getActivity(
            context,
            (threadId ?: 0L).toInt(),
            target,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        // Notifications can't render markdown — flatten so no ** or | shows.
        // A widget can't render here either, so flag it with a small marker.
        val flattened = MarkdownRenderer.plain(context, reply)
        val plain = if (hasCanvas) "📊 ${flattened}".trim() else flattened

        val notification = NotificationCompat.Builder(context, REPLY_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_frosty_notif)
            .setContentTitle(title)
            .setContentText(plain)
            .setStyle(NotificationCompat.BigTextStyle().bigText(plain))
            .setContentIntent(contentIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                context, Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) {
                Log.i(TAG, "POST_NOTIFICATIONS not granted; skipping notification")
                return
            }
        }

        try {
            nm.notify(REPLY_NOTIFICATION_ID, notification)
        } catch (e: SecurityException) {
            Log.w(TAG, "Notification denied: ${e.message}")
        }
    }

    fun snapshotToJson(snapshot: List<ListWithItems>): JSONArray {
        val arr = JSONArray()
        for (entry in snapshot) {
            val itemsArr = JSONArray()
            for (item in entry.items) {
                itemsArr.put(JSONObject().put("text", item.text).put("done", item.done))
            }
            arr.put(JSONObject().put("name", entry.list.name).put("items", itemsArr))
        }
        return arr
    }

    /**
     * Serialize notes for the payload. When a note has attachments, its
     * transcript gains a compact **index entry** per image:
     *
     *   [img_a3f2 · "Receipt from Home Depot, total $47.32" · has_text]
     *
     * ~20 tokens, but it exposes the image id (so the model can act on it),
     * the gist, and a signal that retrievable text exists. The verbatim OCR
     * stays on the gateway behind get_image_text, so it never bloats the
     * hot path — this is what gives images recall without image bytes.
     */
    fun notesToJson(
        notes: List<NoteWithGoal>,
        imagesByNote: Map<Long, List<NoteImageEntity>> = emptyMap(),
    ): JSONArray {
        val arr = JSONArray()
        for (entry in notes) {
            var transcript = entry.note.transcript
            val imgs = imagesByNote[entry.note.id].orEmpty()
            if (imgs.isNotEmpty()) {
                val tags = imgs.joinToString(" ") { img ->
                    val id = (img.remoteId ?: img.sha256).take(8)
                    val summary = img.summary ?: "photo (not yet described)"
                    val textFlag = if (img.hasText) " · has_text" else ""
                    "[img_$id · \"$summary\"$textFlag]"
                }
                transcript = "$transcript $tags"
            }
            val obj = JSONObject()
                .put("transcript", transcript)
                .put("created_at", entry.note.createdAt)
                .put("duration_seconds", entry.note.durationSeconds)
            if (entry.goalTitle != null) obj.put("goal", entry.goalTitle)
            arr.put(obj)
        }
        return arr
    }

    /** Goals are flat; plan tasks nest one level (top-level tasks carry a
     *  `subtasks` array). */
    fun goalsToJson(
        goals: List<GoalWithDetails>,
        notesByGoal: Map<Long, List<NoteEntity>> = emptyMap(),
    ): JSONArray {
        val arr = JSONArray()
        for (entry in goals) {
            arr.put(goalObj(entry, notesByGoal))
        }
        return arr
    }

    private fun goalObj(
        entry: GoalWithDetails,
        notesByGoal: Map<Long, List<NoteEntity>>,
    ): JSONObject {
        val contextArr = JSONArray()
        for (c in entry.context) {
            contextArr.put(JSONObject().put("text", c.text).put("source", c.source))
        }
        val scopedNotesArr = JSONArray()
        for (n in notesByGoal[entry.goal.id].orEmpty()) {
            scopedNotesArr.put(
                JSONObject()
                    .put("transcript", n.transcript)
                    .put("created_at", n.createdAt)
            )
        }
        val goalObj = JSONObject()
            .put("title", entry.goal.title)
            .put("statement", entry.goal.statement)
            .put("status", entry.goal.status)
            .put("well_specified", entry.goal.wellSpecified)
            .put("context", contextArr)
            .put("notes", scopedNotesArr)
        entry.plan?.let { planWithTasks ->
            val sorted = planWithTasks.tasks.sortedBy { it.orderIndex }
            val subsByParent = sorted.filter { it.parentTaskId != null }
                .groupBy { it.parentTaskId!! }
            val tasksArr = JSONArray()
            for (t in sorted.filter { it.parentTaskId == null }) {
                val taskObj = JSONObject()
                    .put("text", t.text)
                    .put("milestone", t.milestone)
                    .put("due", t.dueHint)
                    .put("done", t.done)
                val subsArr = JSONArray()
                for (s in subsByParent[t.id].orEmpty()) {
                    subsArr.put(
                        JSONObject()
                            .put("text", s.text)
                            .put("due", s.dueHint)
                            .put("done", s.done)
                    )
                }
                taskObj.put("subtasks", subsArr)
                tasksArr.put(taskObj)
            }
            goalObj.put(
                "plan",
                JSONObject()
                    .put("summary", planWithTasks.plan.summary)
                    .put("status", planWithTasks.plan.status)
                    .put("tasks", tasksArr)
            )
        }
        return goalObj
    }

    /**
     * Upload attached photos to the gateway's content-addressed store.
     * Returns sha -> remote id (they're the same value today, but keeping
     * the mapping explicit means the client never assumes that). The
     * gateway dedups, so re-uploading a known photo is cheap and returns
     * any caption already computed for it.
     */
    fun uploadImages(
        gatewayBaseUrl: String,
        gatewayApiKey: String?,
        files: List<java.io.File>,
    ): Map<String, String> {
        if (files.isEmpty()) return emptyMap()
        return try {
            val body = MultipartBody.Builder().setType(MultipartBody.FORM)
            for (f in files) {
                body.addFormDataPart(
                    "files", f.name,
                    f.asRequestBody("image/jpeg".toMediaType())
                )
            }
            val request = Request.Builder()
                .url("$gatewayBaseUrl/v1/images/$SESSION_ID")
                .post(body.build())
            addAuth(request, gatewayApiKey)
            httpClient.newCall(request.build()).execute().use { r ->
                val text = r.body?.string() ?: ""
                if (!r.isSuccessful) {
                    Log.w(TAG, "Image upload ${r.code}: ${text.take(200)}")
                    return emptyMap()
                }
                val arr = JSONObject(text).optJSONArray("images") ?: JSONArray()
                val out = mutableMapOf<String, String>()
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    val id = o.optString("id")
                    if (id.isNotEmpty()) out[files[i].name] = id
                }
                Log.i(TAG, "Uploaded ${out.size}/${files.size} image(s)")
                out
            }
        } catch (e: Exception) {
            Log.w(TAG, "Image upload failed: ${e.message}")
            emptyMap()
        }
    }

    /**
     * Pull a captioned image's summary back from the gateway. Returns
     * (summary, hasText) or null if it isn't captioned yet — captioning
     * is asynchronous, so a null here just means "check again later".
     */
    fun fetchImageMeta(
        gatewayBaseUrl: String,
        gatewayApiKey: String?,
        sha: String,
    ): Pair<String, Boolean>? {
        return try {
            val request = Request.Builder().url("$gatewayBaseUrl/v1/images/$sha").get()
            addAuth(request, gatewayApiKey)
            httpClient.newCall(request.build()).execute().use { r ->
                if (!r.isSuccessful) return null
                val o = JSONObject(r.body?.string() ?: "")
                val summary = o.optString("summary")
                if (summary.isNullOrBlank()) null
                else summary to o.optBoolean("has_text", false)
            }
        } catch (e: Exception) {
            null
        }
    }

    /** Videos are big: their own client with generous write/read timeouts
     *  (a 300 MB clip on mobile data can take minutes to send). */
    private val mediaClient by lazy {
        httpClient.newBuilder()
            .writeTimeout(15, TimeUnit.MINUTES)
            .readTimeout(5, TimeUnit.MINUTES)
            .build()
    }

    /**
     * Stream one staged video to the gateway (`POST /v1/media/{session}`).
     * The file is streamed from disk (never loaded into memory) along with the
     * metadata the gateway can't compute itself. Returns the content id, or
     * null on failure (the turn still runs; the agent just won't see it).
     */
    fun uploadMedia(gatewayBaseUrl: String, gatewayApiKey: String?, video: VideoStore.Stored): String? {
        val f = java.io.File(video.path)
        if (!f.exists()) return null
        return try {
            val body = MultipartBody.Builder().setType(MultipartBody.FORM)
                .addFormDataPart("file", f.name, f.asRequestBody(video.mime.toMediaType()))
                .addFormDataPart("duration_s", "%.3f".format(java.util.Locale.US, video.durationMs / 1000.0))
                .addFormDataPart("width", video.width.toString())
                .addFormDataPart("height", video.height.toString())
                .build()
            val request = Request.Builder().url("$gatewayBaseUrl/v1/media/$SESSION_ID").post(body)
            addAuth(request, gatewayApiKey)
            mediaClient.newCall(request.build()).execute().use { r ->
                val text = r.body?.string() ?: ""
                if (!r.isSuccessful) {
                    Log.w(TAG, "Video upload ${r.code}: ${text.take(200)}")
                    return null
                }
                JSONObject(text).optJSONObject("media")?.optString("id")?.ifBlank { null }
                    .also { Log.i(TAG, "Uploaded video ${f.name} (${f.length()} bytes) -> ${it?.take(12)}") }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Video upload failed: ${e.message}")
            null
        }
    }

    /** What the assistant found when it watched a video (null until it has). */
    fun fetchMediaSummary(gatewayBaseUrl: String, gatewayApiKey: String?, sha: String): String? {
        return try {
            val request = Request.Builder().url("$gatewayBaseUrl/v1/media/$sha").get()
            addAuth(request, gatewayApiKey)
            httpClient.newCall(request.build()).execute().use { r ->
                if (!r.isSuccessful) return null
                JSONObject(r.body?.string() ?: "").optString("summary").takeIf { it.isNotBlank() && it != "null" }
            }
        } catch (e: Exception) {
            null
        }
    }

    /** Fire a gateway schedule immediately (debug/testing hook). */
    fun runScheduleNow(gatewayBaseUrl: String, gatewayApiKey: String?, scheduleId: String): Boolean {
        return try {
            val request = Request.Builder()
                .url("$gatewayBaseUrl/v1/schedule/$scheduleId/run")
                .post(ByteArray(0).toRequestBody(null))
            addAuth(request, gatewayApiKey)
            httpClient.newCall(request.build()).execute().use { r ->
                if (!r.isSuccessful) Log.w(TAG, "runScheduleNow $scheduleId -> ${r.code}")
                r.isSuccessful
            }
        } catch (e: Exception) {
            Log.w(TAG, "runScheduleNow failed: ${e.message}")
            false
        }
    }

    // --- sandbox approval (gateway proxies the vmmd daemon) ------------------

    const val APPROVAL_CHANNEL_ID = "sandbox_approval_channel"
    private const val APPROVAL_CHANNEL_NAME = "Sandbox approvals"
    private const val APPROVAL_NOTIFICATION_ID = 4343

    /** One command held for approval. [reason] is the smart-mode review's
     *  explanation (why System One flagged it); null in manual mode. */
    data class PendingApproval(val id: String, val command: String, val reason: String? = null)

    /** Current approval mode ("auto"|"smart"|"manual"), or null if unreachable/unknown. */
    fun getApprovalMode(gatewayBaseUrl: String, gatewayApiKey: String?): String? {
        if (gatewayBaseUrl.isBlank()) return null
        return try {
            val b = Request.Builder().url("$gatewayBaseUrl/v1/sandbox/approval-mode").get()
            addAuth(b, gatewayApiKey)
            httpClient.newCall(b.build()).execute().use { r ->
                if (!r.isSuccessful) null
                else JSONObject(r.body?.string() ?: "").optString("mode").ifBlank { null }
            }
        } catch (e: Exception) {
            null
        }
    }

    /** Set the mode; returns the new mode on success, else null. */
    fun setApprovalMode(gatewayBaseUrl: String, gatewayApiKey: String?, mode: String): String? {
        if (gatewayBaseUrl.isBlank()) return null
        return try {
            val body = JSONObject().put("mode", mode).toString().toRequestBody(JSON_MEDIA_TYPE)
            val b = Request.Builder().url("$gatewayBaseUrl/v1/sandbox/approval-mode").put(body)
            addAuth(b, gatewayApiKey)
            httpClient.newCall(b.build()).execute().use { r ->
                if (!r.isSuccessful) null
                else JSONObject(r.body?.string() ?: "").optString("mode").ifBlank { null }
            }
        } catch (e: Exception) {
            null
        }
    }

    fun listPendingApprovals(gatewayBaseUrl: String, gatewayApiKey: String?): List<PendingApproval> {
        if (gatewayBaseUrl.isBlank()) return emptyList()
        return try {
            val b = Request.Builder().url("$gatewayBaseUrl/v1/sandbox/approvals").get()
            addAuth(b, gatewayApiKey)
            httpClient.newCall(b.build()).execute().use { r ->
                if (!r.isSuccessful) return emptyList()
                val arr = JSONObject(r.body?.string() ?: "").optJSONArray("pending")
                    ?: return emptyList()
                (0 until arr.length()).mapNotNull { i ->
                    val o = arr.optJSONObject(i) ?: return@mapNotNull null
                    val id = o.optString("id").ifBlank { return@mapNotNull null }
                    val reason = o.optJSONObject("review")?.optString("reason")?.ifBlank { null }
                    PendingApproval(id, o.optString("command"), reason)
                }
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun decideApproval(
        gatewayBaseUrl: String, gatewayApiKey: String?, id: String, approve: Boolean,
    ): Boolean {
        if (gatewayBaseUrl.isBlank()) return false
        val verb = if (approve) "approve" else "deny"
        return try {
            val b = Request.Builder()
                .url("$gatewayBaseUrl/v1/sandbox/approvals/$id/$verb")
                .post(ByteArray(0).toRequestBody(null))
            addAuth(b, gatewayApiKey)
            httpClient.newCall(b.build()).execute().use { it.isSuccessful }
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Run [block] (the blocking chat call) while, in manual or smart mode,
     * polling for a held command and surfacing an actionable notification (in
     * smart mode only System One-flagged commands are ever held). The poller
     * runs on its own IO coroutine and is cancelled when the call returns.
     * No-op when the mode is auto or the daemon is unreachable.
     */
    suspend fun <T> withApprovalPolling(
        context: Context,
        gatewayBaseUrl: String,
        gatewayApiKey: String?,
        block: suspend () -> T,
    ): T = coroutineScope {
        val mode = getApprovalMode(gatewayBaseUrl, gatewayApiKey)
        val gated = mode == "manual" || mode == "smart"
        val poller = if (gated) {
            launch(Dispatchers.IO) { approvalPollLoop(context, gatewayBaseUrl, gatewayApiKey) }
        } else null
        try {
            block()
        } finally {
            poller?.cancel()
            cancelApprovalNotification(context)
        }
    }

    private suspend fun approvalPollLoop(context: Context, url: String, key: String?) {
        var shownId: String? = null
        try {
            while (true) {
                val first = listPendingApprovals(url, key).firstOrNull()
                when {
                    first == null -> if (shownId != null) {
                        cancelApprovalNotification(context); shownId = null
                    }
                    first.id != shownId -> {
                        postApprovalNotification(context, first.id, first.command, first.reason)
                        shownId = first.id
                    }
                }
                delay(1500)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "approval poll stopped: ${e.message}")
        }
    }

    fun postApprovalNotification(
        context: Context, approvalId: String, command: String, reason: String? = null,
    ) {
        val nm = NotificationManagerCompat.from(context)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (nm.getNotificationChannel(APPROVAL_CHANNEL_ID) == null) {
                nm.createNotificationChannel(
                    NotificationChannel(
                        APPROVAL_CHANNEL_ID, APPROVAL_CHANNEL_NAME,
                        NotificationManager.IMPORTANCE_HIGH,
                    )
                )
            }
        }

        fun actionIntent(action: String): PendingIntent {
            val i = Intent(context, ApprovalActionReceiver::class.java)
                .setAction(action)
                .putExtra(ApprovalActionReceiver.EXTRA_ID, approvalId)
            return PendingIntent.getBroadcast(
                context, action.hashCode(), i,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }

        val notification = NotificationCompat.Builder(context, APPROVAL_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_frosty_notif)
            // Smart mode leads with *why* it was flagged; manual just shows the command.
            .setContentTitle(if (reason != null) "Flagged command needs approval" else "Sandbox command needs approval")
            .setContentText(reason ?: command)
            .setStyle(NotificationCompat.BigTextStyle().bigText(
                if (reason != null) "$reason\n\n$command" else command
            ))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setOngoing(true)
            .setAutoCancel(false)
            .addAction(0, "Approve", actionIntent(ApprovalActionReceiver.ACTION_APPROVE))
            .addAction(0, "Deny", actionIntent(ApprovalActionReceiver.ACTION_DENY))
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(
                    context, Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) return
        }
        try {
            nm.notify(APPROVAL_NOTIFICATION_ID, notification)
        } catch (e: SecurityException) {
            Log.w(TAG, "approval notification denied: ${e.message}")
        }
    }

    fun cancelApprovalNotification(context: Context) {
        try {
            NotificationManagerCompat.from(context).cancel(APPROVAL_NOTIFICATION_ID)
        } catch (e: Exception) { /* ignore */ }
    }

    // --- schedules (CRUD over the gateway; source of truth is the server) ----

    data class ScheduleInfo(
        val id: String,                // "" when creating
        val cron: String,
        val model: String,
        val prompt: String,
        val notificationTitle: String,
        val enabled: Boolean,
        val timezone: String,          // IANA; "" = server local
        val tzMode: String,            // "fixed" | "device"
        val nextRunTime: String?,      // ISO, server-computed
        // Most recent run's outcome, for the row's status badge. Null = never run.
        val lastRunStatus: String? = null,     // "ok" | "error" | "paused" | …
        val lastRunAt: Double? = null,         // epoch seconds
        val lastRunResumeRunId: String? = null,
    )

    /** One past run of a schedule (its trace), for the run-history screen. */
    data class ScheduleRun(
        val id: String,
        val status: String,            // "ok" | "error" | "paused" | "timeout"
        val at: Double,                // epoch seconds
        val durationMs: Long,
        val calls: Int,
        val replyPreview: String,
        val resumeRunId: String?,      // set when status == "paused"
    )

    private fun parseSchedule(o: JSONObject): ScheduleInfo {
        val lr = o.optJSONObject("last_run")
        return ScheduleInfo(
            id = o.optString("id"),
            cron = o.optString("cron"),
            model = o.optString("model"),
            prompt = o.optString("prompt"),
            notificationTitle = o.optString("notification_title"),
            enabled = o.optBoolean("enabled", true),
            timezone = o.optString("timezone"),
            tzMode = o.optString("tz_mode", "fixed"),
            nextRunTime = o.optString("next_run_time").takeIf { it.isNotBlank() && it != "null" },
            lastRunStatus = lr?.optString("status")?.takeIf { it.isNotBlank() },
            lastRunAt = lr?.optDouble("at")?.takeIf { !it.isNaN() },
            lastRunResumeRunId = lr?.optString("resume_run_id")
                ?.takeIf { it.isNotBlank() && it != "null" },
        )
    }

    private fun parseScheduleRun(o: JSONObject): ScheduleRun = ScheduleRun(
        id = o.optString("id"),
        status = o.optString("status").ifBlank { "ok" },
        at = o.optDouble("at", 0.0),
        durationMs = o.optLong("duration_ms", 0L),
        calls = o.optInt("num_calls", 0),
        replyPreview = o.optString("reply_preview"),
        resumeRunId = o.optString("resume_run_id").takeIf { it.isNotBlank() && it != "null" },
    )

    fun listScheduleRuns(
        gatewayBaseUrl: String, gatewayApiKey: String?, scheduleId: String,
    ): List<ScheduleRun> {
        if (gatewayBaseUrl.isBlank()) return emptyList()
        return try {
            val b = Request.Builder()
                .url("$gatewayBaseUrl/v1/schedules/$scheduleId/runs").get()
            addAuth(b, gatewayApiKey)
            httpClient.newCall(b.build()).execute().use { r ->
                if (!r.isSuccessful) return emptyList()
                val arr = JSONObject(r.body?.string() ?: "").optJSONArray("runs")
                    ?: return emptyList()
                (0 until arr.length()).mapNotNull { i ->
                    arr.optJSONObject(i)?.let { parseScheduleRun(it) }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "listScheduleRuns failed: ${e.message}")
            emptyList()
        }
    }

    fun listSchedules(gatewayBaseUrl: String, gatewayApiKey: String?): List<ScheduleInfo> {
        if (gatewayBaseUrl.isBlank()) return emptyList()
        return try {
            val b = Request.Builder().url("$gatewayBaseUrl/v1/schedules").get()
            addAuth(b, gatewayApiKey)
            httpClient.newCall(b.build()).execute().use { r ->
                if (!r.isSuccessful) return emptyList()
                val arr = JSONObject(r.body?.string() ?: "").optJSONArray("schedules")
                    ?: return emptyList()
                (0 until arr.length()).mapNotNull { i ->
                    arr.optJSONObject(i)?.let { parseSchedule(it) }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "listSchedules failed: ${e.message}")
            emptyList()
        }
    }

    /** Create (id blank → POST) or update (id set → PUT). Returns the saved
     *  schedule, or null with an error string via [onError] on failure. */
    fun saveSchedule(
        gatewayBaseUrl: String, gatewayApiKey: String?, s: ScheduleInfo,
        onError: (String) -> Unit = {},
    ): ScheduleInfo? {
        if (gatewayBaseUrl.isBlank()) { onError("No gateway URL set"); return null }
        return try {
            val body = JSONObject().apply {
                put("cron", s.cron)
                put("model", s.model)
                put("prompt", s.prompt)
                put("notification_title", s.notificationTitle)
                put("enabled", s.enabled)
                put("timezone", s.timezone)
                put("tz_mode", s.tzMode)
            }.toString().toRequestBody(JSON_MEDIA_TYPE)
            val url = if (s.id.isBlank()) "$gatewayBaseUrl/v1/schedules"
                      else "$gatewayBaseUrl/v1/schedules/${s.id}"
            val b = Request.Builder().url(url)
            if (s.id.isBlank()) b.post(body) else b.put(body)
            addAuth(b, gatewayApiKey)
            httpClient.newCall(b.build()).execute().use { r ->
                val text = r.body?.string() ?: ""
                if (!r.isSuccessful) {
                    val detail = try { JSONObject(text).optString("detail", text) } catch (e: Exception) { text }
                    onError(detail.ifBlank { "HTTP ${r.code}" })
                    null
                } else parseSchedule(JSONObject(text))
            }
        } catch (e: Exception) {
            onError(e.message ?: "failed")
            null
        }
    }

    fun deleteSchedule(gatewayBaseUrl: String, gatewayApiKey: String?, id: String): Boolean {
        if (gatewayBaseUrl.isBlank()) return false
        return try {
            val b = Request.Builder().url("$gatewayBaseUrl/v1/schedules/$id").delete()
            addAuth(b, gatewayApiKey)
            httpClient.newCall(b.build()).execute().use { it.isSuccessful }
        } catch (e: Exception) {
            false
        }
    }

    /** Point device-mode schedules at [tz] (IANA). Called on launch / tz change. */
    fun setDeviceTimezone(gatewayBaseUrl: String, gatewayApiKey: String?, tz: String): Boolean {
        if (gatewayBaseUrl.isBlank() || tz.isBlank()) return false
        return try {
            val body = JSONObject().put("tz", tz).toString().toRequestBody(JSON_MEDIA_TYPE)
            val b = Request.Builder().url("$gatewayBaseUrl/v1/schedules/device-tz").put(body)
            addAuth(b, gatewayApiKey)
            httpClient.newCall(b.build()).execute().use { it.isSuccessful }
        } catch (e: Exception) {
            false
        }
    }

    /** The gateway's registered agent ids (for the schedule's model picker). */
    fun fetchAgents(gatewayBaseUrl: String, gatewayApiKey: String?): List<String> {
        if (gatewayBaseUrl.isBlank()) return emptyList()
        return try {
            val b = Request.Builder().url("$gatewayBaseUrl/healthz").get()
            addAuth(b, gatewayApiKey)
            httpClient.newCall(b.build()).execute().use { r ->
                if (!r.isSuccessful) return emptyList()
                val arr = JSONObject(r.body?.string() ?: "").optJSONArray("agents")
                    ?: return emptyList()
                (0 until arr.length()).map { arr.getString(it) }
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    // ---- async delivery (shared by the await-loop and PollWorker) ----------

    /** Safety cap on how long an async turn keeps its worker alive awaiting the
     *  queued reply. Under WorkManager's ~10-min ceiling; past this the periodic
     *  poll net delivers instead. */
    private const val DELIVERY_AWAIT_CAP_MS = 9 * 60_000L
    /** Long-poll hold per request (the gateway caps it at 55 s). */
    private const val DELIVERY_LONGPOLL_S = 50
    private const val DELIVERY_ERROR_BACKOFF_MS = 5_000L

    /** Block until the queued reply for [threadId] lands (or the cap elapses),
     *  applying it through [applyDelivery]. Long-polls, so the reply lands
     *  about a second after it's queued. Only drains this thread's delivery,
     *  leaving others (e.g. scheduled) for [PollWorker]. */
    private suspend fun awaitDelivery(
        context: Context, base: String, apiKey: String?, threadId: Long,
    ): Boolean {
        val db = NoteDatabase.get(context)
        val deadline = System.currentTimeMillis() + DELIVERY_AWAIT_CAP_MS
        var last: PendingResponse? = null
        while (System.currentTimeMillis() < deadline) {
            val resp = try {
                fetchPending(base, apiKey, wait = DELIVERY_LONGPOLL_S)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "await: pending fetch failed: ${e.message}")
                delay(DELIVERY_ERROR_BACKOFF_MS); continue
            }
            last = resp
            for (i in 0 until resp.deliveries.length()) {
                val d = resp.deliveries.optJSONObject(i) ?: continue
                if (d.optLong("thread_id", -1L) == threadId) {
                    applyDelivery(context, db, d, base, apiKey)
                    return true
                }
            }
            // Not running and nothing queued for it: the gateway lost the turn
            // (restart mid-run). Stop waiting; reconcile reports it.
            if (resp.reportsBusy && threadId !in resp.busyThreads) {
                Log.w(TAG, "await: thread $threadId no longer running and no delivery")
                break
            }
        }
        Log.w(TAG, "await: no delivery for thread $threadId; handing off to follow-up")
        if (last != null) {
            try { DeliveryFollowUp.reconcile(context, last) } catch (e: Exception) {
                Log.w(TAG, "await: reconcile failed: ${e.message}")
            }
        } else {
            DeliveryAlarm.armIn(context, 60_000L)
        }
        return false
    }

    /** One `GET /v1/pending` answer: the deliveries plus what's still coming. */
    data class PendingResponse(
        val deliveries: JSONArray,
        val busyThreads: Set<Long>,
        val scheduledRunning: Boolean,
        val nextDue: String?,
        /** False on an older gateway that doesn't report busy threads — then
         *  "not busy" proves nothing and no turn may be called lost. */
        val reportsBusy: Boolean = true,
    )

    /** GET the device's undelivered deliveries. Shared by await-loop + PollWorker.
     *  With [wait] > 0 the gateway holds the request until one is queued (or
     *  the wait ends). Older gateways ignore `wait` and the extra fields. */
    suspend fun fetchPending(base: String, apiKey: String?, wait: Int = 0): PendingResponse =
        kotlinx.coroutines.withContext(Dispatchers.IO) {
            val url = if (wait > 0) "$base/v1/pending/$SESSION_ID?wait=$wait" else "$base/v1/pending/$SESSION_ID"
            val request = Request.Builder().url(url).get()
            addAuth(request, apiKey)
            httpClient.newCall(request.build()).execute().use { response ->
                val bodyStr = response.body?.string() ?: ""
                if (!response.isSuccessful) {
                    throw RuntimeException("HTTP ${response.code}: ${bodyStr.take(120)}")
                }
                val o = JSONObject(bodyStr)
                val busy = o.optJSONArray("busy_threads")
                PendingResponse(
                    deliveries = o.optJSONArray("deliveries") ?: JSONArray(),
                    busyThreads = if (busy == null) emptySet()
                        else (0 until busy.length()).map { busy.optLong(it) }.toSet(),
                    scheduledRunning = o.optBoolean("scheduled_running", false),
                    nextDue = o.optString("next_due").takeIf { it.isNotBlank() && it != "null" },
                    reportsBusy = busy != null,
                )
            }
        }

    /** Apply one queued delivery: run its ops, land it in the right thread (the
     *  explicit `thread_id` for a live turn; else matched by title for a
     *  scheduled check-in), render the reply, honor `resolve`, and ack it.
     *  The single point both delivery lanes converge on. Returns true if acked.
     *
     *  Exactly-once: the delivery id is claimed in the same transaction that
     *  applies it, so a re-fetch after a lost ack — or two workers draining at
     *  once — just re-acks instead of applying the ops and reply twice. */
    suspend fun applyDelivery(
        context: Context, db: NoteDatabase, delivery: JSONObject,
        base: String, apiKey: String?,
    ): Boolean {
        val id = delivery.optInt("id", -1)
        val title = delivery.optString("title").ifBlank { "Agent" }
        val body = delivery.optString("body")
        val ops = delivery.optJSONArray("ops") ?: JSONArray()
        val canvas = delivery.optJSONObject("canvas")?.toString()
        val steps = delivery.optJSONArray("steps")?.takeIf { it.length() > 0 }?.toString()
        val artifacts = delivery.optJSONArray("artifacts")?.takeIf { it.length() > 0 }
        val resume = delivery.optJSONObject("resume")?.toString()
        val scheduleProposals = delivery.optJSONArray("schedule_proposals")
            ?.takeIf { it.length() > 0 }?.toString()
        val wikiProposals = delivery.optJSONArray("wiki_proposals")
            ?.takeIf { it.length() > 0 }?.toString()
        val sources = delivery.optJSONArray("sources")?.takeIf { it.length() > 0 }?.toString()
        // Live turns carry the exact thread; scheduled ones match by title.
        val explicitThreadId = delivery.optLong("thread_id", -1L).takeIf { it >= 0 }
        val source = delivery.optString("source").ifBlank { "schedule" }
        val resolve = delivery.optBoolean("resolve", false)
        val tid: Long? = try {
            db.withTransaction {
                if (id >= 0 && db.appliedDeliveryDao().claim(AppliedDeliveryEntity(id.toLong())) == -1L) {
                    Log.i(TAG, "Delivery $id already applied — re-acking only")
                    return@withTransaction null
                }
                if (ops.length() > 0) {
                    applyOpsJson(db, ops)
                    Log.i(TAG, "Delivery $id applied ${ops.length()} op(s)")
                }
                val threadDao = db.threadDao()
                val t = if (explicitThreadId != null) {
                    threadDao.reopen(explicitThreadId); explicitThreadId
                } else {
                    threadDao.findOpenThreadByTitle(title)?.id
                        ?: threadDao.insert(ThreadEntity(title = title))
                }
                persistReply(
                    db, title, body, goalId = null, source = source, threadId = t,
                    canvas = canvas, steps = steps, artifacts = artifacts, resume = resume,
                    scheduleProposals = scheduleProposals, wikiProposals = wikiProposals,
                    sources = sources,
                )
                if (resolve) threadDao.resolve(t) else threadDao.touch(t)
                t
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Delivery $id handler failed: ${e.message}")
            return false   // rolled back (claim included) — a later poll retries
        }
        // Committed: notify (outside the transaction), stop awaiting, then ack.
        if (tid != null) postReplyNotification(context, title, body, tid, canvas != null)
        explicitThreadId?.let { AwaitedTurns.remove(context, it) }
        if (id >= 0) ackPending(base, apiKey, id)
        return true
    }

    /** Approve a drafted wiki proposal → the gateway activates the entry. An
     *  optional edited body overrides what the agent drafted. */
    fun approveWikiProposal(base: String, apiKey: String?, proposalId: String, editedBody: String? = null): Boolean {
        return try {
            val payload = JSONObject().apply { if (editedBody != null) put("edited_body", editedBody) }
            val b = Request.Builder()
                .url("$base/v1/wiki/proposals/$proposalId/approve")
                .post(payload.toString().toRequestBody(JSON_MEDIA_TYPE))
            addAuth(b, apiKey)
            httpClient.newCall(b.build()).execute().use { it.isSuccessful }
        } catch (e: Exception) {
            Log.w(TAG, "approveWiki $proposalId failed: ${e.message}"); false
        }
    }

    /** Discard a drafted wiki proposal. */
    fun rejectWikiProposal(base: String, apiKey: String?, proposalId: String): Boolean {
        return try {
            val b = Request.Builder()
                .url("$base/v1/wiki/proposals/$proposalId/reject")
                .post(ByteArray(0).toRequestBody(null))
            addAuth(b, apiKey)
            httpClient.newCall(b.build()).execute().use { it.isSuccessful }
        } catch (e: Exception) {
            Log.w(TAG, "rejectWiki $proposalId failed: ${e.message}"); false
        }
    }

    /** DELETE a delivery so the gateway stops returning it. A failed ack is
     *  safe: the delivery comes back on the next poll, hits its claim in
     *  `applied_deliveries`, and is simply acked again. */
    suspend fun ackPending(base: String, apiKey: String?, deliveryId: Int) =
        kotlinx.coroutines.withContext(Dispatchers.IO) { ackPendingBlocking(base, apiKey, deliveryId) }

    private fun ackPendingBlocking(base: String, apiKey: String?, deliveryId: Int) {
        try {
            val request = Request.Builder().url("$base/v1/pending/$deliveryId").delete()
            addAuth(request, apiKey)
            httpClient.newCall(request.build()).execute().use { r ->
                if (!r.isSuccessful) Log.w(TAG, "Ack $deliveryId returned ${r.code}")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Ack $deliveryId failed: ${e.message}")
        }
    }

    /** Apply an ops JSONArray to Room. Caller wraps in a transaction. */
    suspend fun applyOpsJson(db: NoteDatabase, ops: JSONArray) {
        val listDao = db.listDao()
        val goalDao = db.goalDao()
        for (i in 0 until ops.length()) {
            val op = ops.optJSONObject(i) ?: continue
            val type = op.optString("type")
            if (type in GOAL_OP_TYPES) {
                applyGoalOp(goalDao, op)
            } else {
                applyOp(listDao, op)
            }
        }
    }

    private val GOAL_OP_TYPES = setOf(
        "create_goal", "update_goal", "add_goal_context", "set_plan",
        "set_plan_status", "add_task", "set_task_done", "remove_task", "delete_goal",
    )

    private suspend fun applyGoalOp(dao: GoalDao, op: JSONObject) {
        val type = op.optString("type")
        when (type) {
            "create_goal" -> {
                val title = op.optString("title").trim()
                if (title.isNotEmpty()) {
                    dao.insertGoal(
                        GoalEntity(title = title, statement = op.optString("statement").trim())
                    )
                }
            }
            "update_goal" -> {
                val goalId = resolveGoalId(dao, op, type) ?: return
                if (op.has("statement")) dao.updateStatement(goalId, op.optString("statement").trim())
                if (op.has("status")) dao.updateStatus(goalId, op.optString("status").trim())
                if (op.has("well_specified")) dao.updateWellSpecified(goalId, op.optBoolean("well_specified"))
            }
            "add_goal_context" -> {
                val goalId = resolveGoalId(dao, op, type) ?: return
                val text = op.optString("text").trim()
                if (text.isNotEmpty()) {
                    dao.insertContext(
                        GoalContextEntity(
                            goalId = goalId,
                            text = text,
                            source = op.optString("source", "agent"),
                        )
                    )
                }
            }
            "set_plan" -> {
                val goalId = resolveGoalId(dao, op, type) ?: return
                val existing = dao.findPlanByGoalId(goalId)
                if (existing != null && existing.status == "approved") {
                    Log.w(TAG, "Refusing set_plan on approved plan for goal id $goalId")
                    return
                }
                dao.deletePlanByGoalId(goalId)
                val planId = dao.insertPlan(
                    PlanEntity(goalId = goalId, summary = op.optString("summary").trim())
                )
                val tasks = op.optJSONArray("tasks") ?: JSONArray()
                var order = 0
                for (i in 0 until tasks.length()) {
                    val t = tasks.optJSONObject(i) ?: continue
                    val text = t.optString("text").trim()
                    if (text.isEmpty()) continue
                    val parentId = dao.insertTask(
                        GoalTaskEntity(
                            planId = planId,
                            text = text,
                            milestone = t.optString("milestone").trim(),
                            dueHint = t.optString("due").trim(),
                            orderIndex = order++,
                        )
                    )
                    // One level of nesting: subtasks ride inside their parent
                    // task object; anything deeper was refused server-side.
                    val subs = t.optJSONArray("subtasks") ?: JSONArray()
                    for (j in 0 until subs.length()) {
                        val s = subs.optJSONObject(j) ?: continue
                        val subText = s.optString("text").trim()
                        if (subText.isEmpty()) continue
                        dao.insertTask(
                            GoalTaskEntity(
                                planId = planId,
                                parentTaskId = parentId,
                                text = subText,
                                dueHint = s.optString("due").trim(),
                                orderIndex = order++,
                            )
                        )
                    }
                }
            }
            "set_plan_status" -> {
                val goalId = resolveGoalId(dao, op, type) ?: return
                val plan = dao.findPlanByGoalId(goalId) ?: return
                val status = op.optString("status").trim()
                if (status == "draft" || status == "approved") {
                    dao.updatePlanStatus(plan.id, status)
                    // Approval IS activation — one transition, not two states.
                    if (status == "approved") dao.updateStatus(goalId, "active")
                }
            }
            "add_task" -> {
                val goalId = resolveGoalId(dao, op, type) ?: return
                val plan = dao.findPlanByGoalId(goalId) ?: run {
                    Log.w(TAG, "add_task dropped: goal id $goalId has no plan yet")
                    return
                }
                val text = op.optString("text").trim()
                if (text.isEmpty()) return
                // Optional nesting under an existing TOP-LEVEL task. Falls
                // back to top-level (with a log) if the parent can't be found
                // — keeping the content beats dropping it.
                var parentTaskId: Long? = null
                val parentText = op.optString("parent_task").trim()
                if (parentText.isNotEmpty()) {
                    parentTaskId = dao.findTopLevelTaskId(plan.id, parentText)
                        ?: dao.tasksForPlan(plan.id)
                            .firstOrNull {
                                it.parentTaskId == null &&
                                    (it.text.lowercase().contains(parentText.lowercase()) ||
                                        parentText.lowercase().contains(it.text.lowercase()))
                            }?.id
                    if (parentTaskId == null) {
                        Log.w(TAG, "add_task: parent task '$parentText' not found; adding top-level")
                    }
                }
                dao.insertTask(
                    GoalTaskEntity(
                        planId = plan.id,
                        parentTaskId = parentTaskId,
                        text = text,
                        milestone = op.optString("milestone").trim(),
                        dueHint = op.optString("due").trim(),
                        orderIndex = Int.MAX_VALUE,
                    )
                )
            }
            "set_task_done" -> {
                val goalId = resolveGoalId(dao, op, type) ?: return
                val plan = dao.findPlanByGoalId(goalId) ?: return
                val taskId = resolveTaskId(dao, plan.id, op.optString("task").trim(), type) ?: return
                dao.setTaskDoneById(taskId, op.optBoolean("done"))
            }
            "remove_task" -> {
                val goalId = resolveGoalId(dao, op, type) ?: return
                val plan = dao.findPlanByGoalId(goalId) ?: return
                val taskId = resolveTaskId(dao, plan.id, op.optString("task").trim(), type) ?: return
                dao.deleteTaskById(taskId)
            }
            "delete_goal" -> {
                // Exact-only on purpose: a fuzzy match here could delete the
                // wrong goal and cascade its plan/tasks/context away.
                val goalId = dao.findGoalIdByTitle(op.optString("goal").trim())
                if (goalId == null) {
                    Log.w(TAG, "delete_goal dropped: no exact match for '${op.optString("goal")}'")
                    return
                }
                dao.deleteGoalById(goalId)
            }
        }
    }

    /**
     * Resolve a goal id from an op's "goal" title. Tries exact
     * (case-insensitive) first, then a substring match in either direction
     * — the agent often paraphrases ("my 10k" vs stored "Run a 10k").
     * Logs when nothing matches so dropped ops are visible in logcat.
     */
    private suspend fun resolveGoalId(dao: GoalDao, op: JSONObject, opType: String): Long? {
        val title = op.optString("goal").trim()
        if (title.isEmpty()) return null
        dao.findGoalIdByTitle(title)?.let { return it }
        val lc = title.lowercase()
        val match = dao.allGoals().firstOrNull {
            val gt = it.title.lowercase()
            gt.contains(lc) || lc.contains(gt)
        }
        if (match == null) {
            Log.w(TAG, "$opType dropped: no goal matches title '$title'")
        } else {
            Log.i(TAG, "$opType: fuzzy-matched title '$title' -> '${match.title}'")
        }
        return match?.id
    }

    /**
     * Resolve a task id within a plan from an op's "task" text. Exact
     * (case-insensitive) first, then substring either direction. Logs
     * misses so silent no-ops become visible.
     */
    private suspend fun resolveTaskId(
        dao: GoalDao,
        planId: Long,
        text: String,
        opType: String,
    ): Long? {
        if (text.isEmpty()) return null
        dao.findTaskId(planId, text)?.let { return it }
        val lc = text.lowercase()
        val match = dao.tasksForPlan(planId).firstOrNull {
            val tt = it.text.lowercase()
            tt.contains(lc) || lc.contains(tt)
        }
        if (match == null) {
            Log.w(TAG, "$opType dropped: no task matches '$text' in plan $planId")
        } else {
            Log.i(TAG, "$opType: fuzzy-matched task '$text' -> '${match.text}'")
        }
        return match?.id
    }

    private suspend fun applyOp(dao: ListDao, op: JSONObject) {
        when (op.optString("type")) {
            "create_list" -> {
                val name = op.optString("name").trim()
                if (name.isNotEmpty()) dao.insertList(ListEntity(name = name))
            }
            "delete_list" -> {
                val name = op.optString("name").trim()
                if (name.isNotEmpty()) dao.deleteListByName(name)
            }
            "rename_list" -> {
                val oldName = op.optString("old").trim()
                val newName = op.optString("new").trim()
                if (oldName.isNotEmpty() && newName.isNotEmpty()) {
                    dao.renameList(oldName, newName)
                }
            }
            "add_item" -> {
                val listName = op.optString("list").trim()
                val text = op.optString("text").trim()
                if (listName.isEmpty() || text.isEmpty()) return
                var listId = dao.findListIdByName(listName)
                if (listId == null) {
                    dao.insertList(ListEntity(name = listName))
                    listId = dao.findListIdByName(listName)
                }
                if (listId != null) {
                    dao.insertItem(ListItemEntity(listId = listId, text = text))
                }
            }
            "remove_item" -> {
                val listId = dao.findListIdByName(op.optString("list").trim()) ?: return
                val itemId = dao.findItemId(listId, op.optString("text").trim()) ?: return
                dao.deleteItemById(itemId)
            }
            "set_item_done" -> {
                val listId = dao.findListIdByName(op.optString("list").trim()) ?: return
                val itemId = dao.findItemId(listId, op.optString("text").trim()) ?: return
                dao.setItemDoneById(itemId, op.optBoolean("done"))
            }
            else -> Log.w(TAG, "Unknown op type: ${op.optString("type")}")
        }
    }
}
