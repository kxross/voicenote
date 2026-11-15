package com.voicenote.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.voicenote.app.databinding.ActivitySettingsBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private lateinit var prefs: Prefs

    // The model picked in ModelPicker: (model, provider), saved to prefs on
    // Save. ("", "") = let the gateway use its configured default.
    private var pickedModelValue: Pair<String, String> = "" to ""

    private val requestSmsPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) startSmsSweep()
        else Toast.makeText(this, "SMS permission required to sweep", Toast.LENGTH_LONG).show()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        prefs = Prefs(this)

        binding.toolbar.apply {
            setNavigationIcon(androidx.appcompat.R.drawable.abc_ic_ab_back_material)
            setNavigationOnClickListener { finish() }
            title = "Settings"
            setTitleTextColor(0xFFFFFFFF.toInt())
            navigationIcon?.setTint(0xFFFFFFFF.toInt())
        }

        // Load saved values
        binding.etEndpointUrl.setText(prefs.endpointUrl)
        binding.etGatewayUrl.setText(prefs.gatewayBaseUrl)
        binding.etGatewayApiKey.setText(prefs.gatewayApiKey)
        binding.swShowActivity.isChecked = prefs.showActivity

        // Connection is set-once, so it's tucked behind a header — expanded
        // automatically only when nothing's configured yet (first run).
        val needsSetup = prefs.endpointUrl.isBlank() || prefs.gatewayBaseUrl.isBlank()
        setConnectionExpanded(needsSetup)
        binding.connectionHeader.setOnClickListener {
            setConnectionExpanded(binding.connectionCard.visibility != android.view.View.VISIBLE)
        }
        // Approval mode is live daemon state (via the gateway), not a local pref.
        lifecycleScope.launch {
            val mode = withContext(Dispatchers.IO) {
                GatewayAgent.getApprovalMode(prefs.gatewayBaseUrl, prefs.gatewayApiKey)
            }
            // Unknown (daemon unreachable) -> Smart, which fails closed anyway.
            binding.rgApproval.check(
                when (mode) {
                    "manual" -> R.id.rbApprovalManual
                    "auto" -> R.id.rbApprovalAuto
                    else -> R.id.rbApprovalSmart
                }
            )
        }
        binding.etAutoStop.setText(prefs.autoStopMinutes.toString())
        binding.etSmsLookback.setText(prefs.smsLookbackDays.toString())
        binding.etSmsList.setText(prefs.smsTodoList)

        // On-device dictation — only offer it when the platform can do it.
        if (OnDeviceTranscriber.isAvailable(this)) {
            binding.swOnDeviceStt.isChecked = prefs.onDeviceTranscription
        } else {
            binding.swOnDeviceStt.isChecked = false
            binding.swOnDeviceStt.isEnabled = false
            binding.tvOnDeviceSttCaption.text =
                "On-device dictation isn't available on this device — audio is sent to the endpoint."
        }

        setModelOptions(emptyList())   // "Gateway default" only, until fetched
        loadModels()

        binding.btnSave.setOnClickListener {
            val url = binding.etEndpointUrl.text.toString().trim()
            val gatewayUrl = binding.etGatewayUrl.text.toString().trim()
            val gatewayApiKey = binding.etGatewayApiKey.text.toString().trim()

            if (url.isNotBlank() && !url.startsWith("http")) {
                binding.etEndpointUrl.error = "URL must start with http:// or https://"
                return@setOnClickListener
            }

            if (gatewayUrl.isNotBlank() && !gatewayUrl.startsWith("http")) {
                binding.etGatewayUrl.error = "URL must start with http:// or https://"
                return@setOnClickListener
            }

            prefs.endpointUrl = url
            prefs.gatewayBaseUrl = gatewayUrl
            prefs.gatewayApiKey = gatewayApiKey
            prefs.showActivity = binding.swShowActivity.isChecked
            // Push approval mode to the daemon (uses the just-saved gateway URL).
            val approvalMode = when (binding.rgApproval.checkedRadioButtonId) {
                R.id.rbApprovalManual -> "manual"
                R.id.rbApprovalAuto -> "auto"
                else -> "smart"
            }
            lifecycleScope.launch(Dispatchers.IO) {
                GatewayAgent.setApprovalMode(prefs.gatewayBaseUrl, prefs.gatewayApiKey, approvalMode)
            }
            val (pickedModel, pickedProvider) = pickedModelValue
            prefs.assistantModel = pickedModel
            prefs.assistantProvider = pickedProvider
            if (binding.swOnDeviceStt.isEnabled) {
                prefs.onDeviceTranscription = binding.swOnDeviceStt.isChecked
            }
            binding.etAutoStop.text.toString().trim().toIntOrNull()
                ?.let { prefs.autoStopMinutes = it }
            binding.etSmsLookback.text.toString().trim().toIntOrNull()
                ?.let { prefs.smsLookbackDays = it }
            binding.etSmsList.text.toString().trim()
                .takeIf { it.isNotBlank() }?.let { prefs.smsTodoList = it }
            Toast.makeText(this, "Saved", Toast.LENGTH_SHORT).show()
            finish()
        }

        binding.btnSweepSms.setOnClickListener {
            if (prefs.gatewayBaseUrl.isBlank()) {
                Toast.makeText(this, "Set the gateway URL first", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            // Persist the current field values first, so a sweep uses what's
            // on screen rather than what was last saved.
            binding.etSmsLookback.text.toString().trim().toIntOrNull()
                ?.let { prefs.smsLookbackDays = it }
            binding.etSmsList.text.toString().trim()
                .takeIf { it.isNotBlank() }?.let { prefs.smsTodoList = it }

            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_SMS)
                == PackageManager.PERMISSION_GRANTED
            ) {
                startSmsSweep()
            } else {
                requestSmsPermission.launch(Manifest.permission.READ_SMS)
            }
        }

        binding.btnViewNotes.setOnClickListener {
            startActivity(android.content.Intent(this, NotesActivity::class.java))
        }

        binding.btnOpenRegistry.setOnClickListener {
            val base = binding.etGatewayUrl.text.toString().trim()
                .ifBlank { prefs.gatewayBaseUrl }
                .removeSuffix("/")
            if (base.isBlank() || !base.startsWith("http")) {
                Toast.makeText(this, "Set the gateway URL first", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val uri = android.net.Uri.parse("$base/admin")
            // Prefer Chrome; fall back to any browser.
            val chrome = android.content.Intent(android.content.Intent.ACTION_VIEW, uri)
                .setPackage("com.android.chrome")
            try {
                startActivity(chrome)
            } catch (e: android.content.ActivityNotFoundException) {
                try {
                    startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, uri))
                } catch (e2: android.content.ActivityNotFoundException) {
                    Toast.makeText(this, "No browser installed", Toast.LENGTH_SHORT).show()
                }
            }
        }

        binding.btnRunGoalMining.setOnClickListener {
            val base = prefs.gatewayBaseUrl
            if (base.isBlank()) {
                Toast.makeText(this, "Set the gateway URL first", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            binding.btnRunGoalMining.isEnabled = false
            Toast.makeText(this, "Mining…", Toast.LENGTH_SHORT).show()
            lifecycleScope.launch {
                val ok = withContext(Dispatchers.IO) {
                    // Fresh snapshot first so the server-side run sees current
                    // lists/notes/goals, then fire the schedule.
                    SnapshotSync.uploadSnapshot(applicationContext, base, prefs.gatewayApiKey)
                    GatewayAgent.runScheduleNow(base, prefs.gatewayApiKey, "nightly_goal_mining")
                }
                if (ok) {
                    // Delivery is queued by the time runScheduleNow returns —
                    // drain it immediately instead of waiting for the 15-min poll.
                    WorkManager.getInstance(this@SettingsActivity)
                        .enqueue(OneTimeWorkRequestBuilder<PollWorker>().build())
                    Toast.makeText(this@SettingsActivity, "Mining complete — polling", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this@SettingsActivity, "Mining failed — check gateway", Toast.LENGTH_LONG).show()
                }
                binding.btnRunGoalMining.isEnabled = true
            }
        }
    }

    private fun setConnectionExpanded(expanded: Boolean) {
        binding.connectionCard.visibility =
            if (expanded) android.view.View.VISIBLE else android.view.View.GONE
        binding.connectionChevron.text = if (expanded) "▾" else "▸"
    }

    /**
     * Enqueue the sweep and report its outcome. The work runs in WorkManager
     * (the agent turn can take minutes on a local model), so the button is
     * disabled until the worker finishes and its summary comes back.
     */
    private fun startSmsSweep() {
        binding.btnSweepSms.isEnabled = false
        Toast.makeText(this, "Sweeping messages…", Toast.LENGTH_SHORT).show()

        val request = SmsSweepWorker.buildRequest(prefs)
        val wm = WorkManager.getInstance(this)
        wm.enqueue(request)
        wm.getWorkInfoByIdLiveData(request.id).observe(this) { info ->
            when (info?.state) {
                WorkInfo.State.SUCCEEDED -> {
                    val summary = info.outputData.getString(SmsSweepWorker.KEY_SUMMARY)
                        ?: "Sweep complete"
                    Toast.makeText(this, summary, Toast.LENGTH_LONG).show()
                    binding.btnSweepSms.isEnabled = true
                }
                WorkInfo.State.FAILED, WorkInfo.State.CANCELLED -> {
                    Toast.makeText(this, "Sweep failed — check the gateway", Toast.LENGTH_LONG).show()
                    binding.btnSweepSms.isEnabled = true
                }
                else -> {}
            }
        }
    }

    /** Fetch the gateway's Ollama models and fill the spinner, off the main
     *  thread. The gateway URL comes from what's saved; save it first if the
     *  list is empty. */
    private fun loadModels() {
        lifecycleScope.launch {
            val models = withContext(Dispatchers.IO) {
                GatewayAgent.fetchAssistantModels(prefs.gatewayBaseUrl, prefs.gatewayApiKey)
            }
            setModelOptions(models)
        }
    }

    private fun setModelOptions(models: List<GatewayAgent.ModelInfo>) {
        // Only tool-capable models are offered (the picker filters) — the
        // assistant drives its skills via tool calls, so a no-tools model
        // can't run it. "Gateway default" is stored as ("", "").
        // Match on model+provider so the same slug on two providers is distinct;
        // fall back to a name-only match for picks saved before providers.
        val saved = prefs.assistantModel to prefs.assistantProvider
        if (pickedModelValue == ("" to "") && saved.first.isNotBlank()) {
            pickedModelValue = models.firstOrNull { it.name == saved.first && it.provider == saved.second }
                ?.let { it.name to it.provider }
                ?: models.firstOrNull { it.name == saved.first }?.let { it.name to it.provider }
                ?: saved
        }
        binding.tvModelPick.text = ModelPicker.summary(pickedModelValue, models)
        binding.tvModelPick.setOnClickListener {
            if (models.none { it.tools }) {
                Toast.makeText(this, "No models yet — save a gateway URL first", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            ModelPicker.show(this, models, pickedModelValue) { picked ->
                pickedModelValue = picked
                binding.tvModelPick.text = ModelPicker.summary(picked, models)
            }
        }
    }
}
