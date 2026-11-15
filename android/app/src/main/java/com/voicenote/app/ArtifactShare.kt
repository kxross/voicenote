package com.voicenote.app

import android.app.Activity
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.print.PrintAttributes
import android.text.Html
import android.print.PrintManager
import android.webkit.WebView
import androidx.core.content.FileProvider
import java.io.File

/**
 * Sharing an artifact with people who don't have the app. Three formats, all
 * readable anywhere:
 *
 *  - **PDF** — Android's print flow on the rendered reader ("Save as PDF");
 *    the harness's `@media print` rules force ink-on-white.
 *  - **Web page** — a single self-contained `.html` file built from the
 *    *rendered* document (math + diagrams already baked in), styled with the
 *    reader's own CSS, script-free, behind a CSP that blocks every load. Opens
 *    in any browser or mail client.
 *  - **Email** — the rendered document as a formatted email body (Gmail when
 *    installed), with the full web page attached.
 *  - **Text** — the raw markdown, for messaging apps.
 */
object ArtifactShare {

    private const val SHARE_DIR = "shared"

    fun shareText(context: Context, title: String, markdown: String) {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, title)
            putExtra(Intent.EXTRA_TITLE, title)
            putExtra(Intent.EXTRA_TEXT, markdown)
        }
        context.startActivity(Intent.createChooser(send, "Share text"))
    }

    fun printPdf(activity: Activity, web: WebView, title: String) {
        val pm = activity.getSystemService(Context.PRINT_SERVICE) as PrintManager
        val job = title.ifBlank { "Document" }
        // Paper can't expand a collapsed section: open "Also consulted" (and any
        // other <details>) first so every source makes it into the PDF.
        web.evaluateJavascript("document.querySelectorAll('details').forEach(function(d){d.open=true});") {
            pm.print(job, web.createPrintDocumentAdapter(job), PrintAttributes.Builder().build())
        }
    }

    /** Write [renderedBody] (from [ArtifactRenderer.snapshotHtml]) as a
     *  standalone page and hand it to the share sheet. */
    fun shareHtml(context: Context, title: String, renderedBody: String, isDark: Boolean) {
        val uri = writeStandalone(context, title, renderedBody, isDark)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/html"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, title)
            putExtra(Intent.EXTRA_TITLE, title)
            clipData = ClipData.newRawUri(title, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(send, "Share document"))
    }

    private fun writeStandalone(context: Context, title: String, renderedBody: String, isDark: Boolean): Uri {
        val dir = File(context.cacheDir, SHARE_DIR).apply { mkdirs() }
        // Only the latest export is ever needed; don't let old ones pile up.
        dir.listFiles()?.forEach { it.delete() }
        val file = File(dir, "${slug(title)}.html")
        file.writeText(standalonePage(context, title, renderedBody, isDark))
        return FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    }

    private const val GMAIL = "com.google.android.gm"

    /**
     * Email the document **formatted**: the body is the rendered document as
     * rich text (headings, bold, links, lists, labeled table rows — from the
     * page's `__emailHtml`), not raw markdown, and the full self-contained page
     * rides along as an attachment for diagrams, math and exact layout. Opens
     * Gmail directly when installed, otherwise any email app.
     *
     * [emailHtml] comes from [ArtifactRenderer.emailHtml]; [renderedBody] from
     * [ArtifactRenderer.snapshotHtml].
     */
    fun emailFormatted(
        activity: Activity,
        title: String,
        emailHtml: String,
        renderedBody: String,
    ) {
        val html = "<h1>${escape(title)}</h1>$emailHtml"
        // Rich text for Gmail (it keeps a Spanned body's formatting); the HTML
        // itself for clients that read EXTRA_HTML_TEXT.
        val rich = Html.fromHtml(html, Html.FROM_HTML_MODE_LEGACY)
        // Recipients read in light; attach the page that way regardless of theme.
        val attachment = if (renderedBody.isNotBlank()) {
            writeStandalone(activity, title, renderedBody, isDark = false)
        } else null

        fun build(): Intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/html"
            putExtra(Intent.EXTRA_SUBJECT, title)
            putExtra(Intent.EXTRA_TEXT, rich)
            putExtra(Intent.EXTRA_HTML_TEXT, html)
            if (attachment != null) {
                putExtra(Intent.EXTRA_STREAM, attachment)
                clipData = ClipData.newRawUri(title, attachment)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        }

        val gmail = build().setPackage(GMAIL)
        if (gmail.resolveActivity(activity.packageManager) != null) {
            activity.startActivity(gmail)
            return
        }
        // No Gmail: offer only email apps (the mailto: selector filters the
        // chooser down to them).
        val any = build().apply { selector = Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:")) }
        try {
            activity.startActivity(Intent.createChooser(any, "Email document"))
        } catch (e: android.content.ActivityNotFoundException) {
            android.widget.Toast.makeText(activity, "No email app found", android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    private fun standalonePage(context: Context, title: String, body: String, isDark: Boolean): String {
        val css = harnessCss(context)
        val theme = if (isDark) "dark" else "light"
        return """<!doctype html>
<html lang="en" data-theme="$theme">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<meta http-equiv="Content-Security-Policy" content="default-src 'none'; style-src 'unsafe-inline'; img-src data:">
<title>${escape(title)}</title>
<style>
$css
/* Standalone export: own the page background and margins. */
html, body { background: var(--bg); }
#root { max-width: 760px; margin: 0 auto; padding: 28px 22px 56px; }
/* KaTeX fonts aren't embedded, so show the browser-native MathML twin. */
.katex .katex-html { display: none; }
</style>
</head>
<body>
<div id="root">$body</div>
</body>
</html>
"""
    }

    /** The reader's own stylesheet, lifted from the harness so exports always
     *  match what the user saw. */
    private fun harnessCss(context: Context): String = try {
        val html = context.assets.open("artifact/index.html").bufferedReader().use { it.readText() }
        Regex("<style>([\\s\\S]*?)</style>").find(html)?.groupValues?.get(1).orEmpty()
    } catch (e: Exception) { "" }

    private fun slug(title: String): String =
        title.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').take(60).ifBlank { "document" }

    private fun escape(s: String): String =
        s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
}
