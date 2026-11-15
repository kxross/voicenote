package com.voicenote.app

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters

/**
 * Fires a text-only query at the list_manager agent (no audio, no
 * transcript-to-note step). Used by GeofenceReceiver when the phone
 * enters a store's radius. The gateway response gets applied to Room
 * and posted as a notification inside GatewayAgent.callListManager.
 */
class AgentQueryWorker(
    private val context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val storeName = inputData.getString(KEY_STORE_NAME) ?: return Result.success()
        val gatewayUrl = inputData.getString(KEY_GATEWAY_URL)
        val gatewayApiKey = inputData.getString(KEY_GATEWAY_API_KEY)

        if (gatewayUrl.isNullOrBlank()) {
            Log.i(TAG, "No gateway URL configured; skipping agent query for $storeName")
            return Result.success()
        }

        val prompt = "I'm near $storeName. What should I pick up?"
        GatewayAgent.callListManager(
            context = context,
            prompt = prompt,
            gatewayBaseUrl = gatewayUrl,
            gatewayApiKey = gatewayApiKey,
            notificationTitle = "Near $storeName",
            source = "geofence",
        )
        // Cooldown was already updated in the receiver; retrying would spam.
        return Result.success()
    }

    companion object {
        private const val TAG = "AgentQueryWorker"
        const val KEY_STORE_NAME = "store_name"
        const val KEY_GATEWAY_URL = "gateway_url"
        const val KEY_GATEWAY_API_KEY = "gateway_api_key"
    }
}
