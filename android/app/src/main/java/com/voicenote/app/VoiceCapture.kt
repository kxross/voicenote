package com.voicenote.app

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import androidx.work.WorkManager

/**
 * Tap-to-toggle voice capture for secondary screens (goal detail,
 * assistant thread). Wraps AudioRecorder + RecordingService lifecycle +
 * UploadWorker enqueue so each screen doesn't reimplement the flow.
 *
 * Not for the main screen — MainActivity keeps its press-and-hold
 * RecordButton with waveform/timer UI; this is the compact variant.
 */
class VoiceCapture(private val activity: AppCompatActivity) {

    private val recorder = AudioRecorder(activity)
    private var startMs = 0L
    var isRecording = false
        private set

    /** Start capture. Returns false if the recorder failed to start. */
    fun start(): Boolean {
        if (isRecording) return true
        val serviceIntent = Intent(activity, RecordingService::class.java)
        activity.startForegroundService(serviceIntent)
        val started = recorder.start { /* no waveform on compact screens */ }
        if (started == null) {
            activity.stopService(serviceIntent)
            Log.w(TAG, "Recorder failed to start")
            return false
        }
        startMs = System.currentTimeMillis()
        isRecording = true
        return true
    }

    /**
     * Stop capture and enqueue the upload with the given scope. Returns
     * true if a recording was captured and enqueued (false = too short
     * or nothing in progress).
     */
    fun stopAndUpload(
        goalId: Long = -1L,
        replyToMessageId: Long = -1L,
        threadId: Long = -1L,
        listId: Long = -1L,
        imagePaths: Array<String> = emptyArray(),
        /** Recorded in a document's reader: the request is about that doc. */
        artifactId: String? = null,
        /** Staged videos (VideoStore.Stored JSON) to attach to this note. */
        videos: Array<String> = emptyArray(),
    ): Boolean {
        if (!isRecording) return false
        isRecording = false
        val durationSeconds = ((System.currentTimeMillis() - startMs) / 1000).toInt()
        val file = recorder.stop()
        activity.stopService(Intent(activity, RecordingService::class.java))
        if (file == null) {
            Log.i(TAG, "Recording too short; discarded")
            return false
        }
        val prefs = Prefs(activity)
        WorkManager.getInstance(activity).enqueue(
            UploadWorker.buildRequest(
                prefs, file, durationSeconds, goalId, replyToMessageId, threadId,
                imagePaths = imagePaths,
                listId = listId,
                artifactId = artifactId,
                videos = videos,
            )
        )
        return true
    }

    /** Abandon any in-flight recording (e.g. onDestroy). */
    fun cancel(context: Context) {
        if (isRecording) {
            isRecording = false
            recorder.stop()
            context.stopService(Intent(context, RecordingService::class.java))
        }
        recorder.release()
    }

    companion object {
        private const val TAG = "VoiceCapture"
    }
}
