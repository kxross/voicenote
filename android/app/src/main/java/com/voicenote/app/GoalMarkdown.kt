package com.voicenote.app

/**
 * Round-trippable markdown representation of a goal, used by the goal
 * detail screen's edit mode. The format is deliberately strict so it can
 * be parsed back:
 *
 * ```
 * # Goal title
 *
 * Statement text (until the first ## header).
 *
 * ## Context
 * - (note) Mentioned wanting the Honolulu 10k
 * - (agent) Q: Target date?
 * - (user) December, four days a week
 *
 * ## Plan
 * Summary paragraph(s).
 *
 * ### Milestone label
 * - [ ] task text (due: by end of September)
 *   - [x] subtask text
 *
 * ## Notes
 * - transcript of a scoped note
 * ```
 *
 * Rules: context bullets carry their source as a `(source)` prefix —
 * lines without one become source "user". Tasks use GitHub checkboxes;
 * two-space-indented checkboxes are subtasks of the task above (one
 * level). A trailing `(due: ...)` becomes the due hint. Notes bullets
 * map positionally onto the goal's scoped notes: edited text updates
 * the transcript, removed bullets delete, extra bullets append new
 * scoped notes.
 */
object GoalMarkdown {

    data class MdContext(val text: String, val source: String)
    data class MdSub(val text: String, val due: String, val done: Boolean)
    data class MdTask(
        val text: String,
        val due: String,
        val done: Boolean,
        val milestone: String,
        val subtasks: List<MdSub>,
    )

    data class ParsedGoal(
        val title: String,
        val statement: String,
        val context: List<MdContext>,
        val planSummary: String,
        val hasPlanSection: Boolean,
        val tasks: List<MdTask>,
        val notes: List<String>,
    )

    // ---- render -------------------------------------------------------------

    fun render(entry: GoalWithDetails, notes: List<NoteEntity>): String = buildString {
        appendLine("# ${entry.goal.title}")
        appendLine()
        if (entry.goal.statement.isNotBlank()) {
            appendLine(entry.goal.statement)
            appendLine()
        }

        appendLine("## Context")
        for (c in entry.context.sortedBy { it.createdAt }) {
            appendLine("- (${c.source}) ${c.text}")
        }
        appendLine()

        appendLine("## Plan")
        entry.plan?.let { plan ->
            if (plan.plan.summary.isNotBlank()) {
                appendLine(plan.plan.summary)
                appendLine()
            }
            val sorted = plan.tasks.sortedBy { it.orderIndex }
            val subsByParent = sorted.filter { it.parentTaskId != null }.groupBy { it.parentTaskId!! }
            var lastMilestone: String? = null
            for (t in sorted.filter { it.parentTaskId == null }) {
                if (t.milestone.isNotBlank() && t.milestone != lastMilestone) {
                    lastMilestone = t.milestone
                    appendLine("### ${t.milestone}")
                }
                appendLine(taskLine(t.text, t.dueHint, t.done, indent = ""))
                for (s in subsByParent[t.id].orEmpty()) {
                    appendLine(taskLine(s.text, s.dueHint, s.done, indent = "  "))
                }
            }
        }
        appendLine()

        appendLine("## Notes")
        for (n in notes) {
            appendLine("- ${n.transcript.replace("\n", " ")}")
        }
    }

    private fun taskLine(text: String, due: String, done: Boolean, indent: String): String {
        val box = if (done) "[x]" else "[ ]"
        val dueSuffix = if (due.isNotBlank()) " (due: $due)" else ""
        return "$indent- $box $text$dueSuffix"
    }

    // ---- parse --------------------------------------------------------------

    private val CHECKBOX = Regex("""^(\s*)-\s*\[( |x|X)]\s*(.+)$""")
    private val DUE_SUFFIX = Regex("""\s*\(due:\s*([^)]+)\)\s*$""")
    private val SOURCE_PREFIX = Regex("""^\((note|agent|user)\)\s*""")

    /** Returns null if the document has no `# title` line. */
    fun parse(text: String): ParsedGoal? {
        val lines = text.lines()
        var title: String? = null
        val statement = StringBuilder()
        val context = mutableListOf<MdContext>()
        val summary = StringBuilder()
        val tasks = mutableListOf<MdTask>()
        val notes = mutableListOf<String>()
        var hasPlanSection = false

        var section = "head"   // head | context | plan | notes
        var milestone = ""
        var currentSubs: MutableList<MdSub>? = null

        for (raw in lines) {
            val line = raw.trimEnd()
            when {
                line.startsWith("# ") && title == null -> title = line.removePrefix("# ").trim()
                line.equals("## Context", ignoreCase = true) -> section = "context"
                line.equals("## Plan", ignoreCase = true) -> { section = "plan"; hasPlanSection = true }
                line.equals("## Notes", ignoreCase = true) -> section = "notes"
                line.startsWith("### ") && section == "plan" -> milestone = line.removePrefix("### ").trim()
                else -> when (section) {
                    "head" -> if (line.isNotBlank() && title != null) {
                        if (statement.isNotEmpty()) statement.append("\n")
                        statement.append(line.trim())
                    }
                    "context" -> if (line.trimStart().startsWith("- ")) {
                        var body = line.trimStart().removePrefix("- ").trim()
                        var source = "user"
                        SOURCE_PREFIX.find(body)?.let { m ->
                            source = m.groupValues[1]
                            body = body.removeRange(m.range).trim()
                        }
                        if (body.isNotEmpty()) context.add(MdContext(body, source))
                    }
                    "plan" -> {
                        val m = CHECKBOX.find(raw)
                        if (m != null) {
                            val indent = m.groupValues[1]
                            val done = m.groupValues[2].equals("x", ignoreCase = true)
                            var body = m.groupValues[3].trim()
                            var due = ""
                            DUE_SUFFIX.find(body)?.let { d ->
                                due = d.groupValues[1].trim()
                                body = body.removeRange(d.range).trim()
                            }
                            if (body.isNotEmpty()) {
                                if (indent.length >= 2 && currentSubs != null) {
                                    currentSubs!!.add(MdSub(body, due, done))
                                } else {
                                    val subs = mutableListOf<MdSub>()
                                    tasks.add(MdTask(body, due, done, milestone, subs))
                                    currentSubs = subs
                                }
                            }
                        } else if (line.isNotBlank() && !line.startsWith("#")) {
                            if (summary.isNotEmpty()) summary.append("\n")
                            summary.append(line.trim())
                        }
                    }
                    "notes" -> if (line.trimStart().startsWith("- ")) {
                        val body = line.trimStart().removePrefix("- ").trim()
                        if (body.isNotEmpty()) notes.add(body)
                    }
                }
            }
        }

        val t = title ?: return null
        if (t.isBlank()) return null
        return ParsedGoal(
            title = t,
            statement = statement.toString().trim(),
            context = context,
            planSummary = summary.toString().trim(),
            hasPlanSection = hasPlanSection,
            tasks = tasks,
            notes = notes,
        )
    }

    // ---- apply --------------------------------------------------------------

    /**
     * Writes a parsed edit back to Room. Runs inside a transaction the
     * caller opens. Returns false if the title change collided with an
     * existing goal (everything else is still applied).
     */
    suspend fun apply(
        db: NoteDatabase,
        goalId: Long,
        entry: GoalWithDetails,
        originalNotes: List<NoteEntity>,
        parsed: ParsedGoal,
    ): Boolean {
        val goalDao = db.goalDao()
        val noteDao = db.noteDao()
        var titleOk = true

        // Title (unique index — a collision keeps the old title).
        if (parsed.title != entry.goal.title) {
            val existing = goalDao.findGoalIdByTitle(parsed.title)
            if (existing != null && existing != goalId) {
                titleOk = false
            } else {
                goalDao.updateTitle(goalId, parsed.title)
            }
        }

        if (parsed.statement != entry.goal.statement) {
            goalDao.updateStatement(goalId, parsed.statement)
        }

        // Context: wholesale replace (sources round-trip via the prefix).
        goalDao.deleteContextForGoal(goalId)
        for (c in parsed.context) {
            goalDao.insertContext(GoalContextEntity(goalId = goalId, text = c.text, source = c.source))
        }

        // Plan: update summary + wholesale-replace tasks; plan status is
        // preserved (editing an approved plan's tasks doesn't demote it —
        // the user editing by hand IS the approval authority).
        if (parsed.hasPlanSection && (parsed.tasks.isNotEmpty() || parsed.planSummary.isNotBlank())) {
            val plan = goalDao.findPlanByGoalId(goalId)
            val planId = if (plan == null) {
                goalDao.insertPlan(PlanEntity(goalId = goalId, summary = parsed.planSummary))
            } else {
                goalDao.updatePlanSummary(plan.id, parsed.planSummary)
                goalDao.deleteTasksForPlan(plan.id)
                plan.id
            }
            var order = 0
            for (t in parsed.tasks) {
                val parentId = goalDao.insertTask(
                    GoalTaskEntity(
                        planId = planId, text = t.text, milestone = t.milestone,
                        dueHint = t.due, done = t.done, orderIndex = order++,
                    )
                )
                for (s in t.subtasks) {
                    goalDao.insertTask(
                        GoalTaskEntity(
                            planId = planId, parentTaskId = parentId, text = s.text,
                            dueHint = s.due, done = s.done, orderIndex = order++,
                        )
                    )
                }
            }
        } else if (parsed.hasPlanSection) {
            // Empty Plan section == delete the plan.
            goalDao.deletePlanByGoalId(goalId)
        }

        // Notes: positional mapping against the order they were rendered.
        for (i in parsed.notes.indices) {
            val text = parsed.notes[i]
            val original = originalNotes.getOrNull(i)
            if (original == null) {
                noteDao.insert(NoteEntity(transcript = text, goalId = goalId))
            } else if (original.transcript.replace("\n", " ") != text) {
                noteDao.updateTranscript(original.id, text)
            }
        }
        for (i in parsed.notes.size until originalNotes.size) {
            noteDao.deleteById(originalNotes[i].id)
        }

        return titleOk
    }
}
