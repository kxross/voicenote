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
 * Renders an AI-generated React widget ("canvas") into a sandboxed WebView.
 *
 * The widget code is model-generated and therefore untrusted, so the sandbox
 * is deliberately tight:
 *   - Served from a real https origin via [WebViewAssetLoader] so the harness
 *     CSP ('self' + eval, connect-src 'none') is enforced by the engine.
 *   - Every request that isn't one of our bundled assets is denied; network
 *     loads are blocked at the WebView level too (defense in depth).
 *   - File/content access off, no DOM storage, no geolocation, no zoom.
 *   - The ONLY Java object exposed to JS is [HeightBridge], which receives a
 *     single integer (content height) and can read nothing from the app.
 *
 * The JSX never enters the HTML: the harness is a static asset, and the source
 * is injected post-load as a JSON-encoded argument to `window.__renderCanvas`.
 */
object CanvasRenderer {

    private const val HARNESS_URL =
        "https://appassets.androidplatform.net/assets/canvas/index.html"

    /** A denied response for anything the asset loader doesn't serve. */
    private fun denied(): WebResourceResponse =
        WebResourceResponse(
            "text/plain", "utf-8",
            403, "Blocked",
            emptyMap(),
            ByteArrayInputStream(ByteArray(0)),
        )

    /** One-way, number-only channel JS -> native: report content height so the
     *  card can size itself. Holds no app state and returns nothing. */
    private class HeightBridge(
        private val web: WebView,
        private val onHeight: (Int) -> Unit,
    ) {
        @JavascriptInterface
        fun postHeight(cssPx: Int) {
            // Called on a binder thread; hop to the UI thread to touch views.
            web.post {
                val density = web.resources.displayMetrics.density
                val px = (cssPx * density).toInt()
                    .coerceIn(0, (2200 * density).toInt())   // sane ceiling
                onHeight(px)
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled", "AddJavascriptInterface")
    fun render(
        web: WebView,
        canvasJson: String,
        isDark: Boolean,
        onHeight: (Int) -> Unit,
    ) {
        val jsx = try {
            JSONObject(canvasJson).optString("jsx")
        } catch (e: Exception) {
            ""
        }

        val assetLoader = WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(web.context))
            .build()

        web.setBackgroundColor(Color.TRANSPARENT)
        web.isVerticalScrollBarEnabled = false
        web.isHorizontalScrollBarEnabled = false
        web.overScrollMode = WebView.OVER_SCROLL_NEVER

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
            // Model code can't phone home: CSP blocks it, and so does this.
            // Local assets are served by the interceptor, not "network loads",
            // so the harness still loads.
            blockNetworkLoads = true
            cacheMode = WebSettings.LOAD_NO_CACHE
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        }

        // Fresh bridge each render (WebViews are recycled across cards).
        web.removeJavascriptInterface("__canvasHost")
        web.addJavascriptInterface(HeightBridge(web, onHeight), "__canvasHost")

        val theme = if (isDark) "dark" else "light"

        web.webViewClient = object : WebViewClientCompat() {
            override fun shouldInterceptRequest(
                view: WebView,
                request: WebResourceRequest,
            ): WebResourceResponse? {
                // Serve our bundled assets; deny everything else outright.
                return assetLoader.shouldInterceptRequest(request.url) ?: denied()
            }

            // Block any attempt to navigate the frame away from the harness.
            override fun shouldOverrideUrlLoading(
                view: WebView,
                request: WebResourceRequest,
            ): Boolean = true

            override fun onPageFinished(view: WebView, url: String) {
                // Inject JSX as values, not markup. JSONObject.quote produces a
                // safely-escaped JS string literal.
                val jsxLit = JSONObject.quote(jsx)
                val themeLit = JSONObject.quote(theme)
                view.evaluateJavascript(
                    "window.__renderCanvas && window.__renderCanvas($jsxLit, $themeLit);",
                    null,
                )
            }
        }

        web.loadUrl(HARNESS_URL)
    }

    /** Release a recycled WebView's content so it doesn't keep running. */
    fun release(web: WebView) {
        try {
            web.removeJavascriptInterface("__canvasHost")
            web.loadUrl("about:blank")
        } catch (e: Exception) { /* ignore */ }
    }
}
