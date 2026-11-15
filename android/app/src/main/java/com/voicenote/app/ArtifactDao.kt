package com.voicenote.app

import androidx.lifecycle.LiveData
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface ArtifactDao {

    /** Insert a delivered artifact. REPLACE so a redelivered id is idempotent. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(artifact: ArtifactEntity)

    /** One artifact by id — for the reader. */
    @Query("SELECT * FROM artifacts WHERE id = :id LIMIT 1")
    suspend fun get(id: String): ArtifactEntity?

    /** One artifact, observed — the reader live-updates when a revision lands. */
    @Query("SELECT * FROM artifacts WHERE id = :id LIMIT 1")
    fun getLive(id: String): LiveData<ArtifactEntity?>

    /** Artifacts delivered in a thread, oldest first — for inline thread cards. */
    @Query("SELECT * FROM artifacts WHERE threadId = :threadId ORDER BY COALESCE(updatedAt, createdAt) ASC")
    fun forThreadLive(threadId: Long): LiveData<List<ArtifactEntity>>

    /** A thread's markdown documents, most recently changed first — sent with
     *  each turn so the assistant can revise one in place. */
    @Query(
        "SELECT * FROM artifacts WHERE threadId = :threadId AND text IS NOT NULL " +
            "ORDER BY COALESCE(updatedAt, createdAt) DESC LIMIT :limit"
    )
    suspend fun recentForThread(threadId: Long, limit: Int): List<ArtifactEntity>

    /** Every artifact, most recently changed first — for the Artifacts library. */
    @Query("SELECT * FROM artifacts ORDER BY COALESCE(updatedAt, createdAt) DESC")
    fun allLive(): LiveData<List<ArtifactEntity>>

    @Query("UPDATE artifacts SET readAt = :now WHERE id = :id AND readAt IS NULL")
    suspend fun markRead(id: String, now: Long = System.currentTimeMillis())

    @Query("DELETE FROM artifacts WHERE id = :id")
    suspend fun delete(id: String)
}
