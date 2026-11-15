package com.voicenote.app

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import org.json.JSONObject
import java.time.OffsetDateTime

/**
 * Getting replies in on time without push (FCM is a later add-on):
 *
 *  * [AwaitedTurns] remembers which threads have an async turn in flight, so
 *    a drain can tell "still running" (gateway lists it in `busy_threads`)
 *    from "lost" (not busy, nothing pending — the gateway restarted mid-turn).
 *    A lost turn gets an "interrupted" reply instead of silence.
 *  * [DeliveryAlarm] is ONE inexact, Doze-tolerant alarm
 *    (`setAndAllowWhileIdle`, no extra permission) that wakes a drain: soon
 *    while a turn is running, otherwise just after the next scheduled check-in
 *    is due. The 15-min periodic poll stays as the backstop.
 *
 * [reconcile] runs after every drain (PollWorker, and the in-worker await loop
 * when it gives up) and does both.
 */
object DeliveryFollowUp {
    private const val TAG = "DeliveryFollowUp"

    /** Forget an awaited turn after this long, whatever the gateway says. */
    private const val AWAIT_MAX_AGE_MS = 60 * 60_000L

    /** Grace for a just-submitted turn (clock skew / in-flight submit). */
    private const val FRESH_SUBMIT_MS = 15_000L

    /** Check-back ladder while something is still running. */
    private val FOLLOW_UP_MS = longArrayOf(60_000L, 120_000L, 300_000L)

    /** After a check-in's scheduled time, wait this long before draining. */
    private const val AFTER_DUE_MS = 90_000L

    const val INTERRUPTED_REPLY =
        "⚠ This reply was interrupted — the assistant restarted before it finished. " +
            "Tap your message → Resend to try again."

    suspend fun reconcile(context: Context, resp: GatewayAgent.PendingResponse) {
        val now = System.currentTimeMillis()
        val awaited = AwaitedTurns.all(context)
        // Threads that still have a delivery waiting (e.g. one whose apply
        // failed) are neither busy nor lost — leave them for the next drain.
        val pendingThreads = (0 until resp.deliveries.length())
            .mapNotNull { resp.deliveries.optJSONObject(it)?.optLong("thread_id", -1L) }
            .filter { it >= 0 }.toSet()

        var stillRunning = resp.scheduledRunning
        for ((threadId, submittedAt) in awaited) {
            when {
                now - submittedAt > AWAIT_MAX_AGE_MS -> AwaitedTurns.remove(context, threadId)
                threadId in resp.busyThreads -> stillRunning = true
                threadId in pendingThreads -> stillRunning = true
                now - submittedAt < FRESH_SUBMIT_MS -> stillRunning = true
                !resp.reportsBusy -> stillRunning = true   // older gateway: can't tell
                else -> markInterrupted(context, threadId)
            }
        }

        if (stillRunning) {
            val step = AwaitedTurns.bumpFollowUp(context)
            DeliveryAlarm.armIn(context, FOLLOW_UP_MS[minOf(step, FOLLOW_UP_MS.size - 1)])
        } else {
            AwaitedTurns.resetFollowUp(context)
            val due = resp.nextDue?.let { parseIso(it) }
            if (due != null && due > now) DeliveryAlarm.armAt(context, due + AFTER_DUE_MS)
            else DeliveryAlarm.cancel(context)
        }
    }

    /** The gateway no longer runs this thread's turn and never queued its
     *  reply: say so in the thread (the user can resend; nothing re-runs on its
     *  own, since the turn's tools may already have had side effects). */
    private suspend fun markInterrupted(context: Context, threadId: Long) {
        AwaitedTurns.remove(context, threadId)
        Log.w(TAG, "Turn for thread $threadId was lost (not running, no delivery)")
        val db = NoteDatabase.get(context)
        val title = try { db.threadDao().findById(threadId)?.title } catch (e: Exception) { null }
            ?: "assistant"
        GatewayAgent.deliverReply(
            context, title, INTERRUPTED_REPLY, goalId = null, source = "system", threadId = threadId,
        )
        try { db.threadDao().touch(threadId) } catch (e: Exception) { /* best effort */ }
    }

    private fun parseIso(s: String): Long? = try {
        OffsetDateTime.parse(s).toInstant().toEpochMilli()
    } catch (e: Exception) { null }
}

/** Prefs-backed `threadId → submittedAt` of async turns awaiting a reply. */
object AwaitedTurns {
    private const val PREFS = "awaited_turns"
    private const val KEY_MAP = "map"
    private const val KEY_STEP = "follow_up_step"
    private val lock = Any()

    private fun prefs(c: Context) = c.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun read(c: Context): JSONObject = try {
        JSONObject(prefs(c).getString(KEY_MAP, "{}") ?: "{}")
    } catch (e: Exception) { JSONObject() }

    fun add(c: Context, threadId: Long) = synchronized(lock) {
        val m = read(c).put(threadId.toString(), System.currentTimeMillis())
        prefs(c).edit().putString(KEY_MAP, m.toString()).putInt(KEY_STEP, 0).apply()
    }

    fun remove(c: Context, threadId: Long) = synchronized(lock) {
        val m = read(c)
        if (m.has(threadId.toString())) {
            m.remove(threadId.toString())
            prefs(c).edit().putString(KEY_MAP, m.toString()).apply()
        }
    }

    fun all(c: Context): Map<Long, Long> = synchronized(lock) {
        val m = read(c)
        m.keys().asSequence().mapNotNull { k -> k.toLongOrNull()?.let { it to m.optLong(k) } }.toMap()
    }

    fun bumpFollowUp(c: Context): Int = synchronized(lock) {
        val n = prefs(c).getInt(KEY_STEP, 0)
        prefs(c).edit().putInt(KEY_STEP, n + 1).apply()
        n
    }

    fun resetFollowUp(c: Context) = synchronized(lock) {
        prefs(c).edit().putInt(KEY_STEP, 0).apply()
    }
}

/** The single "drain the queue around then" alarm. Re-arming replaces it. */
object DeliveryAlarm {
    private const val REQUEST_CODE = 7311

    private fun pending(c: Context, flags: Int): PendingIntent? = PendingIntent.getBroadcast(
        c.applicationContext, REQUEST_CODE,
        Intent(c.applicationContext, DeliveryAlarmReceiver::class.java),
        flags or PendingIntent.FLAG_IMMUTABLE,
    )

    fun armIn(c: Context, delayMs: Long) = armAt(c, System.currentTimeMillis() + delayMs)

    fun armAt(c: Context, atMs: Long) {
        val am = c.getSystemService(AlarmManager::class.java) ?: return
        val pi = pending(c, PendingIntent.FLAG_UPDATE_CURRENT) ?: return
        // Inexact but allowed in Doze (≈ once per 9 min while idle); needs no
        // SCHEDULE_EXACT_ALARM permission.
        am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMs, pi)
        Log.i("DeliveryAlarm", "armed for +${(atMs - System.currentTimeMillis()) / 1000}s")
    }

    fun cancel(c: Context) {
        val am = c.getSystemService(AlarmManager::class.java) ?: return
        pending(c, PendingIntent.FLAG_NO_CREATE)?.let { am.cancel(it); it.cancel() }
    }
}

class DeliveryAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        PollWorker.enqueueOneShot(context, expedited = true)
    }
}
