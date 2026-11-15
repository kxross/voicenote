package com.voicenote.app

import android.graphics.Typeface
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import org.json.JSONArray

/**
 * Renders the collapsible "activity" accordion on an agent card — what the
 * assistant did this turn (skills loaded, tools called, reasoning), parsed from
 * the message's `steps` JSON. Shared by the plain and canvas agent cards, whose
 * layouts expose the same view ids.
 *
 * Display is gated by two user prefs (showThinking / showToolActivity). The
 * gateway already omits what's toggled off; this is the client-side guard too.
 * Collapsed to a single caret by default; the expand state lives in the adapter
 * so it survives RecyclerView recycling.
 */
object ActivityAccordion {

    fun bind(
        section: View,
        header: View,
        caret: TextView,
        summary: TextView,
        body: LinearLayout,
        stepsJson: String?,
        messageId: Long,
        expanded: MutableSet<Long>,
    ) {
        val prefs = Prefs(section.context)
        val steps = parse(stepsJson, prefs.showActivity, prefs.showActivity)
        if (steps.isEmpty()) {
            section.visibility = View.GONE
            header.setOnClickListener(null)
            return
        }
        section.visibility = View.VISIBLE
        summary.text = summarize(steps)

        body.removeAllViews()
        for (s in steps) body.addView(stepRow(body, s))

        fun apply(isOpen: Boolean) {
            caret.text = if (isOpen) "▾" else "▸"
            body.visibility = if (isOpen) View.VISIBLE else View.GONE
        }
        apply(messageId in expanded)

        header.setOnClickListener {
            val nowOpen = if (messageId in expanded) {
                expanded.remove(messageId); false
            } else {
                expanded.add(messageId); true
            }
            apply(nowOpen)
        }
    }

    private data class Step(val kind: String, val label: String, val ok: Boolean, val text: String)

    private fun parse(json: String?, showThinking: Boolean, showTools: Boolean): List<Step> {
        if (json.isNullOrBlank()) return emptyList()
        return try {
            val arr = JSONArray(json)
            val out = ArrayList<Step>(arr.length())
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                // Prefer the goal-relative label; fall back to the raw name.
                val label = o.optString("label").ifBlank { o.optString("name") }
                when (o.optString("kind")) {
                    "think" -> if (showThinking) {
                        val t = o.optString("text").trim()
                        if (t.isNotEmpty()) out.add(Step("think", "", true, t))
                    }
                    "skill" -> if (showTools) out.add(Step("skill", label, true, ""))
                    "tool" -> if (showTools)
                        out.add(Step("tool", label, o.optBoolean("ok", true), ""))
                }
            }
            out
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun summarize(steps: List<Step>): String {
        val n = steps.size
        val labels = steps.filter { it.kind != "think" }.map { it.label }.distinct().take(2)
        val tail = if (labels.isEmpty()) "" else " · " + labels.joinToString(", ")
        return "$n ${if (n == 1) "step" else "steps"}$tail"
    }

    private fun stepRow(parent: LinearLayout, s: Step): TextView {
        val tv = TextView(parent.context)
        tv.textSize = 12f
        val d = parent.resources.displayMetrics.density
        tv.setPadding(0, (2 * d).toInt(), 0, (2 * d).toInt())
        when (s.kind) {
            "skill" -> {
                tv.text = "◆  ${s.label}"
                tv.setTextColor(0xFFAAAAAA.toInt())
                tv.typeface = Typeface.MONOSPACE
            }
            "tool" -> {
                tv.text = "●  ${s.label}  ${if (s.ok) "✓" else "✗"}"
                tv.setTextColor(if (s.ok) 0xFFAAAAAA.toInt() else 0xFFCC7777.toInt())
                tv.typeface = Typeface.MONOSPACE
            }
            "think" -> {
                tv.text = s.text
                tv.setTextColor(0xFF888888.toInt())
                tv.setTypeface(tv.typeface, Typeface.ITALIC)
            }
        }
        return tv
    }
}
