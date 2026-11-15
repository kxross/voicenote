package com.voicenote.app

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

/**
 * Redraws the widgets once an unlock window lapses, so the chip flips back to
 * 🔒 on its own.
 *
 * Purely cosmetic: the lock itself is an expiry timestamp evaluated at tap
 * time ([Prefs.isWidgetUnlocked]), so recording is blocked the instant the
 * window passes whether or not this ever runs. That matters — WorkManager
 * batches, so this can fire late, and the safety property must not depend on
 * it.
 */
class WidgetRelockWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        RecordWidgetProvider.refreshAll(applicationContext)
        return Result.success()
    }

    companion object {
        private const val UNIQUE_NAME = "widget_relock"

        fun schedule(context: Context) {
            WorkManager.getInstance(context).enqueueUniqueWork(
                UNIQUE_NAME,
                ExistingWorkPolicy.REPLACE,
                OneTimeWorkRequestBuilder<WidgetRelockWorker>()
                    .setInitialDelay(
                        Prefs.WIDGET_UNLOCK_WINDOW_MS + 500, TimeUnit.MILLISECONDS
                    )
                    .build(),
            )
        }
    }
}
