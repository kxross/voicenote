package com.voicenote.app

import android.app.TimePickerDialog
import android.content.res.ColorStateList
import android.os.Bundle
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.lifecycleScope
import com.voicenote.app.CronBuilder.Freq
import com.voicenote.app.databinding.ActivityScheduleEditBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Create or edit one schedule. The "when" is built with a friendly UI and
 *  translated to cron; raw cron is only shown for a Custom schedule. */
class ScheduleEditActivity : AppCompatActivity() {

    private lateinit var binding: ActivityScheduleEditBinding
    private lateinit var prefs: Prefs

    private var scheduleId: String = ""
    private var existingTz: String = ""
    private var existingEnabled: Boolean = true

    // Schedule builder state.
    private var freq = Freq.DAILY
    private var hour = 8
    private var minute = 0
    private val days = sortedSetOf<Int>()
    private var dom = 1
    private val dayChips = mutableListOf<TextView>()

    private val freqLabels = listOf("Hourly", "Daily", "Weekly", "Monthly", "Custom")
    private val freqValues = listOf(Freq.HOURLY, Freq.DAILY, Freq.WEEKLY, Freq.MONTHLY, Freq.CUSTOM)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityScheduleEditBinding.inflate(layoutInflater)
        setContentView(binding.root)
        prefs = Prefs(this)

        scheduleId = intent.getStringExtra(EXTRA_ID) ?: ""
        existingTz = intent.getStringExtra(EXTRA_TZ) ?: ""
        existingEnabled = intent.getBooleanExtra(EXTRA_ENABLED, true)
        val editing = scheduleId.isNotBlank()

        binding.tvScreenTitle.text = if (editing) "EDIT SCHEDULE" else "NEW SCHEDULE"
        binding.btnBack.setOnClickListener { finish() }
        binding.etTitle.setText(intent.getStringExtra(EXTRA_TITLE).orEmpty())
        binding.etPrompt.setText(intent.getStringExtra(EXTRA_PROMPT).orEmpty())
        binding.swFollowTz.isChecked = (intent.getStringExtra(EXTRA_TZ_MODE) ?: "fixed") == "device"

        intent.getStringExtra(EXTRA_NEXT)?.takeIf { it.isNotBlank() && editing }?.let {
            binding.tvNextRun.visibility = View.VISIBLE
            binding.tvNextRun.text = "Next run: ${SchedulesAdapter.formatNext(it)}"
        }

        // Parse the incoming cron (or default) into the builder state.
        val spec = CronBuilder.parse(intent.getStringExtra(EXTRA_CRON) ?: "0 8 * * *")
        freq = spec.freq; hour = spec.hour; minute = spec.minute; dom = spec.dom
        days.clear(); days.addAll(spec.days)
        if (spec.freq == Freq.CUSTOM) binding.etCron.setText(spec.raw)

        setupFreq()
        setupDom()
        setupDays()
        binding.btnTime.setOnClickListener { pickTime() }
        binding.etCron.doAfterTextChanged { if (freq == Freq.CUSTOM) updatePreview() }
        render()

        loadAgents(intent.getStringExtra(EXTRA_MODEL).orEmpty())
        binding.btnSave.setOnClickListener { save() }
        if (editing) {
            binding.btnRunNow.visibility = View.VISIBLE
            binding.btnDelete.visibility = View.VISIBLE
            binding.btnRunNow.setOnClickListener { runNow() }
            binding.btnDelete.setOnClickListener { confirmDelete() }
        }
    }

    private fun setupFreq() {
        val a = ArrayAdapter(this, R.layout.spinner_item, freqLabels)
        a.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        binding.spFreq.adapter = a
        binding.spFreq.setSelection(freqValues.indexOf(freq).coerceAtLeast(0))
        binding.spFreq.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                freq = freqValues[pos]; render()
            }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
    }

    private fun setupDom() {
        val a = ArrayAdapter(this, R.layout.spinner_item, (1..31).map { "Day $it" })
        a.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        binding.spDom.adapter = a
        binding.spDom.setSelection((dom - 1).coerceIn(0, 30))
        binding.spDom.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                dom = pos + 1; updatePreview()
            }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
    }

    private fun setupDays() {
        val d = resources.displayMetrics.density
        dayChips.clear()
        binding.dayRow.removeAllViews()
        for (i in 0..6) {
            val chip = TextView(this).apply {
                text = CronBuilder.DAY_LABELS[i].take(2)
                textSize = 12f
                gravity = android.view.Gravity.CENTER
                setPadding((10 * d).toInt(), (7 * d).toInt(), (10 * d).toInt(), (7 * d).toInt())
                setBackgroundResource(R.drawable.pill_background)
                val lp = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                lp.marginEnd = (6 * d).toInt()
                layoutParams = lp
                setOnClickListener {
                    if (i in days) days.remove(i) else days.add(i)
                    styleChip(this, i in days); updatePreview()
                }
            }
            styleChip(chip, i in days)
            dayChips.add(chip)
            binding.dayRow.addView(chip)
        }
    }

    private fun styleChip(chip: TextView, selected: Boolean) {
        chip.setTextColor(if (selected) 0xFFFFFFFF.toInt() else 0xFF999999.toInt())
        chip.backgroundTintList = if (selected)
            ColorStateList.valueOf(ContextCompat.getColor(this, R.color.accent)) else null
    }

    private fun pickTime() {
        TimePickerDialog(this, { _, h, m -> hour = h; minute = m; render() },
            hour, minute, false).show()
    }

    private fun render() {
        binding.btnTime.text = if (freq == Freq.HOURLY)
            "at :%02d past the hour".format(minute) else CronBuilder.timeLabel(hour, minute)
        binding.btnTime.visibility = if (freq != Freq.CUSTOM) View.VISIBLE else View.GONE
        binding.dayRow.visibility = if (freq == Freq.WEEKLY) View.VISIBLE else View.GONE
        binding.spDom.visibility = if (freq == Freq.MONTHLY) View.VISIBLE else View.GONE
        binding.etCron.visibility = if (freq == Freq.CUSTOM) View.VISIBLE else View.GONE
        updatePreview()
    }

    private fun currentSpec() = CronBuilder.Spec(
        freq, minute, hour, days.toSet(), dom, binding.etCron.text.toString())

    private fun updatePreview() {
        val s = currentSpec()
        binding.tvPreview.text = "${CronBuilder.describe(s)}   ·   ${CronBuilder.toCron(s)}"
    }

    private fun loadAgents(current: String) {
        lifecycleScope.launch {
            val fetched = withContext(Dispatchers.IO) {
                GatewayAgent.fetchAgents(prefs.gatewayBaseUrl, prefs.gatewayApiKey)
            }
            val agents = fetched.ifEmpty { listOf("assistant", "list_manager", "test_model") }
            val a = ArrayAdapter(this@ScheduleEditActivity, R.layout.spinner_item, agents)
            a.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            binding.spModel.adapter = a
            binding.spModel.setSelection(agents.indexOf(current.ifBlank { "assistant" }).coerceAtLeast(0))
        }
    }

    private fun save() {
        val title = binding.etTitle.text.toString().trim()
        val prompt = binding.etPrompt.text.toString().trim()
        if (prompt.isBlank()) {
            Toast.makeText(this, "A prompt is required", Toast.LENGTH_SHORT).show(); return
        }
        if (freq == Freq.WEEKLY && days.isEmpty()) {
            Toast.makeText(this, "Pick at least one day", Toast.LENGTH_SHORT).show(); return
        }
        val cron = CronBuilder.toCron(currentSpec())
        if (cron.isBlank()) {
            Toast.makeText(this, "Set a schedule time", Toast.LENGTH_SHORT).show(); return
        }
        val model = binding.spModel.selectedItem as? String ?: "assistant"
        val follow = binding.swFollowTz.isChecked
        val deviceTz = java.util.TimeZone.getDefault().id
        val timezone = if (follow) deviceTz else existingTz.ifBlank { deviceTz }

        val s = GatewayAgent.ScheduleInfo(
            id = scheduleId, cron = cron, model = model, prompt = prompt,
            notificationTitle = title, enabled = existingEnabled,
            timezone = timezone, tzMode = if (follow) "device" else "fixed", nextRunTime = null,
        )
        lifecycleScope.launch {
            var err: String? = null
            val saved = withContext(Dispatchers.IO) {
                GatewayAgent.saveSchedule(prefs.gatewayBaseUrl, prefs.gatewayApiKey, s) { err = it }
            }
            if (saved != null) finish()
            else Toast.makeText(this@ScheduleEditActivity, err ?: "Save failed", Toast.LENGTH_LONG).show()
        }
    }

    private fun runNow() {
        lifecycleScope.launch {
            val ok = withContext(Dispatchers.IO) {
                GatewayAgent.runScheduleNow(prefs.gatewayBaseUrl, prefs.gatewayApiKey, scheduleId)
            }
            Toast.makeText(this@ScheduleEditActivity,
                if (ok) "Running…" else "Couldn't run", Toast.LENGTH_SHORT).show()
        }
    }

    private fun confirmDelete() {
        AlertDialog.Builder(this)
            .setMessage("Delete this schedule?")
            .setPositiveButton("Delete") { _, _ ->
                lifecycleScope.launch {
                    val ok = withContext(Dispatchers.IO) {
                        GatewayAgent.deleteSchedule(prefs.gatewayBaseUrl, prefs.gatewayApiKey, scheduleId)
                    }
                    if (ok) finish()
                    else Toast.makeText(this@ScheduleEditActivity, "Delete failed", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    companion object {
        const val EXTRA_ID = "id"
        const val EXTRA_CRON = "cron"
        const val EXTRA_MODEL = "model"
        const val EXTRA_PROMPT = "prompt"
        const val EXTRA_TITLE = "title"
        const val EXTRA_ENABLED = "enabled"
        const val EXTRA_TZ = "tz"
        const val EXTRA_TZ_MODE = "tz_mode"
        const val EXTRA_NEXT = "next"
    }
}
