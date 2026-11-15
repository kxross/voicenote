package com.voicenote.app

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkerParameters
import androidx.work.workDataOf

/**
 * Scans recent SMS and asks the assistant to extract to-dos from them.
 *
 * Text-only agent turn, same lane as [AgentQueryWorker] — no audio, no note.
 * Ops come back through GatewayAgent.callListManager and are applied to Room
 * for free.
 *
 * Two deliberate differences from the geofence worker:
 *  - it pins the model to LOCAL Ollama, so message content never leaves the
 *    machine even when Settings points the assistant at OpenRouter;
 *  - it syncs the snapshot afterwards (the geofence lane forgets to, so its
 *    list edits never reach the gateway's cached view).
 */
class SmsSweepWorker(
    private val context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val gatewayUrl = inputData.getString(KEY_GATEWAY_URL)
        val gatewayApiKey = inputData.getString(KEY_GATEWAY_API_KEY)
        val lookbackDays = inputData.getInt(KEY_LOOKBACK_DAYS, 7)
        val listName = inputData.getString(KEY_LIST_NAME)?.takeIf { it.isNotBlank() } ?: "To-do"

        if (gatewayUrl.isNullOrBlank()) {
            Log.i(TAG, "No gateway URL configured; skipping SMS sweep")
            return Result.success(workDataOf(KEY_SUMMARY to "Set the gateway URL first"))
        }

        setProgress(workDataOf(UploadWorker.KEY_STAGE to STAGE_READING))
        val messages = SmsReader.recent(context, lookbackDays)
        if (messages.isEmpty()) {
            Log.i(TAG, "No messages in the last $lookbackDays day(s)")
            return Result.success(workDataOf(KEY_SUMMARY to "No messages to sweep"))
        }

        setProgress(workDataOf(UploadWorker.KEY_STAGE to UploadWorker.STAGE_THINKING))
        val result = GatewayAgent.callListManager(
            context = context,
            prompt = buildPrompt(messages, lookbackDays, listName),
            gatewayBaseUrl = gatewayUrl,
            gatewayApiKey = gatewayApiKey,
            notificationTitle = "SMS sweep",
            source = "sms",
            // Pinned to the local provider on purpose: SMS is the most
            // sensitive data this app touches, so it never goes to a remote
            // API even if the user picked one for normal use. Enforced here in
            // code rather than left to configuration.
            providerOverride = "ollama",
            modelOverride = null,
        )

        // Push the newly-added to-dos back so the gateway's cached view stays
        // current (AgentQueryWorker omits this; UploadWorker does it).
        SnapshotSync.uploadSnapshot(context, gatewayUrl, gatewayApiKey)

        return Result.success(
            workDataOf(KEY_SUMMARY to (result.reply ?: "Swept ${messages.size} messages"))
        )
    }

    private fun buildPrompt(
        messages: List<SmsReader.Message>,
        lookbackDays: Int,
        listName: String,
    ): String = """
        Sweep of ${messages.size} text message(s) from the last $lookbackDays day(s).

        Extract only genuine action items for me — commitments I made, requests
        someone made of me, appointments to book, things to buy, return, or follow
        up on. Ignore chit-chat, marketing, delivery notifications, and anything
        automated. If nothing is actionable, add nothing.

        For each action item, call add_item with list exactly "$listName" and a
        short imperative text. My current lists are in your context — do NOT
        re-add anything already on "$listName", and do not add the same item
        twice within this sweep.

        Reply with one short line summarising what you added, or say nothing new
        was found. Do not call no_response.

        --- messages ---
        ${SmsReader.format(messages)}
    """.trimIndent()

    companion object {
        private const val TAG = "SmsSweepWorker"

        const val KEY_GATEWAY_URL = "gateway_url"
        const val KEY_GATEWAY_API_KEY = "gateway_api_key"
        const val KEY_LOOKBACK_DAYS = "lookback_days"
        const val KEY_LIST_NAME = "list_name"
        const val KEY_SUMMARY = "summary"

        /** Reading the inbox precedes the model call; reuses the shared stage
         *  channel so the assistant inbox shows a pending row. */
        const val STAGE_READING = "reading_sms"

        fun buildRequest(prefs: Prefs): OneTimeWorkRequest =
            OneTimeWorkRequestBuilder<SmsSweepWorker>()
                .setInputData(
                    Data.Builder()
                        .putString(KEY_GATEWAY_URL, prefs.gatewayBaseUrl)
                        .putString(KEY_GATEWAY_API_KEY, prefs.gatewayApiKey)
                        .putInt(KEY_LOOKBACK_DAYS, prefs.smsLookbackDays)
                        .putString(KEY_LIST_NAME, prefs.smsTodoList)
                        .build()
                )
                .setConstraints(
                    Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
                )
                // Same tag the voice path uses, so the existing in-flight
                // indicator in the assistant inbox lights up for sweeps too.
                .addTag(UploadWorker.TAG_AGENT_TURN)
                .build()
    }
}
