package com.voicenote.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofencingEvent

/**
 * Handles ENTER events from Play Services geofencing. Looks up the store
 * by request id, checks the per-store cooldown, and enqueues an
 * AgentQueryWorker if we should fire. Broadcast receivers get ~10 s to
 * finish; we do only cheap work here and defer the network hop to the
 * worker.
 */
class GeofenceReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val event = GeofencingEvent.fromIntent(intent) ?: return
        if (event.hasError()) {
            Log.w(TAG, "Geofencing event error: ${event.errorCode}")
            return
        }
        if (event.geofenceTransition != Geofence.GEOFENCE_TRANSITION_ENTER) return

        val triggered = event.triggeringGeofences ?: return
        val prefs = Prefs(context)
        val now = System.currentTimeMillis()

        for (fence in triggered) {
            val store = Stores.ALL.firstOrNull { it.id == fence.requestId } ?: continue
            val lastFired = prefs.getStoreLastFired(store.id)
            if (now - lastFired < Stores.COOLDOWN_MS) {
                Log.i(TAG, "Skipping ${store.name} — still in cooldown")
                continue
            }
            prefs.setStoreLastFired(store.id, now)
            enqueue(context, store, prefs)
        }
    }

    private fun enqueue(context: Context, store: Store, prefs: Prefs) {
        Log.i(TAG, "Enqueuing agent query for ${store.name}")
        val data = Data.Builder()
            .putString(AgentQueryWorker.KEY_STORE_NAME, store.name)
            .putString(AgentQueryWorker.KEY_GATEWAY_URL, prefs.gatewayBaseUrl)
            .putString(AgentQueryWorker.KEY_GATEWAY_API_KEY, prefs.gatewayApiKey)
            .build()

        val work = OneTimeWorkRequestBuilder<AgentQueryWorker>()
            .setInputData(data)
            .setConstraints(
                Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
            )
            .build()

        WorkManager.getInstance(context).enqueue(work)
    }

    companion object {
        private const val TAG = "GeofenceReceiver"
    }
}
