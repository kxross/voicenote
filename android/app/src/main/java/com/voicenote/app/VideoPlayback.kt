package com.voicenote.app

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.core.content.FileProvider
import java.io.File

/** Play an attached video in the system's video player (read-only grant via
 *  the app's FileProvider — the file stays in app-private storage). */
object VideoPlayback {
    fun open(context: Context, path: String, mime: String) {
        val f = File(path)
        if (!f.exists()) {
            Toast.makeText(context, "Video file is no longer on this device", Toast.LENGTH_SHORT).show()
            return
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", f)
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, mime.ifBlank { "video/*" })
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        try {
            context.startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(context, "No video player available", Toast.LENGTH_SHORT).show()
        }
    }
}
