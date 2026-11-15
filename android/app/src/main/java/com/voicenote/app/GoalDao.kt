package com.voicenote.app

import androidx.lifecycle.LiveData
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction

@Dao
interface GoalDao {

    // ---- reads --------------------------------------------------------------

    @Transaction
    @Query("SELECT * FROM goals ORDER BY createdAt DESC")
    fun allGoalsWithDetails(): LiveData<List<GoalWithDetails>>

    @Transaction
    @Query("SELECT * FROM goals WHERE id = :id")
    fun goalWithDetails(id: Long): LiveData<GoalWithDetails?>

    @Transaction
    @Query("SELECT * FROM goals ORDER BY createdAt DESC")
    suspend fun snapshot(): List<GoalWithDetails>

    @Query("SELECT id FROM goals WHERE title = :title COLLATE NOCASE LIMIT 1")
    suspend fun findGoalIdByTitle(title: String): Long?

    @Query("SELECT * FROM goals WHERE id = :id LIMIT 1")
    suspend fun findGoalById(id: Long): GoalEntity?

    @Query("SELECT * FROM plans WHERE goalId = :goalId LIMIT 1")
    suspend fun findPlanByGoalId(goalId: Long): PlanEntity?

    @Query("SELECT id FROM goal_tasks WHERE planId = :planId AND text = :text COLLATE NOCASE LIMIT 1")
    suspend fun findTaskId(planId: Long, text: String): Long?

    @Query("SELECT id FROM goal_tasks WHERE planId = :planId AND parentTaskId IS NULL AND text = :text COLLATE NOCASE LIMIT 1")
    suspend fun findTopLevelTaskId(planId: Long, text: String): Long?

    // For fuzzy fallback matching when the agent paraphrases a title/task
    // instead of quoting the stored text exactly.
    @Query("SELECT * FROM goals")
    suspend fun allGoals(): List<GoalEntity>

    @Query("SELECT * FROM goal_tasks WHERE planId = :planId ORDER BY orderIndex")
    suspend fun tasksForPlan(planId: Long): List<GoalTaskEntity>

    // ---- goal writes --------------------------------------------------------

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertGoal(goal: GoalEntity): Long

    @Query("UPDATE goals SET statement = :statement, updatedAt = :now WHERE id = :id")
    suspend fun updateStatement(id: Long, statement: String, now: Long = System.currentTimeMillis())

    @Query("UPDATE goals SET status = :status, updatedAt = :now WHERE id = :id")
    suspend fun updateStatus(id: Long, status: String, now: Long = System.currentTimeMillis())

    @Query("UPDATE goals SET wellSpecified = :wellSpecified, updatedAt = :now WHERE id = :id")
    suspend fun updateWellSpecified(id: Long, wellSpecified: Boolean, now: Long = System.currentTimeMillis())

    // ---- edit-mode mutators -------------------------------------------------

    @Query("UPDATE goals SET title = :title, updatedAt = :now WHERE id = :id")
    suspend fun updateTitle(id: Long, title: String, now: Long = System.currentTimeMillis())

    @Query("UPDATE plans SET summary = :summary, updatedAt = :now WHERE id = :id")
    suspend fun updatePlanSummary(id: Long, summary: String, now: Long = System.currentTimeMillis())

    @Query("DELETE FROM goal_tasks WHERE planId = :planId")
    suspend fun deleteTasksForPlan(planId: Long)

    @Query("DELETE FROM goal_context WHERE goalId = :goalId")
    suspend fun deleteContextForGoal(goalId: Long)

    @Query("DELETE FROM goals WHERE id = :id")
    suspend fun deleteGoalById(id: Long)

    // ---- context writes -----------------------------------------------------

    @Insert
    suspend fun insertContext(context: GoalContextEntity): Long

    // ---- plan/task writes ---------------------------------------------------

    @Insert
    suspend fun insertPlan(plan: PlanEntity): Long

    @Query("DELETE FROM plans WHERE goalId = :goalId")
    suspend fun deletePlanByGoalId(goalId: Long)

    @Query("UPDATE plans SET status = :status, updatedAt = :now WHERE id = :id")
    suspend fun updatePlanStatus(id: Long, status: String, now: Long = System.currentTimeMillis())

    @Insert
    suspend fun insertTask(task: GoalTaskEntity): Long

    @Query("UPDATE goal_tasks SET done = :done WHERE id = :id")
    suspend fun setTaskDoneById(id: Long, done: Boolean)

    @Query("DELETE FROM goal_tasks WHERE id = :id")
    suspend fun deleteTaskById(id: Long)
}
