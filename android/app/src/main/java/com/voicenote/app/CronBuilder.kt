package com.voicenote.app

/**
 * Translates a friendly schedule (repeat + time + days/day-of-month) to a 5-field
 * cron string and back, so the UI never exposes cron syntax. Anything the builder
 * can't represent falls back to CUSTOM (raw cron), so power users and
 * API/seed-created schedules still edit cleanly.
 *
 * cron fields: minute hour day-of-month month day-of-week  (dow 0=Sun..6=Sat)
 */
object CronBuilder {

    enum class Freq { HOURLY, DAILY, WEEKLY, MONTHLY, CUSTOM }

    val DAY_LABELS = listOf("Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat")  // 0..6

    data class Spec(
        val freq: Freq,
        val minute: Int = 0,
        val hour: Int = 8,
        val days: Set<Int> = emptySet(),   // 0..6, for WEEKLY
        val dom: Int = 1,                  // 1..31, for MONTHLY
        val raw: String = "",              // for CUSTOM
    )

    fun toCron(s: Spec): String = when (s.freq) {
        Freq.HOURLY -> "${s.minute} * * * *"
        Freq.DAILY -> "${s.minute} ${s.hour} * * *"
        Freq.WEEKLY -> {
            val dow = if (s.days.isEmpty()) "*" else s.days.sorted().joinToString(",")
            "${s.minute} ${s.hour} * * $dow"
        }
        Freq.MONTHLY -> "${s.minute} ${s.hour} ${s.dom} * *"
        Freq.CUSTOM -> s.raw.trim()
    }

    fun timeLabel(hour: Int, minute: Int): String {
        val ampm = if (hour < 12) "AM" else "PM"
        val hh = when { hour == 0 -> 12; hour > 12 -> hour - 12; else -> hour }
        return "%d:%02d %s".format(hh, minute, ampm)
    }

    fun describe(s: Spec): String = when (s.freq) {
        Freq.HOURLY -> "Every hour at :%02d".format(s.minute)
        Freq.DAILY -> "Every day at ${timeLabel(s.hour, s.minute)}"
        Freq.WEEKLY ->
            if (s.days.isEmpty()) "Weekly — pick at least one day"
            else "Weekly on ${s.days.sorted().joinToString(", ") { DAY_LABELS[it] }} " +
                "at ${timeLabel(s.hour, s.minute)}"
        Freq.MONTHLY -> "Monthly on day ${s.dom} at ${timeLabel(s.hour, s.minute)}"
        Freq.CUSTOM -> "Custom cron"
    }

    /** Parse a cron into a builder Spec, or CUSTOM(raw) if it doesn't fit. */
    fun parse(cron: String): Spec {
        val f = cron.trim().split(Regex("\\s+"))
        if (f.size != 5) return Spec(Freq.CUSTOM, raw = cron)
        val (min, hour, dom, mon, dow) = f
        val mi = min.toIntOrNull()
        if (mon != "*") return Spec(Freq.CUSTOM, raw = cron)

        // weekly: dow set, dom any-day
        if (dom == "*" && dow != "*") {
            val hh = hour.toIntOrNull()
            val days = parseDow(dow)
            return if (mi != null && hh != null && days != null)
                Spec(Freq.WEEKLY, mi, hh, days = days) else Spec(Freq.CUSTOM, raw = cron)
        }
        // monthly: specific day-of-month, any weekday
        if (dom != "*" && dow == "*") {
            val hh = hour.toIntOrNull(); val dd = dom.toIntOrNull()
            return if (mi != null && hh != null && dd != null && dd in 1..31)
                Spec(Freq.MONTHLY, mi, hh, dom = dd) else Spec(Freq.CUSTOM, raw = cron)
        }
        // daily / hourly
        if (dom == "*" && dow == "*") {
            if (hour == "*" && mi != null) return Spec(Freq.HOURLY, minute = mi)
            val hh = hour.toIntOrNull()
            return if (mi != null && hh != null) Spec(Freq.DAILY, mi, hh)
                   else Spec(Freq.CUSTOM, raw = cron)
        }
        return Spec(Freq.CUSTOM, raw = cron)
    }

    private fun parseDow(dow: String): Set<Int>? {
        val out = mutableSetOf<Int>()
        for (part in dow.split(",")) {
            if ("-" in part) {
                val ab = part.split("-")
                if (ab.size != 2) return null
                val a = ab[0].toIntOrNull() ?: return null
                val b = ab[1].toIntOrNull() ?: return null
                if (a > b) return null
                for (d in a..b) out.add(if (d == 7) 0 else d)
            } else {
                val d = part.toIntOrNull() ?: return null
                out.add(if (d == 7) 0 else d)
            }
        }
        val norm = out.filter { it in 0..6 }.toSet()
        return norm.ifEmpty { null }
    }
}
