package com.voicenote.app

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.widget.Toast
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.voicenote.app.databinding.ItemAttachmentBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.voicenote.app.databinding.ActivityMainBinding
import okhttp3.sse.EventSource
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var prefs: Prefs
    private val recorder = AudioRecorder(this)
    // On-device dictation path (set only while a dictation recording is live).
    private var transcriber: OnDeviceTranscriber? = null
    private var dictating = false
    private val handler = Handler(Looper.getMainLooper())
    private val listViewModel: ListViewModel by viewModels()
    private val goalViewModel: GoalViewModel by viewModels()
    private val assistantViewModel: AssistantViewModel by viewModels()

    // Photos picked BEFORE recording. The note row doesn't exist until the
    // transcript comes back, so attachments are staged here and handed to
    // UploadWorker as file paths when the recording is enqueued.
    private val pendingImages = mutableListOf<ImageStore.Stored>()
    // Videos picked before recording (at most VideoStore.MAX_PER_NOTE); copied
    // into app storage by VideoStore and uploaded by the worker.
    private val pendingVideos = mutableListOf<VideoStore.Stored>()

    private val pickImages = registerForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(ImageStore.MAX_ATTACHMENTS + VideoStore.MAX_PER_NOTE)
    ) { uris ->
        if (uris.isEmpty()) return@registerForActivityResult
        val (videoUris, imageUris) = uris.partition { VideoStore.isVideo(this, it) }
        val room = ImageStore.MAX_ATTACHMENTS - pendingImages.size
        if (imageUris.isNotEmpty() && room <= 0) {
            Toast.makeText(this, "Up to ${ImageStore.MAX_ATTACHMENTS} photos", Toast.LENGTH_SHORT).show()
        } else if (imageUris.size > room) {
            Toast.makeText(this, "Only $room more photo${if (room == 1) "" else "s"} allowed", Toast.LENGTH_SHORT).show()
        }
        val videoRoom = VideoStore.MAX_PER_NOTE - pendingVideos.size
        if (videoUris.size > videoRoom) {
            Toast.makeText(this, "One video per note", Toast.LENGTH_SHORT).show()
        }
        val takeImages = imageUris.take(room.coerceAtLeast(0))
        val takeVideos = videoUris.take(videoRoom.coerceAtLeast(0))
        lifecycleScope.launch {
            // Decode + downscale (photos) / copy + thumbnail (videos) off the
            // main thread.
            val stored = withContext(Dispatchers.IO) {
                takeImages.mapNotNull { ImageStore.ingest(this@MainActivity, it) }
            }
            val videos = withContext(Dispatchers.IO) {
                takeVideos.map { VideoStore.ingest(this@MainActivity, it) }
            }
            videos.firstNotNullOfOrNull { it.error }?.let {
                Toast.makeText(this@MainActivity, it, Toast.LENGTH_LONG).show()
            }
            pendingImages.addAll(stored)
            pendingVideos.addAll(videos.mapNotNull { it.stored })
            renderAttachments()
        }
    }

    // Live progress feed (SSE) for an in-flight main-screen agent turn. Mirrors
    // the thread screens: while the assistant works, show its fine-grained steps
    // ("Searching the web", "Updating your lists") instead of a flat "Thinking…".
    private var progressSource: EventSource? = null
    private var liveLabel: String? = null

    private var recordingStartMs = 0L
    private val timerRunnable = object : Runnable {
        override fun run() {
            val elapsed = (System.currentTimeMillis() - recordingStartMs) / 1000
            binding.tvDuration.text = "%d:%02d".format(elapsed / 60, elapsed % 60)
            handler.postDelayed(this, 500)
        }
    }

    private var recordingService: RecordingService? = null
    // Tracks whether bindService actually succeeded. Under press-and-hold the
    // stop path could only run while recording, so an unguarded unbindService
    // was safe; with tap-to-toggle any double-stop would throw
    // IllegalArgumentException: Service not registered.
    private var serviceBound = false
    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            recordingService = (binder as RecordingService.RecordingBinder).getService()
        }
        override fun onServiceDisconnected(name: ComponentName) { recordingService = null }
    }

    /** Safety net for tap-to-record: you can walk away mid-recording, which
     *  was impossible while holding the button. */
    private val autoStopRunnable = Runnable {
        if (binding.btnRecord.state == RecordButton.State.RECORDING) {
            Toast.makeText(this, "Auto-stopped after ${prefs.autoStopMinutes} min", Toast.LENGTH_LONG).show()
            stopRecordingAndUpload()
        }
    }

    private val requestPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) startRecording()
        else Toast.makeText(this, "Microphone permission required", Toast.LENGTH_LONG).show()
    }

    private val requestNotificationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* best-effort — notifications silently no-op if denied */ }

    private val requestFineLocation = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            // FINE granted — attempt to layer BACKGROUND on top, then register either way.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
                ContextCompat.checkSelfPermission(
                    this, Manifest.permission.ACCESS_BACKGROUND_LOCATION
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                requestBackgroundLocation.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
            } else {
                GeofenceRegistrar.register(this)
            }
        }
    }

    private val requestBackgroundLocation = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* register regardless — foreground triggers still work without BACKGROUND */
        GeofenceRegistrar.register(this)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        prefs = Prefs(this)

        maybeRequestNotificationPermission()
        maybeRequestLocationPermissions()
        enqueueGatewayPoll()

        listViewModel.allListsWithItems.observe(this) { lists ->
            val hasLists = lists.isNotEmpty()
            binding.btnViewLists.visibility = if (hasLists) View.VISIBLE else View.GONE
            if (hasLists) binding.tvListsMeta.text = lists.size.toString()
        }

        binding.btnViewLists.setOnClickListener {
            startActivity(Intent(this, ListsActivity::class.java))
        }

        goalViewModel.allGoalsWithDetails.observe(this) { goals ->
            val hasGoals = goals.isNotEmpty()
            binding.btnViewGoals.visibility = if (hasGoals) View.VISIBLE else View.GONE
            if (hasGoals) binding.tvGoalsMeta.text = goals.size.toString()
        }

        binding.btnViewGoals.setOnClickListener {
            startActivity(Intent(this, GoalsActivity::class.java))
        }

        // Assistant row meta: "● N new" in the unread orange, else the thread count.
        var assistantTotal = 0
        var assistantUnread = 0
        fun bindAssistantMeta() {
            val meta = binding.tvAssistantMeta
            if (assistantUnread > 0) {
                meta.text = "● $assistantUnread new"
                meta.setTextColor(0xFFFF9500.toInt())
            } else {
                meta.text = assistantTotal.toString()
                meta.setTextColor(0xFF666666.toInt())
            }
        }
        assistantViewModel.totalThreads.observe(this) { total ->
            binding.btnViewAssistant.visibility = if (total > 0) View.VISIBLE else View.GONE
            assistantTotal = total
            bindAssistantMeta()
        }
        assistantViewModel.openUnreadCount.observe(this) { unread ->
            assistantUnread = unread
            bindAssistantMeta()
        }

        binding.btnViewAssistant.setOnClickListener {
            startActivity(Intent(this, AssistantActivity::class.java))
        }

        // Schedules pill — always visible so there's an entry point to create
        // the first schedule (schedules live server-side, not in Room, so no
        // count observer).
        binding.btnViewSchedules.setOnClickListener {
            startActivity(Intent(this, SchedulesActivity::class.java))
        }

        // Artifacts pill — count-gated like the other library pills. Artifacts
        // are only ever produced by the assistant, so hiding it when empty
        // never strands the user.
        NoteDatabase.get(applicationContext).artifactDao().allLive().observe(this) { artifacts ->
            val hasArtifacts = !artifacts.isNullOrEmpty()
            binding.btnViewArtifacts.visibility = if (hasArtifacts) View.VISIBLE else View.GONE
            if (hasArtifacts) binding.tvArtifactsMeta.text = artifacts.size.toString()
        }

        binding.btnViewArtifacts.setOnClickListener {
            startActivity(Intent(this, ArtifactsActivity::class.java))
        }

        binding.btnAttach.setOnClickListener {
            if (pendingImages.size >= ImageStore.MAX_ATTACHMENTS &&
                pendingVideos.size >= VideoStore.MAX_PER_NOTE
            ) {
                Toast.makeText(this, "Up to ${ImageStore.MAX_ATTACHMENTS} photos and one video", Toast.LENGTH_SHORT).show()
            } else {
                // Photo Picker (photos + videos) — no storage permission required.
                pickImages.launch(
                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo)
                )
            }
        }

        binding.btnSettings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        binding.btnSettings.setOnLongClickListener {
            WorkManager.getInstance(this).enqueue(
                OneTimeWorkRequestBuilder<PollWorker>()
                    .setConstraints(
                        Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
                    )
                    .build()
            )
            Toast.makeText(this, "Polling gateway now…", Toast.LENGTH_SHORT).show()
            true
        }

        // Action / No-action toggle — sticky, default Action. "No action"
        // saves the note without bouncing to the LLM.
        binding.toggleMode.check(
            if (prefs.actionMode) R.id.btnActionMode else R.id.btnNoActionMode
        )
        binding.toggleMode.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (isChecked) prefs.actionMode = (checkedId == R.id.btnActionMode)
        }

        binding.btnRecord.onPressStart = {
            if (prefs.endpointUrl.isBlank()) {
                setStatus(getString(R.string.status_no_endpoint), R.color.recording_ring)
            } else if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED
            ) {
                startRecording()
            } else {
                requestPermission.launch(Manifest.permission.RECORD_AUDIO)
            }
        }

        binding.btnRecord.onPressEnd = { stopRecordingAndUpload() }
    }

    private fun startRecording() {
        val serviceIntent = Intent(this, RecordingService::class.java)
        startForegroundService(serviceIntent)
        if (bindService(serviceIntent, serviceConnection, Context.BIND_AUTO_CREATE)) {
            serviceBound = true
        }

        // On-device dictation when the user opted in and the device supports it;
        // otherwise (or on failure) fall back to recording audio for the endpoint.
        dictating = prefs.onDeviceTranscription && OnDeviceTranscriber.isAvailable(this)
        var started = false
        if (dictating) {
            val t = OnDeviceTranscriber(this)
            val ok = t.start(
                onAmplitude = { amp -> runOnUiThread { binding.waveformView.pushAmplitude(amp) } },
                onError = { msg -> Log.w("MainActivity", "on-device dictation: $msg") },
            )
            if (ok) {
                transcriber = t
                started = true
            } else {
                t.cancel()
                dictating = false   // fall through to endpoint recording
            }
        }
        if (!dictating) {
            started = recorder.start { amplitude ->
                runOnUiThread { binding.waveformView.pushAmplitude(amplitude) }
            } != null
        }

        if (started) {
            // Only now does the button latch into RECORDING — a failed start
            // leaves it IDLE and tappable rather than stuck.
            binding.btnRecord.markRecordingStarted()
            binding.waveformView.startRecording()
            binding.tvDuration.visibility = View.VISIBLE
            recordingStartMs = System.currentTimeMillis()
            handler.post(timerRunnable)
            handler.postDelayed(
                autoStopRunnable,
                prefs.autoStopMinutes.coerceIn(1, 60) * 60_000L,
            )
            setStatus(getString(R.string.status_recording), R.color.recording_ring)
        } else {
            // Recorder refused to start: tear the service back down so we
            // don't leave a mic notification hanging.
            releaseService()
            setStatus("Could not start recording", R.color.recording_ring)
        }
    }

    private fun releaseService() {
        if (serviceBound) {
            unbindService(serviceConnection)
            serviceBound = false
        }
        stopService(Intent(this, RecordingService::class.java))
    }

    private fun stopRecordingAndUpload() {
        handler.removeCallbacks(timerRunnable)
        handler.removeCallbacks(autoStopRunnable)
        val durationSeconds = ((System.currentTimeMillis() - recordingStartMs) / 1000).toInt()
        binding.tvDuration.visibility = View.INVISIBLE
        binding.waveformView.stopRecording()
        binding.btnRecord.markRecordingStopped()

        if (dictating) {
            val t = transcriber
            transcriber = null
            dictating = false
            releaseService()
            val transcript = t?.stop().orEmpty()
            if (transcript.isBlank()) {
                binding.btnRecord.state = RecordButton.State.IDLE
                setStatus("Didn't catch that — try again", R.color.text_secondary)
                return
            }
            enqueueDictation(transcript, durationSeconds)
            return
        }

        val file = recorder.stop()
        releaseService()

        if (file == null) {
            binding.btnRecord.state = RecordButton.State.IDLE
            setStatus("Too short — try again", R.color.text_secondary)
            return
        }

        enqueueUpload(file, durationSeconds)
    }

    private fun enqueueUpload(file: File, durationSeconds: Int) {
        enqueueTurn(
            UploadWorker.buildRequest(
                prefs, file, durationSeconds, imagePaths = takeStagedImages(),
                actionMode = prefs.actionMode, videos = takeStagedVideos(),
            )
        )
    }

    /** On-device dictation already has the transcript — no audio to upload. */
    private fun enqueueDictation(transcript: String, durationSeconds: Int) {
        enqueueTurn(
            UploadWorker.buildRequest(
                prefs, file = null, durationSeconds = durationSeconds,
                imagePaths = takeStagedImages(), actionMode = prefs.actionMode,
                transcriptOverride = transcript, videos = takeStagedVideos(),
            )
        )
    }

    /** Hand off the staged attachments and clear the strip — the worker owns the
     *  files from here (it persists NoteImageEntity rows once it has a noteId). */
    private fun takeStagedImages(): Array<String> {
        val imagePaths = pendingImages.map { it.path }.toTypedArray()
        pendingImages.clear()
        renderAttachments()
        return imagePaths
    }

    private fun takeStagedVideos(): Array<String> {
        val videos = pendingVideos.map { it.toJson() }.toTypedArray()
        pendingVideos.clear()
        renderAttachments()
        return videos
    }

    private fun enqueueTurn(uploadRequest: androidx.work.OneTimeWorkRequest) {
        binding.btnRecord.state = RecordButton.State.UPLOADING
        setStatus(getString(R.string.status_uploading), R.color.uploading_ring)

        WorkManager.getInstance(this).enqueue(uploadRequest)

        WorkManager.getInstance(this)
            .getWorkInfoByIdLiveData(uploadRequest.id)
            .observe(this) { workInfo ->
                when (workInfo?.state) {
                    WorkInfo.State.RUNNING -> {
                        // Tail the gateway's live progress feed while the turn
                        // runs, so the main screen shows the same fine-grained
                        // steps the thread screens do. The SSE label takes
                        // precedence over the coarse stage ("Transcribing…" /
                        // "Thinking…") whenever one has arrived.
                        openProgressStream()
                        val stage = workInfo.progress.getString(UploadWorker.KEY_STAGE)
                        val label = liveLabel ?: stage?.let { UploadWorker.stageLabel(it) }
                        if (label != null) setStatus(label, R.color.uploading_ring)
                    }
                    WorkInfo.State.SUCCEEDED -> {
                        closeProgressStream()
                        binding.btnRecord.state = RecordButton.State.SUCCESS
                        setStatus(getString(R.string.status_success), R.color.success_ring)
                        val agentReply = workInfo.outputData.getString(UploadWorker.KEY_AGENT_REPLY)
                        if (!agentReply.isNullOrBlank()) {
                            Toast.makeText(this, "test_model: $agentReply", Toast.LENGTH_LONG).show()
                        }
                        handler.postDelayed({
                            binding.btnRecord.state = RecordButton.State.IDLE
                            setStatus(getString(R.string.status_ready), R.color.text_secondary)
                        }, 2000)
                    }
                    WorkInfo.State.FAILED -> {
                        closeProgressStream()
                        binding.btnRecord.state = RecordButton.State.ERROR
                        setStatus(getString(R.string.status_error), R.color.recording_ring)
                        handler.postDelayed({
                            binding.btnRecord.state = RecordButton.State.IDLE
                            setStatus(getString(R.string.status_ready), R.color.text_secondary)
                        }, 3000)
                    }
                    else -> {}
                }
            }
    }

    private fun setStatus(text: String, colorRes: Int) {
        binding.tvStatus.text = text
        binding.tvStatus.setTextColor(ContextCompat.getColor(this, colorRes))
    }

    /** Open the per-session progress SSE (idempotent). Events arrive off the
     *  main thread, so hop back before touching UI. */
    private fun openProgressStream() {
        if (progressSource != null) return
        progressSource = ProgressStream.open(
            prefs.gatewayBaseUrl, prefs.gatewayApiKey, GatewayAgent.SESSION_ID,
        ) { event -> runOnUiThread { onProgressEvent(event) } }
    }

    private fun closeProgressStream() {
        progressSource?.cancel()
        progressSource = null
        liveLabel = null
    }

    /** Same event shape the thread screens consume. The main screen has no step
     *  log — it just surfaces the latest goal-relative label on the status line. */
    private fun onProgressEvent(event: JSONObject) {
        val label = event.optString("label").takeIf { it.isNotBlank() }
        when (event.optString("phase")) {
            "done" -> liveLabel = null                       // momentarily clear
            else -> if (label != null) liveLabel = label     // start/skill/tool/plan/continue
        }
        liveLabel?.let { setStatus(it, R.color.uploading_ring) }
    }

    /** Opening the app drains any replies already waiting on the gateway. */
    override fun onStart() {
        super.onStart()
        if (prefs.gatewayBaseUrl.isNotBlank()) PollWorker.enqueueOneShot(this)
    }

    override fun onDestroy() {
        super.onDestroy()
        closeProgressStream()
        handler.removeCallbacksAndMessages(null)
        recorder.release()
        transcriber?.cancel()
        transcriber = null
    }

    private fun maybeRequestNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            requestNotificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun renderAttachments() {
        binding.attachRow.removeAllViews()
        val empty = pendingImages.isEmpty() && pendingVideos.isEmpty()
        binding.attachStrip.visibility = if (empty) View.GONE else View.VISIBLE
        val inflater = LayoutInflater.from(this)
        for (vid in pendingVideos.toList()) {
            val row = ItemAttachmentBinding.inflate(inflater, binding.attachRow, false)
            vid.thumbPath?.let { row.ivThumb.setImageURI(android.net.Uri.fromFile(File(it))) }
            row.tvBadge.visibility = View.VISIBLE
            row.tvBadge.text = "▶ ${VideoStore.formatDuration(vid.durationMs)}"
            row.ivThumb.contentDescription = "Attached video, ${VideoStore.formatDuration(vid.durationMs)}"
            row.btnRemove.contentDescription = "Remove video"
            row.btnRemove.setOnClickListener {
                pendingVideos.remove(vid)
                VideoStore.delete(vid)
                renderAttachments()
            }
            binding.attachRow.addView(row.root)
        }
        for (img in pendingImages.toList()) {
            val row = ItemAttachmentBinding.inflate(inflater, binding.attachRow, false)
            row.ivThumb.setImageURI(android.net.Uri.fromFile(File(img.path)))
            row.btnRemove.setOnClickListener {
                pendingImages.remove(img)
                ImageStore.delete(img.path)
                renderAttachments()
            }
            binding.attachRow.addView(row.root)
        }
        binding.btnAttach.setColorFilter(
            ContextCompat.getColor(this, if (empty) R.color.text_secondary else R.color.accent)
        )
    }

    private fun enqueueGatewayPoll() {
        val request = PeriodicWorkRequestBuilder<PollWorker>(15, TimeUnit.MINUTES)
            .setConstraints(
                Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
            )
            .build()
        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            PollWorker.UNIQUE_WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            request,
        )
    }

    private fun maybeRequestLocationPermissions() {
        if (Stores.ALL.isEmpty()) return
        val fineGranted = ContextCompat.checkSelfPermission(
            this, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        if (!fineGranted) {
            requestFineLocation.launch(Manifest.permission.ACCESS_FINE_LOCATION)
            return
        }

        // FINE already granted — bring BACKGROUND up to date on API 29+.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
            ContextCompat.checkSelfPermission(
                this, Manifest.permission.ACCESS_BACKGROUND_LOCATION
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            requestBackgroundLocation.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        } else {
            GeofenceRegistrar.register(this)
        }
    }
}
