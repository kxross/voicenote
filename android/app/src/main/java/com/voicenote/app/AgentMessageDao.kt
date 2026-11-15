package com.voicenote.app

import androidx.lifecycle.LiveData
import androidx.room.Dao
import androidx.room.Embedded
import androidx.room.Insert
import androidx.room.Query

data class AgentMessageWithGoal(
    @Embedded val message: AgentMessageEntity,
    val goalTitle: String?,
)

@Dao
interface AgentMessageDao {

    @Query(
        """SELECT agent_messages.*, goals.title AS goalTitle
             FROM agent_messages
        LEFT JOIN goals ON agent_messages.goalId = goals.id
         ORDER BY agent_messages.createdAt ASC"""
    )
    fun allMessagesWithGoal(): LiveData<List<AgentMessageWithGoal>>

    @Query("SELECT COUNT(*) FROM agent_messages WHERE readAt IS NULL")
    fun unreadCount(): LiveData<Int>

    @Query("SELECT COUNT(*) FROM agent_messages")
    fun totalCount(): LiveData<Int>

    @Insert
    suspend fun insert(message: AgentMessageEntity): Long

    @Query("UPDATE agent_messages SET readAt = :now WHERE readAt IS NULL")
    suspend fun markAllRead(now: Long = System.currentTimeMillis())

    @Query("SELECT scheduleProposalJson FROM agent_messages WHERE id = :id LIMIT 1")
    suspend fun scheduleProposalJson(id: Long): String?

    /** Replace a message's drafted schedule proposals (null clears them, which
     *  removes the card once every proposal on it has been acted on). */
    @Query("UPDATE agent_messages SET scheduleProposalJson = :json WHERE id = :id")
    suspend fun setScheduleProposalJson(id: Long, json: String?)

    @Query("SELECT wikiProposalJson FROM agent_messages WHERE id = :id LIMIT 1")
    suspend fun wikiProposalJson(id: Long): String?

    /** Replace a message's drafted wiki proposals (null clears them → removes
     *  the card once every proposal on it has been acted on). */
    @Query("UPDATE agent_messages SET wikiProposalJson = :json WHERE id = :id")
    suspend fun setWikiProposalJson(id: Long, json: String?)
}
