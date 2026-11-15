package com.voicenote.app

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

/**
 * Pushes the caller's current lists snapshot to the gateway. Called after
 * any list mutation (voice-driven ops applied in the worker, or manual
 * delete/toggle from the UI) so scheduled server-side agent runs see
 * fresh state — see app/scheduler.py + app/state.py on the gateway.
 *
 * Best-effort: failures are logged, never surfaced to the user. If the
 * gateway URL is blank or the network is down, we just skip.
 */
object SnapshotSync {

    private const val TAG = "SnapshotSync"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Suspend variant — call from a coroutine you already own (e.g. a
     * CoroutineWorker's doWork). Runs on the caller's dispatcher.
     */
    suspend fun uploadSnapshot(
        context: Context,
        gatewayBaseUrl: String?,
        gatewayApiKey: String?,
    ) {
        if (gatewayBaseUrl.isNullOrBlank()) return
        try {
            val db = NoteDatabase.get(context)

            // Captioning is async on the gateway; pull back any summaries
            // that have landed since last time. Once mirrored locally they
            // ride along in the notes payload as compact index entries, so
            // no image bytes are ever re-sent.
            val imageDao = db.noteImageDao()
            for (img in imageDao.awaitingSummary()) {
                val sha = img.remoteId ?: continue
                GatewayAgent.fetchImageMeta(gatewayBaseUrl, gatewayApiKey, sha)
                    ?.let { (summary, hasText) ->
                        imageDao.setSummaryBySha(img.sha256, summary, hasText)
                    }
            }
            // Same for videos: once the assistant has watched one, mirror its
            // summary so later turns recall it as text.
            val mediaDao = db.noteMediaDao()
            for (v in mediaDao.awaitingSummary()) {
                val sha = v.remoteId ?: continue
                GatewayAgent.fetchMediaSummary(gatewayBaseUrl, gatewayApiKey, sha)
                    ?.let { mediaDao.setSummaryByRemoteId(sha, it) }
            }
            val snapshot = db.listDao().snapshot()
            val notes = db.noteDao().snapshotWithGoal()
            val goals = db.goalDao().snapshot()
            val notesByGoal = notes
                .filter { it.note.goalId != null }
                .groupBy { it.note.goalId!! }
                .mapValues { (_, v) -> v.map { it.note } }
            val imagesByNote = imageDao.recent().groupBy { it.noteId }
            val body = JSONObject()
                .put("lists", GatewayAgent.snapshotToJson(snapshot))
                .put("notes", GatewayAgent.notesToJson(notes, imagesByNote))
                .put("goals", GatewayAgent.goalsToJson(goals, notesByGoal))

            val requestBuilder = Request.Builder()
                .url("$gatewayBaseUrl/v1/snapshot/${GatewayAgent.SESSION_ID}")
                .post(body.toString().toRequestBody(GatewayAgent.JSON_MEDIA_TYPE))
            GatewayAgent.addAuth(requestBuilder, gatewayApiKey)

            val response = GatewayAgent.httpClient.newCall(requestBuilder.build()).execute()
            response.body?.close()
            if (!response.isSuccessful) {
                Log.w(TAG, "Snapshot upload ${response.code}")
            } else {
                Log.i(TAG, "Snapshot uploaded (${snapshot.size} lists, ${notes.size} notes)")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Snapshot upload failed: ${e.message}")
        }
    }

    /**
     * Fire-and-forget variant for the UI thread. Uses an internal
     * SupervisorJob-scoped Dispatchers.IO coroutine so cancelling the
     * caller's activity doesn't cancel the upload.
     */
    fun uploadSnapshotAsync(context: Context) {
        val prefs = Prefs(context)
        val base = prefs.gatewayBaseUrl
        if (base.isBlank()) return
        val key = prefs.gatewayApiKey
        val appCtx = context.applicationContext
        scope.launch { uploadSnapshot(appCtx, base, key) }
    }
}
