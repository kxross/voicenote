package com.voicenote.app

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A persisted agent reply — every notification the agent produces also
 * lands here so the dialogue survives notification dismissal. `source`
 * records the trigger: "voice", "geofence", or "schedule".
 */
@Entity(
    tableName = "agent_messages",
    foreignKeys = [
        ForeignKey(
            entity = GoalEntity::class,
            parentColumns = ["id"],
            childColumns = ["goalId"],
            onDelete = ForeignKey.SET_NULL,
        ),
        ForeignKey(
            entity = ThreadEntity::class,
            parentColumns = ["id"],
            childColumns = ["threadId"],
            onDelete = ForeignKey.SET_NULL,
        ),
    ],
    indices = [Index("goalId"), Index("threadId")],
)
data class AgentMessageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val body: String,
    val goalId: Long? = null,
    val threadId: Long? = null,
    val source: String = "voice",
    val createdAt: Long = System.currentTimeMillis(),
    val readAt: Long? = null,
    /**
     * An AI-generated React widget ("canvas"), stored as the JSON string
     * `{"title": ..., "jsx": ...}` the gateway returned on the envelope's
     * `canvas` field. Null for ordinary text replies. Rendered in a
     * sandboxed WebView by the thread adapter.
     */
    val canvas: String? = null,
    /**
     * The assistant's activity trace for this reply, as the JSON array the
     * gateway returned on the envelope's `steps` field — e.g.
     * `[{"kind":"skill","name":"lists"},{"kind":"tool","name":"add_item","ok":true}]`.
     * Null/absent for replies with no notable activity. Rendered as the
     * collapsible activity accordion on the agent card.
     */
    val steps: String? = null,
    /**
     * A one-tap "resume" handle for a research run that paused at the
     * auto-continue ceiling, as the JSON string the gateway returned on the
     * envelope's `resume` field — `{"run_id":…, "run_dir":…, "label":…}`. Null
     * for ordinary replies. Rendered as a "Continue" card under the message.
     */
    val resumeJson: String? = null,
    /**
     * Drafted schedule create/edit proposals awaiting the user's approval, as
     * the JSON array string the gateway returned on the envelope's
     * `schedule_proposals` field. Each renders an approval card with a draft of
     * the schedule; approving calls the schedules CRUD, denying discards it.
     * Cleared from the row once every proposal on it has been acted on.
     */
    val scheduleProposalJson: String? = null,
    /**
     * Drafted learned-wiki entry proposals awaiting approval, as the JSON array
     * string the gateway returned on the envelope's `wiki_proposals` field. Each
     * renders an approval card (with a diff for an amendment); approving calls
     * the wiki API, discarding rejects it. Cleared once every proposal on the
     * row has been acted on.
     */
    val wikiProposalJson: String? = null,
    /**
     * The sources behind this reply, as the JSON array the gateway returned on
     * the envelope's `sources` field — `[{n, url, title, domain, snippet, via,
     * cited}]`, cited first. Rendered as the collapsible "Sources" sub-card on
     * the agent message, each row linking out. Null when none were consulted.
     */
    val sourcesJson: String? = null,
)
