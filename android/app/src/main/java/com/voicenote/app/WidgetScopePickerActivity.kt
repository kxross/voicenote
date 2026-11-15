package com.voicenote.app

import android.appwidget.AppWidgetManager
import android.os.Bundle
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Lets a placed widget change what it's scoped to, in place.
 *
 * RemoteViews can't host a spinner, so "switchable in place" is implemented as
 * a dialog: the widget's scope chip opens this, you pick a list or goal (or
 * Unscoped), and the widget redraws. The choice is stored per-appWidgetId, so
 * several widgets can target different things.
 */
class WidgetScopePickerActivity : AppCompatActivity() {

    private data class Choice(val label: String, val goalId: Long, val listId: Long)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val widgetId = intent.getIntExtra(
            AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID
        )
        if (widgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
            finish()
            return
        }

        lifecycleScope.launch {
            val choices = withContext(Dispatchers.IO) { loadChoices() }
            showPicker(widgetId, choices)
        }
    }

    private suspend fun loadChoices(): List<Choice> {
        val db = NoteDatabase.get(applicationContext)
        val out = mutableListOf(Choice("Unscoped", -1L, -1L))
        db.listDao().snapshot().forEach {
            out.add(Choice("List · ${it.list.name}", -1L, it.list.id))
        }
        db.goalDao().snapshot().forEach {
            out.add(Choice("Goal · ${it.goal.title}", it.goal.id, -1L))
        }
        return out
    }

    private fun showPicker(widgetId: Int, choices: List<Choice>) {
        val labels = choices.map { it.label }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Scope this widget")
            .setItems(labels) { _, which ->
                val choice = choices[which]
                val prefs = Prefs(this)
                if (choice.goalId < 0 && choice.listId < 0) {
                    prefs.clearWidgetScope(widgetId)
                } else {
                    prefs.setWidgetScope(
                        widgetId, choice.goalId, choice.listId,
                        // Strip the "List · " / "Goal · " prefix for the chip.
                        choice.label.substringAfter("· ").trim(),
                    )
                }
                RecordWidgetProvider.refreshAll(this)
                finish()
            }
            .setOnCancelListener { finish() }
            .show()
    }
}
