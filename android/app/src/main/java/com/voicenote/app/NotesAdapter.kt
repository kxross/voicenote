package com.voicenote.app

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.voicenote.app.databinding.ItemNoteBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class NotesAdapter(
    private val onDelete: (NoteEntity) -> Unit,
    private val onImageClick: (Long) -> Unit = {},
) : ListAdapter<NoteWithGoal, NotesAdapter.ViewHolder>(DIFF) {

    inner class ViewHolder(private val binding: ItemNoteBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(entry: NoteWithGoal) {
            val note = entry.note
            binding.tvTranscript.text = note.transcript
            binding.tvTimestamp.text = formatTimestamp(note.createdAt)
            binding.tvDuration.text = if (note.durationSeconds > 0)
                formatDuration(note.durationSeconds) else ""
            if (entry.goalTitle != null) {
                binding.tvGoalTag.visibility = View.VISIBLE
                binding.tvGoalTag.text = entry.goalTitle
            } else {
                binding.tvGoalTag.visibility = View.GONE
            }
            if (entry.imageCount > 0 && entry.firstImagePath != null) {
                binding.ivNoteThumb.visibility = View.VISIBLE
                binding.ivNoteThumb.setImageURI(
                    android.net.Uri.fromFile(java.io.File(entry.firstImagePath))
                )
                binding.ivNoteThumb.setOnClickListener { onImageClick(note.id) }
                binding.tvImageCount.visibility =
                    if (entry.imageCount > 1) View.VISIBLE else View.GONE
                binding.tvImageCount.text = "+${entry.imageCount - 1}"
            } else {
                binding.ivNoteThumb.visibility = View.GONE
                binding.ivNoteThumb.setOnClickListener(null)
                binding.tvImageCount.visibility = View.GONE
            }
            binding.btnDelete.setOnClickListener { onDelete(note) }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        ViewHolder(
            ItemNoteBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        )

    override fun onBindViewHolder(holder: ViewHolder, position: Int) =
        holder.bind(getItem(position))

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<NoteWithGoal>() {
            override fun areItemsTheSame(a: NoteWithGoal, b: NoteWithGoal) =
                a.note.id == b.note.id
            override fun areContentsTheSame(a: NoteWithGoal, b: NoteWithGoal) = a == b
        }

        private val DATE_FMT = SimpleDateFormat("MMM d, h:mm a", Locale.getDefault())

        fun formatTimestamp(epochMs: Long): String =
            DATE_FMT.format(Date(epochMs))

        fun formatDuration(secs: Int): String {
            val m = secs / 60
            val s = secs % 60
            return if (m > 0) "${m}m ${s}s" else "${s}s"
        }
    }
}
