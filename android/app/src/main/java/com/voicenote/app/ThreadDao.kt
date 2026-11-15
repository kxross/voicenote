package com.voicenote.app

import androidx.lifecycle.LiveData
import androidx.room.Dao
import androidx.room.Embedded
import androidx.room.Insert
import androidx.room.Query

/** Thread + presentation metadata for the inbox list. A thread carries at
 *  most one scope, so exactly one of goalTitle/listName is non-null. */
data class ThreadListRow(
    @Embedded val thread: ThreadEntity,
    val goalTitle: String?,
    val listName: String?,
    val unreadCount: Int,
    val lastBody: String?,
    val lastActivity: Long,
)

@Dao
interface ThreadDao {

    @Query(
        """SELECT t.*, g.title AS goalTitle, l.name AS listName,
                  (SELECT COUNT(*) FROM agent_messages m
                    WHERE m.threadId = t.id AND m.readAt IS NULL) AS unreadCount,
                  (SELECT m.body FROM agent_messages m
                    WHERE m.threadId = t.id ORDER BY m.createdAt DESC LIMIT 1) AS lastBody,
                  MAX(t.updatedAt,
                      COALESCE((SELECT MAX(m.createdAt) FROM agent_messages m WHERE m.threadId = t.id), 0),
                      COALESCE((SELECT MAX(n.createdAt) FROM notes n WHERE n.threadId = t.id), 0)
                  ) AS lastActivity
             FROM assistant_threads t
        LEFT JOIN goals g ON t.goalId = g.id
        LEFT JOIN lists l ON t.listId = l.id
            WHERE t.status = :status
         ORDER BY lastActivity DESC"""
    )
    fun threadsByStatus(status: String): LiveData<List<ThreadListRow>>

    @Query(
        """SELECT t.*, g.title AS goalTitle, l.name AS listName,
                  0 AS unreadCount, NULL AS lastBody, t.updatedAt AS lastActivity
             FROM assistant_threads t
        LEFT JOIN goals g ON t.goalId = g.id
        LEFT JOIN lists l ON t.listId = l.id
            WHERE t.id = :id"""
    )
    fun threadWithGoal(id: Long): LiveData<ThreadListRow?>

    @Query(
        """SELECT COUNT(*) FROM agent_messages m
             JOIN assistant_threads t ON m.threadId = t.id
            WHERE t.status = 'open' AND m.readAt IS NULL"""
    )
    fun openUnreadCount(): LiveData<Int>

    @Query("SELECT COUNT(*) FROM assistant_threads")
    fun totalThreads(): LiveData<Int>

    @Query("SELECT * FROM assistant_threads WHERE id = :id LIMIT 1")
    suspend fun findById(id: Long): ThreadEntity?

    @Query("SELECT * FROM assistant_threads WHERE goalId = :goalId AND status = 'open' ORDER BY updatedAt DESC LIMIT 1")
    suspend fun findOpenThreadForGoal(goalId: Long): ThreadEntity?

    @Query("SELECT * FROM assistant_threads WHERE listId = :listId AND status = 'open' ORDER BY updatedAt DESC LIMIT 1")
    suspend fun findOpenThreadForList(listId: Long): ThreadEntity?

    @Query("SELECT * FROM assistant_threads WHERE title = :title AND status = 'open' ORDER BY updatedAt DESC LIMIT 1")
    suspend fun findOpenThreadByTitle(title: String): ThreadEntity?

    @Insert
    suspend fun insert(thread: ThreadEntity): Long

    @Query("UPDATE assistant_threads SET status = 'resolved', resolvedAt = :now, updatedAt = :now WHERE id = :id")
    suspend fun resolve(id: Long, now: Long = System.currentTimeMillis())

    @Query("UPDATE assistant_threads SET status = 'open', resolvedAt = NULL, updatedAt = :now WHERE id = :id")
    suspend fun reopen(id: Long, now: Long = System.currentTimeMillis())

    @Query("UPDATE assistant_threads SET updatedAt = :now WHERE id = :id")
    suspend fun touch(id: Long, now: Long = System.currentTimeMillis())

    // ---- thread history -----------------------------------------------------

    @Query("SELECT * FROM agent_messages WHERE threadId = :threadId ORDER BY createdAt ASC")
    suspend fun messagesForThread(threadId: Long): List<AgentMessageEntity>

    @Query("SELECT * FROM notes WHERE threadId = :threadId ORDER BY createdAt ASC")
    suspend fun notesForThread(threadId: Long): List<NoteEntity>

    @Query("SELECT * FROM agent_messages WHERE threadId = :threadId ORDER BY createdAt ASC")
    fun messagesForThreadLive(threadId: Long): LiveData<List<AgentMessageEntity>>

    @Query("SELECT * FROM notes WHERE threadId = :threadId ORDER BY createdAt ASC")
    fun notesForThreadLive(threadId: Long): LiveData<List<NoteEntity>>

    @Query("SELECT id FROM agent_messages WHERE threadId = :threadId ORDER BY createdAt DESC LIMIT 1")
    suspend fun lastMessageId(threadId: Long): Long?

    @Query("UPDATE agent_messages SET readAt = :now WHERE threadId = :threadId AND readAt IS NULL")
    suspend fun markThreadRead(threadId: Long, now: Long = System.currentTimeMillis())

    // ---- delete -------------------------------------------------------------

    @Query("UPDATE notes SET threadId = NULL WHERE threadId = :id")
    suspend fun detachNotesFromThread(id: Long)

    @Query("DELETE FROM agent_messages WHERE threadId = :id")
    suspend fun deleteMessagesForThread(id: Long)

    @Query("DELETE FROM assistant_threads WHERE id = :id")
    suspend fun deleteThreadRow(id: Long)

    /** Remove a thread and its agent replies, but KEEP the user's voice notes
     *  (just un-thread them, so they remain in the Notes list). One atomic op. */
    @androidx.room.Transaction
    suspend fun deleteThreadKeepingNotes(id: Long) {
        detachNotesFromThread(id)
        deleteMessagesForThread(id)
        deleteThreadRow(id)
    }
}
