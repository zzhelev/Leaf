// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.data.git.rewrite

import dev.app.leaf.data.git.JGit
import dev.app.leaf.data.git.worktrees.GetWorktreeBaseBranchGitAction
import dev.app.leaf.data.mappers.JGitCommitMapper
import dev.app.leaf.domain.errors.Either
import dev.app.leaf.domain.errors.GitError
import dev.app.leaf.domain.errors.bind
import dev.app.leaf.domain.errors.either
import dev.app.leaf.domain.interfaces.IGetBranchLinesGitAction
import dev.app.leaf.domain.rewrite.BranchLine
import dev.app.leaf.domain.rewrite.BranchLines
import dev.app.leaf.domain.rewrite.MAX_LINE_COMMITS
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.eclipse.jgit.lib.BranchConfig
import org.eclipse.jgit.lib.Constants
import org.eclipse.jgit.lib.Repository
import org.eclipse.jgit.revwalk.RevCommit
import org.eclipse.jgit.revwalk.RevWalk
import javax.inject.Inject

/** The most commits that a branch has and its base doesn't that Leaf walks, its own line and its merges' sides. */
private const val MAX_OWN_COMMITS = MAX_LINE_COMMITS * 5

/**
 * Reads every local branch's own line (fork-only, step A3 of `docs/fork/squash-and-rebase.md`), as
 * `git rev-list --first-parent <branch> ^<base>` lists it: the first-parent commits from its tip that the base, the
 * worktrees' base branch, doesn't have. The base's whole history counts, not only its first parents, so a commit that
 * the base merged in isn't the branch's own. The base's own line is the commits that its upstream lacks, when it has one
 * (step B3 of `docs/fork/commit-editor.md`): its unpushed commits, which are its own until they're pushed.
 *
 * Each branch's upstream says which of its commits are pushed. JGit, in this process, as the menus need it after every
 * commit and only read.
 */
class GetBranchLinesGitAction @Inject constructor(
    private val jgit: JGit,
    private val getWorktreeBaseBranchGitAction: GetWorktreeBaseBranchGitAction,
    private val commitMapper: JGitCommitMapper,
) : IGetBranchLinesGitAction {
    override suspend operator fun invoke(repositoryPath: String): Either<BranchLines, GitError> = either {
        val base = getWorktreeBaseBranchGitAction(repositoryPath).bind().effective

        jgit.provide(repositoryPath) { git ->
            withContext(Dispatchers.IO) {
                val repository = git.repository

                RevWalk(repository).use { walk ->
                    val baseTip = base?.let(repository::exactRef)?.objectId?.let(walk::parseCommit)
                    val lines = repository.refDatabase.getRefsByPrefix(Constants.R_HEADS).mapNotNull { ref ->
                        // A branch whose commit JGit can't read (a broken ref, a partial clone's gap) has no line
                        runCatching {
                            val tip = walk.parseCommit(ref.objectId)
                            val upstream = upstreamOf(walk, repository, ref.name)
                            // The base's own commits are those its upstream lacks, its unpushed ones (B, decision 5)
                            val (lineBase, lineBaseTip) = if (ref.name == base && upstream != null) {
                                upstream.first to upstream.second
                            } else {
                                base to baseTip
                            }

                            ref.name to lineOf(walk, ref.name, tip, lineBaseTip, lineBase, upstream)
                        }.getOrNull()
                    }.toMap()

                    BranchLines(base = base?.takeIf { baseTip != null }, lines = lines, baseCommits = newestOf(walk, baseTip))
                }
            }
        }
    }

    private fun lineOf(
        walk: RevWalk,
        branch: String,
        tip: RevCommit,
        baseTip: RevCommit?,
        base: String?,
        upstream: Pair<String, RevCommit>?,
    ): BranchLine {
        val own = ownCommits(walk, tip, baseTip)
        val line = mutableListOf<RevCommit>()
        var commit: RevCommit? = tip
        var isTruncated = false

        while (commit != null && commit in own.commits) {
            if (line.size == MAX_LINE_COMMITS) {
                isTruncated = true
                break
            }

            line += commit
            commit = commit.parents.firstOrNull()?.let(walk::parseCommit)
        }

        // Past what was walked, the line may go on
        if (commit != null && own.isTruncated && commit !in own.commits) {
            isTruncated = true
        }

        val pushed = upstream?.let { (_, upstreamTip) ->
            val onUpstream = ownCommits(walk, upstreamTip, baseTip).commits

            line.filter { it in onUpstream }.mapTo(mutableSetOf()) { it.name }
        }.orEmpty()

        return BranchLine(
            branch = branch,
            base = base,
            commits = line.map(commitMapper::toDomain),
            upstream = upstream?.first,
            pushed = pushed,
            isTruncated = isTruncated,
        )
    }

    /** The commits that [tip] has and [baseTip] doesn't, as `git rev-list <tip> ^<base>` lists them, so far. */
    private fun ownCommits(walk: RevWalk, tip: RevCommit, baseTip: RevCommit?): OwnCommits {
        walk.reset()
        walk.markStart(tip)
        baseTip?.let(walk::markUninteresting)

        val commits = HashSet<RevCommit>()

        for (commit in walk) {
            if (commits.size == MAX_OWN_COMMITS) return OwnCommits(commits, isTruncated = true)

            commits += commit
        }

        return OwnCommits(commits, isTruncated = false)
    }

    /** The newest [MAX_LINE_COMMITS] commits of the base, to tell its commits in the menus. */
    private fun newestOf(walk: RevWalk, baseTip: RevCommit?): Set<String> {
        baseTip ?: return emptySet()

        walk.reset()
        walk.markStart(baseTip)

        return walk.asSequence().take(MAX_LINE_COMMITS).mapTo(mutableSetOf()) { it.name }
    }
}

private class OwnCommits(val commits: Set<RevCommit>, val isTruncated: Boolean)

/** The branch that the local branch [branch] tracks (`refs/remotes/origin/x`), when it exists, with its commit. */
private fun upstreamOf(walk: RevWalk, repository: Repository, branch: String): Pair<String, RevCommit>? {
    val tracking = BranchConfig(repository.config, Repository.shortenRefName(branch)).trackingBranch ?: return null
    val ref = repository.exactRef(tracking) ?: return null

    return tracking to walk.parseCommit(ref.objectId)
}
