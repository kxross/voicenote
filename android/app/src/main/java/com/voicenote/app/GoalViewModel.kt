package com.voicenote.app

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.launch

class GoalViewModel(app: Application) : AndroidViewModel(app) {
    private val dao = NoteDatabase.get(app).goalDao()
    private val noteDao = NoteDatabase.get(app).noteDao()

    val allGoalsWithDetails = dao.allGoalsWithDetails()

    fun goalWithDetails(id: Long) = dao.goalWithDetails(id)

    fun notesForGoal(id: Long) = noteDao.notesForGoalLive(id)

    fun deleteGoal(id: Long) = viewModelScope.launch { dao.deleteGoalById(id) }

    fun setTaskDone(id: Long, done: Boolean) = viewModelScope.launch {
        dao.setTaskDoneById(id, done)
    }

    // Approval IS activation: one user action flips both the plan (draft ->
    // approved) and the goal (draft -> active).
    fun approvePlan(planId: Long, goalId: Long) = viewModelScope.launch {
        dao.updatePlanStatus(planId, "approved")
        dao.updateStatus(goalId, "active")
    }
}
