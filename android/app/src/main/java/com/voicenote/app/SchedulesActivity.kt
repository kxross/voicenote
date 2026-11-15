package com.voicenote.app

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.voicenote.app.databinding.ActivitySchedulesBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The schedules inbox: lists server-owned schedules (source of truth is the
 * gateway DB). Tap a row to edit, toggle to enable/disable, "+ New" to create.
 * Also reports the device timezone so device-mode schedules track travel.
 */
class SchedulesActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySchedulesBinding
    private val adapter = SchedulesAdapter(
        onOpenHistory = { s -> openHistory(s) },
        onEdit = { s -> openEditor(s) },
        onToggle = { s, enabled -> toggle(s, enabled) },
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySchedulesBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnBack.setOnClickListener { finish() }
        binding.btnNew.setOnClickListener { openEditor(null) }
        binding.rvSchedules.layoutManager = LinearLayoutManager(this)
        binding.rvSchedules.adapter = adapter
    }

    override fun onResume() {
        super.onResume()
        reportDeviceTz()
        refresh()
    }

    private fun refresh() {
        val prefs = Prefs(this)
        lifecycleScope.launch {
            val list = withContext(Dispatchers.IO) {
                GatewayAgent.listSchedules(prefs.gatewayBaseUrl, prefs.gatewayApiKey)
            }
            adapter.submitList(list)
            binding.tvEmpty.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
        }
    }

    private fun toggle(s: GatewayAgent.ScheduleInfo, enabled: Boolean) {
        val prefs = Prefs(this)
        lifecycleScope.launch {
            val ok = withContext(Dispatchers.IO) {
                GatewayAgent.saveSchedule(prefs.gatewayBaseUrl, prefs.gatewayApiKey,
                    s.copy(enabled = enabled)) != null
            }
            if (!ok) Toast.makeText(this@SchedulesActivity, "Couldn't update", Toast.LENGTH_SHORT).show()
            refresh()
        }
    }

    private fun openHistory(s: GatewayAgent.ScheduleInfo) {
        val i = Intent(this, ScheduleRunsActivity::class.java)
        i.putExtra(ScheduleRunsActivity.EXTRA_ID, s.id)
        i.putExtra(ScheduleRunsActivity.EXTRA_TITLE, s.notificationTitle.ifBlank { s.id })
        startActivity(i)
    }

    private fun openEditor(s: GatewayAgent.ScheduleInfo?) {
        val i = Intent(this, ScheduleEditActivity::class.java)
        if (s != null) {
            i.putExtra(ScheduleEditActivity.EXTRA_ID, s.id)
            i.putExtra(ScheduleEditActivity.EXTRA_CRON, s.cron)
            i.putExtra(ScheduleEditActivity.EXTRA_MODEL, s.model)
            i.putExtra(ScheduleEditActivity.EXTRA_PROMPT, s.prompt)
            i.putExtra(ScheduleEditActivity.EXTRA_TITLE, s.notificationTitle)
            i.putExtra(ScheduleEditActivity.EXTRA_ENABLED, s.enabled)
            i.putExtra(ScheduleEditActivity.EXTRA_TZ, s.timezone)
            i.putExtra(ScheduleEditActivity.EXTRA_TZ_MODE, s.tzMode)
            i.putExtra(ScheduleEditActivity.EXTRA_NEXT, s.nextRunTime)
        }
        startActivity(i)
    }

    private fun reportDeviceTz() {
        val prefs = Prefs(this)
        if (prefs.gatewayBaseUrl.isBlank()) return
        val tz = java.util.TimeZone.getDefault().id
        lifecycleScope.launch(Dispatchers.IO) {
            GatewayAgent.setDeviceTimezone(prefs.gatewayBaseUrl, prefs.gatewayApiKey, tz)
        }
    }
}
