package com.voicenote.app

import android.Manifest
import android.appwidget.AppWidgetManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

/**
 * Invisible trampoline for the widget's record tap.
 *
 * It exists purely to give the mic foreground service a *foreground* launch
 * context: a widget's broadcast receiver is a background context, and on
 * API 30+ starting a `foregroundServiceType="microphone"` service from there
 * yields no microphone. This activity starts the service and finishes
 * immediately — it never draws anything.
 */
class WidgetRecordActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val widgetId = intent.getIntExtra(
            AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID
        )
        val stopping = intent.action == RecordingService.ACTION_STOP

        // Lock chip: arm (or disarm) this widget, then redraw and get out.
        if (intent.action == RecordWidgetProvider.ACTION_TOGGLE_LOCK) {
            val prefs = Prefs(this)
            if (prefs.isWidgetUnlocked(widgetId)) {
                prefs.lockWidget(widgetId)
            } else {
                prefs.unlockWidget(widgetId)
                // Visually re-lock when the window lapses; the functional lock
                // is time-based and needs no timer, but the chip does.
                WidgetRelockWorker.schedule(this)
            }
            RecordWidgetProvider.refreshAll(this)
            finish()
            return
        }

        if (!stopping) {
            // The gate lives HERE, not in the widget's drawing — a stale
            // RemoteViews render can't be used to bypass it.
            if (!Prefs(this).isWidgetUnlocked(widgetId)) {
                Toast.makeText(this, "Locked — tap 🔒 to unlock", Toast.LENGTH_SHORT).show()
                RecordWidgetProvider.refreshAll(this)
                finish()
                return
            }
        }

        if (!stopping) {
            // Mic permission can't be requested usefully from here (we finish
            // immediately), so send the user to the app to grant it once.
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED
            ) {
                Toast.makeText(
                    this, "Open the app once to grant microphone access", Toast.LENGTH_LONG
                ).show()
                startActivity(
                    Intent(this, MainActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
                finish()
                return
            }
            if (Prefs(this).gatewayBaseUrl.isBlank() && Prefs(this).endpointUrl.isBlank()) {
                Toast.makeText(this, "Set the endpoint in Settings first", Toast.LENGTH_LONG).show()
                finish()
                return
            }
        }

        val service = Intent(this, RecordingService::class.java).apply {
            action = if (stopping) RecordingService.ACTION_STOP else RecordingService.ACTION_START
            if (!stopping && widgetId != AppWidgetManager.INVALID_APPWIDGET_ID) {
                val prefs = Prefs(this@WidgetRecordActivity)
                putExtra(RecordingService.EXTRA_GOAL_ID, prefs.getWidgetGoalId(widgetId))
                putExtra(RecordingService.EXTRA_LIST_ID, prefs.getWidgetListId(widgetId))
            }
        }
        startForegroundService(service)

        // No UI, no transition — the widget itself is the feedback surface.
        finish()
        overridePendingTransition(0, 0)
    }
}
