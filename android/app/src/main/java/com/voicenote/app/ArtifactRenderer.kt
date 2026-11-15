package com.voicenote.app

import android.annotation.SuppressLint
import android.graphics.Color
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebSettings
import androidx.webkit.WebViewAssetLoader
import androidx.webkit.WebViewClientCompat
import org.json.JSONObject
import java.io.ByteArrayInputStream

/**
 * Renders a markdown artifact into a sandboxed WebView (marked -> sanitize ->
 * KaTeX -> mermaid). Same tight sandbox as [CanvasRenderer]: the content is
 * model-generated, so it's served from a real https origin so the harness CSP
 * is engine-enforced, every non-bundled request is denied, network loads are
 * blocked, and the only Java object exposed to JS is a one-way height channel.
 *
 * Used both inline (height-driven card preview — not used in Phase 1 cards,
 * which show a native snippet) and full-screen in the reader (scrollable).
 */
object ArtifactRenderer {

    private const val HARNESS_URL =
        "https://appassets.androidplatform.net/assets/artifact/index.html"

    private fun denied(): WebResourceResponse =
        WebResourceResponse(
            "text/plain", "utf-8", 403, "Blocked",
            emptyMap(), ByteArrayInputStream(ByteArray(0)),
        )

    /** One-way, number-only channel JS -> native: report content height. Holds
     *  no app state and returns nothing. Harmless/ignored for a scrolling
     *  reader; used by any inline, height-sized card. */
    private class HeightBridge(
        private val web: WebView,
        private val onHeight: (Int) -> Unit,
    ) {
        @JavascriptInterface
        fun postHeight(cssPx: Int) {
            web.post {
                val density = web.resources.displayMetrics.density
                onHeight((cssPx * density).toInt().coerceAtLeast(0))
            }
        }
    }

    /**
     * @param scrollable true for the full-screen reader (the WebView scrolls
     *   its own content); false for an inline, height-sized card.
     */
    @SuppressLint("SetJavaScriptEnabled", "AddJavascriptInterface")
    fun render(
        web: WebView,
        markdown: String,
        isDark: Boolean,
        scrollable: Boolean,
        onHeight: (Int) -> Unit = {},
        /** The document's citations (JSON array) — rendered as a linked,
         *  numbered Sources section; inline `[n]` markers become links to it. */
        sourcesJson: String? = null,
        /** The previous version's markdown: when set, what changed since it is
         *  highlighted (with a pill to step through the changes). */
        previousMarkdown: String? = null,
        /** Scroll to the first change once rendered. */
        focusChanges: Boolean = false,
        /** false = keep [previousMarkdown] for [toggleChanges] but start with
         *  the highlights off. */
        showChanges: Boolean = true,
    ) {
        val assetLoader = WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(web.context))
            .build()

        // The reader fills the screen on the theme surface; the transparent
        // harness body then shows the right backdrop.
        web.setBackgroundColor(if (isDark) Color.parseColor("#161618") else Color.WHITE)
        web.isVerticalScrollBarEnabled = scrollable
        web.isHorizontalScrollBarEnabled = false
        if (!scrollable) web.overScrollMode = WebView.OVER_SCROLL_NEVER

        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = false
            databaseEnabled = false
            allowFileAccess = false
            allowContentAccess = false
            @Suppress("DEPRECATION") allowFileAccessFromFileURLs = false
            @Suppress("DEPRECATION") allowUniversalAccessFromFileURLs = false
            setGeolocationEnabled(false)
            mediaPlaybackRequiresUserGesture = true
            setSupportZoom(false)
            builtInZoomControls = false
            displayZoomControls = false
            useWideViewPort = false
            loadWithOverviewMode = false
            blockNetworkLoads = true
            cacheMode = WebSettings.LOAD_NO_CACHE
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        }

        web.removeJavascriptInterface("__artifactHost")
        web.addJavascriptInterface(HeightBridge(web, onHeight), "__artifactHost")

        val theme = if (isDark) "dark" else "light"

        web.webViewClient = object : WebViewClientCompat() {
            override fun shouldInterceptRequest(
                view: WebView,
                request: WebResourceRequest,
            ): WebResourceResponse? =
                assetLoader.shouldInterceptRequest(request.url) ?: denied()

            override fun shouldOverrideUrlLoading(
                view: WebView,
                request: WebResourceRequest,
            ): Boolean {
                // Never navigate the harness itself. A link the user actually
                // TAPPED (a source, or a link in the body) opens in their browser
                // — http(s) only, and only with a user gesture, so model content
                // can't redirect anyone on its own.
                val url = request.url
                val scheme = url.scheme?.lowercase()
                if (request.isForMainFrame && request.hasGesture() &&
                    (scheme == "http" || scheme == "https") &&
                    url.host != "appassets.androidplatform.net"
                ) {
                    SourcesCard.open(view.context, url.toString())
                }
                return true
            }

            override fun onPageFinished(view: WebView, url: String) {
                val mdLit = JSONObject.quote(markdown)
                val themeLit = JSONObject.quote(theme)
                // The scrolling full-screen reader gets the padded reader layout.
                val modeLit = JSONObject.quote(if (scrollable) "reader" else "inline")
                val srcLit = JSONObject.quote(sourcesJson ?: "[]")
                val prevLit = JSONObject.quote(previousMarkdown ?: "")
                view.evaluateJavascript(
                    "window.__renderArtifact && window.__renderArtifact(" +
                        "$mdLit, $themeLit, $modeLit, $srcLit, $prevLit, $focusChanges, $showChanges);",
                    null,
                )
            }
        }

        web.loadUrl(HARNESS_URL)
    }

    /** Show or hide the change highlights (when the page has a previous
     *  version to compare against); reports whether they're now showing. The
     *  in-page "Done" can also hide them, so callers flip from the page's
     *  state rather than their own. */
    fun toggleChanges(web: WebView, onResult: (Boolean) -> Unit) {
        web.evaluateJavascript(
            "(function(){return !!(window.__setChanges && window.__setChanges(" +
                "!(window.__changesShown && window.__changesShown())));})()",
        ) { raw -> onResult(raw == "true") }
    }

    /** The document as email-safe HTML (see `__emailHtml` in boot.js). Empty
     *  string if not rendered yet. */
    fun emailHtml(web: WebView, onResult: (String) -> Unit) {
        web.evaluateJavascript("(function(){return window.__emailHtml?window.__emailHtml():'';})()") { raw ->
            val html = try {
                org.json.JSONArray("[${raw ?: "\"\""}]").optString(0, "")
            } catch (e: Exception) { "" }
            onResult(html)
        }
    }

    /** The rendered document body (sanitized HTML with KaTeX + mermaid SVG
     *  already baked in), for export — without any change highlights. Empty
     *  string if not rendered yet. */
    fun snapshotHtml(web: WebView, onResult: (String) -> Unit) {
        web.evaluateJavascript(
            "(function(){if(window.__cleanHtml)return window.__cleanHtml();" +
                "var r=document.getElementById('root');return r?r.innerHTML:'';})()",
        ) { raw ->
            // evaluateJavascript hands back a JSON-encoded string literal.
            val html = try {
                org.json.JSONArray("[${raw ?: "\"\""}]").optString(0, "")
            } catch (e: Exception) { "" }
            onResult(html)
        }
    }

    fun release(web: WebView) {
        try {
            web.removeJavascriptInterface("__artifactHost")
            web.loadUrl("about:blank")
        } catch (e: Exception) { /* ignore */ }
    }
}
