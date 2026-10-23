// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.domain.rewrite

import dev.app.leaf.domain.models.Commit

/** What a row of the commit editor's plan does with its commit, as git's todo names it. */
enum class PlanAction {
    PICK,
    REWORD,

    /** Joins the commit into the commit below it (the older one), with its message added. */
    SQUASH,

    /** Joins the commit into the commit below it, without its message, or with it in place of all for an `amend!`. */
    FIXUP,

    /** Stops after the commit, for its files to be changed. */
    EDIT,
    DROP;

    val joins: Boolean
        get() = this == SQUASH || this == FIXUP
}

/** A commit of the plan and what it does. */
data class PlanRow(
    val commit: Commit,
    val action: PlanAction,
)

/**
 * Rows that become one commit: [target], then the rows that join it ([members]), oldest first, as git's todo lists them.
 * [joinsNothing] is true when [target] is set to join but no kept row is below it, so it stays a commit of its own.
 */
data class PlanGroup(
    val target: PlanRow,
    val members: List<PlanRow>,
    val joinsNothing: Boolean = false,
) {
    val commits: List<Commit>
        get() = listOf(target.commit) + members.map { it.commit }

    /** Whether the rebase stops after the group's first commit, for its files to be changed. */
    val stops: Boolean
        get() = target.action == PlanAction.EDIT
}

/** Why a plan can't run as it is, which the editor shows in its footer. */
sealed interface PlanRefusal {
    /** [merge] would become one commit with others, or stop to be edited, and lose its other parents. */
    data class JoinsMerge(val merge: String) : PlanRefusal

    /** The commits before [merge] change, so it would have to be merged again. */
    data class ReplaysMerge(val merge: String) : PlanRefusal

    /** The plan stops, and `git rebase -i` would have to pick [merge], which it can't. */
    data class MergeInRebase(val merge: String) : PlanRefusal
}

/**
 * The commit editor's plan for some of a branch's own commits (fork-only, step B1 of `docs/fork/commit-editor.md`): a row
 * per commit, oldest first as git's todo lists them, which the editor shows newest first. Nothing in it touches the
 * repository: the plan is checked (B2), then run without a working tree when it doesn't stop (B3), or with
 * `git rebase -i` when it does (B4).
 *
 * @param line the branch's line, as read when the editor opened. A plan is for that line while its tip doesn't move.
 * @param original the commits the editor shows, oldest first: the line's from the first one picked to its tip.
 * @param rows the plan, oldest first.
 * @param messages new messages, by the hash of the commit that leads their group (or of a reworded commit).
 * @param onto the base's commit to move the commits onto, or null to leave them where the branch started.
 */
data class RebasePlan(
    val line: BranchLine,
    val original: List<Commit>,
    val rows: List<PlanRow>,
    val messages: Map<String, String> = emptyMap(),
    val onto: OntoBase? = null,
) {
    /** The branch's tip when the plan was made: the plan is stale once the branch is elsewhere. */
    val expectedTip: String
        get() = line.commits.first().hash

    /** Whether the plan starts where the branch left its base, which moving it onto the latest base needs. */
    val canMoveOnto: Boolean
        get() = line.base != null && !line.isTruncated && original.firstOrNull()?.hash == line.commits.last().hash

    /** The rows that become commits, as groups, oldest first. Dropped rows are left out. */
    val groups: List<PlanGroup> by lazy { groupsOf(rows) }

    /**
     * How many rows at the start stay as they are: picked, in their place, without a new message, and not joined by the
     * row after them. They keep their commits, and `git rebase -i` starts after them. None stays when [onto] moves them.
     */
    val unchangedPrefix: Int by lazy {
        if (onto != null) return@lazy 0

        var count = 0

        while (count < rows.size) {
            val row = rows[count]
            val next = rows.getOrNull(count + 1)

            if (row.commit.hash != original.getOrNull(count)?.hash || !isKeptAsItIs(row) || next?.action?.joins == true) {
                break
            }

            count++
        }

        count
    }

    /** Whether running the plan changes anything. */
    val isChanged: Boolean
        get() = onto != null || unchangedPrefix < rows.size

    val dropped: Int
        get() = rows.count { it.action == PlanAction.DROP }

    /** How many rows join the row below them. */
    val joined: Int
        get() = groups.sumOf { it.members.size }

    /** Whether the kept commits are in another order than they were. */
    val isReordered: Boolean
        get() {
            val kept = rows.filter { it.action != PlanAction.DROP }.map { it.commit.hash }
            val keptSet = kept.toSet()

            return kept != original.map { it.hash }.filter { it in keptSet }
        }

    /** How many rows stop to be edited. */
    val edits: Int
        get() = rows.count { it.action == PlanAction.EDIT }

    /** How many of the commits that the plan rewrites the branch's upstream has: they need a force push. */
    val rewrittenPushed: Int
        get() = rows.drop(unchangedPrefix).count { it.commit.hash in line.pushed }

    /**
     * Whether the commit of row [index] gets other commits below it than it had: then its changes are picked onto
     * another tree, which can conflict. A row whose kept rows below it are the ones it had, in their order, keeps its
     * tree.
     */
    fun isReplayed(index: Int): Boolean {
        if (onto != null) return true

        val hash = rows[index].commit.hash
        val before = rows.subList(0, index).filter { it.action != PlanAction.DROP }.map { it.commit.hash }

        return before != original.takeWhile { it.hash != hash }.map { it.hash }
    }

    /**
     * The message of [group]'s commit: the one written in the editor, else what git's todo gives it, as
     * [groupMessageStart] puts it together. Null keeps the first commit's own message, as for a group of fixups.
     */
    fun messageOf(group: PlanGroup): String? = messages[group.target.commit.hash] ?: defaultMessageOf(group)

    /**
     * The message that [group]'s commit starts from in the editor's panel. Its commit's own for a group of fixups, a
     * `fixup -C` message for an `amend!` fixup, and [groupMessageStart]'s for a group with squashes.
     */
    fun messageStart(group: PlanGroup, start: SquashMessageStart = SquashMessageStart.SUBJECTS): String =
        groupMessageStart(group, start) ?: messageText(group.target.commit.message)

    /** Why the plan can't run as it is, or null when it can. [stops] is whether Leaf's check found a conflict in it. */
    fun refusal(stops: Boolean = false): PlanRefusal? {
        rows.forEachIndexed { index, row ->
            if (row.commit.parentCount > 1 && row.action != PlanAction.DROP) {
                val group = groups.first { group -> group.commits.any { it.hash == row.commit.hash } }

                if (group.members.isNotEmpty() || row.action == PlanAction.EDIT) {
                    return PlanRefusal.JoinsMerge(row.commit.hash)
                }

                if (isReplayed(index)) {
                    return PlanRefusal.ReplaysMerge(row.commit.hash)
                }

                if ((stops || edits > 0) && index >= unchangedPrefix) {
                    return PlanRefusal.MergeInRebase(row.commit.hash)
                }
            }
        }

        return null
    }

    /**
     * The plan with [hash]'s row set to [action]. The first kept row can't join the row below it, as there's none, so
     * the plan stays as it is then.
     */
    fun withAction(hash: String, action: PlanAction): RebasePlan {
        val changed = rows.map { if (it.commit.hash == hash) it.copy(action = action) else it }
        val firstKept = changed.firstOrNull { it.action != PlanAction.DROP }

        return if (action.joins && firstKept?.commit?.hash == hash) this else copy(rows = changed)
    }

    /** The plan with the row at [from] moved to [to] (indices oldest first). A row that lands first doesn't join. */
    fun moved(from: Int, to: Int): RebasePlan {
        if (from == to || from !in rows.indices || to !in rows.indices) return this

        val changed = rows.toMutableList()
        changed.add(to, changed.removeAt(from))

        return copy(rows = withFirstKeptPicked(changed))
    }

    /** The plan with [hash]'s row one place newer ([towardsNewer]) or older. */
    fun movedOne(hash: String, towardsNewer: Boolean): RebasePlan {
        val from = rows.indexOfFirst { it.commit.hash == hash }

        return if (from < 0) this else moved(from, if (towardsNewer) from + 1 else from - 1)
    }

    /** The plan with [message] for the commit of the group that [hash] leads; a lone pick becomes a reword. */
    fun withMessage(hash: String, message: String): RebasePlan {
        val group = groups.firstOrNull { it.target.commit.hash == hash } ?: return this
        val changedRows = if (group.members.isEmpty() && group.target.action == PlanAction.PICK) {
            rows.map { if (it.commit.hash == hash) it.copy(action = PlanAction.REWORD) else it }
        } else {
            rows
        }

        return copy(rows = changedRows, messages = messages + (hash to message))
    }

    /** The plan without the message written for the group that [hash] leads: it starts again from its default. */
    fun withoutMessage(hash: String): RebasePlan = copy(messages = messages - hash)

    /** The plan moved onto [onto], or back to where the branch started with null. Only when [canMoveOnto]. */
    fun withOnto(onto: OntoBase?): RebasePlan = if (onto != null && !canMoveOnto) this else copy(onto = onto)

    /** The plan as it was when the editor opened. */
    fun reset(): RebasePlan = copy(rows = original.map { PlanRow(it, PlanAction.PICK) }, messages = emptyMap())

    /**
     * The `fixup!`, `squash!` and `amend!` rows that aren't joined to the commit that they name, a kept row below them,
     * as `git rebase --autosquash` finds it ([autosquashTarget]): "Put them there" moves them.
     */
    val misplacedFixups: List<PlanRow> by lazy { fixupsToPlace(rows).map { it.first } }

    /**
     * The plan with [misplacedFixups] under the commits that they name, as `git rebase --autosquash` puts them: each
     * after the rows that already join its commit, as a squash for `squash!` and a fixup otherwise.
     */
    fun withFixupsPlaced(): RebasePlan {
        var changed = rows

        // Each round places one row, which then joins the commit it names: at most one round a row.
        repeat(rows.size) {
            val (row, target) = fixupsToPlace(changed).firstOrNull() ?: return copy(rows = withFirstKeptPicked(changed))
            val without = changed.filter { it.commit.hash != row.commit.hash }
            val targetAt = without.indexOfFirst { it.commit.hash == target.commit.hash }
            var at = targetAt + 1

            while (at < without.size && (without[at].action.joins || without[at].action == PlanAction.DROP)) {
                at++
            }

            val action = if (autosquashPrefix(row.commit) == SQUASH_PREFIX) PlanAction.SQUASH else PlanAction.FIXUP
            changed = without.toMutableList().apply { add(at, row.copy(action = action)) }
        }

        return copy(rows = withFirstKeptPicked(changed))
    }

    /** Whether [row] keeps its commit as it is: a pick, or a reword whose message is still the commit's own. */
    private fun isKeptAsItIs(row: PlanRow): Boolean {
        val message = messages[row.commit.hash]
        val keepsMessage = message == null || messageText(message) == messageText(row.commit.message)

        return (row.action == PlanAction.PICK || row.action == PlanAction.REWORD) && keepsMessage
    }

    private fun defaultMessageOf(group: PlanGroup): String? {
        if (group.members.none { it.action == PlanAction.SQUASH || isAmend(it) }) return null

        return groupMessageStart(group, SquashMessageStart.SUBJECTS)
    }

    /**
     * What `git rebase -i` puts together for [group], started as [start] says: the first commit's message, replaced by an
     * `amend!` fixup's (`fixup -C`), then the squashes' messages. An `amend!` or `squash!` commit gives its message
     * without the first paragraph, which names the commit, as git leaves it out. Null for a group of plain fixups.
     */
    private fun groupMessageStart(group: PlanGroup, start: SquashMessageStart): String? {
        var parts = listOf(group.target.commit)

        for (member in group.members) {
            when {
                isAmend(member) -> parts = listOf(withoutNamingParagraph(member.commit))
                member.action == PlanAction.SQUASH -> parts = parts + if (autosquashPrefix(member.commit) == SQUASH_PREFIX) {
                    withoutNamingParagraph(member.commit)
                } else {
                    member.commit
                }
            }
        }

        return if (parts.size == 1 && parts.single() === group.target.commit) {
            null
        } else {
            squashMessageStart(parts.filter { messageText(it.message).isNotEmpty() }.ifEmpty { parts }, start)
        }
    }

    private fun isAmend(row: PlanRow) = row.action == PlanAction.FIXUP && autosquashPrefix(row.commit) == AMEND_PREFIX

    companion object {
        /** A plan that picks [commits] (oldest first) of [line] as they are. */
        fun of(line: BranchLine, commits: List<Commit>): RebasePlan =
            RebasePlan(line, commits, commits.map { PlanRow(it, PlanAction.PICK) })
    }
}

private const val SQUASH_PREFIX = "squash! "
private const val AMEND_PREFIX = "amend! "

/** Groups of [rows] (oldest first): a joining row joins the group below it; one with none below leads its own. */
private fun groupsOf(rows: List<PlanRow>): List<PlanGroup> {
    val groups = mutableListOf<PlanGroup>()

    for (row in rows) {
        if (row.action == PlanAction.DROP) continue

        val last = groups.lastOrNull()

        if (row.action.joins && last != null) {
            groups[groups.lastIndex] = last.copy(members = last.members + row)
        } else {
            groups += PlanGroup(row, emptyList(), joinsNothing = row.action.joins)
        }
    }

    return groups
}

/** The rows of [rows] that name a kept row below them, with that row, when they don't join its group already. */
private fun fixupsToPlace(rows: List<PlanRow>): List<Pair<PlanRow, PlanRow>> {
    val groups = groupsOf(rows)

    return rows.mapIndexedNotNull { index, row ->
        if (row.action == PlanAction.DROP || autosquashPrefix(row.commit) == null) return@mapIndexedNotNull null

        val below = rows.subList(0, index).filter { it.action != PlanAction.DROP }
        val named = autosquashTarget(row.commit, below.map { it.commit }) ?: return@mapIndexedNotNull null
        val target = below.first { it.commit.hash == named.hash }
        val group = groups.first { group -> group.commits.any { it.hash == named.hash } }
        val joinsIt = row.action.joins && group.members.any { it.commit.hash == row.commit.hash }

        if (joinsIt) null else row to target
    }
}

/** [rows] with the first kept row picked when it was set to join: there's nothing below it to join. */
private fun withFirstKeptPicked(rows: List<PlanRow>): List<PlanRow> {
    val first = rows.indexOfFirst { it.action != PlanAction.DROP }

    return if (first >= 0 && rows[first].action.joins) {
        rows.toMutableList().apply { set(first, rows[first].copy(action = PlanAction.PICK)) }
    } else {
        rows
    }
}

/** The autosquash prefix that [commit]'s subject starts with (`fixup! `, `squash! ` or `amend! `), or null. */
private fun autosquashPrefix(commit: Commit): String? {
    val subject = commit.shortMessage

    return listOf("fixup! ", SQUASH_PREFIX, AMEND_PREFIX).firstOrNull { subject.startsWith(it) }
}

/** [commit] with its message's first paragraph left out, the one that names the commit it fixes. */
private fun withoutNamingParagraph(commit: Commit): Commit {
    val text = commit.message.replace("\r\n", "\n").trimStart('\n')
    val end = text.indexOf("\n\n")

    return commit.copy(message = if (end < 0) "" else text.substring(end + 2))
}

private fun messageText(message: String) = message.replace("\r\n", "\n").trim()
