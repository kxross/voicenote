package com.voicenote.app

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Lifecycle: draft -> active (via plan approval — one transition) ->
 * achieved | abandoned. `wellSpecified` is the agent's vetting gate.
 *
 * Goals are FLAT — decomposition happens inside the plan via nested
 * tasks (see GoalTaskEntity.parentTaskId), not by nesting goals.
 */
@Entity(
    tableName = "goals",
    indices = [Index(value = ["title"], unique = true)],
)
data class GoalEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val statement: String = "",
    val status: String = "draft",
    val wellSpecified: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
)
