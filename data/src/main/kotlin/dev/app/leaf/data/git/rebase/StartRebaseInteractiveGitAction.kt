package dev.app.leaf.data.git.rebase

import dev.app.leaf.data.git.JGit
import dev.app.leaf.data.git.cli.remote.PartialCloneFetch
import dev.app.leaf.domain.errors.Either
import dev.app.leaf.domain.errors.GitError
import dev.app.leaf.domain.errors.bind
import dev.app.leaf.domain.exceptions.UncommittedChangesDetectedException
import dev.app.leaf.domain.interfaces.IStartRebaseInteractiveGitAction
import dev.app.leaf.domain.models.Commit
import org.eclipse.jgit.api.RebaseCommand
import org.eclipse.jgit.api.RebaseResult
import org.eclipse.jgit.lib.RebaseTodoLine
import javax.inject.Inject


class StartRebaseInteractiveGitAction @Inject constructor(
    private val jgit: JGit,
    private val partialClone: PartialCloneFetch,
) : IStartRebaseInteractiveGitAction {
    override suspend operator fun invoke(
        repositoryPath: String,
        commit: Commit,
    ): Either<Unit, GitError> = jgit.provide(repositoryPath) { git ->
        val base = git
            .repository
            .resolve(commit.hash) ?: throw Exception("Commit ${commit.hash} not found")

        // Fork-only: JGit can't download what a partial clone lacks, and would stop halfway. The commits that the
        // rebase replays are all downloaded now, whatever the user then does with them.
        partialClone.prefetchForRebase(git.repository, base).bind()

        val interactiveHandler = object : RebaseCommand.InteractiveHandler {
            override fun prepareSteps(steps: MutableList<RebaseTodoLine>?) {}
            override fun modifyCommitMessage(message: String?): String = ""
        }

        val rebaseResult = git
            .rebase()
            .runInteractively(interactiveHandler, true)
            .setOperation(RebaseCommand.Operation.BEGIN)
            .setUpstream(base)
            .call()

        when (rebaseResult.status) {
            RebaseResult.Status.FAILED -> throw UncommittedChangesDetectedException("Rebase interactive failed.")
            RebaseResult.Status.UNCOMMITTED_CHANGES, RebaseResult.Status.CONFLICTS -> throw UncommittedChangesDetectedException(
                "You can't have uncommitted changes before starting a rebase interactive"
            )

            else -> {}
        }
    }
}