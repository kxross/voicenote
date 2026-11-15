package com.voicenote.app

import android.os.Bundle
import android.view.View
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.voicenote.app.databinding.ActivityNotesBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class NotesActivity : AppCompatActivity() {

    private lateinit var binding: ActivityNotesBinding
    private val viewModel: NoteViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityNotesBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnBack.setOnClickListener { finish() }

        val adapter = NotesAdapter(
            onDelete = { note ->
                AlertDialog.Builder(this)
                    .setMessage("Delete this note?")
                    .setPositiveButton("Delete") { _, _ -> viewModel.delete(note) }
                    .setNegativeButton("Cancel", null)
                    .show()
            },
            onImageClick = { noteId -> openImages(noteId) },
        )

        binding.rvNotes.layoutManager = LinearLayoutManager(this)
        binding.rvNotes.adapter = adapter

        viewModel.notesWithGoal.observe(this) { notes ->
            adapter.submitList(notes)
            binding.tvEmpty.visibility = if (notes.isEmpty()) View.VISIBLE else View.GONE
        }
    }

    private fun openImages(noteId: Long) {
        lifecycleScope.launch {
            val paths = withContext(Dispatchers.IO) {
                NoteDatabase.get(this@NotesActivity).noteImageDao()
                    .forNote(noteId).map { it.localPath }
            }
            if (paths.isNotEmpty()) ImageViewerActivity.open(this@NotesActivity, paths)
        }
    }
}
