package com.voicenote.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import com.voicenote.app.databinding.ActivityListDetailBinding

class ListDetailActivity : AppCompatActivity() {

    private lateinit var binding: ActivityListDetailBinding
    private val viewModel: ListViewModel by viewModels()
    private lateinit var voiceCapture: VoiceCapture

    private var listId: Long = -1L

    private val requestMicPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) startRecording()
        else Toast.makeText(this, "Microphone permission required", Toast.LENGTH_LONG).show()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityListDetailBinding.inflate(layoutInflater)
        setContentView(binding.root)
        voiceCapture = VoiceCapture(this)

        listId = intent.getLongExtra(EXTRA_LIST_ID, -1L)
        if (listId < 0) {
            finish()
            return
        }

        binding.btnBack.setOnClickListener { finish() }

        val adapter = ListItemsAdapter(
            onToggle = { item, checked ->
                viewModel.setItemDone(item.id, checked)
                SnapshotSync.uploadSnapshotAsync(this)
            },
            onDelete = { item ->
                viewModel.deleteItem(item.id)
                SnapshotSync.uploadSnapshotAsync(this)
            },
        )

        binding.rvItems.layoutManager = LinearLayoutManager(this)
        binding.rvItems.adapter = adapter

        viewModel.listWithItems(listId).observe(this) { entry ->
            if (entry == null) {
                finish()
                return@observe
            }
            binding.tvTitle.text = entry.list.name.uppercase()
            adapter.submitList(entry.items)
            binding.tvEmpty.visibility = if (entry.items.isEmpty()) View.VISIBLE else View.GONE
        }

        // Tap to start, tap again to send — same interaction as the goal
        // screen's mic.
        binding.btnRecordForList.setOnClickListener {
            if (voiceCapture.isRecording) {
                stopRecording()
            } else if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED
            ) {
                startRecording()
            } else {
                requestMicPermission.launch(Manifest.permission.RECORD_AUDIO)
            }
        }
    }

    private fun startRecording() {
        if (voiceCapture.start()) {
            binding.btnRecordForList.setColorFilter(
                ContextCompat.getColor(this, R.color.accent)
            )
            binding.tvRecordingHint.text = "recording — tap to send"
        } else {
            Toast.makeText(this, "Could not start recording", Toast.LENGTH_SHORT).show()
        }
    }

    private fun stopRecording() {
        binding.btnRecordForList.clearColorFilter()
        binding.tvRecordingHint.text = "tap to ask about this list"
        val uploaded = voiceCapture.stopAndUpload(listId = listId)
        Toast.makeText(
            this,
            if (uploaded) "Uploading…" else "Too short — try again",
            Toast.LENGTH_SHORT,
        ).show()
    }

    override fun onDestroy() {
        super.onDestroy()
        voiceCapture.cancel(this)
    }

    companion object {
        const val EXTRA_LIST_ID = "list_id"
    }
}
