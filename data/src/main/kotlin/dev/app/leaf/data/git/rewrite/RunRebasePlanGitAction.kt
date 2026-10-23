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
import dev.app.leaf.domain.errors.bind
import dev.app.leaf.domain.errors.either
import dev.app.leaf.domain.errors.raiseError
import dev.app.leaf.domain.interfaces.ICheckRebasePlanGitAction
import dev.app.leaf.domain.interfaces.IRunRebasePlanGitAction
import dev.app.leaf.domain.models.TaskProgress
import dev.app.leaf.domain.repositories.RepositoryStateRepository
import dev.app.leaf.domain.rewrite.BranchRewriteResult
import dev.app.leaf.domain.rewrite.PlanCheck
import dev.app.leaf.domain.rewrite.PlanRefusal
import dev.app.leaf.domain.rewrite.PlanStop
import dev.app.leaf.domain.rewrite.RebasePlan
import dev.app.leaf.domain.rewrite.RewriteKind
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.eclipse.jgit.lib.ObjectId
import org.eclipse.jgit.lib.Repository
import org.eclipse.jgit.revwalk.RevWalk
import javax.inject.Inject

/**
 * Runs a commit editor's plan that doesn't stop (fork-only, step B3 of `docs/fork/commit-editor.md`), without a working
 * tree, as part A's rewrites run: Leaf's check ([ICheckRebasePlanGitAction]) gives each group's tree, worked out again
 * now against the commits as they are, and each group becomes `git commit-tree` of that tree, with the message of the
 * plan and the author and author date of its first commit, signed as git's config says. The rows that stay as they are
 * at the start keep their commits, and a group whose commits all came out empty gives none, as `--empty=drop` does.
 *
 * Then one `git update-ref` that expects the old tip moves the branch, with its files where it's checked out when its
 * last tree changed ([moveBranchWithFiles]), and `post-rewrite` runs as after `git rebase`. Refused, changing nothing,
 * when the plan stops after all (the branch or the base changed since it was checked), when the branch moved, or when
 * the plan would merge a merge again. [UndoBranchRewriteGitAction] undoes it.
 */
class RunRebasePlanGitAction @Inject constructor(
    private val jgit: JGit,
    private val gitCli: GitCli,
    private val remoteCommand: GitCliRemoteCommand,
    private val repositoryStateRepository: RepositoryStateRepository,
    private val checkPlan: ICheckRebasePlanGitAction,
) : IRunRebasePlanGitAction {
    override suspend operator fun invoke(
        repositoryPath: String,
        plan: RebasePlan,
    ): Either<BranchRewriteResult, GitError> = either {
        val branchName = Repository.shortenRefName(plan.line.branch)

        when (val refusal = plan.refusal()) {
            is PlanRefusal.JoinsMerge -> raiseError(RewriteBranchError.JoinsMerge(refusal.merge))
            is PlanRefusal.ReplaysMerge -> raiseError(RewriteBranchError.ReplaysMerge(refusal.merge))
            is PlanRefusal.MergeInRebase -> raiseError(RewriteBranchError.ReplaysMerge(refusal.merge))
            null -> Unit
        }

        require(plan.isChanged) { "The plan changes nothing" }

        val check = checkPlan(repositoryPath, plan).bind()

        when (val stop = check.stop) {
            is PlanStop.Conflict -> raiseError(RewriteBranchError.PlanStops(branchName, stop.commit, stop.files))
            is PlanStop.Edit -> raiseError(RewriteBranchError.PlanStops(branchName, stop.commit, emptyList()))
            is PlanStop.Unknown -> raiseError(RewriteBranchError.PlanStops(branchName, null, emptyList()))
            null -> Unit
        }

        jgit.provide(repositoryPath) { git -> run(git.repository, plan, check, branchName) }
    }

    private suspend fun EitherContext<GitError>.run(
        repository: Repository,
        plan: RebasePlan,
        check: PlanCheck,
        branchName: String,
    ): BranchRewriteResult = RevWalk(repository).use { walk ->
        val branch = plan.line.branch
        val place = branchPlace(repository, branch)
        val oldTip = repository.exactRef(branch)?.objectId?.name

        if (oldTip == null || oldTip != plan.expectedTip) {
            raiseError(RewriteBranchError.BranchMoved(branchName))
        }

        val prefix = plan.unchangedPrefix
        val firstCommit = walk.parseCommit(ObjectId.fromString(plan.original.first().hash))
        // The groups to make: after the rows that stay, without those whose commits all came out empty
        val toMake = plan.groups.indices.drop(prefix).filterNot { check.groups[it].isLeftOut }
        val cleanup = repository.config.messageCleanup()
        val newCommits = toMake.map { index ->
            val group = plan.groups[index]
            val sources = group.commits.map { walk.parseCommit(ObjectId.fromString(it.hash)) }
            val target = sources.first()
            val otherParents = if (sources.size == 1) target.otherParents() else emptyList()
            val text = plan.messageOf(group)

            if (text == null) {
                NewCommit(sources, target.rawMessage(), target.encodingName(), otherParents)
            } else {
                val message = cleanCommitMessage(text, cleanup)

                if (message.isBlank()) {
                    raiseError(RewriteBranchError.EmptyMessage)
                }

                NewCommit(sources, message.toByteArray(Charsets.UTF_8), UTF_8, otherParents)
            }
        }

        refuseIfMergedAgain(walk, plan.original.getOrNull(prefix)?.hash, newCommits, branchName)

        val onto = plan.onto
        var parent = onto?.commit
            ?: if (prefix > 0) plan.original[prefix - 1].hash else firstCommit.parents.firstOrNull()?.name
        val sign = signsCommits(gitCli, place)
        val rewritten = mutableListOf<Pair<String, String>>()

        try {
            for ((made, newCommit) in newCommits.withIndex()) {
                currentCoroutineContext().ensureActive()
                repositoryStateRepository.updateTaskProgress(TaskProgress(null, made * 100 / newCommits.size))

                val tree = check.groups[toMake[made]].tree
                val parents = listOfNotNull(parent) + newCommit.otherParents
                val id = commitTree(remoteCommand, place, newCommit, parents, sign, tree)
                newCommit.sources.forEach { source -> rewritten.add(source.name to id) }
                parent = id
            }
        } finally {
            repositoryStateRepository.updateTaskProgress(null)
        }

        // Every commit dropped from a branch that starts at the repository's first commit: there's nothing to point to
        val newTip = parent ?: raiseError(RewriteBranchError.NotOnLine(branchName, firstCommit.name))
        val newTree = toMake.lastOrNull()?.let { check.groups[it].tree }
            ?: walk.parseCommit(ObjectId.fromString(newTip)).tree.name
        val oldTree = walk.parseCommit(ObjectId.fromString(oldTip)).tree.name
        val subject = newCommits.firstOrNull()?.let(::subjectOf) ?: branchName
        val action = onto?.let { "${RewriteKind.REBASE.reflogName} onto ${Repository.shortenRefName(it.base)}" }
            ?: RewriteKind.REBASE.reflogName

        currentCoroutineContext().ensureActive()

        // Once the branch moves, the plan is done: what follows isn't cancelled
        withContext(NonCancellable) {
            if (newTree == oldTree) {
                moveBranch(gitCli, place, branch, oldTip, newTip, "$action: $subject")
            } else {
                moveBranchWithFiles(gitCli, place, branch, oldTip, newTip, "$action: $subject")
            }

            runPostRewrite(gitCli, place, rewritten)
        }

        BranchRewriteResult(branch, RewriteKind.REBASE, oldTip, newTip, rewritten, onto)
    }

    /**
     * Refuses a merge made again whose other parent has [firstChanged], the first commit that the plan changes: it comes
     * from a branch that started on this one, which would bring the old commits back next to the new ones.
     */
    private fun EitherContext<GitError>.refuseIfMergedAgain(
        walk: RevWalk,
        firstChanged: String?,
        newCommits: List<NewCommit>,
        branchName: String,
    ) {
        firstChanged ?: return
        val changed = walk.parseCommit(ObjectId.fromString(firstChanged))

        for (merge in newCommits.filter { it.otherParents.isNotEmpty() }) {
            for (otherParent in merge.otherParents) {
                if (walk.isMergedInto(changed, walk.parseCommit(ObjectId.fromString(otherParent)))) {
                    raiseError(RewriteBranchError.MergedAgain(branchName, merge.sources.first().name))
                }
            }
        }
    }
}
