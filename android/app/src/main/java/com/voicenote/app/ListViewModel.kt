package com.voicenote.app

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.launch

class ListViewModel(app: Application) : AndroidViewModel(app) {
    private val dao = NoteDatabase.get(app).listDao()

    val allListsWithItems = dao.allListsWithItems()

    fun listWithItems(id: Long) = dao.listWithItems(id)

    fun deleteList(id: Long) = viewModelScope.launch { dao.deleteListById(id) }

    fun deleteItem(id: Long) = viewModelScope.launch { dao.deleteItemById(id) }

    fun setItemDone(id: Long, done: Boolean) = viewModelScope.launch {
        dao.setItemDoneById(id, done)
    }
}
