package com.voicenote.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * On a device timezone change (travel), re-point the gateway's device-mode
 * schedules at the new zone so "8am" keeps meaning 8am wherever you are.
 * Registered in the manifest for ACTION_TIMEZONE_CHANGED (an allowlisted system
 * broadcast). Fixed-mode schedules are unaffected server-side.
 */
class TimeZoneReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_TIMEZONE_CHANGED) return
        val app = context.applicationContext
        val pending = goAsync()
        Thread {
            try {
                val prefs = Prefs(app)
                val tz = java.util.TimeZone.getDefault().id
                GatewayAgent.setDeviceTimezone(prefs.gatewayBaseUrl, prefs.gatewayApiKey, tz)
            } finally {
                pending.finish()
            }
        }.start()
    }
}
