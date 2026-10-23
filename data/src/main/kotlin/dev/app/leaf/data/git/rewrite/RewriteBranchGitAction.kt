// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.data.git.rewrite

import dev.app.leaf.data.git.JGit
import dev.app.leaf.data.git.cli.GitCli
import dev.app.leaf.data.git.cli.remote.GitCliRemoteCommand
import dev.app.leaf.data.git.workspace.messageCleanup
import dev.app.leaf.domain.commitmessage.cleanCommitMessage
import dev.app.leaf.domain.errors.Either
import dev.app.leaf.domain.errors.EitherContext
import dev.app.leaf.domain.errors.GitError
import dev.app.leaf.domain.errors.RewriteBranchError
import dev.app.leaf.domain.errors.raiseError
import dev.app.leaf.domain.interfaces.IRewriteBranchGitAction
import dev.app.leaf.domain.models.TaskProgress
import dev.app.leaf.domain.repositories.RepositoryStateRepository
import dev.app.leaf.domain.rewrite.BranchRewrite
import dev.app.leaf.domain.rewrite.BranchRewriteResult
import dev.app.leaf.domain.rewrite.OntoBase
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.eclipse.jgit.lib.ObjectId
import org.eclipse.jgit.lib.Repository
import org.eclipse.jgit.revwalk.RevCommit
import org.eclipse.jgit.revwalk.RevWalk
import javax.inject.Inject

/**
 * Rewrites a local branch's commits as a [BranchRewrite] says (fork-only), with git's commands, so that its identity,
 * signing and hooks apply as in a terminal. Every new commit is `git commit-tree` of a tree that the branch already has,
 * so nothing is replayed and nothing can conflict, and the branch ends with the tree it had: the working tree and the
 * index stay as they are. One `git update-ref` that expects the old tip then moves the branch, and `post-rewrite` runs
 * with what changed, as after `git rebase`.
 *
 * A squash of the whole branch onto the latest base ([BranchRewrite.onto]) is the one exception: its tree is the merge
 * of the base and the branch, and a checked-out branch's working tree takes the base's changes with it
 * ([moveBranchWithFiles]), refused while uncommitted changes are in their way.
 *
 * git runs in the worktree that has the branch checked out ([branchPlace]), or in the tab's. Each `commit-tree` goes
 * through [GitCliRemoteCommand], as ssh-keygen may ask for the passphrase of a key that signs commits. Cancelling before
 * the branch moves leaves only commits that nothing points to.
 */
class RewriteBranchGitAction @Inject constructor(
    private val jgit: JGit,
    private val gitCli: GitCli,
    private val remoteCommand: GitCliRemoteCommand,
    private val repositoryStateRepository: RepositoryStateRepository,
) : IRewriteBranchGitAction {
    override suspend operator fun invoke(
        repositoryPath: String,
        rewrite: BranchRewrite,
    ): Either<BranchRewriteResult, GitError> = jgit.provide(repositoryPath) { git ->
        val repository = git.repository
        val branchName = Repository.shortenRefName(rewrite.branch)
        val place = branchPlace(repository, rewrite.branch)
        val oldTip = repository.exactRef(rewrite.branch)?.objectId?.name

        if (oldTip == null || oldTip != rewrite.expectedTip) {
            raiseError(RewriteBranchError.BranchMoved(branchName))
        }

        val line = readLine(repository, rewrite, branchName)
        val newCommits = newCommits(repository, rewrite, line, branchName)
        refuseIfMergedAgain(repository, line, newCommits, branchName)

        val onto = rewrite.onto
        // On the latest base: one commit, of the whole branch, with the merge's tree
        val ontoTree = onto?.let {
            requireWholeBranch(repository, line, newCommits, it)
            ontoBaseTree(gitCli, place, it, rewrite.branch, oldTip)
        }


        val sign = signsCommits(gitCli, place)
        val rewritten = mutableListOf<Pair<String, String>>()
        var parent = onto?.commit ?: line.first().parents.firstOrNull()?.name

        try {
            for ((index, newCommit) in newCommits.withIndex()) {
                currentCoroutineContext().ensureActive()
                repositoryStateRepository.updateTaskProgress(TaskProgress(null, index * 100 / newCommits.size))

                val parents = listOfNotNull(parent) + newCommit.otherParents
                val id = commitTree(remoteCommand, place, newCommit, parents, sign, ontoTree)
                newCommit.sources.forEach { source -> rewritten.add(source.name to id) }
                parent = id
            }
        } finally {
            repositoryStateRepository.updateTaskProgress(null)
        }

        val newTip = checkNotNull(parent)
        val subject = subjectOf(newCommits.first())
        val action = onto?.let { "${rewrite.kind.reflogName} onto ${Repository.shortenRefName(it.base)}" }
            ?: rewrite.kind.reflogName

        currentCoroutineContext().ensureActive()

        // Once the branch moves, the rewrite is done: what follows isn't cancelled
        withContext(NonCancellable) {
            if (ontoTree == null) {
                moveBranch(gitCli, place, rewrite.branch, oldTip, newTip, "$action: $subject")
            } else {
                moveBranchWithFiles(gitCli, place, rewrite.branch, oldTip, newTip, "$action: $subject")
            }

            runPostRewrite(gitCli, place, rewritten)
        }

        BranchRewriteResult(rewrite.branch, rewrite.kind, oldTip, newTip, rewritten, onto)
    }

    /**
     * A squash onto the base is of the whole branch: one new commit, whose oldest source's parent is where the branch
     * left the base, which [onto] has. Anything else would take commits off the branch that the merge still has.
     */
    private fun requireWholeBranch(
        repository: Repository,
        line: List<RevCommit>,
        newCommits: List<NewCommit>,
        onto: OntoBase,
    ) {
        require(newCommits.size == 1) { "A squash onto the base makes one commit of the whole branch" }

        val forkPoint = line.first().parents.firstOrNull()

        RevWalk(repository).use { walk ->
            require(forkPoint != null && walk.isMergedInto(walk.parseCommit(forkPoint), walk.parseCommit(ObjectId.fromString(onto.commit)))) {
                "The branch didn't leave the base at its oldest commit's parent"
            }
        }
    }

    /**
     * The branch's first-parent line from the first group's oldest commit to the tip, oldest first. A commit that isn't
     * on it is refused.
     */
    private fun EitherContext<GitError>.readLine(
        repository: Repository,
        rewrite: BranchRewrite,
        branchName: String,
    ): List<RevCommit> {
        val oldest = rewrite.groups.firstOrNull()?.commits?.firstOrNull()
            ?: raiseError(RewriteBranchError.NotOnLine(branchName, rewrite.expectedTip))

        val line = mutableListOf<RevCommit>()

        RevWalk(repository).use { walk ->
            var commit: RevCommit? = walk.parseCommit(ObjectId.fromString(rewrite.expectedTip))

            while (commit != null) {
                line.add(commit)

                if (commit.name == oldest) {
                    return line.reversed()
                }

                commit = if (commit.parentCount > 0) walk.parseCommit(commit.getParent(0)) else null
            }
        }

        raiseError(RewriteBranchError.NotOnLine(branchName, oldest))
    }

    /**
     * The commits to make, oldest first: each group as one, and every other commit of [line] made again. The groups'
     * commits must follow each other on [line] in their order, and a group of several can't join a merge. A new message is
     * cleaned up as `git commit` cleans an edited one, with the repository's `commit.cleanup`.
     */
    private fun EitherContext<GitError>.newCommits(
        repository: Repository,
        rewrite: BranchRewrite,
        line: List<RevCommit>,
        branchName: String,
    ): List<NewCommit> {
        val cleanup = repository.config.messageCleanup()
        val newCommits = mutableListOf<NewCommit>()
        var position = 0

        for (group in rewrite.groups) {
            val first = group.commits.firstOrNull() ?: raiseError(RewriteBranchError.NotOnLine(branchName, ""))
            val start = (position until line.size).firstOrNull { line[it].name == first }
                ?: raiseError(RewriteBranchError.NotOnLine(branchName, first))

            for (index in group.commits.indices) {
                val commit = line.getOrNull(start + index)

                if (commit?.name != group.commits[index]) {
                    raiseError(RewriteBranchError.NotOnLine(branchName, group.commits[index]))
                }
            }

            line.subList(position, start).mapTo(newCommits) { it.madeAgain() }

            val sources = line.subList(start, start + group.commits.size)

            if (sources.size > 1) {
                sources.firstOrNull { it.parentCount > 1 }?.let { raiseError(RewriteBranchError.JoinsMerge(it.name)) }
            }

            val otherParents = if (sources.size == 1) sources.first().otherParents() else emptyList()
            val text = group.message
            val newCommit = if (text == null) {
                NewCommit(sources, sources.first().rawMessage(), sources.first().encodingName(), otherParents)
            } else {
                val message = cleanCommitMessage(text, cleanup)

                if (message.isBlank()) {
                    raiseError(RewriteBranchError.EmptyMessage)
                }

                NewCommit(sources, message.toByteArray(Charsets.UTF_8), UTF_8, otherParents)
            }

            newCommits.add(newCommit)
            position = start + group.commits.size
        }

        line.subList(position, line.size).mapTo(newCommits) { it.madeAgain() }

        return newCommits
    }

    /**
     * Refuses a merge after the first rewritten commit whose other parent has that commit: it comes from a branch that
     * started on this one, which would bring the old commits back next to the new ones.
     */
    private fun EitherContext<GitError>.refuseIfMergedAgain(
        repository: Repository,
        line: List<RevCommit>,
        newCommits: List<NewCommit>,
        branchName: String,
    ) {
        val merges = newCommits.filter { it.otherParents.isNotEmpty() }

        if (merges.isEmpty()) {
            return
        }

        RevWalk(repository).use { walk ->
            val firstChanged = walk.parseCommit(line.first())

            for (merge in merges) {
                for (parent in merge.otherParents) {
                    if (walk.isMergedInto(firstChanged, walk.parseCommit(ObjectId.fromString(parent)))) {
                        raiseError(RewriteBranchError.MergedAgain(branchName, merge.sources.first().name))
                    }
                }
            }
        }
    }
}
