package com.voicenote.app

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.text.TextUtils
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import org.json.JSONArray

/**
 * The collapsible "Sources" sub-card on an agent message: the web pages the
 * assistant drew on this turn, as structured, linked citations. Parsed from the
 * message's `sourcesJson` (the envelope's `sources` field — cited first, each
 * `{n, url, title, domain, snippet, via, cited}`). Shared by the plain and
 * canvas agent cards, whose layouts expose the same view ids.
 *
 * Cited sources come first under their inline `[n]` number; ones the assistant
 * consulted but didn't cite follow under "Also consulted". Tap a row to open
 * the page; long-press to copy its link. Collapsed by default; the expand state
 * lives in the adapter so it survives RecyclerView recycling.
 */
object SourcesCard {

    data class Source(
        val n: Int,
        val url: String,
        val title: String,
        val domain: String,
        val snippet: String,
        val cited: Boolean,
        /** "search" (a web_search result), "browse" (a page the agent opened
         *  in the sandbox), or "link" (a link the model wrote). */
        val via: String = "search",
    )

    fun parse(json: String?): List<Source> {
        if (json.isNullOrBlank()) return emptyList()
        return try {
            val arr = JSONArray(json)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val url = o.optString("url").trim()
                if (!isWebUrl(url)) return@mapNotNull null
                val domain = o.optString("domain").ifBlank { Uri.parse(url).host.orEmpty() }
                Source(
                    n = o.optInt("n", i + 1),
                    url = url,
                    title = o.optString("title").ifBlank { domain },
                    domain = domain,
                    snippet = o.optString("snippet"),
                    cited = o.optBoolean("cited", false),
                    via = o.optString("via", "search"),
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun bind(
        section: View,
        header: View,
        caret: TextView,
        summary: TextView,
        body: LinearLayout,
        sourcesJson: String?,
        messageId: Long,
        expanded: MutableSet<Long>,
    ) {
        val sources = parse(sourcesJson)
        if (sources.isEmpty()) {
            section.visibility = View.GONE
            header.setOnClickListener(null)
            return
        }
        section.visibility = View.VISIBLE
        summary.text = summarize(sources)

        body.removeAllViews()
        val cited = sources.filter { it.cited }
        val consulted = sources.filterNot { it.cited }
        cited.forEach { body.addView(row(body, it)) }
        if (consulted.isNotEmpty()) {
            if (cited.isNotEmpty()) body.addView(subheader(body, "ALSO CONSULTED"))
            consulted.forEach { body.addView(row(body, it)) }
        }

        fun apply(isOpen: Boolean) {
            caret.text = if (isOpen) "▾" else "▸"
            body.visibility = if (isOpen) View.VISIBLE else View.GONE
        }
        apply(messageId in expanded)

        header.setOnClickListener {
            val nowOpen = if (messageId in expanded) {
                expanded.remove(messageId); false
            } else {
                expanded.add(messageId); true
            }
            apply(nowOpen)
        }
    }

    private fun summarize(sources: List<Source>): String {
        val cited = sources.count { it.cited }
        val rest = sources.size - cited
        return when {
            cited == 0 -> "Sources · ${sources.size} consulted"
            rest == 0 -> "Sources · $cited cited"
            else -> "Sources · $cited cited · $rest also consulted"
        }
    }

    private fun subheader(parent: LinearLayout, text: String): TextView {
        val d = parent.resources.displayMetrics.density
        return TextView(parent.context).apply {
            this.text = text
            textSize = 10f
            letterSpacing = 0.08f
            typeface = Typeface.MONOSPACE
            setTextColor(0xFF6E6E6E.toInt())
            setPadding(0, (10 * d).toInt(), 0, (2 * d).toInt())
        }
    }

    private fun row(parent: LinearLayout, s: Source): View {
        val ctx = parent.context
        val d = parent.resources.displayMetrics.density
        val title = TextView(ctx).apply {
            text = "[${s.n}]  ${s.title}"
            textSize = 13f
            setTextColor(if (s.cited) 0xFFDDDDDD.toInt() else 0xFFAAAAAA.toInt())
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
        }
        val meta = TextView(ctx).apply {
            // Pages the agent opened itself in the sandbox are marked as such.
            text = if (s.via == "browse") "${s.domain} · browsed  ↗" else "${s.domain}  ↗"
            textSize = 11f
            typeface = Typeface.MONOSPACE
            setTextColor(0xFF7A9CC6.toInt())
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setPadding((28 * d).toInt(), (1 * d).toInt(), 0, 0)
        }
        return LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, (5 * d).toInt(), 0, (5 * d).toInt())
            val tv = android.util.TypedValue()
            ctx.theme.resolveAttribute(android.R.attr.selectableItemBackground, tv, true)
            setBackgroundResource(tv.resourceId)
            contentDescription = "Source ${s.n}: ${s.title}, ${s.domain}. Opens in browser."
            addView(title)
            addView(meta)
            setOnClickListener { open(ctx, s.url) }
            setOnLongClickListener { copy(ctx, s.url); true }
        }
    }

    private fun isWebUrl(url: String): Boolean {
        val scheme = try { Uri.parse(url).scheme?.lowercase() } catch (e: Exception) { null }
        return scheme == "http" || scheme == "https"
    }

    /** Open a source in the user's browser. Only http(s) — the URLs are
     *  model-collected, so nothing else (intent:, file:, javascript:) is ever
     *  launched. */
    fun open(context: Context, url: String) {
        if (!isWebUrl(url)) return
        try {
            context.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse(url))
                    .addCategory(Intent.CATEGORY_BROWSABLE)
                    .apply { if (context !is android.app.Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
            )
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(context, "No browser available", Toast.LENGTH_SHORT).show()
        }
    }

    private fun copy(context: Context, url: String) {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("Source link", url))
        Toast.makeText(context, "Link copied", Toast.LENGTH_SHORT).show()
    }

    /** " · N SOURCES" suffix for a document card's kind label ("" if none). */
    fun countLabel(json: String?): String {
        val n = parse(json).size
        return if (n == 0) "" else " · $n SOURCE${if (n == 1) "" else "S"}"
    }

    /** Markdown list of the sources, for sharing a document as text. */
    fun toMarkdown(json: String?): String {
        val sources = parse(json)
        if (sources.isEmpty()) return ""
        val lines = mutableListOf("", "", "## Sources", "")
        sources.filter { it.cited }.forEach {
            val tag = if (it.via == "browse") " (browsed)" else ""
            lines += "${it.n}. [${it.title}](${it.url}) — ${it.domain}$tag"
        }
        val consulted = sources.filterNot { it.cited }
        if (consulted.isNotEmpty()) {
            lines += ""
            lines += "Also consulted:"
            lines += ""
            consulted.forEach { lines += "- [${it.title}](${it.url}) — ${it.domain}" }
        }
        return lines.joinToString("\n")
    }
}
