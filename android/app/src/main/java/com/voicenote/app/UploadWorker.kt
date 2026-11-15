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
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

class UploadWorker(
    private val context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    companion object {
        const val KEY_FILE_PATH = "file_path"
        const val KEY_TRANSCRIPT_OVERRIDE = "transcript_override"
        const val KEY_ENDPOINT_URL = "endpoint_url"
        const val KEY_DURATION_SECONDS = "duration_seconds"
        const val KEY_GATEWAY_URL = "gateway_url"
        const val KEY_GATEWAY_API_KEY = "gateway_api_key"
        const val KEY_AGENT_REPLY = "agent_reply"
        const val KEY_GOAL_ID = "goal_id"                       // -1 = unscoped
        const val KEY_LIST_ID = "list_id"                       // -1 = unscoped
        const val KEY_REPLY_TO_MESSAGE_ID = "reply_to_message"  // -1 = not a reply
        const val KEY_THREAD_ID = "thread_id"                   // -1 = no explicit thread
        const val KEY_IMAGE_PATHS = "image_paths"               // staged attachments
        const val KEY_ACTION_MODE = "action_mode"               // false = save note, skip LLM
        const val KEY_ASSISTANT_MODEL = "assistant_model"       // "" = gateway default
        const val KEY_ASSISTANT_PROVIDER = "assistant_provider" // ollama | openrouter | ""
        const val KEY_ARTIFACT_ID = "artifact_id"               // spoken from the reader: the doc it's about
        const val KEY_VIDEOS = "videos"                         // staged videos (VideoStore.Stored JSON)
        const val STAGE_UPLOADING_VIDEO = "uploading_video"

        // --- live progress -------------------------------------------------
        // Published via setProgress() so the assistant screens + main screen
        // can show what's happening during a turn (the LLM call alone can run
        // for minutes). Observed by tag, since callers don't hold the work id.
        const val KEY_STAGE = "stage"
        const val STAGE_TRANSCRIBING = "transcribing"
        const val STAGE_THINKING = "thinking"

        /** Every agent turn carries this — the inbox watches it. */
        const val TAG_AGENT_TURN = "agent_turn"

        /** Turns bound to a known thread also carry this, so that thread's
         *  conversation can show the pending bubble inline. */
        fun threadTag(threadId: Long): String = "thread-$threadId"

        /** Tag on a request spoken from a document's reader, so the reader can
         *  show the turn's progress. */
        fun artifactTag(artifactId: String): String = "artifact-$artifactId"

        /** Stage -> human label. Shared by the thread bubble, the inbox row,
         *  and the main screen so all three say the same thing. */
        fun stageLabel(stage: String?): String = when (stage) {
            STAGE_THINKING -> "Thinking…"
            STAGE_TRANSCRIBING -> "Transcribing…"
            STAGE_UPLOADING_VIDEO -> "Uploading video…"
            SmsSweepWorker.STAGE_READING -> "Reading messages…"
            else -> "Working…"
        }

        // Whisper uploads use a long write timeout because m4a payloads can be big.
        private val whisperClient = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(120, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build()

        /**
         * One place that knows how to build an upload request — used by
         * MainActivity (unscoped), GoalDetailActivity (goal-scoped), and
         * AssistantActivity (message reply, inherits the message's scope).
         */
        fun buildRequest(
            prefs: Prefs,
            file: File?,
            durationSeconds: Int,
            goalId: Long = -1L,
            replyToMessageId: Long = -1L,
            threadId: Long = -1L,
            imagePaths: Array<String> = emptyArray(),
            actionMode: Boolean = true,
            listId: Long = -1L,
            // On-device dictation supplies the transcript directly; when set the
            // worker skips the audio upload and [file] may be null.
            transcriptOverride: String? = null,
            // Spoken from a document's reader: the gateway treats the request
            // as about that document (and revises it in place).
            artifactId: String? = null,
            // Staged videos (VideoStore.Stored.toJson()); uploaded after the note
            // row exists and handed to the agent as `videos`.
            videos: Array<String> = emptyArray(),
        ): OneTimeWorkRequest {
            val inputData = Data.Builder()
                .putStringArray(KEY_IMAGE_PATHS, imagePaths)
                .putStringArray(KEY_VIDEOS, videos)
                .putString(KEY_FILE_PATH, file?.absolutePath)
                .putString(KEY_TRANSCRIPT_OVERRIDE, transcriptOverride)
                .putString(KEY_ENDPOINT_URL, prefs.endpointUrl)
                .putInt(KEY_DURATION_SECONDS, durationSeconds)
                .putString(KEY_GATEWAY_URL, prefs.gatewayBaseUrl)
                .putString(KEY_GATEWAY_API_KEY, prefs.gatewayApiKey)
                .putLong(KEY_GOAL_ID, goalId)
                .putLong(KEY_LIST_ID, listId)
                .putLong(KEY_REPLY_TO_MESSAGE_ID, replyToMessageId)
                .putLong(KEY_THREAD_ID, threadId)
                .putBoolean(KEY_ACTION_MODE, actionMode)
                .putString(KEY_ASSISTANT_MODEL, prefs.assistantModel)
                .putString(KEY_ASSISTANT_PROVIDER, prefs.assistantProvider)
                .putString(KEY_ARTIFACT_ID, artifactId)
                .build()
            return OneTimeWorkRequestBuilder<UploadWorker>()
                .setInputData(inputData)
                .setConstraints(
                    Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
                )
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.SECONDS)
                .addTag(TAG_AGENT_TURN)
                .apply { if (threadId >= 0) addTag(threadTag(threadId)) }
                .apply { if (!artifactId.isNullOrBlank()) addTag(artifactTag(artifactId)) }
                .build()
        }
    }

    override suspend fun doWork(): Result {
        val transcriptOverride = inputData.getString(KEY_TRANSCRIPT_OVERRIDE)?.takeIf { it.isNotBlank() }
        val filePath = inputData.getString(KEY_FILE_PATH)
        val endpointUrl = inputData.getString(KEY_ENDPOINT_URL)
        val durationSeconds = inputData.getInt(KEY_DURATION_SECONDS, 0)
        val gatewayUrl = inputData.getString(KEY_GATEWAY_URL)
        val gatewayApiKey = inputData.getString(KEY_GATEWAY_API_KEY)
        val goalId = inputData.getLong(KEY_GOAL_ID, -1L).takeIf { it >= 0 }
        val listId = inputData.getLong(KEY_LIST_ID, -1L).takeIf { it >= 0 }
        val replyToMessageId = inputData.getLong(KEY_REPLY_TO_MESSAGE_ID, -1L).takeIf { it >= 0 }
        val explicitThreadId = inputData.getLong(KEY_THREAD_ID, -1L).takeIf { it >= 0 }
        val imagePaths = inputData.getStringArray(KEY_IMAGE_PATHS) ?: emptyArray()
        val actionMode = inputData.getBoolean(KEY_ACTION_MODE, true)
        val assistantModel = inputData.getString(KEY_ASSISTANT_MODEL)
        val assistantProvider = inputData.getString(KEY_ASSISTANT_PROVIDER)
        val artifactId = inputData.getString(KEY_ARTIFACT_ID)?.takeIf { it.isNotBlank() }
        val videos = inputData.getStringArray(KEY_VIDEOS) ?: emptyArray()

        // Need either a supplied transcript (on-device dictation) or a recorded
        // file to send to the transcription endpoint.
        if (transcriptOverride == null && filePath.isNullOrBlank()) {
            Log.e("UploadWorker", "No transcript and no audio file")
            return Result.failure()
        }

        return try {
            val db = NoteDatabase.get(context)
            val threadDao = db.threadDao()

            // 1) Obtain the transcript: supplied directly (on-device dictation),
            //    or by uploading the recorded audio to the endpoint.
            val transcript: String = if (transcriptOverride != null) {
                transcriptOverride
            } else {
                if (endpointUrl.isNullOrBlank()) {
                    Log.e("UploadWorker", "No transcription endpoint configured")
                    return Result.failure()
                }
                setProgress(workDataOf(KEY_STAGE to STAGE_TRANSCRIBING))
                val file = File(filePath!!)
                if (!file.exists()) {
                    Log.e("UploadWorker", "File not found: $filePath")
                    return Result.failure()
                }
                val requestBody = MultipartBody.Builder()
                    .setType(MultipartBody.FORM)
                    .addFormDataPart(
                        name = "file",
                        filename = file.name,
                        body = file.asRequestBody("audio/mp4".toMediaType())
                    )
                    .addFormDataPart("model", "parakeet-tdt-0.6b-v2")
                    .build()
                val response = whisperClient.newCall(
                    Request.Builder().url(endpointUrl).post(requestBody).build()
                ).execute()
                val bodyStr = response.body?.string() ?: ""
                if (!response.isSuccessful) {
                    Log.w("UploadWorker", "Server error ${response.code}: $bodyStr")
                    return if (response.code in 400..499) Result.failure() else Result.retry()
                }
                Log.i("UploadWorker", "Success (${response.code}): $bodyStr")
                val parsed = try {
                    JSONObject(bodyStr).getString("text").trim()
                } catch (e: Exception) {
                    Log.w("UploadWorker", "Could not parse transcript from response: $bodyStr")
                    ""
                }
                file.delete()
                parsed
            }

            // 2) Common downstream: save the note, open/attach the thread, upload
            //    attachments, and (in Action mode) run the agent turn. The record
            //    button's Action / No-action mode is the single switch; No-action
            //    transcribes and saves without the assistant bounce.
            val willCallAgent = actionMode &&
                !gatewayUrl.isNullOrBlank() && transcript.isNotBlank()

                // Thread attachment rules:
                //  1. explicit thread (thread-detail mic) -> that thread,
                //     reopening it if it was resolved.
                //  2. goal-scoped recording -> the goal's open thread if any.
                //  3. list-scoped recording -> that list's open thread if any.
                //  4. unscoped main-screen Action recording -> open a thread
                //     NOW, as soon as the transcript lands, so the user's
                //     message shows up in the assistant immediately instead of
                //     only after the model replies (eager creation).
                //  5. anything else -> unthreaded; a thread is created only if
                //     the agent engages (deferred creation).
                var threadId: Long? = explicitThreadId
                if (threadId != null) {
                    threadDao.reopen(threadId)
                } else if (goalId != null) {
                    threadId = threadDao.findOpenThreadForGoal(goalId)?.id
                } else if (listId != null) {
                    threadId = threadDao.findOpenThreadForList(listId)?.id
                } else if (willCallAgent) {
                    threadId = threadDao.insert(
                        ThreadEntity(title = GatewayAgent.threadTitleFor("assistant", transcript))
                    )
                    Log.i("UploadWorker", "Opened thread $threadId eagerly after transcription")
                }

                var noteId: Long? = null
                if (transcript.isNotBlank()) {
                    noteId = db.noteDao().insert(
                        NoteEntity(
                            transcript = transcript,
                            durationSeconds = durationSeconds,
                            goalId = goalId,
                            replyToMessageId = replyToMessageId,
                            threadId = threadId,
                        )
                    )
                    Log.i("UploadWorker", "Saved note (goal=$goalId thread=$threadId): \"${transcript.take(60)}…\"")

                    // Attachments were staged before recording; now that the
                    // note row exists they get their FK. Files were already
                    // downscaled by ImageStore at pick time.
                    if (imagePaths.isNotEmpty()) {
                        val imageDao = db.noteImageDao()
                        for (p in imagePaths) {
                            val f = File(p)
                            if (!f.exists()) {
                                Log.w("UploadWorker", "Attachment missing: $p")
                                continue
                            }
                            imageDao.insert(
                                NoteImageEntity(
                                    noteId = noteId!!,
                                    localPath = p,
                                    sha256 = ImageStore.sha256Of(f),
                                    bytes = f.length().toInt(),
                                )
                            )
                        }
                        Log.i("UploadWorker", "Attached ${imagePaths.size} image(s) to note $noteId")

                        // Push blobs to the gateway before the agent call so
                        // the payload can reference them by id (and so the
                        // agent can see them inline on this first turn).
                        if (!gatewayUrl.isNullOrBlank()) {
                            val files = imagePaths.map { File(it) }.filter { it.exists() }
                            val ids = GatewayAgent.uploadImages(gatewayUrl, gatewayApiKey, files)
                            for (row in imageDao.forNote(noteId)) {
                                ids[File(row.localPath).name]?.let { rid ->
                                    imageDao.setRemoteId(row.id, rid)
                                }
                            }
                        }
                    }

                    // Videos: rows now that the note exists, then a streamed
                    // upload (can take a while on mobile data — the stage says so).
                    val staged = videos.mapNotNull { VideoStore.Stored.fromJson(it) }
                        .filter { File(it.path).exists() }
                    if (staged.isNotEmpty()) {
                        val mediaDao = db.noteMediaDao()
                        for (v in staged) {
                            val rowId = mediaDao.insert(
                                NoteMediaEntity(
                                    noteId = noteId!!, localPath = v.path, thumbPath = v.thumbPath,
                                    mime = v.mime, bytes = v.bytes, durationMs = v.durationMs,
                                    width = v.width, height = v.height,
                                )
                            )
                            if (!gatewayUrl.isNullOrBlank()) {
                                setProgress(workDataOf(KEY_STAGE to STAGE_UPLOADING_VIDEO))
                                GatewayAgent.uploadMedia(gatewayUrl, gatewayApiKey, v)?.let { rid ->
                                    mediaDao.setRemoteId(rowId, rid)
                                }
                            }
                        }
                        Log.i("UploadWorker", "Attached ${staged.size} video(s) to note $noteId")
                    }
                }

                // "No action" (main record button toggled off) transcribes and
                // saves the note but skips the LLM bounce. It STILL syncs to the
                // gateway (blobs were already uploaded above; push the snapshot
                // below) so recall + scheduled goal-mining still see the note.
                val agentReply = if (willCallAgent) {
                    // The LLM round trip is the long pole — tell the UI.
                    setProgress(workDataOf(KEY_STAGE to STAGE_THINKING))
                    val scopedGoal = goalId?.let { db.goalDao().findGoalById(it) }
                    val scopedList = listId?.let { db.listDao().findListById(it) }
                    val result = GatewayAgent.callListManager(
                        context = context,
                        prompt = transcript,
                        gatewayBaseUrl = gatewayUrl!!,  // willCallAgent guarantees non-null/non-blank
                        gatewayApiKey = gatewayApiKey,
                        notificationTitle = scopedGoal?.title ?: scopedList?.name ?: "assistant",
                        scopedGoalId = scopedGoal?.id,
                        scopedGoalTitle = scopedGoal?.title,
                        scopedListId = scopedList?.id,
                        scopedListTitle = scopedList?.name,
                        source = "voice",
                        threadId = threadId,
                        currentNoteId = noteId,
                        noteId = noteId,
                        modelOverride = assistantModel,
                        providerOverride = assistantProvider,
                        focusArtifactId = artifactId,
                        // Long research turns can run many minutes; submit async
                        // so the reply arrives via the delivery queue instead of
                        // holding (and timing out) the HTTP socket.
                        async = true,
                    )
                    // Deferred thread creation: if the agent engaged and a new
                    // thread was opened, attach the triggering note to it.
                    if (result.threadId != null && threadId == null && noteId != null) {
                        db.noteDao().setThreadId(noteId, result.threadId)
                    }
                    SnapshotSync.uploadSnapshot(context, gatewayUrl, gatewayApiKey)
                    result.reply
                } else {
                    // No-action (or agent disabled): keep the gateway's note
                    // cache current without an LLM call.
                    if (!gatewayUrl.isNullOrBlank() && transcript.isNotBlank()) {
                        SnapshotSync.uploadSnapshot(context, gatewayUrl, gatewayApiKey)
                    }
                    null
                }

                if (agentReply != null) {
                    Result.success(workDataOf(KEY_AGENT_REPLY to agentReply))
                } else {
                    Result.success()
                }
        } catch (e: Exception) {
            Log.e("UploadWorker", "Upload failed: ${e.message}")
            Result.retry()
        }
    }
}
