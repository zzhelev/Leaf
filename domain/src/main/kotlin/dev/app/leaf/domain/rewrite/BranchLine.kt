// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.domain.rewrite

import dev.app.leaf.domain.errors.BranchOperation
import dev.app.leaf.domain.errors.Either
import dev.app.leaf.domain.models.Commit

/**
 * A local branch's own commits (fork-only): its first-parent line from the tip down to the first commit that its base
 * has, newest first as the log shows them, as `git rev-list --first-parent <branch> ^<base>` lists them. A merge of the
 * base into the branch is on the line, and the base's commits that it brings aren't.
 *
 * @param branch the branch's full name (`refs/heads/x`).
 * @param base the branch it's compared to (`refs/heads/main`), or null when there's none: the line then goes down to
 * the first commit.
 * @param upstream the branch that [branch] tracks (`refs/remotes/origin/x`), null without one.
 * @param pushed the commits of the line that [upstream] has, which a rewrite replaces there only with a force push.
 * @param isTruncated whether the line goes on past [commits], which Leaf reads only so far.
 */
data class BranchLine(
    val branch: String,
    val base: String?,
    val commits: List<Commit>,
    val upstream: String? = null,
    val pushed: Set<String> = emptySet(),
    val isTruncated: Boolean = false,
) {
    private val hashes: Set<String> by lazy { commits.mapTo(mutableSetOf()) { it.hash } }

    fun has(commit: String): Boolean = commit in hashes
}

/** Why commits of a branch can't be rewritten as asked, which the menus show at the item's right. */
sealed interface RewriteRefusal {
    /** [commit] isn't one of the branch's own commits: its base has it, or it's on another line. */
    data class NotOnLine(val commit: String) : RewriteRefusal

    /** The commits aren't one after the other on the branch's line. */
    data object NotConsecutive : RewriteRefusal

    /** Joining them would join [merge], whose other parents would be lost. */
    data class JoinsMerge(val merge: String) : RewriteRefusal

    /** The branch's first commit has no parent to join: its parent is the base's. */
    data object FirstCommit : RewriteRefusal

    /** There's only one commit to join. */
    data object OneCommit : RewriteRefusal

    /** The base branch, which other branches share, has the commits ([base] is its short name, such as `main`). */
    data class OnBase(val base: String) : RewriteRefusal

    /** No local branch has the commits as its own: they're a remote branch's, a tag's or a stash's. */
    data object NotOnLocalBranch : RewriteRefusal

    /** [count] branches have the commits as their own, none of them the current one: which to rewrite isn't clear. */
    data class OnSeveralBranches(val count: Int) : RewriteRefusal

    /** A worktree has the branch in the middle of [operation]. */
    data class Busy(val operation: BranchOperation) : RewriteRefusal

    /** The branch has more commits of its own than Leaf reads. */
    data object TooManyCommits : RewriteRefusal
}

/** Commits of a branch's line that one action joins or rewords, oldest first. */
data class LineRange(
    val line: BranchLine,
    val commits: List<Commit>,
) {
    /**
     * How many of the commits that a rewrite of the range changes, the range's and all after it, the upstream has: they
     * stay there until a force push replaces them.
     */
    val rewrittenPushed: Int
        get() {
            val oldest = line.commits.indexOfFirst { it.hash == commits.first().hash }

            return line.commits.take(oldest + 1).count { it.hash in line.pushed }
        }

    /** Joins the commits into one with [message]. */
    fun squash(message: String) = rewrite(RewriteKind.SQUASH, message)

    /** Joins the commits into one with the oldest one's message. */
    fun fixup() = rewrite(RewriteKind.FIXUP, message = null)

    /** Gives the commit [message]. */
    fun reword(message: String) = rewrite(RewriteKind.REWORD, message)

    private fun rewrite(kind: RewriteKind, message: String?) = BranchRewrite(
        branch = line.branch,
        expectedTip = line.commits.first().hash,
        groups = listOf(RewriteGroup(commits.map { it.hash }, message)),
        kind = kind,
    )
}

/** The commit [hash] alone, to give it another message. A merge can have one: it keeps its parents. */
fun BranchLine.commitToReword(hash: String): Either<LineRange, RewriteRefusal> {
    val commit = commits.firstOrNull { it.hash == hash } ?: return Either.Err(RewriteRefusal.NotOnLine(hash))

    return Either.Ok(LineRange(this, listOf(commit)))
}

/** The commit [hash] and its parent, to squash it or fix it up into the parent. */
fun BranchLine.commitWithParent(hash: String): Either<LineRange, RewriteRefusal> {
    val index = commits.indexOfFirst { it.hash == hash }

    return when (index) {
        -1 -> Either.Err(RewriteRefusal.NotOnLine(hash))
        commits.lastIndex -> Either.Err(if (isTruncated) RewriteRefusal.TooManyCommits else RewriteRefusal.FirstCommit)
        else -> joined(listOf(commits[index + 1], commits[index]))
    }
}

/** The commits [hashes], two or more one after the other on the line, to squash into one. */
fun BranchLine.commitsToJoin(hashes: Collection<String>): Either<LineRange, RewriteRefusal> {
    val wanted = hashes.toSet()
    val missing = wanted.firstOrNull { hash -> commits.none { it.hash == hash } }

    if (missing != null) {
        return Either.Err(RewriteRefusal.NotOnLine(missing))
    }

    if (wanted.size < 2) {
        return Either.Err(RewriteRefusal.OneCommit)
    }

    val indices = commits.indices.filter { commits[it].hash in wanted }

    if (indices.last() - indices.first() + 1 != indices.size) {
        return Either.Err(RewriteRefusal.NotConsecutive)
    }

    return joined(indices.reversed().map { commits[it] })
}

/** Every commit of the line, to squash the branch into one commit. */
fun BranchLine.allCommits(): Either<LineRange, RewriteRefusal> {
    if (isTruncated) {
        return Either.Err(RewriteRefusal.TooManyCommits)
    }

    if (commits.size < 2) {
        return Either.Err(RewriteRefusal.OneCommit)
    }

    return joined(commits.reversed())
}

/** [oldestFirst] as one range to join, unless one of them is a merge: the new commit would have only one parent. */
private fun BranchLine.joined(oldestFirst: List<Commit>): Either<LineRange, RewriteRefusal> {
    val merge = oldestFirst.firstOrNull { it.parentCount > 1 }

    if (merge != null) {
        return Either.Err(RewriteRefusal.JoinsMerge(merge.hash))
    }

    return Either.Ok(LineRange(this, oldestFirst))
}
