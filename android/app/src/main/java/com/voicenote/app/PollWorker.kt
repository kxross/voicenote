package com.voicenote.app

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.WorkerParameters

/**
 * Drains pending deliveries the gateway has queued for this device — both
 * scheduled check-ins and the replies to async interactive turns. Runs as a
 * PeriodicWorkRequest from MainActivity (15 min, WorkManager's floor) and as a
 * one-shot ([enqueueOneShot]): when the app opens, on boot, and from the
 * [DeliveryAlarm] that [DeliveryFollowUp.reconcile] arms after every drain.
 *
 * Each delivery is applied through [GatewayAgent.applyDelivery] — the single
 * code path shared with the in-worker await-loop — which applies ops, lands the
 * reply in the right thread, and acks it, exactly once. A single delivery's
 * failure doesn't cascade.
 *
 * When FCM is wired up later (docs/FCM_SETUP.md), the FirebaseMessagingService
 * should just call [enqueueOneShot] — a wake-up signal, no content.
 */
class PollWorker(
    private val context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val prefs = Prefs(context)
        val base = prefs.gatewayBaseUrl
        if (base.isBlank()) return Result.success()

        val apiKey = prefs.gatewayApiKey

        val resp = try {
            GatewayAgent.fetchPending(base, apiKey)
        } catch (e: Exception) {
            Log.w(TAG, "Fetch pending failed: ${e.message}")
            return Result.retry()
        }

        val db = NoteDatabase.get(context)
        val deliveries = resp.deliveries
        if (deliveries.length() > 0) {
            Log.i(TAG, "Draining ${deliveries.length()} pending deliver(y|ies)")
            for (i in 0 until deliveries.length()) {
                val delivery = deliveries.optJSONObject(i) ?: continue
                GatewayAgent.applyDelivery(context, db, delivery, base, apiKey)
            }
            // If ops applied, push a fresh snapshot back so the server view
            // stays consistent.
            SnapshotSync.uploadSnapshot(context, base, apiKey)
        }

        // What's still coming: flag lost turns, arm the next check-back.
        try {
            DeliveryFollowUp.reconcile(context, resp)
        } catch (e: Exception) {
            Log.w(TAG, "Reconcile failed: ${e.message}")
        }
        try {
            db.appliedDeliveryDao().prune(System.currentTimeMillis() - CLAIM_TTL_MS)
        } catch (e: Exception) { /* best effort */ }

        return Result.success()
    }

    /** Only used when an expedited run falls back to a foreground service
     *  (API < 31). Silent, low-importance. */
    override suspend fun getForegroundInfo(): ForegroundInfo {
        val nm = context.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && nm?.getNotificationChannel(SYNC_CHANNEL) == null) {
            nm?.createNotificationChannel(
                NotificationChannel(SYNC_CHANNEL, "Checking for replies", NotificationManager.IMPORTANCE_MIN)
            )
        }
        val n = NotificationCompat.Builder(context, SYNC_CHANNEL)
            .setSmallIcon(R.drawable.ic_frosty)
            .setContentTitle("Checking for replies…")
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setSilent(true)
            .build()
        return ForegroundInfo(SYNC_NOTIFICATION_ID, n)
    }

    companion object {
        private const val TAG = "PollWorker"
        const val UNIQUE_WORK_NAME = "gateway-poll"
        private const val ONESHOT_WORK_NAME = "gateway-poll-oneshot"
        private const val SYNC_CHANNEL = "delivery_sync_channel"
        private const val SYNC_NOTIFICATION_ID = 4243
        /** Applied-delivery claims older than this are pruned. */
        private const val CLAIM_TTL_MS = 30L * 24 * 60 * 60_000L

        /** Fire a single drain now (coalesced — KEEP). [expedited] runs it
         *  promptly even in Doze (from [DeliveryAlarm]); it degrades to normal
         *  work when the expedited quota is spent. */
        fun enqueueOneShot(context: Context, expedited: Boolean = false) {
            val b = OneTimeWorkRequestBuilder<PollWorker>()
                .setConstraints(
                    Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
                )
            if (expedited) b.setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            WorkManager.getInstance(context).enqueueUniqueWork(
                ONESHOT_WORK_NAME,
                ExistingWorkPolicy.KEEP,
                b.build(),
            )
        }
    }
}
