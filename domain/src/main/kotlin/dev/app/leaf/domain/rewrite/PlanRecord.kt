// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.domain.rewrite

import java.util.Base64

/** The name of the plan's record in git's `rebase-merge` folder, which git leaves alone and removes when it's done. */
const val PLAN_RECORD_FILE_NAME = "leaf-plan"

private const val HEADER = "leaf-plan 1"

/**
 * What Leaf leaves in `rebase-merge/leaf-plan` while a commit editor's plan waits at a stop (fork-only, step B4 of
 * `docs/fork/commit-editor.md`): the plan, for "Back to the plan" after an abort, also after a restart, and the twins that
 * stand for the plan's commits ([twins], twin to the commit of the group it stands for), so that the banner, the
 * resolver and Skip name the commits that the user knows.
 */
data class PlanRecord(
    val branch: String,
    val expectedTip: String,
    val onto: OntoBase?,
    val rows: List<Pair<String, PlanAction>>,
    val messages: Map<String, String>,
    val twins: Map<String, String>,
) {
    /** The commit of the plan that [commit] stands for: the group's, for a twin, else [commit] itself. */
    fun originalOf(commit: String): String = twins[commit] ?: commit

    /**
     * The plan again on [line], the branch's line now: null when the branch isn't back at [expectedTip], or the line
     * doesn't have the plan's commits.
     */
    fun planOn(line: BranchLine): RebasePlan? {
        if (line.branch != branch || line.commits.firstOrNull()?.hash != expectedTip) return null

        val hashes = rows.mapTo(mutableSetOf()) { it.first }
        val oldest = line.commits.indexOfLast { it.hash in hashes }

        if (oldest < 0) return null

        val original = line.commits.subList(0, oldest + 1).reversed()
        val byHash = original.associateBy { it.hash }

        if (original.size != rows.size || rows.any { it.first !in byHash }) return null

        return RebasePlan(
            line = line,
            original = original,
            rows = rows.map { (hash, action) -> PlanRow(byHash.getValue(hash), action) },
            messages = messages,
            onto = onto,
        )
    }

    /** The record as its file has it: a line per field, the messages in base64. */
    fun format(): String = buildString {
        appendLine(HEADER)
        appendLine("branch $branch")
        appendLine("tip $expectedTip")
        onto?.let { appendLine("onto ${it.base} ${it.commit}") }
        rows.forEach { (hash, action) -> appendLine("row $hash ${action.name}") }
        messages.forEach { (hash, text) ->
            appendLine("message $hash ${Base64.getEncoder().encodeToString(text.toByteArray(Charsets.UTF_8))}")
        }
        twins.forEach { (twin, group) -> appendLine("twin $twin $group") }
    }

    companion object {
        /** The record of [plan], with [twins] by the group they stand for, as [rebaseTodo] takes them. */
        fun of(plan: RebasePlan, twins: Map<String, String>) = PlanRecord(
            branch = plan.line.branch,
            expectedTip = plan.expectedTip,
            onto = plan.onto,
            rows = plan.rows.map { it.commit.hash to it.action },
            messages = plan.messages,
            twins = twins.entries.associate { (group, twin) -> twin to group },
        )

        /** The record in [text], or null when it isn't one, or of a later version. */
        fun parse(text: String): PlanRecord? {
            val lines = text.lines().filter { it.isNotBlank() }

            if (lines.firstOrNull() != HEADER) return null

            var branch: String? = null
            var tip: String? = null
            var onto: OntoBase? = null
            val rows = mutableListOf<Pair<String, PlanAction>>()
            val messages = mutableMapOf<String, String>()
            val twins = mutableMapOf<String, String>()

            for (line in lines.drop(1)) {
                val fields = line.split(' ')

                when (fields.first()) {
                    "branch" -> branch = fields.getOrNull(1)
                    "tip" -> tip = fields.getOrNull(1)
                    "onto" -> onto = if (fields.size == 3) OntoBase(fields[1], fields[2]) else return null
                    "row" -> rows += (fields.getOrNull(1) ?: return null) to
                        (PlanAction.entries.firstOrNull { it.name == fields.getOrNull(2) } ?: return null)

                    "message" -> {
                        val bytes = runCatching { Base64.getDecoder().decode(fields.getOrNull(2).orEmpty()) }.getOrNull()
                        messages[fields.getOrNull(1) ?: return null] = bytes?.toString(Charsets.UTF_8) ?: return null
                    }

                    "twin" -> twins[fields.getOrNull(1) ?: return null] = fields.getOrNull(2) ?: return null
                }
            }

            return PlanRecord(branch ?: return null, tip ?: return null, onto, rows, messages, twins)
        }
    }
}
