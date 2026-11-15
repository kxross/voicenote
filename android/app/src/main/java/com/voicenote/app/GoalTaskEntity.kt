package com.voicenote.app

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One checklist item of a plan. `parentTaskId` enables ONE level of
 * nesting: a top-level task may carry subtasks; subtasks may not nest
 * further (enforced at op-apply and by server-side tool guards).
 * Deleting a parent task cascades through its subtasks.
 */
@Entity(
    tableName = "goal_tasks",
    foreignKeys = [
        ForeignKey(
            entity = PlanEntity::class,
            parentColumns = ["id"],
            childColumns = ["planId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = GoalTaskEntity::class,
            parentColumns = ["id"],
            childColumns = ["parentTaskId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("planId"), Index("parentTaskId")],
)
data class GoalTaskEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val planId: Long,
    val parentTaskId: Long? = null,
    val text: String,
    val milestone: String = "",
    val dueHint: String = "",
    val done: Boolean = false,
    val orderIndex: Int = 0,
    val createdAt: Long = System.currentTimeMillis(),
)
