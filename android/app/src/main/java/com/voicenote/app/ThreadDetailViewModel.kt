package com.voicenote.app

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.MediatorLiveData
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.launch

/** One row of a thread's conversation. */
sealed class ConvoRow {
    abstract val timestamp: Long

    data class Agent(val message: AgentMessageEntity) : ConvoRow() {
        override val timestamp get() = message.createdAt
    }

    /** A long-form document the assistant published this turn. Sorts right
     *  after its delivering agent message (inserted just after it). */
    data class Artifact(val artifact: ArtifactEntity) : ConvoRow() {
        // A revised document moves down to the reply that revised it.
        override val timestamp get() = artifact.lastChanged
    }

    /** A paused research run — a one-tap "Continue" card under its message. */
    data class Resume(
        val runId: String,
        val label: String,
        val messageId: Long,
        private val ts: Long,
    ) : ConvoRow() {
        override val timestamp get() = ts
    }

    /** A drafted schedule awaiting approval — an Approve/Discard card. */
    data class ScheduleProposal(
        val proposal: GatewayAgent.ScheduleProposal,
        val messageId: Long,
        private val ts: Long,
    ) : ConvoRow() {
        override val timestamp get() = ts
    }

    /** A drafted learned-wiki entry awaiting approval — an Approve/Edit/Discard card. */
    data class WikiProposal(
        val proposal: GatewayAgent.WikiProposal,
        val messageId: Long,
        private val ts: Long,
    ) : ConvoRow() {
        override val timestamp get() = ts
    }

    data class Reply(
        val note: NoteEntity,
        val images: List<NoteImageEntity> = emptyList(),
        val videos: List<NoteMediaEntity> = emptyList(),
    ) : ConvoRow() {
        override val timestamp get() = note.createdAt
    }

    /** A sandbox command held for approval (manual mode). Sorts to the bottom,
     *  just above the working bubble, so it's the thing you act on. */
    /** A held sandbox command. [reason] is set in smart mode: why the System
     *  One review flagged it (null = manual mode, held as a matter of course). */
    data class Approval(val id: String, val command: String, val reason: String? = null) : ConvoRow() {
        override val timestamp get() = Long.MAX_VALUE - 1
    }

    /** An in-flight turn for this thread — the animated "working" bubble.
     *  Always sorts last so it sits at the bottom of the conversation.
     *  [label] is the live goal-relative status ("Updating your lists") from the
     *  progress feed when available; [liveSteps] accumulates completed steps. */
    data class Pending(
        val stage: String,
        val label: String? = null,
        val liveSteps: List<String> = emptyList(),
    ) : ConvoRow() {
        override val timestamp get() = Long.MAX_VALUE
    }
}

class ThreadDetailViewModel(app: Application) : AndroidViewModel(app) {
    private val threadDao = NoteDatabase.get(app).threadDao()
    private val imageDao = NoteDatabase.get(app).noteImageDao()
    private val artifactDao = NoteDatabase.get(app).artifactDao()
    private val mediaDao = NoteDatabase.get(app).noteMediaDao()

    fun thread(id: Long) = threadDao.threadWithGoal(id)

    fun convo(id: Long): MediatorLiveData<List<ConvoRow>> {
        val messages = threadDao.messagesForThreadLive(id)
        val notes = threadDao.notesForThreadLive(id)
        val images = imageDao.imagesForThreadLive(id)
        val artifacts = artifactDao.forThreadLive(id)
        val videos = mediaDao.mediaForThreadLive(id)
        return MediatorLiveData<List<ConvoRow>>().apply {
            fun merge() {
                val byNote = images.value.orEmpty().groupBy { it.noteId }
                val videosByNote = videos.value.orEmpty().groupBy { it.noteId }
                // Messages before artifacts before notes so that, when an agent
                // message and its artifact share a millisecond, the stable sort
                // keeps the card directly under its lead-in.
                val rows =
                    messages.value.orEmpty().flatMap { m ->
                        val out = mutableListOf<ConvoRow>(ConvoRow.Agent(m))
                        GatewayAgent.ResumeInfo.parse(m.resumeJson)?.let {
                            out += ConvoRow.Resume(it.runId, it.label, m.id, m.createdAt)
                        }
                        for (p in GatewayAgent.ScheduleProposal.parseList(m.scheduleProposalJson)) {
                            out += ConvoRow.ScheduleProposal(p, m.id, m.createdAt)
                        }
                        for (p in GatewayAgent.WikiProposal.parseList(m.wikiProposalJson)) {
                            out += ConvoRow.WikiProposal(p, m.id, m.createdAt)
                        }
                        out
                    } +
                    artifacts.value.orEmpty().map { ConvoRow.Artifact(it) } +
                    notes.value.orEmpty().map {
                        ConvoRow.Reply(it, byNote[it.id].orEmpty(), videosByNote[it.id].orEmpty())
                    }
                value = rows.sortedBy { it.timestamp }
            }
            addSource(messages) { merge() }
            addSource(notes) { merge() }
            addSource(images) { merge() }
            addSource(artifacts) { merge() }
            addSource(videos) { merge() }
        }
    }

    fun markRead(id: Long) = viewModelScope.launch { threadDao.markThreadRead(id) }

    fun resolve(id: Long) = viewModelScope.launch { threadDao.resolve(id) }

    /** Delete this thread + its agent replies; keep the user's voice notes. */
    fun delete(id: Long) = viewModelScope.launch { threadDao.deleteThreadKeepingNotes(id) }
}
