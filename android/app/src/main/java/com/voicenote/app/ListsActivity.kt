package com.voicenote.app

import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import com.voicenote.app.databinding.ActivityListsBinding

class ListsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityListsBinding
    private val viewModel: ListViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityListsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnBack.setOnClickListener { finish() }

        val adapter = ListsAdapter(
            onOpen = { entry ->
                startActivity(
                    Intent(this, ListDetailActivity::class.java)
                        .putExtra(ListDetailActivity.EXTRA_LIST_ID, entry.list.id)
                )
            },
            onDelete = { entry ->
                AlertDialog.Builder(this)
                    .setMessage("Delete \"${entry.list.name}\" and all its items?")
                    .setPositiveButton("Delete") { _, _ ->
                        viewModel.deleteList(entry.list.id)
                        SnapshotSync.uploadSnapshotAsync(this)
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            },
        )

        binding.rvLists.layoutManager = LinearLayoutManager(this)
        binding.rvLists.adapter = adapter

        viewModel.allListsWithItems.observe(this) { lists ->
            adapter.submitList(lists)
            binding.tvEmpty.visibility = if (lists.isEmpty()) View.VISIBLE else View.GONE
        }
    }
}
