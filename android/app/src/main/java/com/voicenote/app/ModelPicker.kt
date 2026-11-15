package com.voicenote.app

import android.app.Activity
import android.app.Dialog
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.Editable
import android.text.InputType
import android.text.TextUtils
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView

/**
 * The assistant-model picker: models grouped **endpoint › maker › model**
 * ("OpenRouter › Anthropic › claude-opus-5.5"), drilled into one level at a
 * time, with a search box that searches every model at once. Replaces a flat
 * spinner that couldn't cope with hundreds of OpenRouter models.
 *
 * Selection is the same (model name, provider) pair the settings always
 * stored; ("", "") is "Gateway default".
 */
object ModelPicker {

    /** One selectable model with its derived grouping. */
    data class Entry(
        val info: GatewayAgent.ModelInfo,
        val endpoint: String,     // display label of the provider/endpoint
        val maker: String,        // who made the model
        val label: String,        // model name as shown at the leaf level
    ) {
        val value: Pair<String, String> get() = info.name to info.provider
    }

    // ---- grouping -----------------------------------------------------------

    private val ENDPOINT_ORDER = listOf("ollama", "vllm", "openrouter")

    fun endpointLabel(provider: String): String = when (provider) {
        "ollama" -> "Ollama"
        "vllm" -> "vLLM"
        "openrouter" -> "OpenRouter"
        else -> provider.replaceFirstChar { it.uppercase() }
    }

    private fun endpointNote(provider: String): String = when (provider) {
        "ollama", "vllm" -> "local"
        else -> "cloud"
    }

    private val MAKER_NAMES = mapOf(
        "openai" to "OpenAI", "anthropic" to "Anthropic", "google" to "Google",
        "qwen" to "Qwen", "mistralai" to "Mistral", "z-ai" to "Z.ai",
        "deepseek" to "DeepSeek", "moonshotai" to "Moonshot AI", "nvidia" to "NVIDIA",
        "x-ai" to "xAI", "meta" to "Meta", "meta-llama" to "Meta", "cohere" to "Cohere",
        "amazon" to "Amazon", "minimax" to "MiniMax", "openrouter" to "OpenRouter",
        "microsoft" to "Microsoft", "ibm-granite" to "IBM", "bytedance-seed" to "ByteDance",
        "baidu" to "Baidu", "tencent" to "Tencent", "xiaomi" to "Xiaomi",
        "inclusionai" to "inclusionAI", "thinkingmachines" to "Thinking Machines",
        "stepfun" to "StepFun", "upstage" to "Upstage", "liquid" to "Liquid AI",
        "sakana" to "Sakana AI", "aion-labs" to "Aion Labs", "ibm" to "IBM",
    )

    /** Local model tags carry no vendor ("qwen3.8:latest"), so infer it from
     *  the model family. */
    private val FAMILY_MAKERS = listOf(
        Regex("qwen|qwq", RegexOption.IGNORE_CASE) to "Qwen",
        Regex("gemma|gemini", RegexOption.IGNORE_CASE) to "Google",
        Regex("llama", RegexOption.IGNORE_CASE) to "Meta",
        Regex("mistral|mixtral|devstral|magistral|codestral", RegexOption.IGNORE_CASE) to "Mistral",
        Regex("deepseek", RegexOption.IGNORE_CASE) to "DeepSeek",
        Regex("^phi", RegexOption.IGNORE_CASE) to "Microsoft",
        Regex("gpt-oss|gpt", RegexOption.IGNORE_CASE) to "OpenAI",
        Regex("granite", RegexOption.IGNORE_CASE) to "IBM",
        Regex("command", RegexOption.IGNORE_CASE) to "Cohere",
        Regex("glm", RegexOption.IGNORE_CASE) to "Z.ai",
        Regex("kimi", RegexOption.IGNORE_CASE) to "Moonshot AI",
        Regex("nemotron", RegexOption.IGNORE_CASE) to "NVIDIA",
    )

    private fun prettySlug(slug: String): String =
        slug.split('-', '_').filter { it.isNotBlank() }
            .joinToString(" ") { it.replaceFirstChar { c -> c.uppercase() } }

    fun entryOf(m: GatewayAgent.ModelInfo): Entry {
        val name = m.name
        val slash = name.indexOf('/')
        return if (m.provider == "openrouter" && slash > 0) {
            // "anthropic/claude-opus-5.5" (and "~anthropic/…" aliases)
            val vendor = name.substring(0, slash).trimStart('~')
            Entry(m, endpointLabel(m.provider), MAKER_NAMES[vendor] ?: prettySlug(vendor),
                name.substring(slash + 1))
        } else {
            // Local: "qwen3.8:latest", "huihui_ai/Qwen3.6-abliterated:27b"
            val base = name.substringAfterLast('/')
            val maker = FAMILY_MAKERS.firstOrNull { it.first.containsMatchIn(base) }?.second
                ?: (if (slash > 0) prettySlug(name.substring(0, slash)) else "Other")
            Entry(m, endpointLabel(m.provider), maker, name)
        }
    }

    /** "OpenRouter › Anthropic › claude-opus-5.5" for the settings field. */
    fun summary(value: Pair<String, String>, models: List<GatewayAgent.ModelInfo>): String {
        if (value.first.isBlank()) return "Gateway default"
        val m = models.firstOrNull { it.name == value.first && it.provider == value.second }
            ?: GatewayAgent.ModelInfo(value.first, value.second.ifBlank { "ollama" }, false, true)
        val e = entryOf(m)
        return "${e.endpoint} › ${e.maker} › ${e.label}"
    }

    // ---- natural, newest-first ordering of model names -------------------------

    private val NUM = Regex("\\d+")

    private fun naturalKey(s: String): List<Any> {
        val out = mutableListOf<Any>()
        var last = 0
        for (m in NUM.findAll(s)) {
            if (m.range.first > last) out += s.substring(last, m.range.first).lowercase()
            out += m.value.toLong()
            last = m.range.last + 1
        }
        if (last < s.length) out += s.substring(last).lowercase()
        return out
    }

    private val naturalOrder = Comparator<String> { a, b ->
        val ka = naturalKey(a); val kb = naturalKey(b)
        for (i in 0 until minOf(ka.size, kb.size)) {
            val x = ka[i]; val y = kb[i]
            val c = when {
                x is Long && y is Long -> x.compareTo(y)
                else -> x.toString().compareTo(y.toString())
            }
            if (c != 0) return@Comparator c
        }
        ka.size.compareTo(kb.size)
    }

    // ---- the dialog -----------------------------------------------------------

    private const val BG = 0xFF161618.toInt()
    private const val FIELD = 0xFF1F1F22.toInt()
    private const val LINE = 0xFF2A2A2A.toInt()
    private const val TEXT = 0xFFDDDDDD.toInt()
    private const val MUTED = 0xFF888888.toInt()
    private const val DIM = 0xFF555555.toInt()
    private const val ACCENT = 0xFFFF9500.toInt()

    /** A row in the current level. */
    private data class Row(
        val title: String,
        val meta: String,
        val selected: Boolean,
        val drill: Boolean,
        val onTap: () -> Unit,
    )

    fun show(
        activity: Activity,
        models: List<GatewayAgent.ModelInfo>,
        current: Pair<String, String>,
        onPick: (Pair<String, String>) -> Unit,
    ) {
        val entries = models.filter { it.tools }.map { entryOf(it) }
        val currentEntry = entries.firstOrNull { it.value == current }
        val d = activity.resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()

        val dialog = Dialog(activity)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)

        // Navigation state: null endpoint = top level.
        var endpoint: String? = null
        var maker: String? = null
        var query = ""

        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(10), dp(8), dp(8))
            background = GradientDrawable().apply { setColor(BG); cornerRadius = dp(14).toFloat() }
        }
        val header = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, dp(8), dp(6))
        }
        val back = TextView(activity).apply {
            text = "‹"
            textSize = 24f
            setTextColor(MUTED)
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(dp(40), dp(40))
            contentDescription = "Back"
            val tv = TypedValue()
            activity.theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, tv, true)
            setBackgroundResource(tv.resourceId)
        }
        val title = TextView(activity).apply {
            textSize = 12f
            letterSpacing = 0.12f
            setTextColor(MUTED)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.START
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                .apply { marginStart = dp(4) }
        }
        header.addView(back); header.addView(title)

        val search = EditText(activity).apply {
            hint = "Search ${entries.size} models"
            setHintTextColor(DIM)
            setTextColor(TEXT)
            textSize = 14f
            isSingleLine = true
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            background = GradientDrawable().apply {
                setColor(FIELD); cornerRadius = dp(8).toFloat(); setStroke(dp(1), LINE)
            }
            setPadding(dp(12), dp(10), dp(12), dp(10))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { setMargins(dp(8), 0, dp(8), dp(8)) }
        }

        var rows: List<Row> = emptyList()
        val adapter = object : BaseAdapter() {
            override fun getCount() = rows.size
            override fun getItem(position: Int) = rows[position]
            override fun getItemId(position: Int) = position.toLong()
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val r = rows[position]
                val row = (convertView as? LinearLayout) ?: LinearLayout(activity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    minimumHeight = dp(48)
                    setPadding(dp(14), dp(8), dp(12), dp(8))
                    addView(TextView(activity).apply {
                        id = View.generateViewId(); tag = "t"
                        textSize = 15f
                        maxLines = 2
                        ellipsize = TextUtils.TruncateAt.END
                        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                    })
                    addView(TextView(activity).apply {
                        tag = "m"
                        textSize = 11f
                        typeface = Typeface.MONOSPACE
                        maxLines = 1
                        setPadding(dp(10), 0, dp(6), 0)
                    })
                    addView(TextView(activity).apply {
                        tag = "c"
                        textSize = 16f
                        gravity = Gravity.CENTER
                        layoutParams = LinearLayout.LayoutParams(dp(20), ViewGroup.LayoutParams.WRAP_CONTENT)
                    })
                }
                val t = row.findViewWithTag<TextView>("t")
                val m = row.findViewWithTag<TextView>("m")
                val c = row.findViewWithTag<TextView>("c")
                t.text = r.title
                t.setTextColor(if (r.selected) Color.WHITE else TEXT)
                t.typeface = if (r.selected) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
                m.text = r.meta
                m.setTextColor(if (r.selected) ACCENT else DIM)
                c.text = when { r.drill -> "›"; r.selected -> "✓"; else -> "" }
                c.setTextColor(if (r.selected) ACCENT else DIM)
                row.contentDescription = listOf(r.title, r.meta, if (r.selected) "selected" else "")
                    .filter { it.isNotBlank() }.joinToString(", ")
                return row
            }
        }
        val list = ListView(activity).apply {
            this.adapter = adapter
            divider = GradientDrawable().apply { setColor(0xFF222224.toInt()) }
            dividerHeight = dp(1)
            setOnItemClickListener { _, _, pos, _ -> rows.getOrNull(pos)?.onTap?.invoke() }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                (activity.resources.displayMetrics.heightPixels * 0.6).toInt(),
            )
        }

        fun pick(v: Pair<String, String>) { onPick(v); dialog.dismiss() }

        lateinit var refresh: () -> Unit
        refresh = {
            val q = query.trim().lowercase()
            back.visibility = if (endpoint != null && q.isEmpty()) View.VISIBLE else View.INVISIBLE
            rows = when {
                q.isNotEmpty() -> {
                    title.text = "SEARCH"
                    entries.filter {
                        it.info.name.lowercase().contains(q) || it.maker.lowercase().contains(q) ||
                            it.endpoint.lowercase().contains(q)
                    }.sortedWith(compareBy<Entry> { it.endpoint }.thenBy { it.maker })
                        .take(200)
                        .map { e ->
                            Row(e.label, "${e.endpoint} › ${e.maker}", e == currentEntry, false) { pick(e.value) }
                        }
                }
                endpoint == null -> {
                    title.text = "ASSISTANT MODEL"
                    val byEndpoint = entries.groupBy { it.info.provider }
                    val order = byEndpoint.keys.sortedBy {
                        ENDPOINT_ORDER.indexOf(it).let { i -> if (i < 0) 99 else i }
                    }
                    listOf(Row("Gateway default", "", current.first.isBlank(), false) { pick("" to "") }) +
                        order.map { p ->
                            val n = byEndpoint[p]!!.size
                            Row(
                                endpointLabel(p), "${endpointNote(p)} · $n",
                                currentEntry?.info?.provider == p, true,
                            ) { endpoint = p; refresh() }
                        }
                }
                maker == null -> {
                    title.text = endpointLabel(endpoint!!).uppercase()
                    entries.filter { it.info.provider == endpoint }
                        .groupBy { it.maker }
                        .entries.sortedWith(compareByDescending<Map.Entry<String, List<Entry>>> { it.value.size }
                            .thenBy { it.key })
                        .map { (mk, list) ->
                            Row(
                                mk, "${list.size}",
                                currentEntry?.info?.provider == endpoint && currentEntry?.maker == mk, true,
                            ) { maker = mk; refresh() }
                        }
                }
                else -> {
                    title.text = "${endpointLabel(endpoint!!).uppercase()} › ${maker!!.uppercase()}"
                    entries.filter { it.info.provider == endpoint && it.maker == maker }
                        .sortedWith(compareBy(naturalOrder.reversed()) { it.label })
                        .map { e ->
                            Row(e.label, if (e.info.thinking) "thinks" else "", e == currentEntry, false) {
                                pick(e.value)
                            }
                        }
                }
            }
            adapter.notifyDataSetChanged()
            list.setSelection(0)
        }

        back.setOnClickListener {
            if (maker != null) maker = null else endpoint = null
            refresh()
        }
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) { query = s?.toString().orEmpty(); refresh() }
        })
        // System back steps up a level before closing.
        dialog.setOnKeyListener { _, keyCode, event ->
            if (keyCode == android.view.KeyEvent.KEYCODE_BACK &&
                event.action == android.view.KeyEvent.ACTION_UP &&
                query.isEmpty() && endpoint != null
            ) {
                if (maker != null) maker = null else endpoint = null
                refresh(); true
            } else false
        }

        // Open where the current pick lives, so changing within a maker is one tap.
        if (currentEntry != null) { endpoint = currentEntry.info.provider; maker = currentEntry.maker }

        root.addView(header)
        if (entries.size > 12) root.addView(search)
        root.addView(list)
        dialog.setContentView(root)
        dialog.window?.setBackgroundDrawable(GradientDrawable().apply { setColor(Color.TRANSPARENT) })
        dialog.window?.setLayout(
            (activity.resources.displayMetrics.widthPixels * 0.92).toInt(),
            ViewGroup.LayoutParams.WRAP_CONTENT,
        )
        refresh()
        dialog.show()
    }
}
