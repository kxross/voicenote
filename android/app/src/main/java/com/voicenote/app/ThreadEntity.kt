package com.voicenote.app

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A PR-style conversation session between the user and the assistant.
 * Opens when an interaction with intent starts, collects multi-turn
 * back-and-forth (agent messages + user voice replies), and is resolved
 * — by the agent (`resolve_thread()` tool) or the user (Resolve button /
 * swipe) — when nothing is pending. Recording into a resolved thread
 * reopens it.
 */
@Entity(
    tableName = "assistant_threads",
    foreignKeys = [
        ForeignKey(
            entity = GoalEntity::class,
            parentColumns = ["id"],
            childColumns = ["goalId"],
            onDelete = ForeignKey.SET_NULL,
        ),
        ForeignKey(
            entity = ListEntity::class,
            parentColumns = ["id"],
            childColumns = ["listId"],
            onDelete = ForeignKey.SET_NULL,
        ),
    ],
    indices = [Index("goalId"), Index("status"), Index("listId")],
)
data class ThreadEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val goalId: Long? = null,
    /** Set when the conversation is scoped to a list, mirroring [goalId].
     *  A thread has at most one scope. */
    val listId: Long? = null,
    val status: String = "open",   // open | resolved
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val resolvedAt: Long? = null,
)
