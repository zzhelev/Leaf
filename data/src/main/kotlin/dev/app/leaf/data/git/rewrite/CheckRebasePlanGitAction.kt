// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.data.git.rewrite

import dev.app.leaf.common.printError
import dev.app.leaf.data.git.JGit
import dev.app.leaf.data.git.cli.GitCli
import dev.app.leaf.data.git.cli.remote.readableGitOutput
import dev.app.leaf.domain.errors.Either
import dev.app.leaf.domain.errors.GitError
import dev.app.leaf.domain.interfaces.ICheckRebasePlanGitAction
import dev.app.leaf.domain.rewrite.CheckedGroup
import dev.app.leaf.domain.rewrite.PlanCheck
import dev.app.leaf.domain.rewrite.PlanStop
import dev.app.leaf.domain.rewrite.RebasePlan
import dev.app.leaf.domain.rewrite.UnknownStopReason
import org.eclipse.jgit.errors.MissingObjectException
import org.eclipse.jgit.lib.Constants
import org.eclipse.jgit.lib.ObjectId
import org.eclipse.jgit.lib.ObjectInserter
import org.eclipse.jgit.lib.Repository
import org.eclipse.jgit.revwalk.RevWalk
import javax.inject.Inject

private const val TAG = "CheckRebasePlan"

/** How many merges the check remembers for a tab, so that a change late in a plan only merges the rows after it. */
private const val REMEMBERED_MERGES = 2_000

/** What `git merge-tree` gave for picking a commit onto a tree. */
internal sealed interface PickResult {
    data class Tree(val id: String) : PickResult
    data class Conflicts(val files: List<String>) : PickResult
    data class CantTell(val reason: UnknownStopReason) : PickResult
}

/**
 * Leaf's check of a commit editor's plan (fork-only, step B2 of `docs/fork/commit-editor.md`): the plan replayed one
 * commit at a time, without a working tree, from the tree that the plan starts on (the base's commit for a move onto
 * it, else the parent of the plan's first commit).
 *
 * For each commit, a tree that is its parent's tree takes the commit's own tree, as nothing below it changed (part A's
 * case: a plan that only joins and rewords merges nothing). Any other is `git merge-tree --merge-base=<parent>`, the
 * three-way merge that a cherry-pick makes, which is what `git rebase -i` gives for it (git 2.40 and later; tested
 * against git's own rebase). A commit that leaves the tree as it was comes out empty and is left out, as `--empty=drop`
 * does. The check stops at the first conflict, and at the first group that stops to be edited.
 *
 * `GIT_NO_LAZY_FETCH` keeps a partial clone from downloading what the merges read (git 2.44 and later): a missing
 * object makes the plan one that Leaf can't tell about.
 */
class CheckRebasePlanGitAction @Inject constructor(
    private val jgit: JGit,
    private val gitCli: GitCli,
) : ICheckRebasePlanGitAction {
    private val picks = object : LinkedHashMap<Triple<String, String, String>, PickResult>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Triple<String, String, String>, PickResult>?) =
            size > REMEMBERED_MERGES
    }

    override suspend operator fun invoke(
        repositoryPath: String,
        plan: RebasePlan,
    ): Either<PlanCheck, GitError> = jgit.provide(repositoryPath) { git ->
        val repository = git.repository

        try {
            check(repository, plan)
        } catch (e: MissingObjectException) {
            printError(TAG, "A commit or tree of the plan is missing: ${e.message}")
            PlanCheck(emptyList(), PlanStop.Unknown(UnknownStopReason.MISSING_OBJECTS))
        }
    }

    private suspend fun check(repository: Repository, plan: RebasePlan): PlanCheck = RevWalk(repository).use { walk ->
        val trees = CommitTrees(walk)
        val onto = plan.onto
        var tree = if (onto != null) trees.of(onto.commit) else trees.parentOf(plan.original.first().hash)
        val checked = mutableListOf<CheckedGroup>()

        for (group in plan.groups) {
            val empty = mutableSetOf<String>()

            for ((index, commit) in group.commits.withIndex()) {
                val parentTree = trees.parentOf(commit.hash)
                val commitTree = trees.of(commit.hash)
                val next = if (tree == parentTree) {
                    commitTree
                } else {
                    when (val pick = pick(repository, parentTree, tree, commitTree)) {
                        is PickResult.Tree -> pick.id
                        is PickResult.Conflicts -> return@use PlanCheck(checked, PlanStop.Conflict(commit.hash, pick.files))
                        is PickResult.CantTell -> return@use PlanCheck(checked, PlanStop.Unknown(pick.reason))
                    }
                }

                // A commit that started empty is kept, as git keeps it; one that leaves the tree as it was is left out.
                if (next == tree && commitTree != parentTree) {
                    empty += commit.hash
                }

                tree = next

                if (index == 0 && group.stops) {
                    return@use PlanCheck(checked, PlanStop.Edit(commit.hash))
                }
            }

            checked += CheckedGroup(group.target.commit.hash, group.commits.map { it.hash }, tree, empty)
        }

        PlanCheck(checked, null)
    }

    /** Picking the change from [parentTree] to [commitTree] onto [tree], remembered for the tab. */
    private suspend fun pick(repository: Repository, parentTree: String, tree: String, commitTree: String): PickResult {
        val key = Triple(parentTree, tree, commitTree)

        synchronized(picks) { picks[key] }?.let { return it }

        val result = mergeTreePick(gitCli, repository, parentTree, tree, commitTree)

        if (result !is PickResult.CantTell) {
            synchronized(picks) { picks[key] = result }
        }

        return result
    }
}

/**
 * `git merge-tree --write-tree --merge-base=<parentTree> <tree> <commitTree>`: the tree of picking a commit onto [tree],
 * or the files that conflict (exit 1). With `-z`, git prints the tree, then each conflicted file, each ended by NUL.
 */
internal suspend fun mergeTreePick(
    gitCli: GitCli,
    repository: Repository,
    parentTree: String,
    tree: String,
    commitTree: String,
): PickResult {
    val args = listOf(
        "--git-dir=${repository.directory.absolutePath}",
        "merge-tree", "--write-tree", "-z", "--name-only", "--no-messages",
        "--merge-base=$parentTree", tree, commitTree,
    )
    val noDownloads = mapOf("GIT_NO_LAZY_FETCH" to "1")
    val output = when (val result = gitCli.execute(repository.directory, args, environment = noDownloads)) {
        is Either.Err -> {
            printError(TAG, "git merge-tree didn't run: ${result.error}")
            return PickResult.CantTell(UnknownStopReason.FAILED)
        }

        is Either.Ok -> result.value
    }
    val fields = output.stdout.split('\u0000').filter { it.isNotEmpty() }

    return when (output.exitCode) {
        0 -> fields.firstOrNull()?.let { PickResult.Tree(it.trim()) } ?: PickResult.CantTell(UnknownStopReason.FAILED)
        1 -> PickResult.Conflicts(fields.drop(1).distinct())
        else -> {
            val stderr = output.stderr

            printError(TAG, "git merge-tree failed with ${output.exitCode}: ${readableGitOutput(stderr)}")

            when {
                MISSING_OBJECT.any { stderr.contains(it, ignoreCase = true) } ->
                    PickResult.CantTell(UnknownStopReason.MISSING_OBJECTS)

                output.exitCode == 129 || OLD_GIT.any { stderr.contains(it, ignoreCase = true) } ->
                    PickResult.CantTell(UnknownStopReason.OLD_GIT)

                else -> PickResult.CantTell(UnknownStopReason.FAILED)
            }
        }
    }
}

/**
 * What git prints when an object isn't there: "unable to read" in any repository, and in a partial clone, where
 * `GIT_NO_LAZY_FETCH` stops the download, "could not fetch <id> from promisor remote" (git 2.54).
 */
private val MISSING_OBJECT = listOf("unable to read", "could not read", "missing object", "from promisor remote")

/** What a git without `--merge-base`, or that takes only commits there, prints. */
private val OLD_GIT = listOf("unknown option", "usage: git merge-tree", "not something we can merge", "is not a commit")

/** The trees of commits, read once for a check. A commit without parents starts from the empty tree. */
private class CommitTrees(private val walk: RevWalk) {
    private val trees = mutableMapOf<String, String>()
    private val parentTrees = mutableMapOf<String, String>()

    fun of(commit: String): String = trees.getOrPut(commit) { walk.parseCommit(ObjectId.fromString(commit)).tree.name }

    fun parentOf(commit: String): String = parentTrees.getOrPut(commit) {
        val parsed = walk.parseCommit(ObjectId.fromString(commit))

        if (parsed.parentCount == 0) EMPTY_TREE else of(parsed.getParent(0).name)
    }

    companion object {
        val EMPTY_TREE: String = ObjectInserter.Formatter().idFor(Constants.OBJ_TREE, ByteArray(0)).name
    }
}
