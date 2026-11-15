package com.voicenote.app

import android.net.Uri
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.voicenote.app.databinding.ItemAgentArtifactBinding
import com.voicenote.app.databinding.ItemAgentCanvasBinding
import com.voicenote.app.databinding.ItemAgentMessageBinding
import com.voicenote.app.databinding.ItemApprovalBinding
import com.voicenote.app.databinding.ItemPendingAgentBinding
import com.voicenote.app.databinding.ItemResumeBinding
import com.voicenote.app.databinding.ItemScheduleProposalBinding
import com.voicenote.app.databinding.ItemWikiProposalBinding
import com.voicenote.app.databinding.ItemUserReplyBinding
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Renders one thread's conversation: agent message cards + user reply
 * bubbles, chronological. Replies happen via the thread-level bottom mic
 * in ThreadDetailActivity — there are no per-message affordances.
 */
class ThreadDetailAdapter(
    private val onResend: (NoteEntity) -> Unit = {},
    private val onApprove: (String) -> Unit = {},
    private val onDeny: (String) -> Unit = {},
    private val onOpenArtifact: (ArtifactEntity) -> Unit = {},
    private val onResume: (ConvoRow.Resume) -> Unit = {},
    private val onScheduleDecision: (ConvoRow.ScheduleProposal, Boolean) -> Unit = { _, _ -> },
    private val onWikiDecision: (ConvoRow.WikiProposal, Boolean) -> Unit = { _, _ -> },
) : ListAdapter<ConvoRow, RecyclerView.ViewHolder>(DIFF) {

    // Which messages' activity accordions / sources cards are expanded. Kept
    // here (not in the VH) so expand state survives scroll/recycle.
    private val expandedActivity = mutableSetOf<Long>()
    private val expandedSources = mutableSetOf<Long>()

    inner class AgentVH(private val binding: ItemAgentMessageBinding) :
        RecyclerView.ViewHolder(binding.root) {
        fun bind(item: ConvoRow.Agent) {
            val msg = item.message
            binding.tvMsgTitle.text = "${msg.title} · ${msg.source}".uppercase()
            // Agent replies are markdown — render bold/lists/code/tables.
            MarkdownRenderer.render(binding.tvBody, msg.body)
            binding.tvTimestamp.text = TIME_FMT.format(Date(msg.createdAt))
            binding.tvGoalTag.visibility = View.GONE   // whole thread carries the scope
            ActivityAccordion.bind(
                binding.activitySection, binding.activityHeader, binding.tvActivityCaret,
                binding.tvActivitySummary, binding.activityBody, msg.steps, msg.id, expandedActivity,
            )
            SourcesCard.bind(
                binding.sourcesSection, binding.sourcesHeader, binding.tvSourcesCaret,
                binding.tvSourcesSummary, binding.sourcesBody, msg.sourcesJson, msg.id, expandedSources,
            )
        }
    }

    inner class ReplyVH(private val binding: ItemUserReplyBinding) :
        RecyclerView.ViewHolder(binding.root) {
        fun bind(item: ConvoRow.Reply) {
            binding.tvReplyBody.text = item.note.transcript
            binding.tvReplyTimestamp.text = TIME_FMT.format(Date(item.note.createdAt))
            binding.tvReplyGoalTag.visibility = View.GONE
            binding.btnResendReply.setOnClickListener { onResend(item.note) }
            bindImages(item.images, item.videos)
        }

        private fun bindImages(images: List<NoteImageEntity>, videos: List<NoteMediaEntity>) {
            val strip = binding.replyImages
            strip.removeAllViews()
            if (images.isEmpty() && videos.isEmpty()) {
                strip.visibility = View.GONE
                return
            }
            strip.visibility = View.VISIBLE
            val ctx = strip.context
            val paths = images.map { it.localPath }
            val density = ctx.resources.displayMetrics.density
            val sizePx = (48 * density).toInt()
            val marginPx = (4 * density).toInt()
            // Videos first: thumbnail + "▶ 0:42", tap plays it in the system player.
            videos.forEachIndexed { index, v ->
                val frame = android.widget.FrameLayout(ctx).apply {
                    layoutParams = LinearLayout.LayoutParams(sizePx, sizePx).apply {
                        if (index > 0) marginStart = marginPx
                    }
                    contentDescription = "Attached video, ${v.durationLabel}. Plays the video."
                    setOnClickListener { VideoPlayback.open(ctx, v.localPath, v.mime) }
                }
                frame.addView(ImageView(ctx).apply {
                    scaleType = ImageView.ScaleType.CENTER_CROP
                    setBackgroundResource(R.drawable.input_background)
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                    v.thumbPath?.let { setImageURI(Uri.fromFile(File(it))) }
                })
                frame.addView(android.widget.TextView(ctx).apply {
                    text = "▶ ${v.durationLabel}"
                    textSize = 9f
                    setTextColor(android.graphics.Color.WHITE)
                    setBackgroundColor(0xB3000000.toInt())
                    typeface = android.graphics.Typeface.MONOSPACE
                    setPadding((3 * density).toInt(), 0, (3 * density).toInt(), 0)
                    layoutParams = android.widget.FrameLayout.LayoutParams(
                        android.widget.FrameLayout.LayoutParams.WRAP_CONTENT,
                        android.widget.FrameLayout.LayoutParams.WRAP_CONTENT,
                        android.view.Gravity.BOTTOM or android.view.Gravity.START,
                    )
                })
                strip.addView(frame)
            }
            images.forEachIndexed { i, img ->
                val index = i
                val offset = videos.size
                val iv = ImageView(ctx).apply {
                    layoutParams = LinearLayout.LayoutParams(sizePx, sizePx).apply {
                        if (index + offset > 0) marginStart = marginPx
                    }
                    scaleType = ImageView.ScaleType.CENTER_CROP
                    setBackgroundResource(R.drawable.input_background)
                    contentDescription = "Attached photo"
                    setImageURI(Uri.fromFile(File(img.localPath)))
                    setOnClickListener { ImageViewerActivity.open(ctx, paths, index) }
                }
                strip.addView(iv)
            }
        }
    }

    /** Agent message that carries an AI-generated React widget. Renders the
     *  optional caption plus the widget in a sandboxed WebView. */
    inner class CanvasVH(private val binding: ItemAgentCanvasBinding) :
        RecyclerView.ViewHolder(binding.root) {
        fun bind(item: ConvoRow.Agent) {
            val msg = item.message
            binding.tvMsgTitle.text = "${msg.title} · ${msg.source}".uppercase()
            if (msg.body.isBlank()) {
                binding.tvBody.visibility = View.GONE
            } else {
                binding.tvBody.visibility = View.VISIBLE
                MarkdownRenderer.render(binding.tvBody, msg.body)
            }
            binding.tvTimestamp.text = TIME_FMT.format(Date(msg.createdAt))
            ActivityAccordion.bind(
                binding.activitySection, binding.activityHeader, binding.tvActivityCaret,
                binding.tvActivitySummary, binding.activityBody, msg.steps, msg.id, expandedActivity,
            )
            SourcesCard.bind(
                binding.sourcesSection, binding.sourcesHeader, binding.tvSourcesCaret,
                binding.tvSourcesSummary, binding.sourcesBody, msg.sourcesJson, msg.id, expandedSources,
            )

            val web = binding.canvasWeb
            val canvas = msg.canvas ?: return
            CanvasRenderer.render(
                web = web,
                canvasJson = canvas,
                // The agent card is always dark; match it so the widget blends.
                isDark = true,
            ) { px ->
                val lp = web.layoutParams
                if (lp != null && px > 0 && lp.height != px) {
                    lp.height = px
                    web.layoutParams = lp
                }
            }
        }

        fun releaseWeb() = CanvasRenderer.release(binding.canvasWeb)
    }

    /** A long-form document card. Tapping it opens the full-screen reader; the
     *  card itself shows only a native title + plain-text preview (no WebView,
     *  so the conversation list scrolls cheaply). */
    inner class ArtifactVH(private val binding: ItemAgentArtifactBinding) :
        RecyclerView.ViewHolder(binding.root) {
        fun bind(item: ConvoRow.Artifact) {
            val a = item.artifact
            binding.tvKind.text = "▤ ${a.kind.uppercase()}${a.revisionLabel}${SourcesCard.countLabel(a.sourcesJson)}"
            binding.tvArtTitle.text = a.title
            binding.tvArtPreview.text = mdPreview(a.text)
            binding.card.setOnClickListener { onOpenArtifact(a) }
        }
    }

    /** A one-tap "Continue" card for a paused research run. */
    inner class ResumeVH(private val binding: ItemResumeBinding) :
        RecyclerView.ViewHolder(binding.root) {
        fun bind(item: ConvoRow.Resume) {
            binding.tvResumeLabel.text = item.label
            binding.btnContinue.isEnabled = true
            binding.btnContinue.setOnClickListener {
                binding.btnContinue.isEnabled = false
                binding.tvResumeLabel.text = "Resuming…"
                onResume(item)
            }
        }
    }

    /** A drafted schedule awaiting approval: shows the draft + Approve/Discard. */
    inner class ScheduleProposalVH(private val binding: ItemScheduleProposalBinding) :
        RecyclerView.ViewHolder(binding.root) {
        fun bind(item: ConvoRow.ScheduleProposal) {
            val p = item.proposal
            binding.tvKind.text = if (p.action == "update") "⧗ EDIT SCHEDULE · APPROVE"
                                  else "⧗ NEW SCHEDULE · APPROVE"
            binding.tvSchedTitle.text = p.title
            val zone = if (p.tzMode == "device") "device time" else (p.timezone.ifBlank { "server time" })
            binding.tvWhen.text = "${p.summary}  ·  $zone"
            binding.tvWhat.text = "${p.agent}: ${p.prompt}"
            binding.btnApproveSched.isEnabled = true
            binding.btnDiscardSched.isEnabled = true
            binding.btnApproveSched.setOnClickListener {
                binding.btnApproveSched.isEnabled = false
                binding.btnDiscardSched.isEnabled = false
                onScheduleDecision(item, true)
            }
            binding.btnDiscardSched.setOnClickListener {
                binding.btnApproveSched.isEnabled = false
                binding.btnDiscardSched.isEnabled = false
                onScheduleDecision(item, false)
            }
        }
    }

    inner class WikiProposalVH(private val binding: ItemWikiProposalBinding) :
        RecyclerView.ViewHolder(binding.root) {
        fun bind(item: ConvoRow.WikiProposal) {
            val p = item.proposal
            binding.tvWikiKind.text = if (p.op == "amend") "✦ WIKI ENTRY · UPDATE · APPROVE"
                                      else "✦ WIKI ENTRY · NEW · APPROVE"
            binding.tvWikiTitle.text = p.title
            binding.tvWikiWhen.text = p.whenToUse.ifBlank { p.kind }
            binding.tvWikiBody.text = mdPreview(p.body)
            binding.btnApproveWiki.isEnabled = true
            binding.btnDiscardWiki.isEnabled = true
            binding.btnApproveWiki.setOnClickListener {
                binding.btnApproveWiki.isEnabled = false
                binding.btnDiscardWiki.isEnabled = false
                onWikiDecision(item, true)
            }
            binding.btnDiscardWiki.setOnClickListener {
                binding.btnApproveWiki.isEnabled = false
                binding.btnDiscardWiki.isEnabled = false
                onWikiDecision(item, false)
            }
        }
    }

    inner class ApprovalVH(private val binding: ItemApprovalBinding) :
        RecyclerView.ViewHolder(binding.root) {
        fun bind(item: ConvoRow.Approval) {
            binding.tvCommand.text = item.command
            // Smart mode: say it was flagged, and why. Manual: plain request.
            binding.tvApprovalHeader.text =
                if (item.reason != null) "SANDBOX · FLAGGED BY REVIEW" else "SANDBOX · APPROVAL NEEDED"
            binding.tvReason.text = item.reason.orEmpty()
            binding.tvReason.visibility =
                if (item.reason != null) android.view.View.VISIBLE else android.view.View.GONE
            binding.btnApprove.setOnClickListener { onApprove(item.id) }
            binding.btnDeny.setOnClickListener { onDeny(item.id) }
        }
    }

    inner class PendingVH(private val binding: ItemPendingAgentBinding) :
        RecyclerView.ViewHolder(binding.root) {
        fun bind(item: ConvoRow.Pending) {
            // Prefer the live goal-relative label; fall back to the coarse stage.
            binding.tvStage.text = item.label ?: UploadWorker.stageLabel(item.stage)
            if (item.liveSteps.isEmpty()) {
                binding.tvLiveSteps.visibility = View.GONE
            } else {
                binding.tvLiveSteps.visibility = View.VISIBLE
                binding.tvLiveSteps.text = item.liveSteps.joinToString("\n") { "✓  $it" }
            }
        }
    }

    override fun getItemViewType(position: Int): Int = when (val row = getItem(position)) {
        is ConvoRow.Agent -> if (row.message.canvas != null) TYPE_AGENT_CANVAS else TYPE_AGENT
        is ConvoRow.Artifact -> TYPE_ARTIFACT
        is ConvoRow.Resume -> TYPE_RESUME
        is ConvoRow.ScheduleProposal -> TYPE_SCHED_PROPOSAL
        is ConvoRow.WikiProposal -> TYPE_WIKI_PROPOSAL
        is ConvoRow.Reply -> TYPE_REPLY
        is ConvoRow.Pending -> TYPE_PENDING
        is ConvoRow.Approval -> TYPE_APPROVAL
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            TYPE_AGENT -> AgentVH(ItemAgentMessageBinding.inflate(inflater, parent, false))
            TYPE_AGENT_CANVAS -> CanvasVH(ItemAgentCanvasBinding.inflate(inflater, parent, false))
            TYPE_ARTIFACT -> ArtifactVH(ItemAgentArtifactBinding.inflate(inflater, parent, false))
            TYPE_RESUME -> ResumeVH(ItemResumeBinding.inflate(inflater, parent, false))
            TYPE_SCHED_PROPOSAL -> ScheduleProposalVH(ItemScheduleProposalBinding.inflate(inflater, parent, false))
            TYPE_WIKI_PROPOSAL -> WikiProposalVH(ItemWikiProposalBinding.inflate(inflater, parent, false))
            TYPE_PENDING -> PendingVH(ItemPendingAgentBinding.inflate(inflater, parent, false))
            TYPE_APPROVAL -> ApprovalVH(ItemApprovalBinding.inflate(inflater, parent, false))
            else -> ReplyVH(ItemUserReplyBinding.inflate(inflater, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val item = getItem(position)) {
            is ConvoRow.Agent -> if (holder is CanvasVH) holder.bind(item) else (holder as AgentVH).bind(item)
            is ConvoRow.Artifact -> (holder as ArtifactVH).bind(item)
            is ConvoRow.Resume -> (holder as ResumeVH).bind(item)
            is ConvoRow.ScheduleProposal -> (holder as ScheduleProposalVH).bind(item)
            is ConvoRow.WikiProposal -> (holder as WikiProposalVH).bind(item)
            is ConvoRow.Reply -> (holder as ReplyVH).bind(item)
            is ConvoRow.Pending -> (holder as PendingVH).bind(item)
            is ConvoRow.Approval -> (holder as ApprovalVH).bind(item)
        }
    }

    override fun onViewRecycled(holder: RecyclerView.ViewHolder) {
        if (holder is CanvasVH) holder.releaseWeb()
        super.onViewRecycled(holder)
    }

    companion object {
        private const val TYPE_AGENT = 0
        private const val TYPE_REPLY = 1
        private const val TYPE_PENDING = 2
        private const val TYPE_AGENT_CANVAS = 3
        private const val TYPE_APPROVAL = 4
        private const val TYPE_ARTIFACT = 5
        private const val TYPE_RESUME = 6
        private const val TYPE_SCHED_PROPOSAL = 7
        private const val TYPE_WIKI_PROPOSAL = 8

        private val TIME_FMT = SimpleDateFormat("MMM d, h:mm a", Locale.getDefault())

        /** Flatten a markdown body to a short plain-text preview for the card. */
        fun mdPreview(md: String?): String {
            if (md.isNullOrBlank()) return ""
            val out = StringBuilder()
            for (raw in md.lineSequence()) {
                val line = raw.trim()
                if (line.isEmpty()) { if (out.isNotEmpty()) break else continue }
                if (line.startsWith("```") || line.startsWith("|") ||
                    line.startsWith("---") || line.startsWith("===")) continue
                var t = line.trimStart('#', '>', '-', '*', '+', ' ', '\t')
                t = t.replace(Regex("\\*\\*|__|\\*|`"), "")
                    .replace(Regex("!?\\[([^\\]]*)\\]\\([^)]*\\)"), "$1")
                if (t.isNotBlank()) {
                    if (out.isNotEmpty()) out.append("  ")
                    out.append(t)
                }
                if (out.length > 160) break
            }
            return out.toString().take(180)
        }

        private val DIFF = object : DiffUtil.ItemCallback<ConvoRow>() {
            override fun areItemsTheSame(a: ConvoRow, b: ConvoRow): Boolean = when {
                a is ConvoRow.Agent && b is ConvoRow.Agent -> a.message.id == b.message.id
                a is ConvoRow.Artifact && b is ConvoRow.Artifact -> a.artifact.id == b.artifact.id
                a is ConvoRow.Resume && b is ConvoRow.Resume -> a.messageId == b.messageId
                a is ConvoRow.ScheduleProposal && b is ConvoRow.ScheduleProposal -> a.proposal.id == b.proposal.id
                a is ConvoRow.WikiProposal && b is ConvoRow.WikiProposal -> a.proposal.proposalId == b.proposal.proposalId
                a is ConvoRow.Reply && b is ConvoRow.Reply -> a.note.id == b.note.id
                // there's only ever one pending bubble — same item, so a stage
                // change re-binds the label instead of re-inflating the row
                a is ConvoRow.Pending && b is ConvoRow.Pending -> true
                a is ConvoRow.Approval && b is ConvoRow.Approval -> a.id == b.id
                else -> false
            }

            override fun areContentsTheSame(a: ConvoRow, b: ConvoRow): Boolean = a == b
        }
    }
}
