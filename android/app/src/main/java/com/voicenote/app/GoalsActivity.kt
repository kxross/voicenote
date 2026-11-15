package com.voicenote.app

import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import com.voicenote.app.databinding.ActivityGoalsBinding

class GoalsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityGoalsBinding
    private val viewModel: GoalViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityGoalsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnBack.setOnClickListener { finish() }

        val adapter = GoalsAdapter(
            onOpen = { entry ->
                startActivity(
                    Intent(this, GoalDetailActivity::class.java)
                        .putExtra(GoalDetailActivity.EXTRA_GOAL_ID, entry.goal.id)
                )
            },
            onDelete = { entry ->
                AlertDialog.Builder(this)
                    .setMessage("Delete goal \"${entry.goal.title}\" and its plan?")
                    .setPositiveButton("Delete") { _, _ ->
                        viewModel.deleteGoal(entry.goal.id)
                        SnapshotSync.uploadSnapshotAsync(this)
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            },
        )

        binding.rvGoals.layoutManager = LinearLayoutManager(this)
        binding.rvGoals.adapter = adapter

        viewModel.allGoalsWithDetails.observe(this) { goals ->
            adapter.submitList(goals)
            binding.tvEmpty.visibility = if (goals.isEmpty()) View.VISIBLE else View.GONE
        }
    }
}
