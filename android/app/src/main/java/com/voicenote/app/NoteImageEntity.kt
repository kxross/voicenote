package com.voicenote.app

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A photo attached to a voice note. The file lives in app-private
 * storage (already downscaled by [ImageStore]); `sha256` is the
 * content-address the gateway dedups on, and `remoteId` is the id the
 * gateway returned once uploaded.
 *
 * `summary` / `hasText` mirror the gateway's structured caption record so
 * the Notes UI can show what the assistant knows about each image without
 * a round trip. The verbatim OCR text is NOT mirrored here — it stays on
 * the gateway, reachable via the images skill, so it never bloats either
 * the device DB or the LLM hot path.
 */
@Entity(
    tableName = "note_images",
    foreignKeys = [
        ForeignKey(
            entity = NoteEntity::class,
            parentColumns = ["id"],
            childColumns = ["noteId"],
            onDelete = ForeignKey.CASCADE,
        )
    ],
    indices = [Index("noteId"), Index("sha256")],
)
data class NoteImageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val noteId: Long,
    val localPath: String,
    val sha256: String,
    val width: Int = 0,
    val height: Int = 0,
    val bytes: Int = 0,
    val remoteId: String? = null,
    val summary: String? = null,
    val hasText: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
)
