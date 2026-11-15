package com.voicenote.app

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters

/**
 * Resumes a paused research run: fires a text-only agent turn with
 * `resume_run_id` set, so the gateway re-seeds from the run's on-disk
 * checkpoint and continues. Runs in WorkManager so it survives the user
 * leaving the thread screen. The reply (and any further resume handoff) is
 * delivered through GatewayAgent.callListManager like any other turn.
 */
class ResumeWorker(
    private val context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val runId = inputData.getString(KEY_RUN_ID) ?: return Result.success()
        val threadId = inputData.getLong(KEY_THREAD_ID, -1L).takeIf { it >= 0 }
        val title = inputData.getString(KEY_TITLE) ?: "assistant"
        val prefs = Prefs(context)
        if (prefs.gatewayBaseUrl.isBlank()) return Result.success()
        return try {
            GatewayAgent.resumeRun(
                context = context,
                gatewayBaseUrl = prefs.gatewayBaseUrl,
                gatewayApiKey = prefs.gatewayApiKey,
                runId = runId,
                threadId = threadId,
                notificationTitle = title,
            )
            Result.success()
        } catch (e: Exception) {
            Log.w(TAG, "Resume of $runId failed: ${e.message}")
            Result.retry()
        }
    }

    companion object {
        private const val TAG = "ResumeWorker"
        const val KEY_RUN_ID = "run_id"
        const val KEY_THREAD_ID = "thread_id"
        const val KEY_TITLE = "title"

        fun enqueue(context: Context, runId: String, threadId: Long?, title: String) {
            val data = Data.Builder()
                .putString(KEY_RUN_ID, runId)
                .putLong(KEY_THREAD_ID, threadId ?: -1L)
                .putString(KEY_TITLE, title)
                .build()
            WorkManager.getInstance(context).enqueue(
                OneTimeWorkRequestBuilder<ResumeWorker>().setInputData(data).build()
            )
        }
    }
}
