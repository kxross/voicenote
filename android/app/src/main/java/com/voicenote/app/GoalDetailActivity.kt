package com.voicenote.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Paint
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.room.withTransaction
import com.voicenote.app.databinding.ActivityGoalDetailBinding
import com.voicenote.app.databinding.ItemGoalTaskBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class GoalDetailActivity : AppCompatActivity() {

    private lateinit var binding: ActivityGoalDetailBinding
    private val viewModel: GoalViewModel by viewModels()
    private lateinit var voiceCapture: VoiceCapture
    private var goalId: Long = -1L

    // Edit-mode state: the entry/notes as of when Edit was tapped, so the
    // save applies against exactly what the user saw.
    private var currentEntry: GoalWithDetails? = null
    private var currentNotes: List<NoteEntity> = emptyList()
    private var editEntry: GoalWithDetails? = null
    private var editNotes: List<NoteEntity> = emptyList()

    private val requestMicPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) startRecording()
        else Toast.makeText(this, "Microphone permission required", Toast.LENGTH_LONG).show()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityGoalDetailBinding.inflate(layoutInflater)
        setContentView(binding.root)
        voiceCapture = VoiceCapture(this)

        goalId = intent.getLongExtra(EXTRA_GOAL_ID, -1L)
        if (goalId < 0) {
            finish()
            return
        }

        binding.btnBack.setOnClickListener { finish() }

        binding.btnRecordForGoal.setOnClickListener {
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

        viewModel.goalWithDetails(goalId).observe(this) { entry ->
            if (entry == null) {
                finish()
                return@observe
            }
            currentEntry = entry
            render(entry)
        }

        viewModel.notesForGoal(goalId).observe(this) { notes ->
            currentNotes = notes
            renderNotes(notes)
        }

        binding.btnEdit.setOnClickListener { enterEditMode() }
        binding.btnCancelEdit.setOnClickListener { exitEditMode() }
        binding.btnSaveEdit.setOnClickListener { saveEdit() }
    }

    private fun enterEditMode() {
        val entry = currentEntry ?: return
        if (voiceCapture.isRecording) stopRecording()
        editEntry = entry
        editNotes = currentNotes
        binding.etMarkdown.setText(GoalMarkdown.render(entry, editNotes))
        binding.svContent.visibility = View.GONE
        binding.btnRecordForGoal.visibility = View.GONE
        binding.tvRecordingHint.visibility = View.GONE
        binding.btnEdit.visibility = View.GONE
        binding.llEditMode.visibility = View.VISIBLE
    }

    private fun exitEditMode() {
        editEntry = null
        binding.llEditMode.visibility = View.GONE
        binding.svContent.visibility = View.VISIBLE
        binding.btnRecordForGoal.visibility = View.VISIBLE
        binding.btnEdit.visibility = View.VISIBLE
    }

    private fun saveEdit() {
        val entry = editEntry ?: return
        val parsed = GoalMarkdown.parse(binding.etMarkdown.text.toString())
        if (parsed == null) {
            Toast.makeText(this, "Needs a '# Title' heading on the first line", Toast.LENGTH_LONG).show()
            return
        }
        val notesSnapshot = editNotes
        lifecycleScope.launch {
            val titleOk = withContext(Dispatchers.IO) {
                val db = NoteDatabase.get(this@GoalDetailActivity)
                db.withTransaction {
                    GoalMarkdown.apply(db, goalId, entry, notesSnapshot, parsed)
                }
            }
            if (!titleOk) {
                Toast.makeText(this@GoalDetailActivity, "Title already in use — kept the old one", Toast.LENGTH_LONG).show()
            } else {
                Toast.makeText(this@GoalDetailActivity, "Saved", Toast.LENGTH_SHORT).show()
            }
            exitEditMode()
            SnapshotSync.uploadSnapshotAsync(this@GoalDetailActivity)
        }
    }

    private fun startRecording() {
        if (voiceCapture.start()) {
            binding.btnRecordForGoal.setColorFilter(ContextCompat.getColor(this, R.color.accent))
            binding.tvRecordingHint.visibility = View.VISIBLE
        } else {
            Toast.makeText(this, "Could not start recording", Toast.LENGTH_SHORT).show()
        }
    }

    private fun stopRecording() {
        binding.btnRecordForGoal.setColorFilter(ContextCompat.getColor(this, R.color.text_secondary))
        binding.tvRecordingHint.visibility = View.GONE
        if (voiceCapture.stopAndUpload(goalId = goalId)) {
            Toast.makeText(this, "Uploading…", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "Too short — try again", Toast.LENGTH_SHORT).show()
        }
    }

    private fun renderNotes(notes: List<NoteEntity>) {
        binding.llNotes.removeAllViews()
        if (notes.isEmpty()) {
            binding.llNotes.addView(makeContextRow("No notes for this goal yet — tap the mic to add one", isQuestion = false, dim = true))
            return
        }
        for (n in notes) {
            binding.llNotes.addView(makeContextRow(n.transcript, isQuestion = false, dim = false))
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        voiceCapture.cancel(this)
    }

    private fun render(entry: GoalWithDetails) {
        binding.tvTitle.text = entry.goal.title.uppercase()
        binding.tvStatement.text = entry.goal.statement.ifBlank { entry.goal.title }

        val badge = buildString {
            append(entry.goal.status.uppercase())
            if (!entry.goal.wellSpecified && entry.goal.status == "draft") {
                append(" · NEEDS REFINEMENT")
            }
        }
        binding.tvStatusBadge.text = badge

        renderContext(entry.context)
        renderPlan(entry.plan)
    }

    private fun renderContext(context: List<GoalContextEntity>) {
        binding.llContext.removeAllViews()
        if (context.isEmpty()) {
            binding.llContext.addView(makeContextRow("No context yet", isQuestion = false, dim = true))
            return
        }
        for (c in context.sortedBy { it.createdAt }) {
            // Q: = open vetting question, P: = plan proposal awaiting response —
            // both are agent asks the user should see highlighted.
            val needsResponse = c.source == "agent" &&
                (c.text.startsWith("Q:") || c.text.startsWith("P:"))
            binding.llContext.addView(makeContextRow(c.text, needsResponse, dim = false))
        }
    }

    private fun makeContextRow(text: String, isQuestion: Boolean, dim: Boolean): TextView {
        return TextView(this).apply {
            this.text = if (isQuestion) text else "• $text"
            textSize = 14f
            setLineSpacing(0f, 1.3f)
            setPadding(0, 4, 0, 4)
            setTextColor(
                when {
                    dim -> Color.parseColor("#555555")
                    // Agent's open questions pop in accent so the user sees
                    // what the agent is waiting on.
                    isQuestion -> Color.parseColor("#FF9500")
                    else -> Color.parseColor("#BBBBBB")
                }
            )
        }
    }

    private fun renderPlan(plan: PlanWithTasks?) {
        binding.llTasks.removeAllViews()

        if (plan == null) {
            binding.tvNoPlan.visibility = View.VISIBLE
            binding.tvPlanSummary.visibility = View.GONE
            binding.btnApprovePlan.visibility = View.GONE
            return
        }

        binding.tvNoPlan.visibility = View.GONE
        binding.tvPlanSummary.visibility = View.VISIBLE
        binding.tvPlanSummary.text = plan.plan.summary

        if (plan.plan.status == "draft") {
            binding.btnApprovePlan.visibility = View.VISIBLE
            binding.btnApprovePlan.setOnClickListener {
                // Approval IS activation — plan approved + goal active together.
                viewModel.approvePlan(plan.plan.id, goalId)
                SnapshotSync.uploadSnapshotAsync(this)
            }
        } else {
            binding.btnApprovePlan.visibility = View.GONE
        }

        val sorted = plan.tasks.sortedBy { it.orderIndex }
        val subsByParent = sorted.filter { it.parentTaskId != null }.groupBy { it.parentTaskId!! }
        var lastMilestone: String? = null
        for (task in sorted.filter { it.parentTaskId == null }) {
            if (task.milestone.isNotBlank() && task.milestone != lastMilestone) {
                lastMilestone = task.milestone
                binding.llTasks.addView(makeMilestoneHeader(task.milestone))
            }
            binding.llTasks.addView(makeTaskRow(task, indent = false))
            for (sub in subsByParent[task.id].orEmpty()) {
                binding.llTasks.addView(makeTaskRow(sub, indent = true))
            }
        }
    }

    private fun makeTaskRow(task: GoalTaskEntity, indent: Boolean): View {
        val row = ItemGoalTaskBinding.inflate(LayoutInflater.from(this), binding.llTasks, false)
        if (indent) {
            row.root.setPadding(
                (32 * resources.displayMetrics.density).toInt(),
                row.root.paddingTop, row.root.paddingRight, row.root.paddingBottom,
            )
        }
        row.cbDone.setOnCheckedChangeListener(null)
        row.cbDone.isChecked = task.done
        row.tvText.text = task.text
        row.tvText.paintFlags = if (task.done) {
            row.tvText.paintFlags or Paint.STRIKE_THRU_TEXT_FLAG
        } else {
            row.tvText.paintFlags and Paint.STRIKE_THRU_TEXT_FLAG.inv()
        }
        if (task.dueHint.isNotBlank()) {
            row.tvDue.visibility = View.VISIBLE
            row.tvDue.text = task.dueHint
        }
        row.cbDone.setOnCheckedChangeListener { _, checked ->
            viewModel.setTaskDone(task.id, checked)
            SnapshotSync.uploadSnapshotAsync(this)
        }
        return row.root
    }

    private fun makeMilestoneHeader(milestone: String): TextView {
        return TextView(this).apply {
            text = milestone.uppercase()
            textSize = 11f
            letterSpacing = 0.1f
            typeface = android.graphics.Typeface.MONOSPACE
            setTextColor(Color.parseColor("#666666"))
            setPadding(0, 24, 0, 6)
        }
    }

    companion object {
        const val EXTRA_GOAL_ID = "goal_id"
    }
}
