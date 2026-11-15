package com.voicenote.app

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews

/**
 * Home-screen record widget: tap to start, tap again to stop.
 *
 * Two things shape this implementation:
 *  - RemoteViews only deliver *clicks*, never touch down/up, so press-and-hold
 *    is impossible here; the interaction is a tap toggle.
 *  - A widget's onReceive is a background context, and on API 30+ starting a
 *    `foregroundServiceType="microphone"` service from there gets no mic. So
 *    the record tap goes through [WidgetRecordActivity], a no-UI trampoline
 *    that starts the service from the foreground.
 */
class RecordWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray,
    ) {
        for (id in appWidgetIds) render(context, appWidgetManager, id)
    }

    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        val prefs = Prefs(context)
        for (id in appWidgetIds) prefs.clearWidgetScope(id)
    }

    companion object {

        const val ACTION_TOGGLE_LOCK = "com.voicenote.app.WIDGET_TOGGLE_LOCK"

        /** Redraw every placed widget — called when recording starts/stops or
         *  the scope changes. */
        fun refreshAll(context: Context) {
            val mgr = AppWidgetManager.getInstance(context)
            val ids = mgr.getAppWidgetIds(
                ComponentName(context, RecordWidgetProvider::class.java)
            )
            for (id in ids) render(context, mgr, id)
        }

        /** Re-lock every widget — called after a recording ends so nothing is
         *  left armed. */
        fun relockAll(context: Context) {
            val prefs = Prefs(context)
            val mgr = AppWidgetManager.getInstance(context)
            for (id in mgr.getAppWidgetIds(
                ComponentName(context, RecordWidgetProvider::class.java)
            )) {
                prefs.lockWidget(id)
            }
        }

        fun render(context: Context, mgr: AppWidgetManager, widgetId: Int) {
            val prefs = Prefs(context)
            val recording = RecordingService.isRecording
            val unlocked = prefs.isWidgetUnlocked(widgetId)
            val views = RemoteViews(context.packageName, R.layout.widget_record)

            views.setTextViewText(
                R.id.widgetState,
                when {
                    recording -> "Recording — tap to send"
                    unlocked -> "Unlocked — tap to record"
                    else -> "Locked"
                },
            )
            views.setTextViewText(R.id.widgetLock, if (unlocked) "🔓" else "🔒")
            val label = prefs.getWidgetLabel(widgetId).ifBlank { "Unscoped" }
            views.setTextViewText(R.id.widgetScope, label)

            // Lock chip toggles the arm state.
            val lockIntent = Intent(context, WidgetRecordActivity::class.java).apply {
                action = ACTION_TOGGLE_LOCK
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            }
            views.setOnClickPendingIntent(
                R.id.widgetLock,
                PendingIntent.getActivity(
                    context, widgetId + 200_000, lockIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                ),
            )

            // Record tap -> trampoline activity (foreground) -> service.
            val recordIntent = Intent(context, WidgetRecordActivity::class.java).apply {
                action = if (recording) RecordingService.ACTION_STOP else RecordingService.ACTION_START
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            }
            views.setOnClickPendingIntent(
                R.id.widgetRecord,
                PendingIntent.getActivity(
                    context, widgetId, recordIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                ),
            )

            // Scope chip is its own tap target — "switchable in place".
            val scopeIntent = Intent(context, WidgetScopePickerActivity::class.java).apply {
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            }
            views.setOnClickPendingIntent(
                R.id.widgetScope,
                PendingIntent.getActivity(
                    // Offset the request code so it can't collide with the
                    // record PendingIntent for the same widget.
                    context, widgetId + 100_000, scopeIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                ),
            )

            mgr.updateAppWidget(widgetId, views)
        }
    }
}
