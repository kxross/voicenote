package com.voicenote.app

import android.util.Log
import okhttp3.Request
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources
import org.json.JSONObject

/**
 * Tails the gateway's per-session live progress feed
 * (`GET /v1/progress/{session}`) and hands each event to a callback. Used by
 * ThreadDetailActivity to drive the in-flight status line — "Updating your
 * lists", "Searching the web" — as the assistant works.
 *
 * Events arrive on an OkHttp dispatcher thread, so [onEvent] must hop to the
 * main thread itself. Reuses GatewayAgent's OkHttp client (its 300s read
 * timeout suits a long-lived SSE; the server sends keepalives every 15s).
 */
object ProgressStream {

    private const val TAG = "ProgressStream"

    /** Open a feed. Returns the EventSource; call cancel() to close it, or null
     *  if the gateway URL is blank. */
    fun open(
        gatewayBaseUrl: String,
        gatewayApiKey: String?,
        session: String,
        onEvent: (JSONObject) -> Unit,
    ): EventSource? {
        if (gatewayBaseUrl.isBlank()) return null
        val builder = Request.Builder()
            .url("$gatewayBaseUrl/v1/progress/$session")
            .header("Accept", "text/event-stream")
        GatewayAgent.addAuth(builder, gatewayApiKey)

        val listener = object : EventSourceListener() {
            override fun onEvent(source: EventSource, id: String?, type: String?, data: String) {
                try {
                    onEvent(JSONObject(data))
                } catch (e: Exception) {
                    // Non-JSON keepalive/comment lines never reach here, but be safe.
                    Log.v(TAG, "skip event: ${e.message}")
                }
            }

            override fun onFailure(source: EventSource, t: Throwable?, response: okhttp3.Response?) {
                Log.v(TAG, "progress stream closed: ${t?.message ?: response?.code}")
            }
        }
        return EventSources.createFactory(GatewayAgent.httpClient)
            .newEventSource(builder.build(), listener)
    }
}
