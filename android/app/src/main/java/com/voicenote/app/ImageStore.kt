package com.voicenote.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.ExifInterface
import android.graphics.Matrix
import android.net.Uri
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest

/**
 * Copies picked photos into app-private storage, downscaled.
 *
 * Downscaling is the single biggest lever in the whole photo feature: a
 * 12MB phone photo becomes ~150KB before it ever touches the network,
 * the gateway blob store, or a vision model — and vision models gain
 * nothing from full sensor resolution. Everything downstream (upload
 * time, base64 inflation, dedup hashing, VRAM) benefits.
 */
object ImageStore {

    private const val TAG = "ImageStore"
    const val MAX_ATTACHMENTS = 5
    private const val MAX_DIM = 1024
    private const val JPEG_QUALITY = 80

    data class Stored(
        val path: String,
        val sha256: String,
        val width: Int,
        val height: Int,
        val bytes: Int,
    )

    private fun dir(context: Context): File =
        File(context.filesDir, "images").apply { mkdirs() }

    /**
     * Read [uri], downscale to fit MAX_DIM on the long edge, honour EXIF
     * rotation, write JPEG into app storage, and return its metadata.
     * Returns null if the image can't be decoded.
     */
    fun ingest(context: Context, uri: Uri): Stored? {
        return try {
            // Pass 1: bounds only, so we can pick a sane sample size instead
            // of decoding a 12MP bitmap into memory just to shrink it.
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, bounds)
            }
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
                Log.w(TAG, "Could not read bounds for $uri")
                return null
            }

            val opts = BitmapFactory.Options().apply {
                inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, MAX_DIM)
            }
            var bmp = context.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, opts)
            } ?: return null

            bmp = scaleToMax(bmp, MAX_DIM)
            bmp = applyExifRotation(context, uri, bmp)

            val file = File(dir(context), "img_${System.currentTimeMillis()}_${bmp.hashCode()}.jpg")
            FileOutputStream(file).use { out ->
                bmp.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
            }
            val w = bmp.width
            val h = bmp.height
            bmp.recycle()

            val sha = sha256(file)
            Log.i(TAG, "Ingested $uri -> ${file.name} ${w}x$h ${file.length() / 1024}KB")
            Stored(file.absolutePath, sha, w, h, file.length().toInt())
        } catch (e: Exception) {
            Log.e(TAG, "Ingest failed for $uri: ${e.message}")
            null
        }
    }

    fun delete(path: String) {
        runCatching { File(path).delete() }
    }

    private fun sampleSize(w: Int, h: Int, max: Int): Int {
        var sample = 1
        var longEdge = maxOf(w, h)
        while (longEdge / 2 >= max) {
            longEdge /= 2
            sample *= 2
        }
        return sample
    }

    private fun scaleToMax(bmp: Bitmap, max: Int): Bitmap {
        val longEdge = maxOf(bmp.width, bmp.height)
        if (longEdge <= max) return bmp
        val ratio = max.toFloat() / longEdge
        val scaled = Bitmap.createScaledBitmap(
            bmp, (bmp.width * ratio).toInt().coerceAtLeast(1),
            (bmp.height * ratio).toInt().coerceAtLeast(1), true
        )
        if (scaled != bmp) bmp.recycle()
        return scaled
    }

    private fun applyExifRotation(context: Context, uri: Uri, bmp: Bitmap): Bitmap {
        return try {
            val degrees = context.contentResolver.openInputStream(uri)?.use { input ->
                when (ExifInterface(input).getAttributeInt(
                    ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL
                )) {
                    ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                    ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                    ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                    else -> 0f
                }
            } ?: 0f
            if (degrees == 0f) return bmp
            val m = Matrix().apply { postRotate(degrees) }
            val rotated = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
            if (rotated != bmp) bmp.recycle()
            rotated
        } catch (e: Exception) {
            bmp
        }
    }

    /** Content address of an already-stored file (used by UploadWorker when
     *  it only has the path from WorkData). */
    fun sha256Of(file: File): String = sha256(file)

    private fun sha256(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(8192)
            while (true) {
                val read = input.read(buf)
                if (read <= 0) break
                md.update(buf, 0, read)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }
}
