package com.voicenote.app

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.voicenote.app.databinding.ItemArtifactLibraryBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** The Artifacts library list: every published document, newest first. */
class ArtifactsAdapter(
    private val onOpen: (ArtifactEntity) -> Unit,
    private val onLongPress: (ArtifactEntity) -> Unit = {},
) : ListAdapter<ArtifactEntity, ArtifactsAdapter.VH>(DIFF) {

    inner class VH(private val binding: ItemArtifactLibraryBinding) :
        RecyclerView.ViewHolder(binding.root) {
        fun bind(a: ArtifactEntity) {
            binding.tvKind.text = "▤ ${a.kind.uppercase()}${a.revisionLabel}${SourcesCard.countLabel(a.sourcesJson)}"
            binding.tvTitle.text = a.title
            binding.tvDate.text = DATE_FMT.format(Date(a.lastChanged))
            binding.tvPreview.text = ThreadDetailAdapter.mdPreview(a.text)
            binding.root.setOnClickListener { onOpen(a) }
            binding.root.setOnLongClickListener { onLongPress(a); true }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(ItemArtifactLibraryBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(getItem(position))

    companion object {
        private val DATE_FMT = SimpleDateFormat("MMM d", Locale.getDefault())

        private val DIFF = object : DiffUtil.ItemCallback<ArtifactEntity>() {
            override fun areItemsTheSame(a: ArtifactEntity, b: ArtifactEntity) = a.id == b.id
            override fun areContentsTheSame(a: ArtifactEntity, b: ArtifactEntity) = a == b
        }
    }
}
