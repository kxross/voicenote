package com.voicenote.app

import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.voicenote.app.databinding.ActivityArtifactsBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The Artifacts library — every long-form document the assistant has
 *  published, across all threads. Tap one to open the reader; long-press to
 *  share or delete it. */
class ArtifactsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityArtifactsBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityArtifactsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.btnBack.setOnClickListener { finish() }

        val adapter = ArtifactsAdapter(
            onOpen = { ArtifactReaderActivity.open(this, it.id) },
            onLongPress = { showActions(it) },
        )
        binding.list.layoutManager = LinearLayoutManager(this)
        binding.list.adapter = adapter

        NoteDatabase.get(applicationContext).artifactDao().allLive().observe(this) { items ->
            adapter.submitList(items)
            val empty = items.isNullOrEmpty()
            binding.tvEmpty.visibility = if (empty) View.VISIBLE else View.GONE
            binding.list.visibility = if (empty) View.GONE else View.VISIBLE
        }
    }

    private fun showActions(art: ArtifactEntity) {
        AlertDialog.Builder(this)
            .setTitle(art.title)
            .setItems(arrayOf("Share…", "Delete")) { _, which ->
                when (which) {
                    // Sharing exports the *rendered* page, so it goes via the reader.
                    0 -> ArtifactReaderActivity.openForShare(this, art.id)
                    1 -> confirmDelete(art)
                }
            }
            .show()
    }

    private fun confirmDelete(art: ArtifactEntity) {
        AlertDialog.Builder(this)
            .setTitle("Delete document?")
            .setMessage("“${art.title}” will be removed from your library and its thread. This can't be undone.")
            .setPositiveButton("Delete") { _, _ ->
                lifecycleScope.launch {
                    withContext(Dispatchers.IO) {
                        NoteDatabase.get(applicationContext).artifactDao().delete(art.id)
                    }
                    Toast.makeText(this@ArtifactsActivity, "Deleted", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}
