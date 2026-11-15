package com.voicenote.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.work.WorkManager

/**
 * Foreground service — required on Android 10+ to keep the microphone
 * recording when the user switches away from the app.
 *
 * It plays two roles:
 *  1. For MainActivity, it is just the FGS wrapper; the activity owns the
 *     recorder because the waveform needs per-frame amplitude callbacks.
 *  2. For the home-screen widget, it owns the recorder *itself*
 *     ([ACTION_START] / [ACTION_STOP]) — a widget has no activity to hold one,
 *     and a broadcast receiver can't legally start a mic FGS on API 30+.
 *
 * [isRecording] guards the two paths from overlapping.
 */
class RecordingService : Service() {

    inner class RecordingBinder : Binder() {
        fun getService(): RecordingService = this@RecordingService
    }

    private val binder = RecordingBinder()
    private val handler = Handler(Looper.getMainLooper())

    // Widget-owned capture state (null when the activity is driving).
    private var recorder: AudioRecorder? = null
    private var startedAtMs = 0L
    private var scopeGoalId = -1L
    private var scopeListId = -1L
    private val autoStop = Runnable {
        Log.i(TAG, "Auto-stopping widget recording")
        stopWidgetRecording()
    }

    override fun onBind(intent: Intent): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification())

        when (intent?.action) {
            ACTION_START -> startWidgetRecording(intent)
            ACTION_STOP -> stopWidgetRecording()
            // No action = the activity is driving; just hold the foreground.
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacks(autoStop)
        recorder?.stop()
        recorder?.release()
        recorder = null
        isRecording = false
        RecordWidgetProvider.relockAll(this)
        RecordWidgetProvider.refreshAll(this)
        super.onDestroy()
        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    // ---- widget-driven capture ---------------------------------------------

    private fun startWidgetRecording(intent: Intent) {
        if (isRecording) {
            Log.w(TAG, "Already recording; ignoring widget start")
            return
        }
        scopeGoalId = intent.getLongExtra(EXTRA_GOAL_ID, -1L)
        scopeListId = intent.getLongExtra(EXTRA_LIST_ID, -1L)

        val rec = AudioRecorder(this)
        if (rec.start { /* no waveform on the widget path */ } == null) {
            Log.w(TAG, "Recorder failed to start from widget")
            rec.release()
            stopSelf()
            return
        }
        recorder = rec
        startedAtMs = System.currentTimeMillis()
        isRecording = true
        val minutes = Prefs(this).autoStopMinutes.coerceIn(1, 60)
        handler.postDelayed(autoStop, minutes * 60_000L)
        RecordWidgetProvider.refreshAll(this)
        Log.i(TAG, "Widget recording started (goal=$scopeGoalId list=$scopeListId)")
    }

    private fun stopWidgetRecording() {
        handler.removeCallbacks(autoStop)
        val rec = recorder ?: run { stopSelf(); return }
        val durationSeconds = ((System.currentTimeMillis() - startedAtMs) / 1000).toInt()
        val file = rec.stop()
        rec.release()
        recorder = null
        isRecording = false

        if (file != null) {
            val prefs = Prefs(this)
            WorkManager.getInstance(this).enqueue(
                UploadWorker.buildRequest(
                    prefs, file, durationSeconds,
                    goalId = scopeGoalId,
                    listId = scopeListId,
                    actionMode = prefs.actionMode,
                )
            )
            Log.i(TAG, "Widget recording uploaded (${durationSeconds}s)")
        } else {
            Log.i(TAG, "Widget recording too short; discarded")
        }
        // Never leave a widget armed after a capture.
        RecordWidgetProvider.relockAll(this)
        RecordWidgetProvider.refreshAll(this)
        stopSelf()
    }

    // ---- notification -------------------------------------------------------

    private fun buildNotification(): Notification {
        val tapIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_frosty_notif)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(getString(R.string.notification_text))
            .setContentIntent(tapIntent)
            .setOngoing(true)
            .setSilent(true)
            .build()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.channel_desc)
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java)
            .createNotificationChannel(channel)
    }

    companion object {
        private const val TAG = "RecordingService"
        const val CHANNEL_ID = "recording_channel"
        const val NOTIFICATION_ID = 1

        const val ACTION_START = "com.voicenote.app.RECORD_START"
        const val ACTION_STOP = "com.voicenote.app.RECORD_STOP"
        const val EXTRA_GOAL_ID = "goal_id"
        const val EXTRA_LIST_ID = "list_id"

        /**
         * True while the *widget* path holds the mic. Read by the widget to
         * draw its state and by the activity to avoid double-recording.
         */
        @Volatile
        var isRecording: Boolean = false
            private set
    }
}
