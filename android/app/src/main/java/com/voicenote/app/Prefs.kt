package com.voicenote.app

import android.content.Context
import androidx.core.content.edit
import androidx.preference.PreferenceManager

class Prefs(context: Context) {
    private val prefs = PreferenceManager.getDefaultSharedPreferences(context)

    var endpointUrl: String
        get() = prefs.getString(KEY_ENDPOINT_URL, "") ?: ""
        set(value) = prefs.edit { putString(KEY_ENDPOINT_URL, value) }

    /**
     * Base URL for the gateway (no path). Callers append /v1/chat/completions,
     * /v1/snapshot/{session}, /v1/pending/{session}, etc.
     *
     * Legacy values that included the /v1/chat/completions suffix (from
     * before this refactor) are transparently stripped on read.
     */
    var gatewayBaseUrl: String
        get() {
            val raw = prefs.getString(KEY_GATEWAY_URL, "") ?: ""
            return raw.removeSuffix("/v1/chat/completions").trimEnd('/')
        }
        set(value) = prefs.edit {
            putString(KEY_GATEWAY_URL, value.trimEnd('/'))
        }

    /** Convenience: full URL of the OpenAI-compatible chat endpoint. */
    val chatCompletionsUrl: String
        get() = if (gatewayBaseUrl.isBlank()) "" else "$gatewayBaseUrl/v1/chat/completions"

    var gatewayApiKey: String
        get() = prefs.getString(KEY_GATEWAY_API_KEY, "") ?: ""
        set(value) = prefs.edit { putString(KEY_GATEWAY_API_KEY, value) }

    /**
     * Main record button mode. true = "Action" (transcribe → assistant, the
     * present flow); false = "No action" (transcribe + save note only, no LLM
     * bounce — the note still syncs to the gateway). Sticky across sessions,
     * default Action. Only the main record button reads this.
     */
    var actionMode: Boolean
        get() = prefs.getBoolean(KEY_ACTION_MODE, true)
        set(value) = prefs.edit { putBoolean(KEY_ACTION_MODE, value) }

    /**
     * Which Ollama model the assistant should use. Empty = let the gateway use
     * its configured default. Sent with each assistant request; the gateway
     * overrides the model for that turn only.
     */
    var assistantModel: String
        get() = prefs.getString(KEY_ASSISTANT_MODEL, "") ?: ""
        set(value) = prefs.edit { putString(KEY_ASSISTANT_MODEL, value) }

    /**
     * Which provider serves [assistantModel] — "ollama" or "openrouter".
     * Empty = gateway default. Carried explicitly rather than inferred from
     * the model string, since Ollama tags and OpenRouter slugs are easy to
     * confuse.
     */
    var assistantProvider: String
        get() = prefs.getString(KEY_ASSISTANT_PROVIDER, "") ?: ""
        set(value) = prefs.edit { putString(KEY_ASSISTANT_PROVIDER, value) }

    /**
     * Transcribe speech on the device (Android's offline recognizer) instead of
     * uploading audio to the transcription [endpointUrl]. Default off — the
     * endpoint is the primary path; on-device is an opt-in for privacy / offline
     * dictation. Only the main record button honors this; the home-screen widget
     * always uses the endpoint. Falls back to the endpoint if the device has no
     * on-device recognizer. See [OnDeviceTranscriber].
     */
    var onDeviceTranscription: Boolean
        get() = prefs.getBoolean(KEY_ON_DEVICE_STT, false)
        set(value) = prefs.edit { putBoolean(KEY_ON_DEVICE_STT, value) }

    /**
     * Safety cap on a single recording, in minutes. Tap-to-record makes it
     * possible to walk away mid-recording, which press-and-hold prevented by
     * construction.
     */
    var autoStopMinutes: Int
        get() = prefs.getInt(KEY_AUTO_STOP_MINUTES, 5)
        set(value) = prefs.edit { putInt(KEY_AUTO_STOP_MINUTES, value.coerceIn(1, 60)) }

    /**
     * Show the assistant's activity — the tools/skills it used and its
     * reasoning ("thinking") — in the collapsible accordion on each reply.
     * Display-only: the model always reasons and uses tools; this just controls
     * what the UI reveals. Default on. Sent to the gateway (as both show_tools
     * and show_thinking) so it can omit the trace from the payload when off.
     */
    var showActivity: Boolean
        get() = prefs.getBoolean(KEY_SHOW_ACTIVITY, true)
        set(value) = prefs.edit { putBoolean(KEY_SHOW_ACTIVITY, value) }

    /** How far back the SMS sweep scans, in days. */
    var smsLookbackDays: Int
        get() = prefs.getInt(KEY_SMS_LOOKBACK_DAYS, 7)
        set(value) = prefs.edit { putInt(KEY_SMS_LOOKBACK_DAYS, value.coerceIn(1, 90)) }

    /**
     * List the sweep files to-dos into. Pinned rather than agent-chosen: list
     * lookup is exact (case-insensitive) with no fuzzy fallback, so a
     * paraphrased name would silently create a second list.
     */
    var smsTodoList: String
        get() = prefs.getString(KEY_SMS_TODO_LIST, "To-do")?.takeIf { it.isNotBlank() } ?: "To-do"
        set(value) = prefs.edit { putString(KEY_SMS_TODO_LIST, value.trim()) }

    // ---- per-widget scope ---------------------------------------------------
    // Each placed widget remembers its own list/goal scope, keyed by
    // appWidgetId, so several widgets can target different things.

    fun getWidgetGoalId(widgetId: Int): Long = prefs.getLong("widget_goal_$widgetId", -1L)
    fun getWidgetListId(widgetId: Int): Long = prefs.getLong("widget_list_$widgetId", -1L)
    fun getWidgetLabel(widgetId: Int): String =
        prefs.getString("widget_label_$widgetId", "") ?: ""

    /** Set (or clear, with both ids -1) a widget's scope in one write. */
    fun setWidgetScope(widgetId: Int, goalId: Long, listId: Long, label: String) = prefs.edit {
        putLong("widget_goal_$widgetId", goalId)
        putLong("widget_list_$widgetId", listId)
        putString("widget_label_$widgetId", label)
    }

    fun clearWidgetScope(widgetId: Int) = prefs.edit {
        remove("widget_goal_$widgetId")
        remove("widget_list_$widgetId")
        remove("widget_label_$widgetId")
        remove("widget_unlock_$widgetId")
    }

    // ---- widget lock --------------------------------------------------------
    // Stored as an expiry timestamp rather than a boolean, so the widget
    // re-locks itself by simply becoming stale — there's no timer to miss and
    // no way to be left armed in a pocket.

    fun isWidgetUnlocked(widgetId: Int): Boolean =
        System.currentTimeMillis() < prefs.getLong("widget_unlock_$widgetId", 0L)

    fun unlockWidget(widgetId: Int, windowMs: Long = WIDGET_UNLOCK_WINDOW_MS) = prefs.edit {
        putLong("widget_unlock_$widgetId", System.currentTimeMillis() + windowMs)
    }

    fun lockWidget(widgetId: Int) = prefs.edit { remove("widget_unlock_$widgetId") }

    fun getStoreLastFired(id: String): Long =
        prefs.getLong("store_fired_$id", 0L)

    fun setStoreLastFired(id: String, whenMs: Long) =
        prefs.edit { putLong("store_fired_$id", whenMs) }

    companion object {
        /** How long a widget stays armed after you tap its lock. */
        const val WIDGET_UNLOCK_WINDOW_MS = 15_000L

        private const val KEY_ENDPOINT_URL = "endpoint_url"
        private const val KEY_GATEWAY_URL = "gateway_url"
        private const val KEY_GATEWAY_API_KEY = "gateway_api_key"
        private const val KEY_ACTION_MODE = "action_mode"
        private const val KEY_ASSISTANT_MODEL = "assistant_model"
        private const val KEY_ASSISTANT_PROVIDER = "assistant_provider"
        private const val KEY_AUTO_STOP_MINUTES = "auto_stop_minutes"
        private const val KEY_ON_DEVICE_STT = "on_device_stt"
        private const val KEY_SMS_LOOKBACK_DAYS = "sms_lookback_days"
        private const val KEY_SMS_TODO_LIST = "sms_todo_list"
        private const val KEY_SHOW_ACTIVITY = "show_activity"
    }
}
