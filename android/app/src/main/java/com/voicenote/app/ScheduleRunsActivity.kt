package com.voicenote.app

import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.voicenote.app.databinding.ActivityScheduleRunsBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * One schedule's run history (its trace log, served by the gateway). Shows each
 * run's outcome, time, and reply preview; a paused run offers one-tap Resume,
 * which continues the checkpointed research and delivers into the schedule's
 * check-in thread.
 */
class ScheduleRunsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityScheduleRunsBinding
    private lateinit var scheduleId: String
    private lateinit var scheduleTitle: String

    private val adapter = ScheduleRunsAdapter(onResume = { run -> resume(run) })

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityScheduleRunsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        scheduleId = intent.getStringExtra(EXTRA_ID).orEmpty()
        scheduleTitle = intent.getStringExtra(EXTRA_TITLE).orEmpty().ifBlank { scheduleId }

        binding.tvHeader.text = scheduleTitle.uppercase()
        binding.btnBack.setOnClickListener { finish() }
        binding.rvRuns.layoutManager = LinearLayoutManager(this)
        binding.rvRuns.adapter = adapter
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        if (scheduleId.isBlank()) return
        val prefs = Prefs(this)
        lifecycleScope.launch {
            val runs = withContext(Dispatchers.IO) {
                GatewayAgent.listScheduleRuns(prefs.gatewayBaseUrl, prefs.gatewayApiKey, scheduleId)
            }
            adapter.submitList(runs)
            binding.tvEmpty.visibility = if (runs.isEmpty()) View.VISIBLE else View.GONE
        }
    }

    private fun resume(run: GatewayAgent.ScheduleRun) {
        val runId = run.resumeRunId ?: return
        val prefs = Prefs(this)
        if (prefs.gatewayBaseUrl.isBlank()) {
            Toast.makeText(this, "Set the gateway URL first", Toast.LENGTH_SHORT).show()
            return
        }
        Toast.makeText(this, "Resuming…", Toast.LENGTH_SHORT).show()
        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                GatewayAgent.resumeRun(
                    context = applicationContext,
                    gatewayBaseUrl = prefs.gatewayBaseUrl,
                    gatewayApiKey = prefs.gatewayApiKey,
                    runId = runId,
                    threadId = null,
                    notificationTitle = scheduleTitle,
                )
            }
            refresh()
        }
    }

    companion object {
        const val EXTRA_ID = "schedule_id"
        const val EXTRA_TITLE = "schedule_title"
    }
}
