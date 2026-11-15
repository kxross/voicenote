package com.voicenote.app

import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.voicenote.app.databinding.ItemScheduleBinding
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

class SchedulesAdapter(
    private val onOpenHistory: (GatewayAgent.ScheduleInfo) -> Unit,
    private val onEdit: (GatewayAgent.ScheduleInfo) -> Unit,
    private val onToggle: (GatewayAgent.ScheduleInfo, Boolean) -> Unit,
) : ListAdapter<GatewayAgent.ScheduleInfo, SchedulesAdapter.VH>(DIFF) {

    inner class VH(private val binding: ItemScheduleBinding) :
        RecyclerView.ViewHolder(binding.root) {
        fun bind(s: GatewayAgent.ScheduleInfo) {
            binding.tvTitle.text = s.notificationTitle.ifBlank { s.id }
            val zone = when (s.tzMode) {
                "device" -> "device tz"
                else -> s.timezone.ifBlank { "server tz" }
            }
            binding.tvSub.text = "${s.cron}  ·  $zone"
            binding.tvNext.text = if (!s.enabled) "disabled" else "next: ${formatNext(s.nextRunTime)}"

            // Last-run badge: icon + color by outcome, with a relative time.
            val status = s.lastRunStatus
            if (status == null) {
                binding.tvStatus.visibility = View.GONE
            } else {
                binding.tvStatus.visibility = View.VISIBLE
                binding.tvStatus.text = "${statusGlyph(status)} ${statusLabel(status)}${agoSuffix(s.lastRunAt)}"
                binding.tvStatus.setTextColor(statusColor(status))
            }

            // Clear the listener before setting checked to avoid a feedback loop.
            binding.swEnabled.setOnCheckedChangeListener(null)
            binding.swEnabled.isChecked = s.enabled
            binding.swEnabled.setOnCheckedChangeListener { _, isChecked ->
                if (isChecked != s.enabled) onToggle(s, isChecked)
            }
            // Card opens run history; the pencil opens the editor.
            binding.card.setOnClickListener { onOpenHistory(s) }
            binding.btnEdit.setOnClickListener { onEdit(s) }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        VH(ItemScheduleBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(getItem(position))

    companion object {
        private val NEXT_FMT = DateTimeFormatter.ofPattern("EEE MMM d, h:mm a", Locale.getDefault())

        // --- last-run status badge ---------------------------------------
        fun statusGlyph(status: String): String = when (status) {
            "ok" -> "✓"
            "paused" -> "⏸"
            else -> "✕"   // error / timeout / anything unexpected
        }

        fun statusLabel(status: String): String = when (status) {
            "ok" -> "ran ok"
            "paused" -> "paused — tap to resume"
            "error" -> "failed"
            "timeout" -> "timed out"
            else -> status
        }

        fun statusColor(status: String): Int = when (status) {
            "ok" -> Color.parseColor("#30D158")      // success_ring
            "paused" -> Color.parseColor("#FF9500")  // uploading_ring (amber)
            else -> Color.parseColor("#FF3B30")      // accent (red)
        }

        /** " · 2h ago" from an epoch-seconds timestamp; "" when unknown. */
        fun agoSuffix(epochSeconds: Double?): String {
            if (epochSeconds == null || epochSeconds <= 0) return ""
            val deltaMs = System.currentTimeMillis() - (epochSeconds * 1000).toLong()
            if (deltaMs < 0) return ""
            val mins = deltaMs / 60_000
            val rel = when {
                mins < 1 -> "just now"
                mins < 60 -> "${mins}m ago"
                mins < 1440 -> "${mins / 60}h ago"
                else -> "${mins / 1440}d ago"
            }
            return " · $rel"
        }

        /** Render the server-computed ISO next-run in the device's timezone. */
        fun formatNext(iso: String?): String {
            if (iso.isNullOrBlank()) return "—"
            return try {
                OffsetDateTime.parse(iso)
                    .atZoneSameInstant(ZoneId.systemDefault())
                    .format(NEXT_FMT)
            } catch (e: Exception) {
                iso
            }
        }

        private val DIFF = object : DiffUtil.ItemCallback<GatewayAgent.ScheduleInfo>() {
            override fun areItemsTheSame(a: GatewayAgent.ScheduleInfo, b: GatewayAgent.ScheduleInfo) =
                a.id == b.id
            override fun areContentsTheSame(a: GatewayAgent.ScheduleInfo, b: GatewayAgent.ScheduleInfo) =
                a == b
        }
    }
}
