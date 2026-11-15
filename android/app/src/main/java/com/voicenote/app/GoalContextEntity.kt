package com.voicenote.app

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A context feature attached to a goal. `source` is "note" (mined from a
 * voice note), "agent" (the agent's own open questions, text prefixed
 * "Q:"), or "user" (a direct answer).
 */
@Entity(
    tableName = "goal_context",
    foreignKeys = [
        ForeignKey(
            entity = GoalEntity::class,
            parentColumns = ["id"],
            childColumns = ["goalId"],
            onDelete = ForeignKey.CASCADE,
        )
    ],
    indices = [Index("goalId")],
)
data class GoalContextEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val goalId: Long,
    val text: String,
    val source: String,
    val createdAt: Long = System.currentTimeMillis(),
)
