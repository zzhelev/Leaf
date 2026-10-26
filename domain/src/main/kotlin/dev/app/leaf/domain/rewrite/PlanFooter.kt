// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.domain.rewrite

import dev.app.leaf.domain.models.Commit

/**
 * Where a commit editor's plan would replay if it stops (fork-only, step B5 of `docs/fork/commit-editor.md`): the
 * worktree that has the branch checked out, or the tab's, which checks it out first when none has (decision 2a).
 *
 * @param worktreeName the folder's name of the worktree that has the branch, or null when none has it.
 * @param isTabs whether that worktree is the tab's own. True when none has the branch, as it then runs in the tab's.
 * @param uncommitted how many tracked files have uncommitted changes there, which a rebase can't start with.
 * @param recentChange when something last changed in another worktree that has the branch, if it was a few minutes ago:
 * an agent may still be working there.
 */
data class PlanPlace(
    val worktreeName: String? = null,
    val isTabs: Boolean = true,
    val uncommitted: Int = 0,
    val recentChange: Long? = null,
)

/** What the editor's footer says about a plan, besides its counts: only what is to be acted on or known before Start. */
sealed interface PlanNote {
    /** Whether Start waits until this changes. */
    val blocks: Boolean
        get() = false

    /** Picking [commit] conflicts in [file], and in [more] other files. */
    data class StopsAtConflict(val commit: Commit, val file: String, val more: Int) : PlanNote

    /** The plan stops after [commit], for its files to be changed. */
    data class StopsToEdit(val commit: Commit) : PlanNote

    /** Leaf can't tell whether the plan stops, for [reason]: it replays with git, which may. */
    data class MayStop(val reason: UnknownStopReason) : PlanNote

    /** The plan can't run as it is. */
    data class Refused(val refusal: PlanRefusal) : PlanNote {
        override val blocks get() = true
    }

    /** It replays in another worktree, named [worktreeName], which has the branch checked out. */
    data class RunsIn(val worktreeName: String) : PlanNote

    /** No worktree has [branch] checked out: the tab checks it out first. */
    data class ChecksOutHere(val branch: String) : PlanNote

    /** The other worktree that would replay it has [count] uncommitted changes, which Leaf doesn't set aside there. */
    data class ChangesThere(val worktreeName: String, val count: Int) : PlanNote {
        override val blocks get() = true
    }

    /** Something changed in [worktreeName] at [at]: an agent may still be working there. */
    data class RecentChange(val worktreeName: String, val at: Long) : PlanNote

    /** Moving the branch's files would overwrite uncommitted changes to [files] in [worktreePath]. */
    data class FilesInTheWay(val worktreePath: String, val files: List<String>) : PlanNote {
        override val blocks get() = true
    }

    /** [count] of the commits that the plan rewrites are on [upstream] (its short name): a force push replaces them. */
    data class Pushed(val count: Int, val upstream: String) : PlanNote

    /** The branch moved since the plan was made: the plan is for its old commits. */
    data object BranchMoved : PlanNote {
        override val blocks get() = true
    }

    /** A message of the plan is empty, which git refuses. */
    data object EmptyMessage : PlanNote {
        override val blocks get() = true
    }
}

/** What Start does: rewrite the commits without a worktree (B3), or replay them with `git rebase -i` (B4). */
enum class PlanStart {
    REWRITE,
    REBASE,
}

/**
 * The editor's footer for a plan: "7 commits → 5 · 1 joined · 1 dropped · reordered", the notes, the box that sets
 * uncommitted changes aside (choice 3a) and Start.
 *
 * @param after how many commits the plan gives, without the groups that the check found empty.
 * @param autostashCount the uncommitted changes that the box offers to set aside, or null when it doesn't show.
 * @param isChecked whether the check has answered for the plan as it is: Start waits for it.
 */
data class PlanFooter(
    val before: Int,
    val after: Int,
    val joined: Int,
    val dropped: Int,
    val isReordered: Boolean,
    val edits: Int,
    val notes: List<PlanNote>,
    val autostashCount: Int?,
    val start: PlanStart,
    val isChecked: Boolean,
    val canStart: Boolean,
)

/**
 * The footer of [plan], with [check] when the check has answered for it (null while it runs), [place] for where it
 * replays if it stops, [autostash] for the box, and [currentTip], the branch's tip now (null when Leaf hasn't read it).
 */
fun planFooter(
    plan: RebasePlan,
    check: PlanCheck?,
    place: PlanPlace,
    autostash: Boolean,
    currentTip: String? = plan.expectedTip,
): PlanFooter {
    val byHash = plan.original.associateBy { it.hash }
    val stop = check?.stop
    val stops = stop != null
    val notes = mutableListOf<PlanNote>()

    if (currentTip != null && currentTip != plan.expectedTip) {
        notes += PlanNote.BranchMoved
    }

    plan.refusal(stops = stops)?.let { notes += PlanNote.Refused(it) }

    when (stop) {
        is PlanStop.Conflict -> byHash[stop.commit]?.let { commit ->
            notes += PlanNote.StopsAtConflict(commit, stop.files.firstOrNull().orEmpty(), (stop.files.size - 1).coerceAtLeast(0))
        }

        is PlanStop.Edit -> byHash[stop.commit]?.let { notes += PlanNote.StopsToEdit(it) }
        is PlanStop.Unknown -> notes += PlanNote.MayStop(stop.reason)
        null -> Unit
    }

    var autostashCount: Int? = null

    if (stops && plan.isChanged) {
        val name = place.worktreeName

        when {
            !place.isTabs && name != null -> {
                notes += PlanNote.RunsIn(name)

                if (place.uncommitted > 0) {
                    notes += PlanNote.ChangesThere(name, place.uncommitted)
                }
            }

            name == null -> notes += PlanNote.ChecksOutHere(shortName(plan.line.branch))
        }

        if (place.isTabs && place.uncommitted > 0) {
            autostashCount = place.uncommitted
        }
    }

    if (!stops) {
        check?.inTheWay?.let { notes += PlanNote.FilesInTheWay(it.worktreePath, it.files) }
    }

    // Either way, the plan moves the branch that another worktree has checked out
    if (plan.isChanged && !place.isTabs && place.worktreeName != null && place.recentChange != null) {
        notes += PlanNote.RecentChange(place.worktreeName, place.recentChange)
    }

    val pushed = plan.rewrittenPushed
    val upstream = plan.line.upstream

    if (plan.isChanged && pushed > 0 && upstream != null) {
        notes += PlanNote.Pushed(pushed, shortName(upstream))
    }

    if (plan.groups.any { group -> plan.messageOf(group)?.isBlank() == true }) {
        notes += PlanNote.EmptyMessage
    }

    val leftOut = check?.groups?.count { it.isLeftOut } ?: 0
    val isChecked = check != null
    val canStart = plan.isChanged && isChecked && notes.none { it.blocks } && (autostashCount == null || autostash)

    return PlanFooter(
        before = plan.rows.size,
        after = plan.groups.size - leftOut,
        joined = plan.joined,
        dropped = plan.dropped,
        isReordered = plan.isReordered,
        edits = plan.edits,
        notes = notes,
        autostashCount = autostashCount,
        start = if (stops) PlanStart.REBASE else PlanStart.REWRITE,
        isChecked = isChecked,
        canStart = canStart,
    )
}

/**
 * What a row of the editor shows besides its commit and action (B5).
 *
 * @param isMoved whether the row left the order it had: not in the longest run of rows that kept their order.
 * @param joinedBy how many rows join this one ("+2").
 * @param isPickedAlone whether the row is set to join but has nothing below it to join, so it stays a commit.
 * @param stopFiles the files that picking it conflicts in, when the check stops there; empty otherwise.
 * @param isEmpty whether its changes are already there, so it's left out.
 * @param isPushed whether the branch's upstream has the commit.
 */
data class PlanRowMarks(
    val isMoved: Boolean = false,
    val joinedBy: Int = 0,
    val isPickedAlone: Boolean = false,
    val stopFiles: List<String> = emptyList(),
    val isEmpty: Boolean = false,
    val isPushed: Boolean = false,
)

/** What each row of [plan] shows, by its commit's hash, with [check] when it has answered. */
fun planRowMarks(plan: RebasePlan, check: PlanCheck?): Map<String, PlanRowMarks> {
    val kept = plan.rows.filter { it.action != PlanAction.DROP }.map { it.commit.hash }
    val inOrder = longestInOrder(kept, plan.original.map { it.hash })
    val joinedBy = plan.groups.associate { it.target.commit.hash to it.members.size }
    val pickedAlone = plan.groups.filter { it.joinsNothing }.mapTo(mutableSetOf()) { it.target.commit.hash }
    val conflict = check?.stop as? PlanStop.Conflict
    val empty = check?.emptyCommits.orEmpty()

    return plan.rows.associate { row ->
        val hash = row.commit.hash
        val isKept = row.action != PlanAction.DROP

        hash to PlanRowMarks(
            isMoved = isKept && hash !in inOrder,
            joinedBy = joinedBy[hash] ?: 0,
            isPickedAlone = hash in pickedAlone,
            stopFiles = if (conflict?.commit == hash) conflict.files else emptyList(),
            isEmpty = isKept && hash in empty,
            isPushed = hash in plan.line.pushed,
        )
    }
}

/** The hashes of [ids] that keep their order of [original]: a longest run of them in that order. */
private fun longestInOrder(ids: List<String>, original: List<String>): Set<String> {
    val positions = original.withIndex().associate { (index, hash) -> hash to index }
    val sequence = ids.map { positions[it] ?: -1 }
    val tails = mutableListOf<Int>()
    val previous = IntArray(ids.size) { -1 }

    for ((index, value) in sequence.withIndex()) {
        var low = 0
        var high = tails.size

        while (low < high) {
            val middle = (low + high) / 2

            if (sequence[tails[middle]] < value) low = middle + 1 else high = middle
        }

        previous[index] = if (low > 0) tails[low - 1] else -1

        if (low == tails.size) tails += index else tails[low] = index
    }

    val result = mutableSetOf<String>()
    var at = tails.lastOrNull() ?: -1

    while (at >= 0) {
        result += ids[at]
        at = previous[at]
    }

    return result
}
