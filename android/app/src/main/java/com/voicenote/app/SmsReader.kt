package com.voicenote.app

import android.content.Context
import android.provider.Telephony
import android.util.Log
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Read-only scan of the SMS inbox for the to-do sweep.
 *
 * Deliberately narrow: it reads a bounded, recent window, drops anything that
 * looks automated (2FA codes, marketing blasts), and caps the result so a busy
 * inbox can't blow up the model's context. Sender stays a raw address —
 * resolving contact names would mean asking for READ_CONTACTS too, a second
 * sensitive permission for marginal benefit.
 */
object SmsReader {

    private const val TAG = "SmsReader"

    /** Hard ceiling per sweep, regardless of lookback. */
    const val MAX_MESSAGES = 200

    data class Message(
        val sender: String,
        val body: String,
        val timestamp: Long,
    )

    /**
     * Inbox messages newer than [lookbackDays], newest first, filtered and
     * capped. Returns empty if the permission is missing or the query fails —
     * callers treat that as "nothing to sweep" rather than an error.
     */
    fun recent(context: Context, lookbackDays: Int): List<Message> {
        val since = System.currentTimeMillis() -
            TimeUnit.DAYS.toMillis(lookbackDays.coerceAtLeast(1).toLong())
        val out = mutableListOf<Message>()
        try {
            context.contentResolver.query(
                Telephony.Sms.Inbox.CONTENT_URI,
                arrayOf(
                    Telephony.Sms.ADDRESS,
                    Telephony.Sms.BODY,
                    Telephony.Sms.DATE,
                ),
                "${Telephony.Sms.DATE} >= ?",
                arrayOf(since.toString()),
                "${Telephony.Sms.DATE} DESC",
            )?.use { cursor ->
                val iAddr = cursor.getColumnIndexOrThrow(Telephony.Sms.ADDRESS)
                val iBody = cursor.getColumnIndexOrThrow(Telephony.Sms.BODY)
                val iDate = cursor.getColumnIndexOrThrow(Telephony.Sms.DATE)
                while (cursor.moveToNext() && out.size < MAX_MESSAGES) {
                    val sender = cursor.getString(iAddr) ?: continue
                    val body = cursor.getString(iBody) ?: continue
                    if (body.isBlank()) continue
                    if (looksAutomated(sender, body)) continue
                    out.add(Message(sender, body.trim(), cursor.getLong(iDate)))
                }
            }
        } catch (e: SecurityException) {
            Log.w(TAG, "READ_SMS not granted: ${e.message}")
            return emptyList()
        } catch (e: Exception) {
            Log.w(TAG, "SMS query failed: ${e.message}")
            return emptyList()
        }
        Log.i(TAG, "Swept ${out.size} message(s) from the last $lookbackDays day(s)")
        return out
    }

    /**
     * True for senders/bodies that are almost certainly machine-generated.
     * Keeping these out matters twice over: they're never real to-dos, and
     * they'd otherwise dominate the context window.
     */
    fun looksAutomated(sender: String, body: String): Boolean {
        // Short codes (3-6 digits once formatting is stripped) are the
        // hallmark of automated senders; real contacts have full numbers.
        val digits = sender.filter { it.isDigit() }
        val normalized = sender.trim()
        if (normalized.all { it.isDigit() || it == '-' || it == ' ' || it == '+' } &&
            digits.length in 3..6
        ) {
            return true
        }

        val lower = body.lowercase()
        if (OPT_OUT.any { it in lower }) return true
        // A verification code: a bare 4-8 digit run alongside code-ish wording.
        if (CODE_WORDS.any { it in lower } && CODE_PATTERN.containsMatchIn(body)) return true
        return false
    }

    /** Render for the model: newest last reads more naturally as a transcript. */
    fun format(messages: List<Message>): String =
        messages.sortedBy { it.timestamp }.joinToString("\n") { m ->
            "[${STAMP.format(Date(m.timestamp))}] ${m.sender}: ${m.body.replace('\n', ' ')}"
        }

    private val STAMP = SimpleDateFormat("MMM d HH:mm", Locale.getDefault())
    private val CODE_PATTERN = Regex("""\b\d{4,8}\b""")
    private val CODE_WORDS = listOf(
        "verification", "verify", "one-time", "one time", "otp",
        "security code", "login code", "access code", "do not share",
    )
    private val OPT_OUT = listOf(
        "unsubscribe", "stop to opt out", "reply stop", "txt stop",
        "msg&data rates", "message and data rates",
    )
}
