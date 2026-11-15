package com.voicenote.app

import android.graphics.Paint
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.voicenote.app.databinding.ItemListRowBinding

class ListItemsAdapter(
    private val onToggle: (ListItemEntity, Boolean) -> Unit,
    private val onDelete: (ListItemEntity) -> Unit,
) : ListAdapter<ListItemEntity, ListItemsAdapter.ViewHolder>(DIFF) {

    inner class ViewHolder(private val binding: ItemListRowBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(item: ListItemEntity) {
            binding.cbDone.setOnCheckedChangeListener(null)
            binding.cbDone.isChecked = item.done
            binding.tvText.text = item.text
            binding.tvText.paintFlags = if (item.done) {
                binding.tvText.paintFlags or Paint.STRIKE_THRU_TEXT_FLAG
            } else {
                binding.tvText.paintFlags and Paint.STRIKE_THRU_TEXT_FLAG.inv()
            }
            binding.cbDone.setOnCheckedChangeListener { _, checked ->
                onToggle(item, checked)
            }
            binding.btnDelete.setOnClickListener { onDelete(item) }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        ViewHolder(
            ItemListRowBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        )

    override fun onBindViewHolder(holder: ViewHolder, position: Int) =
        holder.bind(getItem(position))

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<ListItemEntity>() {
            override fun areItemsTheSame(a: ListItemEntity, b: ListItemEntity) = a.id == b.id
            override fun areContentsTheSame(a: ListItemEntity, b: ListItemEntity) = a == b
        }
    }
}
