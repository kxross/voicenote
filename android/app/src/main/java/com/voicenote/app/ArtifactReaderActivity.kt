package com.voicenote.app

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Bundle
import android.view.HapticFeedbackConstants
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.voicenote.app.databinding.ActivityArtifactReaderBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Full-screen reader for a published markdown artifact, rendered in the
 *  sandboxed artifact WebView. The top bar shares the document (PDF / web page
 *  / text — readable without the app) or deletes it.
 *
 *  **Ask for changes:** the bottom mic records a voice request about THIS
 *  document. It runs through the normal transcribe → assistant path, tagged
 *  with the document's id, and the assistant revises it in place. The reader
 *  observes the row, so the revision lands right here with what changed
 *  highlighted; if the assistant answered instead of editing (a question back),
 *  the bar points to the thread.
 *
 *  **Versions:** a revised document keeps its previous version. The version
 *  bar toggles the change highlights and flips between the two versions. */
class ArtifactReaderActivity : AppCompatActivity() {

    private lateinit var binding: ActivityArtifactReaderBinding
    private lateinit var voiceCapture: VoiceCapture
    private var artifactId: String = ""
    /** The version currently on screen. */
    private var artifact: ArtifactEntity? = null
    private var isDark = true
    private var changesShown = false
    /** Set while the user flips versions, so the resulting row update isn't
     *  mistaken for a fresh revision. */
    private var swapping = false

    private enum class Ask { IDLE, RECORDING, WORKING }
    private var ask = Ask.IDLE
    /** The document's revision when the user's request went out; a higher
     *  revision arriving means the request was carried out. */
    private var askedAtRevision: Int? = null

    private val requestMicPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) startAsk()
        else Toast.makeText(this, "Microphone permission required", Toast.LENGTH_LONG).show()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityArtifactReaderBinding.inflate(layoutInflater)
        setContentView(binding.root)
        voiceCapture = VoiceCapture(this)
        binding.btnBack.setOnClickListener { finish() }
        binding.btnShare.setOnClickListener { showShareOptions() }
        binding.btnDelete.setOnClickListener { confirmDelete() }
        binding.btnChanges.setOnClickListener { toggleChanges() }
        binding.btnSwapVersion.setOnClickListener { swapVersion() }
        binding.btnAskMic.setOnClickListener { onMic() }

        artifactId = intent.getStringExtra(EXTRA_ID).orEmpty()
        if (artifactId.isBlank()) { finish(); return }
        askedAtRevision = savedInstanceState?.getInt(STATE_ASKED_AT, -1)?.takeIf { it >= 0 }

        isDark = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) !=
            Configuration.UI_MODE_NIGHT_NO

        val dao = NoteDatabase.get(applicationContext).artifactDao()
        val offerShare = savedInstanceState == null && intent.getBooleanExtra(EXTRA_SHARE, false)
        dao.getLive(artifactId).observe(this) { art -> onArtifact(art, offerShare) }

        // Progress of a request spoken here (transcribing → revising).
        WorkManager.getInstance(this)
            .getWorkInfosByTagLiveData(UploadWorker.artifactTag(artifactId))
            .observe(this) { infos -> onWork(infos.orEmpty()) }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        askedAtRevision?.let { outState.putInt(STATE_ASKED_AT, it) }
    }

    // ---- the document -------------------------------------------------------

    private fun hasNewerRevision(a: ArtifactEntity): Boolean {
        val other = a.previousRevision ?: return false
        return a.previousText != null && a.revision > other
    }

    private fun onArtifact(art: ArtifactEntity?, offerShare: Boolean) {
        val shown = artifact
        if (art == null) {
            if (shown == null) Toast.makeText(this, "Document not found", Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        when {
            shown == null -> {
                // Opening a revision you haven't seen yet: show what changed.
                changesShown = hasNewerRevision(art) && art.readAt == null
                show(art, focus = changesShown)
                markRead()
                // Opened from the library's "Share…": offer the formats once the
                // document is on screen (the export snapshots the rendered page).
                if (offerShare) binding.readerWeb.postDelayed({ showShareOptions() }, 400)
            }
            shown.text != art.text || shown.title != art.title -> {
                val revisedNow = !swapping && hasNewerRevision(art) && art.revision > shown.revision
                swapping = false
                if (revisedNow) {
                    changesShown = true
                    show(art, focus = true)
                    onRevisionArrived()
                } else {
                    changesShown = changesShown && hasNewerRevision(art)
                    show(art, focus = false)
                }
                markRead()
            }
            else -> { artifact = art; bindVersion(art) }   // e.g. only readAt changed
        }
    }

    private fun show(art: ArtifactEntity, focus: Boolean) {
        artifact = art
        binding.tvTitle.text = art.title
        val newer = hasNewerRevision(art)
        ArtifactRenderer.render(
            web = binding.readerWeb,
            markdown = art.text.orEmpty(),
            isDark = isDark,
            scrollable = true,
            sourcesJson = art.sourcesJson,
            previousMarkdown = if (newer) art.previousText else null,
            focusChanges = focus && changesShown,
            showChanges = changesShown,
        )
        bindVersion(art)
    }

    private fun markRead() {
        lifecycleScope.launch(Dispatchers.IO) {
            NoteDatabase.get(applicationContext).artifactDao().markRead(artifactId)
        }
    }

    /** "v3 · revised   Show changes   View v2" — or, on the older version,
     *  "v2 · earlier version   Back to latest". */
    private fun bindVersion(art: ArtifactEntity) {
        val other = art.previousRevision
        if (art.previousText == null || other == null) {
            binding.versionBar.visibility = View.GONE
            return
        }
        binding.versionBar.visibility = View.VISIBLE
        val newer = art.revision > other
        binding.tvVersion.text =
            if (newer) "v${art.revision} · revised by the assistant" else "v${art.revision} · earlier version"
        binding.btnChanges.visibility = if (newer) View.VISIBLE else View.GONE
        binding.btnChanges.text = if (changesShown) "Hide changes" else "Show changes"
        binding.btnSwapVersion.text = if (newer) "View v$other" else "Back to latest"
    }

    private fun toggleChanges() {
        ArtifactRenderer.toggleChanges(binding.readerWeb) { shown ->
            changesShown = shown
            artifact?.let { bindVersion(it) }
        }
    }

    private fun swapVersion() {
        val cur = artifact ?: return
        if (cur.previousText == null) return
        swapping = true
        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                NoteDatabase.get(applicationContext).artifactDao().insert(cur.swapped())
            }
        }
    }

    // ---- ask for changes by voice --------------------------------------------

    private fun onMic() {
        when (ask) {
            Ask.RECORDING -> sendAsk()
            Ask.WORKING -> Toast.makeText(this, "Still working on your last request", Toast.LENGTH_SHORT).show()
            Ask.IDLE -> if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED
            ) startAsk() else requestMicPermission.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    private fun startAsk() {
        if (!voiceCapture.start()) {
            Toast.makeText(this, "Could not start recording", Toast.LENGTH_SHORT).show()
            return
        }
        ask = Ask.RECORDING
        binding.btnAskMic.setColorFilter(ContextCompat.getColor(this, R.color.accent))
        binding.btnAskMic.contentDescription = "Send your request"
        setHint("Listening… say what to change, then tap to send")
        binding.btnAskMic.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
    }

    private fun sendAsk() {
        val art = artifact ?: return
        binding.btnAskMic.clearColorFilter()
        binding.btnAskMic.contentDescription = "Ask for changes by voice"
        val sent = voiceCapture.stopAndUpload(
            threadId = art.threadId ?: -1L,
            replyToMessageId = art.messageId,
            artifactId = art.id,
        )
        if (!sent) {
            ask = Ask.IDLE
            setHint("Too short — tap the mic and try again")
            return
        }
        askedAtRevision = art.revision
        ask = Ask.WORKING
        setWorking("Sending your request…")
    }

    private fun onWork(infos: List<WorkInfo>) {
        val live = infos.firstOrNull {
            it.state == WorkInfo.State.RUNNING || it.state == WorkInfo.State.ENQUEUED
        }
        if (live != null) {
            if (ask == Ask.RECORDING) return
            ask = Ask.WORKING
            setWorking(
                when (live.progress.getString(UploadWorker.KEY_STAGE)) {
                    UploadWorker.STAGE_TRANSCRIBING -> "Transcribing your request…"
                    UploadWorker.STAGE_THINKING -> "Revising the document…"
                    else -> "Sending your request…"
                }
            )
            return
        }
        if (ask != Ask.WORKING) return
        // The turn finished. A revision updates the row just before the worker
        // completes; give that update a moment to arrive before concluding the
        // assistant answered in the thread instead.
        val failed = infos.any { it.state == WorkInfo.State.FAILED }
        binding.root.postDelayed({ concludeAsk(failed) }, 800)
    }

    private fun concludeAsk(failed: Boolean) {
        if (ask != Ask.WORKING) return   // the revision already arrived
        ask = Ask.IDLE
        binding.askProgress.visibility = View.GONE
        askedAtRevision = null
        val threadId = artifact?.threadId
        when {
            failed -> setHint("Couldn't send that — tap the mic to try again")
            threadId != null -> setHint("The assistant replied in the thread — tap to open") {
                startActivity(
                    Intent(this, ThreadDetailActivity::class.java)
                        .putExtra(ThreadDetailActivity.EXTRA_THREAD_ID, threadId)
                )
            }
            else -> setHint("The assistant replied — see your assistant threads")
        }
    }

    private fun onRevisionArrived() {
        val asked = askedAtRevision != null
        ask = Ask.IDLE
        askedAtRevision = null
        binding.askProgress.visibility = View.GONE
        binding.btnAskMic.alpha = 1f
        setHint(if (asked) "Updated — what changed is highlighted" else "A new revision arrived — changes highlighted")
        binding.readerWeb.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
    }

    private fun setWorking(label: String) {
        binding.askProgress.visibility = View.VISIBLE
        binding.btnAskMic.alpha = 0.45f
        setHint(label)
    }

    private fun setHint(text: String, onTap: (() -> Unit)? = null) {
        binding.tvAskHint.text = text
        if (ask != Ask.WORKING) {
            binding.askProgress.visibility = View.GONE
            binding.btnAskMic.alpha = 1f
        }
        binding.tvAskHint.setTextColor(
            if (onTap != null) 0xFFE0A646.toInt() else ContextCompat.getColor(this, R.color.text_secondary)
        )
        binding.tvAskHint.setOnClickListener(onTap?.let { f -> View.OnClickListener { f() } })
        binding.tvAskHint.isClickable = onTap != null
    }

    // ---- share / delete -------------------------------------------------------

    private fun showShareOptions() {
        val art = artifact ?: return
        val options = arrayOf(
            "Email — formatted, full version attached",
            "PDF — save or send as a PDF",
            "Web page — opens in any browser",
            "Text — paste into a message",
        )
        AlertDialog.Builder(this)
            .setTitle("Share “${art.title}”")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> emailDocument(art, retried = false)
                    1 -> ArtifactShare.printPdf(this, binding.readerWeb, art.title)
                    2 -> exportWebPage(art, retried = false)
                    // Text keeps the citations: a markdown Sources list follows.
                    3 -> ArtifactShare.shareText(
                        this, art.title, art.text.orEmpty() + SourcesCard.toMarkdown(art.sourcesJson),
                    )
                }
            }
            .show()
    }

    private fun emailDocument(art: ArtifactEntity, retried: Boolean) {
        ArtifactRenderer.emailHtml(binding.readerWeb) { emailHtml ->
            if (emailHtml.isBlank()) {
                // Still rendering (diagrams/math settle asynchronously): one retry.
                if (!retried) binding.readerWeb.postDelayed({ emailDocument(art, retried = true) }, 600)
                else Toast.makeText(this, "Still rendering — try again in a moment", Toast.LENGTH_SHORT).show()
                return@emailHtml
            }
            ArtifactRenderer.snapshotHtml(binding.readerWeb) { body ->
                ArtifactShare.emailFormatted(this, art.title, emailHtml, body)
            }
        }
    }

    private fun exportWebPage(art: ArtifactEntity, retried: Boolean) {
        ArtifactRenderer.snapshotHtml(binding.readerWeb) { html ->
            when {
                html.isNotBlank() -> ArtifactShare.shareHtml(this, art.title, html, isDark)
                // Still rendering (diagrams/math settle asynchronously): one retry.
                !retried -> binding.readerWeb.postDelayed({ exportWebPage(art, retried = true) }, 600)
                else -> Toast.makeText(this, "Still rendering — try again in a moment", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun confirmDelete() {
        val art = artifact ?: return
        AlertDialog.Builder(this)
            .setTitle("Delete document?")
            .setMessage("“${art.title}” will be removed from your library and its thread. This can't be undone.")
            .setPositiveButton("Delete") { _, _ ->
                lifecycleScope.launch {
                    withContext(Dispatchers.IO) {
                        NoteDatabase.get(applicationContext).artifactDao().delete(art.id)
                    }
                    Toast.makeText(this@ArtifactReaderActivity, "Deleted", Toast.LENGTH_SHORT).show()
                    // The row observer finishes the activity once the row is gone.
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    override fun onDestroy() {
        if (::voiceCapture.isInitialized) voiceCapture.cancel(this)
        if (::binding.isInitialized) ArtifactRenderer.release(binding.readerWeb)
        super.onDestroy()
    }

    companion object {
        const val EXTRA_ID = "artifact_id"
        const val EXTRA_SHARE = "artifact_share"
        private const val STATE_ASKED_AT = "asked_at_revision"

        fun open(context: Context, artifactId: String) {
            context.startActivity(
                Intent(context, ArtifactReaderActivity::class.java)
                    .putExtra(EXTRA_ID, artifactId)
            )
        }

        /** Open the reader and immediately offer the share formats. */
        fun openForShare(context: Context, artifactId: String) {
            context.startActivity(
                Intent(context, ArtifactReaderActivity::class.java)
                    .putExtra(EXTRA_ID, artifactId)
                    .putExtra(EXTRA_SHARE, true)
            )
        }
    }
}
