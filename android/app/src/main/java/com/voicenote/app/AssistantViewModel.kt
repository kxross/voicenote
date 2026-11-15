package com.voicenote.app

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.MediatorLiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.launch

/** One row in the assistant inbox. Open threads listed first; resolved
 *  threads live behind a collapsible section at the bottom. */
sealed class InboxRow {
    /** An agent turn is in flight. Threads are created lazily (only once the
     *  agent engages), so a brand-new note has no card yet — this transient
     *  row is what shows "something is happening" until the reply lands. */
    data class Pending(val stage: String) : InboxRow()
    data class Open(val row: ThreadListRow) : InboxRow()
    data class ResolvedHeader(val count: Int, val expanded: Boolean) : InboxRow()
    data class Resolved(val row: ThreadListRow) : InboxRow()
}

class AssistantViewModel(app: Application) : AndroidViewModel(app) {
    private val threadDao = NoteDatabase.get(app).threadDao()

    /** Pill support on the main screen. */
    val openUnreadCount = threadDao.openUnreadCount()
    val totalThreads = threadDao.totalThreads()

    private val openThreads = threadDao.threadsByStatus("open")
    private val resolvedThreads = threadDao.threadsByStatus("resolved")
    private val resolvedExpanded = MutableLiveData(false)
    private val pendingStage = MutableLiveData<String?>(null)

    val rows = MediatorLiveData<List<InboxRow>>().apply {
        fun rebuild() {
            val open = openThreads.value.orEmpty()
            val resolved = resolvedThreads.value.orEmpty()
            val expanded = resolvedExpanded.value == true

            val out = mutableListOf<InboxRow>()
            pendingStage.value?.let { out.add(InboxRow.Pending(it)) }
            out.addAll(open.map { InboxRow.Open(it) })
            if (resolved.isNotEmpty()) {
                out.add(InboxRow.ResolvedHeader(resolved.size, expanded))
                if (expanded) out.addAll(resolved.map { InboxRow.Resolved(it) })
            }
            value = out
        }
        addSource(openThreads) { rebuild() }
        addSource(resolvedThreads) { rebuild() }
        addSource(resolvedExpanded) { rebuild() }
        addSource(pendingStage) { rebuild() }
    }

    /** Null clears the transient in-flight row. */
    fun setPendingStage(stage: String?) {
        if (pendingStage.value != stage) pendingStage.value = stage
    }

    fun toggleResolvedSection() {
        resolvedExpanded.value = resolvedExpanded.value != true
    }

    fun resolveThread(id: Long) = viewModelScope.launch { threadDao.resolve(id) }

    fun reopenThread(id: Long) = viewModelScope.launch { threadDao.reopen(id) }

    /** Delete the thread + its agent replies; keep the user's voice notes. */
    fun deleteThread(id: Long) = viewModelScope.launch { threadDao.deleteThreadKeepingNotes(id) }

    /** Batch version for multi-select. */
    fun deleteThreads(ids: List<Long>) = viewModelScope.launch {
        for (id in ids) threadDao.deleteThreadKeepingNotes(id)
    }
}
