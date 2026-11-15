package com.voicenote.app

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A long-form document the assistant "published" to be read in a full-screen
 * reader (and browsed in the Artifacts library) rather than crammed into a chat
 * bubble. Delivered on the gateway envelope's `artifacts` field.
 *
 * Phase 1 is markdown: [text] holds the GitHub-flavored markdown body, rendered
 * on-device in a sandboxed WebView (marked + mermaid + KaTeX). [ref]/[mime] are
 * reserved for Phase 2 binaries (PDF/image) streamed from the gateway blob
 * store — null for markdown.
 *
 * [id] is the gateway-assigned artifact id (stable across redelivery). Rows are
 * tied to the agent message that delivered them; deleting that message (e.g.
 * when a thread is deleted) cascades the artifact away.
 */
@Entity(
    tableName = "artifacts",
    foreignKeys = [
        ForeignKey(
            entity = AgentMessageEntity::class,
            parentColumns = ["id"],
            childColumns = ["messageId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("messageId"), Index("threadId")],
)
data class ArtifactEntity(
    @PrimaryKey val id: String,
    val messageId: Long,
    val threadId: Long? = null,
    val kind: String = "markdown",
    val title: String,
    /** Markdown body for kind="markdown"; null for binary artifacts. */
    val text: String? = null,
    /** Blob-store path for binary artifacts (Phase 2); null for markdown. */
    val ref: String? = null,
    /** MIME type for binary artifacts (Phase 2); null for markdown. */
    val mime: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val readAt: Long? = null,
    /** This document's sources (same shape as the message's `sourcesJson`),
     *  `cited` relative to this document's text. The reader renders them as a
     *  linked, numbered Sources section and makes inline `[n]` markers tappable. */
    val sourcesJson: String? = null,
    /** 1 for a fresh document; bumped each time the assistant revises it in
     *  place (the gateway re-delivers the same [id] flagged `revised`). */
    @ColumnInfo(defaultValue = "1") val revision: Int = 1,
    /** When the document last changed (a revision or an undo); null if never.
     *  Orders the thread card and the library by recency. */
    val updatedAt: Long? = null,
    /** The other version, for one-step undo/redo of a revision: swapping
     *  these with the current fields flips between the two. Null = nothing
     *  to undo. */
    val previousText: String? = null,
    val previousTitle: String? = null,
    val previousSourcesJson: String? = null,
    val previousRevision: Int? = null,
) {
    /** When the document last changed — creation or latest revision. */
    val lastChanged: Long get() = updatedAt ?: createdAt

    /** " · REVISED v3" suffix for card kind labels ("" for a first version). */
    val revisionLabel: String get() = if (revision > 1) " · REVISED v$revision" else ""

    /** This row with its current and previous versions swapped. */
    fun swapped(now: Long = System.currentTimeMillis()): ArtifactEntity = copy(
        text = previousText,
        title = previousTitle ?: title,
        sourcesJson = previousSourcesJson,
        revision = previousRevision ?: revision,
        previousText = text,
        previousTitle = title,
        previousSourcesJson = sourcesJson,
        previousRevision = revision,
        updatedAt = now,
    )
}
