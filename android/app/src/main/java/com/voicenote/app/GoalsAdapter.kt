package com.voicenote.app

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.voicenote.app.databinding.ItemGoalBinding

class GoalsAdapter(
    private val onOpen: (GoalWithDetails) -> Unit,
    private val onDelete: (GoalWithDetails) -> Unit,
) : ListAdapter<GoalWithDetails, GoalsAdapter.ViewHolder>(DIFF) {

    inner class ViewHolder(private val binding: ItemGoalBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(entry: GoalWithDetails) {
            binding.tvTitle.text = entry.goal.title
            binding.tvStatus.text = statusLabel(entry)
            binding.tvProgress.text = progressLabel(entry)
            binding.root.setOnClickListener { onOpen(entry) }
            binding.btnDelete.setOnClickListener { onDelete(entry) }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        ViewHolder(ItemGoalBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: ViewHolder, position: Int) =
        holder.bind(getItem(position))

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<GoalWithDetails>() {
            override fun areItemsTheSame(a: GoalWithDetails, b: GoalWithDetails) =
                a.goal.id == b.goal.id
            override fun areContentsTheSame(a: GoalWithDetails, b: GoalWithDetails) = a == b
        }

        private fun statusLabel(entry: GoalWithDetails): String {
            val base = entry.goal.status.uppercase()
            return if (!entry.goal.wellSpecified && entry.goal.status == "draft") {
                "$base · NEEDS REFINEMENT"
            } else base
        }

        private fun progressLabel(entry: GoalWithDetails): String {
            val plan = entry.plan ?: return "no plan yet"
            val total = plan.tasks.size
            val done = plan.tasks.count { it.done }
            val approval = if (plan.plan.status == "draft") " (draft plan)" else ""
            return "$done/$total tasks$approval"
        }
    }
}
