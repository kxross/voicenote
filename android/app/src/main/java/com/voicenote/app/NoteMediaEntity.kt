package com.voicenote.app

import androidx.lifecycle.LiveData
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query

/**
 * A video attached to a voice note (sibling of [NoteImageEntity]). The
 * original file lives in app-private storage (copied by [VideoStore], not
 * re-encoded) with a small JPEG thumbnail beside it. `remoteId` is the
 * gateway's content id once uploaded; `summary` mirrors what the assistant
 * found when it watched the video, so later turns recall it as text.
 */
@Entity(
    tableName = "note_media",
    foreignKeys = [
        ForeignKey(
            entity = NoteEntity::class,
            parentColumns = ["id"],
            childColumns = ["noteId"],
            onDelete = ForeignKey.CASCADE,
        )
    ],
    indices = [Index("noteId")],
)
data class NoteMediaEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val noteId: Long,
    val localPath: String,
    val thumbPath: String? = null,
    val mime: String,
    val bytes: Long = 0,
    val durationMs: Long = 0,
    val width: Int = 0,
    val height: Int = 0,
    val remoteId: String? = null,
    val summary: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
) {
    /** "0:42" */
    val durationLabel: String get() = VideoStore.formatDuration(durationMs)
}

@Dao
interface NoteMediaDao {

    @Insert
    suspend fun insert(media: NoteMediaEntity): Long

    @Query("SELECT * FROM note_media WHERE noteId = :noteId ORDER BY createdAt ASC")
    suspend fun forNote(noteId: Long): List<NoteMediaEntity>

    /** Videos attached to any note in a thread — for the reply-bubble strips. */
    @Query(
        """SELECT nm.* FROM note_media nm
                  JOIN notes n ON nm.noteId = n.id
                 WHERE n.threadId = :threadId
              ORDER BY nm.createdAt ASC"""
    )
    fun mediaForThreadLive(threadId: Long): LiveData<List<NoteMediaEntity>>

    @Query("SELECT * FROM note_media ORDER BY createdAt DESC LIMIT :limit")
    suspend fun recent(limit: Int = 100): List<NoteMediaEntity>

    @Query("UPDATE note_media SET remoteId = :remoteId WHERE id = :id")
    suspend fun setRemoteId(id: Long, remoteId: String)

    @Query("UPDATE note_media SET summary = :summary WHERE remoteId = :remoteId")
    suspend fun setSummaryByRemoteId(remoteId: String, summary: String)

    /** Uploaded videos whose watch-summary hasn't been pulled back yet. */
    @Query("SELECT * FROM note_media WHERE remoteId IS NOT NULL AND summary IS NULL")
    suspend fun awaitingSummary(): List<NoteMediaEntity>
}
