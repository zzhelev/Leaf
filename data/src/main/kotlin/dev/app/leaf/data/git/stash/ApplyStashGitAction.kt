package dev.app.leaf.data.git.stash

import dev.app.leaf.data.git.JGit
import dev.app.leaf.data.git.sparse.SparseCheckoutGit
import dev.app.leaf.domain.errors.Either
import dev.app.leaf.domain.errors.GitError
import dev.app.leaf.domain.errors.bind
import dev.app.leaf.domain.errors.mapOk
import dev.app.leaf.domain.interfaces.IApplyStashGitAction
import dev.app.leaf.domain.models.Commit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.eclipse.jgit.api.Git
import javax.inject.Inject

class ApplyStashGitAction @Inject constructor(
    private val jgit: JGit,
    private val sparseCheckout: SparseCheckoutGit,
) : IApplyStashGitAction {
    override suspend operator fun invoke(repositoryPath: String, stashInfo: Commit) = jgit.provide(repositoryPath) { git ->
        invoke(git, stashInfo).bind()
    }

    suspend operator fun invoke(git: Git, stashInfo: Commit): Either<Unit, GitError> {
        // Fork-only: JGit's stash apply forgets that the index skips the files that a sparse checkout leaves out.
        // `--index` restores the staged changes too, as JGit does.
        if (sparseCheckout.applies(git.repository)) {
            return sparseCheckout.run(git.repository, "stash", "apply", "--index", stashInfo.hash).mapOk { }
        }

        git.stashApply()
            .setStashRef(stashInfo.hash)
            .call()

        return Either.Ok(Unit)
    }
}