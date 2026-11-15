package com.voicenote.app

import androidx.lifecycle.LiveData
import androidx.room.Dao
import androidx.room.Embedded
import androidx.room.Insert
import androidx.room.Query

data class NoteWithGoal(
    @Embedded val note: NoteEntity,
    val goalTitle: String?,
    val imageCount: Int = 0,
    val firstImagePath: String? = null,
)

@Dao
interface NoteDao {
    @Insert
    suspend fun insert(note: NoteEntity): Long

    @Query("SELECT * FROM notes ORDER BY createdAt DESC")
    fun allNotes(): LiveData<List<NoteEntity>>

    @Query(
        """SELECT notes.*, goals.title AS goalTitle,
                  (SELECT COUNT(*) FROM note_images ni WHERE ni.noteId = notes.id) AS imageCount,
                  (SELECT ni.localPath FROM note_images ni WHERE ni.noteId = notes.id
                    ORDER BY ni.createdAt ASC LIMIT 1) AS firstImagePath
             FROM notes
        LEFT JOIN goals ON notes.goalId = goals.id
         ORDER BY notes.createdAt DESC"""
    )
    fun allNotesWithGoal(): LiveData<List<NoteWithGoal>>

    @Query("SELECT * FROM notes WHERE id = :id LIMIT 1")
    suspend fun findById(id: Long): NoteEntity?

    @Query("SELECT * FROM notes ORDER BY createdAt DESC LIMIT :limit")
    suspend fun snapshot(limit: Int = 100): List<NoteEntity>

    @Query(
        """SELECT notes.*, goals.title AS goalTitle,
                  (SELECT COUNT(*) FROM note_images ni WHERE ni.noteId = notes.id) AS imageCount,
                  (SELECT ni.localPath FROM note_images ni WHERE ni.noteId = notes.id
                    ORDER BY ni.createdAt ASC LIMIT 1) AS firstImagePath
             FROM notes
        LEFT JOIN goals ON notes.goalId = goals.id
         ORDER BY notes.createdAt DESC LIMIT :limit"""
    )
    suspend fun snapshotWithGoal(limit: Int = 100): List<NoteWithGoal>

    @Query("SELECT * FROM notes WHERE goalId = :goalId ORDER BY createdAt DESC")
    suspend fun notesForGoal(goalId: Long): List<NoteEntity>

    @Query("SELECT * FROM notes WHERE goalId = :goalId ORDER BY createdAt DESC")
    fun notesForGoalLive(goalId: Long): LiveData<List<NoteEntity>>

    @Query(
        """SELECT notes.*, goals.title AS goalTitle,
                  (SELECT COUNT(*) FROM note_images ni WHERE ni.noteId = notes.id) AS imageCount,
                  (SELECT ni.localPath FROM note_images ni WHERE ni.noteId = notes.id
                    ORDER BY ni.createdAt ASC LIMIT 1) AS firstImagePath
             FROM notes
        LEFT JOIN goals ON notes.goalId = goals.id
            WHERE notes.replyToMessageId IS NOT NULL
         ORDER BY notes.createdAt ASC"""
    )
    fun threadReplies(): LiveData<List<NoteWithGoal>>

    @Query("UPDATE notes SET threadId = :threadId WHERE id = :noteId")
    suspend fun setThreadId(noteId: Long, threadId: Long)

    @Query("UPDATE notes SET transcript = :transcript WHERE id = :id")
    suspend fun updateTranscript(id: Long, transcript: String)

    @Query("DELETE FROM notes WHERE id = :id")
    suspend fun deleteById(id: Long)
}
