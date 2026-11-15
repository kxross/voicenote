package com.voicenote.app

import android.content.res.ColorStateList
import android.text.format.DateUtils
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.voicenote.app.databinding.ItemInboxPendingBinding
import com.voicenote.app.databinding.ItemThreadCardBinding
import com.voicenote.app.databinding.ItemThreadHeaderBinding

class ThreadListAdapter(
    private val onOpenThread: (ThreadListRow) -> Unit,
    private val onToggleResolved: () -> Unit,
    /** Reports the current multi-select count (0 = selection mode ended). */
    private val onSelectionChanged: (Int) -> Unit = {},
) : ListAdapter<InboxRow, RecyclerView.ViewHolder>(DIFF) {

    // Thread ids currently selected for a batch action. Non-empty == selection
    // mode: taps toggle instead of opening, and the host shows its action bar.
    private val selectedIds = linkedSetOf<Long>()

    val selectionMode: Boolean get() = selectedIds.isNotEmpty()

    fun selectedThreadIds(): List<Long> = selectedIds.toList()

    fun clearSelection() {
        if (selectedIds.isEmpty()) return
        selectedIds.clear()
        notifyDataSetChanged()      // clear every card's selected tint
        onSelectionChanged(0)
    }

    private fun toggle(id: Long) {
        if (!selectedIds.remove(id)) selectedIds.add(id)
        val pos = currentList.indexOfFirst {
            (it is InboxRow.Open && it.row.thread.id == id) ||
                (it is InboxRow.Resolved && it.row.thread.id == id)
        }
        if (pos >= 0) notifyItemChanged(pos)
        onSelectionChanged(selectedIds.size)
    }

    inner class CardVH(private val binding: ItemThreadCardBinding) :
        RecyclerView.ViewHolder(binding.root) {
        fun bind(row: ThreadListRow, resolved: Boolean) {
            binding.tvThreadTitle.text = row.thread.title
            binding.tvThreadTime.text = DateUtils.getRelativeTimeSpanString(
                row.lastActivity, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS
            )
            // Snippets are one-liners — flatten markdown so no ** or | leaks in.
            binding.tvThreadSnippet.text =
                MarkdownRenderer.plain(binding.root.context, row.lastBody)
            binding.tvThreadSnippet.visibility =
                if (row.lastBody.isNullOrBlank()) View.GONE else View.VISIBLE
            binding.unreadDot.visibility =
                if (!resolved && row.unreadCount > 0) View.VISIBLE else View.GONE
            // A thread carries at most one scope; show whichever it has.
            val scope = row.goalTitle ?: row.listName
            if (scope != null) {
                binding.tvThreadGoalTag.visibility = View.VISIBLE
                binding.tvThreadGoalTag.text = scope
            } else {
                binding.tvThreadGoalTag.visibility = View.GONE
            }
            binding.tvThreadTitle.alpha = if (resolved) 0.55f else 1f
            binding.tvThreadSnippet.alpha = if (resolved) 0.55f else 1f

            // Selected cards get an accent-dim fill (tints the existing rounded
            // card drawable, so shape/stroke are preserved).
            val id = row.thread.id
            binding.card.backgroundTintList =
                if (id in selectedIds)
                    ColorStateList.valueOf(
                        ContextCompat.getColor(binding.root.context, R.color.accent_dim)
                    )
                else null

            binding.card.setOnClickListener {
                if (selectionMode) toggle(id) else onOpenThread(row)
            }
            binding.card.setOnLongClickListener { toggle(id); true }
        }
    }

    inner class ResolvedHeaderVH(private val binding: ItemThreadHeaderBinding) :
        RecyclerView.ViewHolder(binding.root) {
        fun bind(item: InboxRow.ResolvedHeader) {
            binding.tvHeaderTitle.text = "Resolved"
            binding.tvHeaderCount.text = item.count.toString()
            binding.tvChevron.text = if (item.expanded) "▾" else "▸"
            val color = ContextCompat.getColor(
                binding.root.context,
                if (item.expanded) R.color.accent else R.color.text_secondary,
            )
            binding.tvChevron.setTextColor(color)
            binding.tvHeaderTitle.setTextColor(color)
            binding.root.setOnClickListener { onToggleResolved() }
        }
    }

    inner class PendingVH(private val binding: ItemInboxPendingBinding) :
        RecyclerView.ViewHolder(binding.root) {
        fun bind(item: InboxRow.Pending) {
            binding.tvStage.text = UploadWorker.stageLabel(item.stage)
        }
    }

    override fun getItemViewType(position: Int): Int = when (getItem(position)) {
        is InboxRow.Pending -> TYPE_PENDING
        is InboxRow.Open -> TYPE_OPEN
        is InboxRow.ResolvedHeader -> TYPE_HEADER
        is InboxRow.Resolved -> TYPE_RESOLVED
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            TYPE_HEADER -> ResolvedHeaderVH(ItemThreadHeaderBinding.inflate(inflater, parent, false))
            TYPE_PENDING -> PendingVH(ItemInboxPendingBinding.inflate(inflater, parent, false))
            else -> CardVH(ItemThreadCardBinding.inflate(inflater, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val item = getItem(position)) {
            is InboxRow.Pending -> (holder as PendingVH).bind(item)
            is InboxRow.Open -> (holder as CardVH).bind(item.row, resolved = false)
            is InboxRow.ResolvedHeader -> (holder as ResolvedHeaderVH).bind(item)
            is InboxRow.Resolved -> (holder as CardVH).bind(item.row, resolved = true)
        }
    }

    companion object {
        const val TYPE_OPEN = 0
        const val TYPE_HEADER = 1
        const val TYPE_RESOLVED = 2
        const val TYPE_PENDING = 3

        private val DIFF = object : DiffUtil.ItemCallback<InboxRow>() {
            override fun areItemsTheSame(a: InboxRow, b: InboxRow): Boolean = when {
                a is InboxRow.Open && b is InboxRow.Open -> a.row.thread.id == b.row.thread.id
                a is InboxRow.Resolved && b is InboxRow.Resolved -> a.row.thread.id == b.row.thread.id
                a is InboxRow.ResolvedHeader && b is InboxRow.ResolvedHeader -> true
                a is InboxRow.Pending && b is InboxRow.Pending -> true
                else -> false
            }

            override fun areContentsTheSame(a: InboxRow, b: InboxRow): Boolean = a == b
        }
    }
}
