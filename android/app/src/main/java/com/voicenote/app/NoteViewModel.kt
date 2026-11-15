package com.voicenote.app

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.launch

class NoteViewModel(app: Application) : AndroidViewModel(app) {
    private val dao = NoteDatabase.get(app).noteDao()

    val notes = dao.allNotes()   // LiveData<List<NoteEntity>>, newest first

    val notesWithGoal = dao.allNotesWithGoal()   // joined with goal titles, for display

    fun delete(note: NoteEntity) = viewModelScope.launch {
        dao.deleteById(note.id)
    }
}
