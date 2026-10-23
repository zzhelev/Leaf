// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.domain.rewrite

/**
 * A commit that carries a group's message into `git rebase -i` (fork-only, step B4 of `docs/fork/commit-editor.md`): the
 * tree and the parent of [source], the author and author date of [authorOf], and [message]. git picks it in place of
 * [source], so it never opens an editor for the plan, and a Continue from a terminal keeps the plan's message.
 *
 * @param group the commit that leads the group, which the twin stands for.
 * @param source the group's first commit that isn't empty: [group] itself, unless the check found it empty.
 */
data class TwinCommit(
    val group: String,
    val source: String,
    val authorOf: String,
    val message: String,
)

/** How `git rebase -i` of a commit editor's plan went (B4). */
sealed interface PlanRebaseOutcome {
    /** It ran to the end, and [result] says from where to where, for the strip and Undo. */
    data class Done(val result: BranchRewriteResult) : PlanRebaseOutcome

    /** It stopped, at a conflict or an edit, in the worktree whose git dir is [gitDir]. */
    data class Stopped(val gitDir: String) : PlanRebaseOutcome
}

/**
 * What `git rebase -i` gets for a [RebasePlan] that stops: `--onto [onto] [upstream]` (null for `--root`), and [lines],
 * the todo, made with the twins' ids.
 */
data class RebaseTodo(
    val upstream: String?,
    val onto: String?,
    val lines: List<String>,
)

/**
 * The twins that the plan needs for `git rebase -i` ([TwinCommit]), from the first row that changes: a group with a new
 * message, and a group whose first commit the check found empty, which starts from its first commit that isn't
 * (finding 8). Groups left out whole need none.
 */
fun RebasePlan.twinsFor(check: PlanCheck): List<TwinCommit> = changedGroups(check).mapNotNull { (group, empty) ->
    val source = group.commits.firstOrNull { it.hash !in empty } ?: return@mapNotNull null
    val message = messageOf(group)

    when {
        message != null -> TwinCommit(group.target.commit.hash, source.hash, group.target.commit.hash, message)
        source.hash != group.target.commit.hash ->
            TwinCommit(group.target.commit.hash, source.hash, group.target.commit.hash, group.target.commit.message)

        else -> null
    }
}

/**
 * The todo for `git rebase -i` ([RebaseTodo]), from the commit after the rows that stay as they are, with [twins] (by the
 * group they stand for) made from [twinsFor]. Every commit of the plan is listed, as `rebase.missingCommitsCheck` asks:
 * a dropped row and a commit that a twin stands for as `drop`. A group starts with `pick` (or `edit`), its twin's or its
 * commit's, and the rows that join it are `fixup`, as the twin has the group's message. A group that the check found
 * empty whole is dropped, so that git doesn't put what joins it into the commit below.
 */
fun RebasePlan.rebaseTodo(check: PlanCheck, twins: Map<String, String>): RebaseTodo {
    val empties = changedGroups(check).associate { (group, empty) -> group.target.commit.hash to empty }
    val lines = mutableListOf<String>()

    for (row in rows.drop(unchangedPrefix)) {
        if (row.action == PlanAction.DROP) {
            lines += "drop ${row.commit.hash}"
            continue
        }

        val group = groups.first { group -> group.commits.any { it.hash == row.commit.hash } }

        // A group's lines go out once, at its first row
        if (row.commit.hash != group.target.commit.hash) continue

        val empty = empties[group.target.commit.hash].orEmpty()
        val head = group.commits.firstOrNull { it.hash !in empty }

        for (commit in group.commits) {
            val twin = twins[group.target.commit.hash]

            when {
                head == null || commit.hash in empty && commit != head -> lines += "drop ${commit.hash}"
                commit == head && twin != null -> {
                    lines += "drop ${commit.hash}"
                    lines += "${if (group.stops) "edit" else "pick"} $twin"
                }

                commit == head -> lines += "${if (group.stops) "edit" else "pick"} ${commit.hash}"
                else -> lines += "fixup ${commit.hash}"
            }
        }
    }

    val upstream = if (unchangedPrefix > 0) original[unchangedPrefix - 1].hash else original.first().parentsHashes.firstOrNull()

    return RebaseTodo(upstream = upstream, onto = onto?.commit ?: upstream, lines = lines)
}

/** The plan's groups after the rows that stay as they are, with the commits that the check found empty in each. */
private fun RebasePlan.changedGroups(check: PlanCheck): List<Pair<PlanGroup, Set<String>>> =
    groups.withIndex().drop(unchangedPrefix).map { (index, group) ->
        group to check.groups.getOrNull(index)?.emptyCommits.orEmpty()
    }
