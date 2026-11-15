package com.voicenote.app

import androidx.lifecycle.LiveData
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

@Dao
interface NoteImageDao {

    @Insert
    suspend fun insert(image: NoteImageEntity): Long

    @Query("SELECT * FROM note_images WHERE noteId = :noteId ORDER BY createdAt ASC")
    suspend fun forNote(noteId: Long): List<NoteImageEntity>

    @Query("SELECT * FROM note_images WHERE noteId = :noteId ORDER BY createdAt ASC")
    fun forNoteLive(noteId: Long): LiveData<List<NoteImageEntity>>

    /** All images attached to any note in a thread — powers the reply-bubble
     *  thumbnail strips in the assistant surface. */
    @Query(
        """SELECT ni.* FROM note_images ni
                  JOIN notes n ON ni.noteId = n.id
                 WHERE n.threadId = :threadId
              ORDER BY ni.createdAt ASC"""
    )
    fun imagesForThreadLive(threadId: Long): LiveData<List<NoteImageEntity>>

    /** All images, newest first — grouped by noteId to build payload index
     *  entries without an N+1 query per note. */
    @Query("SELECT * FROM note_images ORDER BY createdAt DESC LIMIT :limit")
    suspend fun recent(limit: Int = 200): List<NoteImageEntity>

    @Query("UPDATE note_images SET remoteId = :remoteId WHERE id = :id")
    suspend fun setRemoteId(id: Long, remoteId: String)

    @Query("UPDATE note_images SET summary = :summary, hasText = :hasText WHERE sha256 = :sha")
    suspend fun setSummaryBySha(sha: String, summary: String, hasText: Boolean)

    @Query("SELECT * FROM note_images WHERE remoteId IS NOT NULL AND summary IS NULL")
    suspend fun awaitingSummary(): List<NoteImageEntity>

    @Query("DELETE FROM note_images WHERE id = :id")
    suspend fun deleteById(id: Long)
}
