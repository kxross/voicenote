package com.voicenote.app

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Thin wrapper around MediaRecorder.
 * Outputs M4A/AAC at 16kHz mono — optimal for speech / transcription.
 */
class AudioRecorder(private val context: Context) {

    private var recorder: MediaRecorder? = null
    private var currentFile: File? = null
    private var amplitudeCallback: ((Float) -> Unit)? = null
    private var amplitudeThread: Thread? = null

    val isRecording: Boolean get() = recorder != null

    /**
     * Start recording. Returns the output file, or null on error.
     */
    fun start(onAmplitude: ((Float) -> Unit)? = null): File? {
        amplitudeCallback = onAmplitude
        val dir = context.cacheDir
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val file = File(dir, "voice_note_$timestamp.m4a")
        currentFile = file

        recorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MediaRecorder(context)
        } else {
            @Suppress("DEPRECATION")
            MediaRecorder()
        }

        try {
            recorder!!.apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioSamplingRate(16000)   // 16kHz — sweet spot for STT
                setAudioEncodingBitRate(96000)
                setAudioChannels(1)            // Mono — half the file size, same quality for voice
                setOutputFile(file.absolutePath)
                prepare()
                start()
            }
            startAmplitudePolling()
            return file
        } catch (e: Exception) {
            Log.e("AudioRecorder", "Failed to start recording", e)
            recorder?.release()
            recorder = null
            currentFile = null
            return null
        }
    }

    /**
     * Stop recording. Returns the output file path, or null if nothing was recorded.
     */
    fun stop(): File? {
        stopAmplitudePolling()
        val file = currentFile
        try {
            recorder?.stop()
        } catch (e: Exception) {
            Log.e("AudioRecorder", "Stop error (probably too short)", e)
            recorder?.release()
            recorder = null
            currentFile = null
            return null
        }
        recorder?.release()
        recorder = null
        currentFile = null

        // Reject files under 0.5 KB (basically empty / too short)
        if (file == null || !file.exists() || file.length() < 500) {
            file?.delete()
            return null
        }
        return file
    }

    private fun startAmplitudePolling() {
        amplitudeThread = Thread {
            while (recorder != null) {
                try {
                    val maxAmp = recorder?.maxAmplitude ?: 0
                    // Normalize to 0..1 (max amplitude is ~32768)
                    val normalized = (maxAmp / 32768f).coerceIn(0f, 1f)
                    amplitudeCallback?.invoke(normalized)
                    Thread.sleep(80)
                } catch (_: InterruptedException) {
                    break
                } catch (_: Exception) {
                    break
                }
            }
        }.also { it.isDaemon = true; it.start() }
    }

    private fun stopAmplitudePolling() {
        amplitudeThread?.interrupt()
        amplitudeThread = null
    }

    fun release() {
        stopAmplitudePolling()
        recorder?.release()
        recorder = null
    }
}
