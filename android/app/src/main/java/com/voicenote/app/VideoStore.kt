package com.voicenote.app

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import org.json.JSONObject
import java.io.File

/**
 * Video attachments, staged on-device before upload (sibling of [ImageStore]).
 * The original is copied into app-private storage as-is (no re-encode in v1 —
 * all decoding happens in the gateway's sandbox), with a small JPEG thumbnail
 * for the strips. Enforces the size/length caps up front so an oversized clip
 * is refused with a clear message instead of failing mid-upload.
 */
object VideoStore {

    private const val TAG = "VideoStore"

    /** Keep in sync with the gateway's MEDIA_MAX_MB. */
    const val MAX_BYTES = 300L * 1024 * 1024
    const val MAX_DURATION_MS = 5 * 60 * 1000L + 5_000   // 5 min (+ slack)
    const val MAX_PER_NOTE = 1
    private const val THUMB_DIM = 320

    data class Stored(
        val path: String,
        val thumbPath: String?,
        val mime: String,
        val bytes: Long,
        val durationMs: Long,
        val width: Int,
        val height: Int,
    ) {
        fun toJson(): String = JSONObject()
            .put("path", path).put("thumb", thumbPath ?: "").put("mime", mime)
            .put("bytes", bytes).put("durationMs", durationMs)
            .put("width", width).put("height", height)
            .toString()

        companion object {
            fun fromJson(s: String): Stored? = try {
                val o = JSONObject(s)
                Stored(
                    path = o.getString("path"),
                    thumbPath = o.optString("thumb").ifBlank { null },
                    mime = o.optString("mime", "video/mp4"),
                    bytes = o.optLong("bytes"),
                    durationMs = o.optLong("durationMs"),
                    width = o.optInt("width"),
                    height = o.optInt("height"),
                )
            } catch (e: Exception) { null }
        }
    }

    /** Result of [ingest]: the staged video, or a user-facing reason it was refused. */
    data class Ingest(val stored: Stored? = null, val error: String? = null)

    private fun dir(context: Context): File =
        File(context.filesDir, "media").apply { mkdirs() }

    fun isVideo(context: Context, uri: Uri): Boolean =
        context.contentResolver.getType(uri)?.startsWith("video/") == true

    fun formatDuration(ms: Long): String {
        val s = (ms + 500) / 1000
        return "%d:%02d".format(s / 60, s % 60)
    }

    /** Copy a picked video into app storage with metadata + thumbnail. Run off
     *  the main thread. */
    fun ingest(context: Context, uri: Uri): Ingest {
        val cr = context.contentResolver
        val declaredSize = try {
            cr.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { c ->
                if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else null
            }
        } catch (e: Exception) { null }
        if (declaredSize != null && declaredSize > MAX_BYTES) {
            return Ingest(error = "That video is over ${MAX_BYTES / (1024 * 1024)} MB")
        }

        val mmr = MediaMetadataRetriever()
        try {
            mmr.setDataSource(context, uri)
            val durationMs = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            if (durationMs > MAX_DURATION_MS) {
                return Ingest(error = "Videos can be up to 5 minutes (this one is ${formatDuration(durationMs)})")
            }
            var w = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
            var h = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
            val rot = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
            if (rot % 180 == 90) { val t = w; w = h; h = t }
            val mime = cr.getType(uri) ?: "video/mp4"
            val ext = when {
                mime.contains("quicktime") -> "mov"
                mime.contains("3gpp") -> "3gp"
                mime.contains("webm") -> "webm"
                else -> "mp4"
            }
            val stamp = System.currentTimeMillis()
            val out = File(dir(context), "vid_$stamp.$ext")
            var copied = 0L
            cr.openInputStream(uri)?.use { input ->
                out.outputStream().use { o ->
                    val buf = ByteArray(1 shl 16)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        copied += n
                        if (copied > MAX_BYTES) {
                            o.close(); out.delete()
                            return Ingest(error = "That video is over ${MAX_BYTES / (1024 * 1024)} MB")
                        }
                        o.write(buf, 0, n)
                    }
                }
            } ?: return Ingest(error = "Couldn't read that video")

            val thumb = try {
                val at = (durationMs / 2).coerceAtMost(1_000L) * 1000   // µs
                mmr.getFrameAtTime(at, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)?.let { bmp ->
                    val scale = THUMB_DIM.toFloat() / maxOf(bmp.width, bmp.height)
                    val small = if (scale < 1f) Bitmap.createScaledBitmap(
                        bmp, (bmp.width * scale).toInt().coerceAtLeast(1),
                        (bmp.height * scale).toInt().coerceAtLeast(1), true,
                    ) else bmp
                    val f = File(dir(context), "vid_$stamp.thumb.jpg")
                    f.outputStream().use { small.compress(Bitmap.CompressFormat.JPEG, 80, it) }
                    f.absolutePath
                }
            } catch (e: Exception) { null }

            return Ingest(Stored(out.absolutePath, thumb, mime, copied, durationMs, w, h))
        } catch (e: Exception) {
            Log.w(TAG, "ingest failed: ${e.message}")
            return Ingest(error = "Couldn't read that video")
        } finally {
            try { mmr.release() } catch (e: Exception) { /* ignore */ }
        }
    }

    fun delete(stored: Stored) {
        File(stored.path).delete()
        stored.thumbPath?.let { File(it).delete() }
    }
}
