package com.voicenote.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
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
import java.io.File
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.voicenote.app.databinding.ActivityThreadDetailBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.sse.EventSource
import org.json.JSONObject

/**
 * One conversation thread. Bottom-bar mic records a reply into this
 * thread (reopening it if resolved); the Resolve button archives it.
 */
class ThreadDetailActivity : AppCompatActivity() {

    private lateinit var binding: ActivityThreadDetailBinding
    private val viewModel: ThreadDetailViewModel by viewModels()
    private lateinit var voiceCapture: VoiceCapture
    private val adapter = ThreadDetailAdapter(
        onResend = { note -> resendNote(note) },
        onApprove = { id -> decideApproval(id, true) },
        onDeny = { id -> decideApproval(id, false) },
        onOpenArtifact = { art -> ArtifactReaderActivity.open(this, art.id) },
        onResume = { r ->
            ResumeWorker.enqueue(this, r.runId, threadId.takeIf { it >= 0 }, currentThread?.title ?: "assistant")
            Toast.makeText(this, "Resuming research…", Toast.LENGTH_SHORT).show()
        },
        onScheduleDecision = { row, approve -> decideSchedule(row, approve) },
        onWikiDecision = { row, approve -> decideWiki(row, approve) },
    )

    private var threadId: Long = -1L
    private var currentThread: ThreadEntity? = null
    private var currentGoalId: Long? = null
    private var currentListId: Long? = null

    // Conversation rows and the in-flight turn are observed separately, then
    // combined so the "working" bubble always renders last.
    private var convoRows: List<ConvoRow> = emptyList()
    private var pendingStage: String? = null

    // Live progress feed state (SSE) for the in-flight turn.
    private var liveLabel: String? = null
    private val liveSteps = mutableListOf<String>()
    private var progressSource: EventSource? = null

    // Sandbox approval (manual mode): a held command shown as an in-thread card.
    private var approvalRow: ConvoRow.Approval? = null
    private var approvalPollJob: Job? = null

    private val requestMicPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) startRecording()
        else Toast.makeText(this, "Microphone permission required", Toast.LENGTH_LONG).show()
    }

    // Photos staged for the next reply. Same contract as the main screen: the
    // note row doesn't exist until the transcript comes back, so attachments
    // live here and are handed to UploadWorker as paths on send.
    private val pendingImages = mutableListOf<ImageStore.Stored>()
    private val pendingVideos = mutableListOf<VideoStore.Stored>()

    private val pickImages = registerForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(ImageStore.MAX_ATTACHMENTS + VideoStore.MAX_PER_NOTE)
    ) { uris ->
        if (uris.isEmpty()) return@registerForActivityResult
        val (videoUris, imageUris) = uris.partition { VideoStore.isVideo(this, it) }
        val room = ImageStore.MAX_ATTACHMENTS - pendingImages.size
        if (imageUris.isNotEmpty() && room <= 0) {
            Toast.makeText(this, "Up to ${ImageStore.MAX_ATTACHMENTS} photos", Toast.LENGTH_SHORT).show()
        }
        val videoRoom = VideoStore.MAX_PER_NOTE - pendingVideos.size
        if (videoUris.size > videoRoom) {
            Toast.makeText(this, "One video per reply", Toast.LENGTH_SHORT).show()
        }
        lifecycleScope.launch {
            // Downscale / copy off the main thread — full-size media would jank the UI.
            val stored = withContext(Dispatchers.IO) {
                imageUris.take(room.coerceAtLeast(0)).mapNotNull { ImageStore.ingest(this@ThreadDetailActivity, it) }
            }
            val videos = withContext(Dispatchers.IO) {
                videoUris.take(videoRoom.coerceAtLeast(0)).map { VideoStore.ingest(this@ThreadDetailActivity, it) }
            }
            videos.firstNotNullOfOrNull { it.error }?.let {
                Toast.makeText(this@ThreadDetailActivity, it, Toast.LENGTH_LONG).show()
            }
            pendingImages.addAll(stored)
            pendingVideos.addAll(videos.mapNotNull { it.stored })
            renderAttachments()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityThreadDetailBinding.inflate(layoutInflater)
        setContentView(binding.root)
        voiceCapture = VoiceCapture(this)

        threadId = intent.getLongExtra(EXTRA_THREAD_ID, -1L)
        if (threadId < 0) {
            finish()
            return
        }

        binding.btnBack.setOnClickListener { finish() }

        binding.rvConvo.layoutManager = LinearLayoutManager(this).apply { stackFromEnd = true }
        binding.rvConvo.adapter = adapter

        viewModel.thread(threadId).observe(this) { row ->
            if (row == null) {
                finish()
                return@observe
            }
            currentThread = row.thread
            currentGoalId = row.thread.goalId
            currentListId = row.thread.listId
            binding.tvThreadTitle.text = row.thread.title
            // The pill shows whichever scope the thread has and deep-links to
            // that list or goal.
            if (row.goalTitle != null) {
                binding.tvGoalPill.visibility = View.VISIBLE
                binding.tvGoalPill.text = row.goalTitle
                binding.tvGoalPill.setOnClickListener {
                    row.thread.goalId?.let { gid ->
                        startActivity(
                            Intent(this, GoalDetailActivity::class.java)
                                .putExtra(GoalDetailActivity.EXTRA_GOAL_ID, gid)
                        )
                    }
                }
            } else if (row.listName != null) {
                binding.tvGoalPill.visibility = View.VISIBLE
                binding.tvGoalPill.text = row.listName
                binding.tvGoalPill.setOnClickListener {
                    row.thread.listId?.let { lid ->
                        startActivity(
                            Intent(this, ListDetailActivity::class.java)
                                .putExtra(ListDetailActivity.EXTRA_LIST_ID, lid)
                        )
                    }
                }
            } else {
                binding.tvGoalPill.visibility = View.GONE
            }
            val resolved = row.thread.status == "resolved"
            binding.btnResolve.visibility = if (resolved) View.GONE else View.VISIBLE
            binding.tvMicHint.text = if (resolved) "tap to reply (reopens)" else "tap to reply"
        }

        viewModel.convo(threadId).observe(this) { rows ->
            convoRows = rows
            submitConvo()
        }

        // Live turn feedback: while an upload/agent turn tagged for this thread
        // is in flight, show the animated bubble with its current stage.
        WorkManager.getInstance(this)
            .getWorkInfosByTagLiveData(UploadWorker.threadTag(threadId))
            .observe(this) { infos ->
                val live = infos?.firstOrNull {
                    it.state == WorkInfo.State.RUNNING || it.state == WorkInfo.State.ENQUEUED
                }
                pendingStage = when {
                    live == null -> null
                    else -> live.progress.getString(UploadWorker.KEY_STAGE)
                        ?: UploadWorker.STAGE_TRANSCRIBING
                }
                // Tail the gateway's live progress feed only while a turn runs,
                // and poll for sandbox commands held for approval.
                if (live != null) {
                    openProgressStream(); startApprovalPolling()
                } else {
                    closeProgressStream(); stopApprovalPolling()
                }
                submitConvo()
            }

        binding.btnResolve.setOnClickListener {
            viewModel.resolve(threadId)
            Toast.makeText(this, "Resolved", Toast.LENGTH_SHORT).show()
        }

        binding.btnDeleteThread.setOnClickListener {
            androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("Delete thread?")
                .setMessage("Removes this conversation and the assistant's replies. Your voice notes are kept.")
                .setPositiveButton("Delete") { _, _ ->
                    viewModel.delete(threadId)
                    Toast.makeText(this, "Thread deleted", Toast.LENGTH_SHORT).show()
                    finish()
                }
                .setNegativeButton("Cancel", null)
                .show()
        }

        binding.btnReplyMic.setOnClickListener {
            if (voiceCapture.isRecording) {
                stopRecording()
            } else if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED
            ) {
                startRecording()
            } else {
                requestMicPermission.launch(Manifest.permission.RECORD_AUDIO)
            }
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

        viewModel.markRead(threadId)
    }

    private fun renderAttachments() {
        binding.attachRow.removeAllViews()
        val empty = pendingImages.isEmpty() && pendingVideos.isEmpty()
        binding.attachStrip.visibility = if (empty) View.GONE else View.VISIBLE
        val inflater = LayoutInflater.from(this)
        for (vid in pendingVideos.toList()) {
            val row = ItemAttachmentBinding.inflate(inflater, binding.attachRow, false)
            vid.thumbPath?.let { row.ivThumb.setImageURI(Uri.fromFile(File(it))) }
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
            row.ivThumb.setImageURI(Uri.fromFile(File(img.path)))
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

    /** Hand the staged paths to the worker and clear the strip — the worker
     *  owns the files from here. */
    private fun takePendingImagePaths(): Array<String> {
        val paths = pendingImages.map { it.path }.toTypedArray()
        pendingImages.clear()
        renderAttachments()
        return paths
    }

    private fun takePendingVideos(): Array<String> {
        val videos = pendingVideos.map { it.toJson() }.toTypedArray()
        pendingVideos.clear()
        renderAttachments()
        return videos
    }

    private fun resendNote(note: NoteEntity) {
        val prefs = Prefs(this)
        if (prefs.gatewayBaseUrl.isBlank()) {
            Toast.makeText(this, "Set a gateway URL in Settings first", Toast.LENGTH_SHORT).show()
            return
        }
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setMessage("Resend this message to the assistant?")
            .setPositiveButton("Resend") { _, _ ->
                WorkManager.getInstance(this).enqueue(ResendWorker.buildRequest(prefs, note))
                Toast.makeText(this, "Resending…", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun submitConvo() {
        val stage = pendingStage
        val rows = buildList {
            addAll(convoRows)
            approvalRow?.let { add(it) }
            if (stage != null) add(ConvoRow.Pending(stage, liveLabel, liveSteps.toList()))
        }
        adapter.submitList(rows) {
            if (rows.isNotEmpty()) binding.rvConvo.scrollToPosition(rows.size - 1)
        }
    }

    /** Poll the gateway for a held sandbox command while a turn runs; show it as
     *  an in-thread Approve/Deny card. (The worker also posts a notification.) */
    private fun startApprovalPolling() {
        if (approvalPollJob?.isActive == true) return
        val prefs = Prefs(this)
        approvalPollJob = lifecycleScope.launch(Dispatchers.IO) {
            while (isActive) {
                val first = GatewayAgent
                    .listPendingApprovals(prefs.gatewayBaseUrl, prefs.gatewayApiKey)
                    .firstOrNull()
                withContext(Dispatchers.Main) {
                    val newRow = first?.let { ConvoRow.Approval(it.id, it.command, it.reason) }
                    if (newRow?.id != approvalRow?.id) {
                        approvalRow = newRow
                        submitConvo()
                    }
                }
                delay(1500)
            }
        }
    }

    private fun stopApprovalPolling() {
        approvalPollJob?.cancel()
        approvalPollJob = null
        if (approvalRow != null) {
            approvalRow = null
            submitConvo()
        }
    }

    private fun decideApproval(id: String, approve: Boolean) {
        // Optimistic: clear the card immediately; the daemon resolves server-side.
        approvalRow = null
        submitConvo()
        val prefs = Prefs(this)
        lifecycleScope.launch(Dispatchers.IO) {
            GatewayAgent.decideApproval(prefs.gatewayBaseUrl, prefs.gatewayApiKey, id, approve)
        }
    }

    /** Approve or discard a drafted schedule. Approve writes it via the
     *  schedules CRUD; either way the proposal is consumed so the card clears. */
    private fun decideSchedule(row: ConvoRow.ScheduleProposal, approve: Boolean) {
        val p = row.proposal
        val prefs = Prefs(this)
        if (!approve) {
            Toast.makeText(this, "Discarded", Toast.LENGTH_SHORT).show()
            consumeScheduleProposal(row.messageId, p.id)
            return
        }
        lifecycleScope.launch {
            val deviceTz = java.util.TimeZone.getDefault().id
            val tz = if (p.tzMode == "device") deviceTz else p.timezone.ifBlank { deviceTz }
            val info = GatewayAgent.ScheduleInfo(
                id = if (p.action == "update") p.scheduleId else "",
                cron = p.cron, model = p.agent, prompt = p.prompt,
                notificationTitle = p.title, enabled = p.enabled,
                timezone = tz, tzMode = p.tzMode, nextRunTime = null,
            )
            var err: String? = null
            val saved = withContext(Dispatchers.IO) {
                GatewayAgent.saveSchedule(prefs.gatewayBaseUrl, prefs.gatewayApiKey, info) { err = it }
            }
            if (saved != null) {
                Toast.makeText(
                    this@ThreadDetailActivity,
                    if (p.action == "update") "Schedule updated" else "Schedule created",
                    Toast.LENGTH_SHORT,
                ).show()
                consumeScheduleProposal(row.messageId, p.id)
            } else {
                Toast.makeText(this@ThreadDetailActivity, err ?: "Couldn't save schedule", Toast.LENGTH_LONG).show()
            }
        }
    }

    /** Remove one acted-on proposal from its message; clears the column when none
     *  remain, which removes the card. */
    private fun consumeScheduleProposal(messageId: Long, proposalId: String) {
        lifecycleScope.launch(Dispatchers.IO) {
            val dao = NoteDatabase.get(this@ThreadDetailActivity).agentMessageDao()
            val json = dao.scheduleProposalJson(messageId) ?: return@launch
            val remaining = org.json.JSONArray()
            try {
                val arr = org.json.JSONArray(json)
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    if (o.optString("id") != proposalId) remaining.put(o)
                }
            } catch (e: Exception) { /* fall through -> clear */ }
            dao.setScheduleProposalJson(messageId, if (remaining.length() == 0) null else remaining.toString())
        }
    }

    /** Approve or discard a drafted wiki entry. Approve activates it via the wiki
     *  API; discard rejects the server-side draft. Either way the card clears. */
    private fun decideWiki(row: ConvoRow.WikiProposal, approve: Boolean) {
        val p = row.proposal
        val prefs = Prefs(this)
        lifecycleScope.launch {
            if (!approve) {
                withContext(Dispatchers.IO) {
                    GatewayAgent.rejectWikiProposal(prefs.gatewayBaseUrl, prefs.gatewayApiKey, p.proposalId)
                }
                Toast.makeText(this@ThreadDetailActivity, "Discarded", Toast.LENGTH_SHORT).show()
                consumeWikiProposal(row.messageId, p.proposalId)
                return@launch
            }
            val ok = withContext(Dispatchers.IO) {
                GatewayAgent.approveWikiProposal(prefs.gatewayBaseUrl, prefs.gatewayApiKey, p.proposalId)
            }
            if (ok) {
                Toast.makeText(
                    this@ThreadDetailActivity,
                    if (p.op == "amend") "Wiki entry updated" else "Saved to wiki",
                    Toast.LENGTH_SHORT,
                ).show()
                consumeWikiProposal(row.messageId, p.proposalId)
            } else {
                Toast.makeText(this@ThreadDetailActivity, "Couldn't save to wiki", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun consumeWikiProposal(messageId: Long, proposalId: String) {
        lifecycleScope.launch(Dispatchers.IO) {
            val dao = NoteDatabase.get(this@ThreadDetailActivity).agentMessageDao()
            val json = dao.wikiProposalJson(messageId) ?: return@launch
            val remaining = org.json.JSONArray()
            try {
                val arr = org.json.JSONArray(json)
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    if (o.optString("proposal_id") != proposalId) remaining.put(o)
                }
            } catch (e: Exception) { /* fall through -> clear */ }
            dao.setWikiProposalJson(messageId, if (remaining.length() == 0) null else remaining.toString())
        }
    }

    /** Open the per-session progress SSE (idempotent). Events arrive off the
     *  main thread, so we hop back before touching UI state. */
    private fun openProgressStream() {
        if (progressSource != null) return
        val prefs = Prefs(this)
        progressSource = ProgressStream.open(
            prefs.gatewayBaseUrl, prefs.gatewayApiKey, GatewayAgent.SESSION_ID,
        ) { event -> runOnUiThread { onProgressEvent(event) } }
    }

    private fun closeProgressStream() {
        progressSource?.cancel()
        progressSource = null
        if (liveLabel != null || liveSteps.isNotEmpty()) {
            liveLabel = null
            liveSteps.clear()
        }
    }

    private fun onProgressEvent(event: JSONObject) {
        val label = event.optString("label").takeIf { it.isNotBlank() }
        when (event.optString("phase")) {
            "skill", "tool" -> if (label != null) {
                liveLabel = label
                liveSteps.add(label)
            }
            "done" -> liveLabel = null     // worker clears the row momentarily
            else -> if (label != null) liveLabel = label   // start / thinking
        }
        submitConvo()
    }

    private fun startRecording() {
        if (voiceCapture.start()) {
            binding.btnReplyMic.setColorFilter(ContextCompat.getColor(this, R.color.accent))
            binding.tvMicHint.text = "recording — tap to send"
        } else {
            Toast.makeText(this, "Could not start recording", Toast.LENGTH_SHORT).show()
        }
    }

    private fun stopRecording() {
        binding.btnReplyMic.setColorFilter(ContextCompat.getColor(this, R.color.text_secondary))
        binding.tvMicHint.text = "tap to reply"
        // Reply targets the thread; also mark it as answering the latest
        // agent message so the Notes screen threading stays informative.
        lifecycleScope.launch {
            val lastMsgId = withContext(Dispatchers.IO) {
                NoteDatabase.get(this@ThreadDetailActivity).threadDao().lastMessageId(threadId)
            }
            val uploaded = voiceCapture.stopAndUpload(
                goalId = currentGoalId ?: -1L,
                replyToMessageId = lastMsgId ?: -1L,
                threadId = threadId,
                listId = currentListId ?: -1L,
                imagePaths = takePendingImagePaths(),
                videos = takePendingVideos(),
            )
            Toast.makeText(
                this@ThreadDetailActivity,
                if (uploaded) "Uploading reply…" else "Too short — try again",
                Toast.LENGTH_SHORT,
            ).show()
        }
    }

    /** Opening a thread drains any replies already waiting on the gateway. */
    override fun onStart() {
        super.onStart()
        if (Prefs(this).gatewayBaseUrl.isNotBlank()) PollWorker.enqueueOneShot(this)
    }

    override fun onDestroy() {
        super.onDestroy()
        closeProgressStream()
        voiceCapture.cancel(this)
    }

    companion object {
        const val EXTRA_THREAD_ID = "thread_id"
    }
}
