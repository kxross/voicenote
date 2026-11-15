package com.voicenote.app

import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.activity.addCallback
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.google.android.material.snackbar.Snackbar
import com.voicenote.app.databinding.ActivityAssistantBinding

/**
 * The assistant inbox: open threads as cards (swipe to resolve), resolved
 * threads behind a collapsible section at the bottom. Tap a card to open
 * the conversation.
 */
class AssistantActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAssistantBinding
    private val viewModel: AssistantViewModel by viewModels()
    private lateinit var adapter: ThreadListAdapter
    private var approvalMode: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAssistantBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnBack.setOnClickListener { finish() }
        binding.tvApprovalChip.setOnClickListener { toggleApprovalMode() }

        adapter = ThreadListAdapter(
            onOpenThread = { row ->
                startActivity(
                    Intent(this, ThreadDetailActivity::class.java)
                        .putExtra(ThreadDetailActivity.EXTRA_THREAD_ID, row.thread.id)
                )
            },
            onToggleResolved = { viewModel.toggleResolvedSection() },
            onSelectionChanged = { count -> renderSelectionBar(count) },
        )

        binding.rvThread.layoutManager = LinearLayoutManager(this)
        binding.rvThread.adapter = adapter

        // Selection action bar (long-press a card to begin a multi-select).
        binding.btnSelCancel.setOnClickListener { adapter.clearSelection() }
        binding.btnSelDelete.setOnClickListener { confirmDeleteSelected() }

        // Back exits selection mode first, only then leaves the screen.
        onBackPressedDispatcher.addCallback(this) {
            if (adapter.selectionMode) adapter.clearSelection() else {
                isEnabled = false
                onBackPressedDispatcher.onBackPressed()
            }
        }

        // Swipe an OPEN card away to resolve it (with undo).
        val swipe = object : ItemTouchHelper.SimpleCallback(0, ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT) {
            override fun getSwipeDirs(rv: RecyclerView, vh: RecyclerView.ViewHolder): Int {
                if (adapter.selectionMode) return 0   // no swipe-resolve mid-select
                val pos = vh.bindingAdapterPosition
                if (pos == RecyclerView.NO_POSITION) return 0
                return if (adapter.getItemViewType(pos) == ThreadListAdapter.TYPE_OPEN) {
                    super.getSwipeDirs(rv, vh)
                } else 0
            }

            override fun onMove(
                rv: RecyclerView,
                vh: RecyclerView.ViewHolder,
                target: RecyclerView.ViewHolder,
            ) = false

            override fun onSwiped(vh: RecyclerView.ViewHolder, direction: Int) {
                val pos = vh.bindingAdapterPosition
                val item = adapter.currentList.getOrNull(pos) as? InboxRow.Open ?: return
                val threadId = item.row.thread.id
                viewModel.resolveThread(threadId)
                Snackbar.make(binding.root, "Resolved", Snackbar.LENGTH_LONG)
                    .setAction("Undo") { viewModel.reopenThread(threadId) }
                    .show()
            }
        }
        ItemTouchHelper(swipe).attachToRecyclerView(binding.rvThread)

        viewModel.rows.observe(this) { rows ->
            adapter.submitList(rows)
            binding.tvEmpty.visibility = if (rows.isEmpty()) View.VISIBLE else View.GONE
        }

        renderSelectionBar(0)

        // Any agent turn in flight (including brand-new notes that don't have a
        // thread yet) surfaces as the transient pending row at the top.
        WorkManager.getInstance(this)
            .getWorkInfosByTagLiveData(UploadWorker.TAG_AGENT_TURN)
            .observe(this) { infos ->
                val live = infos?.firstOrNull {
                    it.state == WorkInfo.State.RUNNING || it.state == WorkInfo.State.ENQUEUED
                }
                viewModel.setPendingStage(
                    if (live == null) null
                    else live.progress.getString(UploadWorker.KEY_STAGE)
                        ?: UploadWorker.STAGE_TRANSCRIBING
                )
            }
    }

    override fun onResume() {
        super.onResume()
        refreshApprovalChip()   // reflect the live mode (may have changed in Settings)
    }

    private fun refreshApprovalChip() {
        val prefs = Prefs(this)
        lifecycleScope.launch {
            approvalMode = withContext(Dispatchers.IO) {
                GatewayAgent.getApprovalMode(prefs.gatewayBaseUrl, prefs.gatewayApiKey)
            }
            renderApprovalChip()
        }
    }

    private fun renderApprovalChip() {
        val chip = binding.tvApprovalChip
        when (approvalMode) {
            "smart" -> { chip.text = "Approvals: Smart"; chip.alpha = 1f; chip.isClickable = true }
            "manual" -> { chip.text = "Approvals: Manual"; chip.alpha = 1f; chip.isClickable = true }
            "auto" -> { chip.text = "Approvals: Off"; chip.alpha = 1f; chip.isClickable = true }
            else -> { chip.text = "Approvals —"; chip.alpha = 0.45f; chip.isClickable = false }
        }
    }

    /** Tap cycles Smart -> Manual -> Off -> Smart. */
    private fun toggleApprovalMode() {
        val cur = approvalMode ?: return
        val next = when (cur) {
            "smart" -> "manual"
            "manual" -> "auto"
            else -> "smart"
        }
        val prefs = Prefs(this)
        lifecycleScope.launch {
            val set = withContext(Dispatchers.IO) {
                GatewayAgent.setApprovalMode(prefs.gatewayBaseUrl, prefs.gatewayApiKey, next)
            }
            if (set != null) approvalMode = set
            renderApprovalChip()
        }
    }

    /** Show the contextual delete bar (and cover the normal header) while a
     *  selection is active; hide it when the count is zero. */
    private fun renderSelectionBar(count: Int) {
        if (count > 0) {
            binding.selectionBar.visibility = View.VISIBLE
            binding.tvSelCount.text = "$count selected"
        } else {
            binding.selectionBar.visibility = View.GONE
        }
    }

    private fun confirmDeleteSelected() {
        val ids = adapter.selectedThreadIds()
        if (ids.isEmpty()) return
        val n = ids.size
        AlertDialog.Builder(this)
            .setTitle(if (n == 1) "Delete thread?" else "Delete $n threads?")
            .setMessage("Removes the selected conversation(s) and the assistant's replies. Your voice notes are kept.")
            .setPositiveButton("Delete") { _, _ ->
                viewModel.deleteThreads(ids)
                adapter.clearSelection()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}
