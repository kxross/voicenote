package com.voicenote.app

import android.content.Context
import android.widget.TextView
import io.noties.markwon.Markwon
import io.noties.markwon.ext.tables.TablePlugin

/**
 * Single source of truth for agent-reply markdown.
 *
 * The assistant's replies are markdown — bold, bullets, inline code, and the
 * occasional table. Conversation bubbles get the real thing ([render]); places
 * that must stay a clean single line (inbox snippets, notification bodies) get
 * the same parse flattened to plain text ([plain]) so no stray `**` or `|`
 * leaks into them.
 */
object MarkdownRenderer {

    @Volatile
    private var instance: Markwon? = null

    private fun markwon(context: Context): Markwon =
        instance ?: synchronized(this) {
            instance ?: Markwon.builder(context.applicationContext)
                .usePlugin(TablePlugin.create(context.applicationContext))
                .build()
                .also { instance = it }
        }

    /** Render markdown into a TextView (formatted). */
    fun render(view: TextView, markdown: String?) {
        val md = markdown.orEmpty()
        if (md.isBlank()) {
            view.text = ""
            return
        }
        markwon(view.context).setMarkdown(view, md)
    }

    /**
     * Flatten markdown to plain text — same parser, markers dropped. For
     * one-line previews and notifications, where formatting can't render.
     */
    fun plain(context: Context, markdown: String?): String {
        val md = markdown.orEmpty()
        if (md.isBlank()) return ""
        return try {
            markwon(context).toMarkdown(md).toString().trim()
        } catch (e: Exception) {
            md.trim()   // never let a preview crash on odd input
        }
    }
}
