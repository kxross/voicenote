package com.voicenote.app

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A voice-note transcript. Optionally scoped to a goal (recorded from
 * the goal's screen or as a reply to a goal-scoped agent message) and
 * optionally threaded under an agent message. Both links use SET_NULL —
 * notes are the user's raw history and must survive goal/message
 * deletion.
 */
@Entity(
    tableName = "notes",
    foreignKeys = [
        ForeignKey(
            entity = GoalEntity::class,
            parentColumns = ["id"],
            childColumns = ["goalId"],
            onDelete = ForeignKey.SET_NULL,
        ),
        ForeignKey(
            entity = AgentMessageEntity::class,
            parentColumns = ["id"],
            childColumns = ["replyToMessageId"],
            onDelete = ForeignKey.SET_NULL,
        ),
        ForeignKey(
            entity = ThreadEntity::class,
            parentColumns = ["id"],
            childColumns = ["threadId"],
            onDelete = ForeignKey.SET_NULL,
        ),
    ],
    indices = [Index("goalId"), Index("replyToMessageId"), Index("threadId")],
)
data class NoteEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val transcript: String,
    val createdAt: Long = System.currentTimeMillis(),   // epoch ms
    val durationSeconds: Int = 0,
    val goalId: Long? = null,
    val replyToMessageId: Long? = null,
    val threadId: Long? = null,
)
