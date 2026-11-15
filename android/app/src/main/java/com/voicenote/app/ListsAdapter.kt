package com.voicenote.app

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.voicenote.app.databinding.ItemListBinding

class ListsAdapter(
    private val onOpen: (ListWithItems) -> Unit,
    private val onDelete: (ListWithItems) -> Unit,
) : ListAdapter<ListWithItems, ListsAdapter.ViewHolder>(DIFF) {

    inner class ViewHolder(private val binding: ItemListBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(entry: ListWithItems) {
            binding.tvName.text = entry.list.name
            binding.tvCounts.text = formatCounts(entry.items)
            binding.root.setOnClickListener { onOpen(entry) }
            binding.btnDelete.setOnClickListener { onDelete(entry) }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        ViewHolder(
            ItemListBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        )

    override fun onBindViewHolder(holder: ViewHolder, position: Int) =
        holder.bind(getItem(position))

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<ListWithItems>() {
            override fun areItemsTheSame(a: ListWithItems, b: ListWithItems) =
                a.list.id == b.list.id
            override fun areContentsTheSame(a: ListWithItems, b: ListWithItems) = a == b
        }

        private fun formatCounts(items: List<ListItemEntity>): String {
            val total = items.size
            val done = items.count { it.done }
            if (total == 0) return "empty"
            val itemLabel = if (total == 1) "item" else "items"
            return "$total $itemLabel · $done done"
        }
    }
}
