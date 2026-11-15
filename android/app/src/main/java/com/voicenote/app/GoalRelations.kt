package com.voicenote.app

import androidx.room.Embedded
import androidx.room.Relation

data class PlanWithTasks(
    @Embedded val plan: PlanEntity,
    @Relation(parentColumn = "id", entityColumn = "planId")
    val tasks: List<GoalTaskEntity>,
)

data class GoalWithDetails(
    @Embedded val goal: GoalEntity,
    @Relation(parentColumn = "id", entityColumn = "goalId")
    val context: List<GoalContextEntity>,
    @Relation(
        parentColumn = "id",
        entityColumn = "goalId",
        entity = PlanEntity::class,
    )
    val plan: PlanWithTasks?,
)
