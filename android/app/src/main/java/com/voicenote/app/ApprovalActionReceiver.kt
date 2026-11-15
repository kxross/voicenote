package com.voicenote.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Handles the Approve / Deny actions on the sandbox-approval notification.
 * Resolves the held command through the gateway proxy off the main thread, then
 * clears the notification. Triggered only by our own PendingIntents, so it's not
 * exported.
 */
class ApprovalActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val approve = intent.action == ACTION_APPROVE
        val id = intent.getStringExtra(EXTRA_ID) ?: return
        val app = context.applicationContext
        val pending = goAsync()
        Thread {
            try {
                val prefs = Prefs(app)
                GatewayAgent.decideApproval(prefs.gatewayBaseUrl, prefs.gatewayApiKey, id, approve)
            } finally {
                GatewayAgent.cancelApprovalNotification(app)
                pending.finish()
            }
        }.start()
    }

    companion object {
        const val ACTION_APPROVE = "com.voicenote.app.action.APPROVE"
        const val ACTION_DENY = "com.voicenote.app.action.DENY"
        const val EXTRA_ID = "approval_id"
    }
}
