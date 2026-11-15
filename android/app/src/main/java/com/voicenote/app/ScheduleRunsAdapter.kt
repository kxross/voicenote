package com.voicenote.app

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.voicenote.app.databinding.ItemScheduleRunBinding
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** One schedule's run history. Each row shows the run's outcome, when it ran,
 *  and a one-line reply preview; a paused run offers a Resume button. */
class ScheduleRunsAdapter(
    private val onResume: (GatewayAgent.ScheduleRun) -> Unit,
) : ListAdapter<GatewayAgent.ScheduleRun, ScheduleRunsAdapter.VH>(DIFF) {

    inner class VH(private val binding: ItemScheduleRunBinding) :
        RecyclerView.ViewHolder(binding.root) {
        fun bind(run: GatewayAgent.ScheduleRun) {
            binding.tvStatus.text =
                "${SchedulesAdapter.statusGlyph(run.status)} ${SchedulesAdapter.statusLabel(run.status)}"
            binding.tvStatus.setTextColor(SchedulesAdapter.statusColor(run.status))
            binding.tvWhen.text = formatWhen(run.at)

            val secs = run.durationMs / 1000.0
            val dur = if (secs >= 1) "%.0fs".format(secs) else "${run.durationMs}ms"
            binding.tvMeta.text = "${run.calls} call${if (run.calls == 1) "" else "s"}  ·  $dur"

            val preview = run.replyPreview.trim()
            binding.tvPreview.visibility = if (preview.isBlank()) View.GONE else View.VISIBLE
            binding.tvPreview.text = preview

            val canResume = run.status == "paused" && !run.resumeRunId.isNullOrBlank()
            binding.btnResume.visibility = if (canResume) View.VISIBLE else View.GONE
            binding.btnResume.setOnClickListener { if (canResume) onResume(run) }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        VH(ItemScheduleRunBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(getItem(position))

    companion object {
        private val WHEN_FMT = DateTimeFormatter.ofPattern("MMM d, h:mm a", Locale.getDefault())

        fun formatWhen(epochSeconds: Double): String {
            if (epochSeconds <= 0) return ""
            return try {
                Instant.ofEpochMilli((epochSeconds * 1000).toLong())
                    .atZone(ZoneId.systemDefault())
                    .format(WHEN_FMT)
            } catch (e: Exception) {
                ""
            }
        }

        private val DIFF = object : DiffUtil.ItemCallback<GatewayAgent.ScheduleRun>() {
            override fun areItemsTheSame(a: GatewayAgent.ScheduleRun, b: GatewayAgent.ScheduleRun) =
                a.id == b.id
            override fun areContentsTheSame(a: GatewayAgent.ScheduleRun, b: GatewayAgent.ScheduleRun) =
                a == b
        }
    }
}
