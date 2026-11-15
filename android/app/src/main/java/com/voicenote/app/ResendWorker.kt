package com.voicenote.app

import android.content.Context
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import java.util.concurrent.TimeUnit

/**
 * Re-runs an already-saved note's transcript through the assistant. There is no
 * audio to re-transcribe (it's deleted after the first pass), so "resend" means
 * re-sending the transcript text. Continues the note's existing thread when it
 * has one; otherwise the agent's deferred-thread logic may open a new one.
 *
 * Mirrors AgentQueryWorker (text-only gateway call) but keyed by a note id, and
 * carries the agent-turn tags so the inbox/thread progress UI picks it up.
 */
class ResendWorker(
    private val context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val noteId = inputData.getLong(KEY_NOTE_ID, -1L).takeIf { it >= 0 } ?: return Result.success()
        val gatewayUrl = inputData.getString(KEY_GATEWAY_URL)
        val gatewayApiKey = inputData.getString(KEY_GATEWAY_API_KEY)
        val model = inputData.getString(KEY_ASSISTANT_MODEL)
        val provider = inputData.getString(KEY_ASSISTANT_PROVIDER)

        if (gatewayUrl.isNullOrBlank()) {
            Log.i(TAG, "No gateway configured; skipping resend of note $noteId")
            return Result.success()
        }

        val db = NoteDatabase.get(context)
        val note = db.noteDao().findById(noteId) ?: return Result.success()
        if (note.transcript.isBlank()) return Result.success()

        val goal = note.goalId?.let { db.goalDao().findGoalById(it) }

        // Mirror the voice path so the pending bubble reads "Thinking…".
        setProgress(workDataOf(UploadWorker.KEY_STAGE to UploadWorker.STAGE_THINKING))

        val result = GatewayAgent.callListManager(
            context = context,
            prompt = note.transcript,
            gatewayBaseUrl = gatewayUrl,
            gatewayApiKey = gatewayApiKey,
            notificationTitle = goal?.title ?: "assistant",
            scopedGoalId = goal?.id,
            scopedGoalTitle = goal?.title,
            source = "resend",
            threadId = note.threadId,
            currentNoteId = note.id,
            noteId = note.id,
            modelOverride = model,
            providerOverride = provider,
        )

        // Deferred thread creation: attach this note to a thread the agent
        // opened on engage, so the conversation stays linked.
        val newThreadId = result.threadId
        if (newThreadId != null && note.threadId == null) {
            db.noteDao().setThreadId(note.id, newThreadId)
        }
        SnapshotSync.uploadSnapshot(context, gatewayUrl, gatewayApiKey)
        return Result.success()
    }

    companion object {
        private const val TAG = "ResendWorker"
        const val KEY_NOTE_ID = "note_id"
        const val KEY_GATEWAY_URL = "gateway_url"
        const val KEY_GATEWAY_API_KEY = "gateway_api_key"
        const val KEY_ASSISTANT_MODEL = "assistant_model"
        const val KEY_ASSISTANT_PROVIDER = "assistant_provider"

        /** Build a resend request for [note], tagged so the inbox and (if the
         *  note is threaded) that thread's progress UI show the pending turn. */
        fun buildRequest(prefs: Prefs, note: NoteEntity): OneTimeWorkRequest {
            val data = Data.Builder()
                .putLong(KEY_NOTE_ID, note.id)
                .putString(KEY_GATEWAY_URL, prefs.gatewayBaseUrl)
                .putString(KEY_GATEWAY_API_KEY, prefs.gatewayApiKey)
                .putString(KEY_ASSISTANT_MODEL, prefs.assistantModel)
                .putString(KEY_ASSISTANT_PROVIDER, prefs.assistantProvider)
                .build()
            return OneTimeWorkRequestBuilder<ResendWorker>()
                .setInputData(data)
                .setConstraints(
                    Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
                )
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.SECONDS)
                .addTag(UploadWorker.TAG_AGENT_TURN)
                .apply { note.threadId?.let { addTag(UploadWorker.threadTag(it)) } }
                .build()
        }
    }
}
