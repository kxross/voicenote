package com.voicenote.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Geofences don't survive device reboot — re-register on boot.
 * `LOCKED_BOOT_COMPLETED` fires earlier (before device unlock) on
 * devices with direct boot; either action gets us re-registered.
 *
 * Alarms don't survive reboot either: after unlock, drain once — the drain
 * re-arms the delivery follow-up alarm (the DB needs the unlocked device).
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED -> {
                GeofenceRegistrar.register(context)
                PollWorker.enqueueOneShot(context)
            }
            Intent.ACTION_LOCKED_BOOT_COMPLETED -> GeofenceRegistrar.register(context)
        }
    }
}
