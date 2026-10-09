package dev.app.leaf.data.git.repository

import dev.app.leaf.domain.errors.Either
import dev.app.leaf.domain.errors.GenericError
import dev.app.leaf.domain.errors.GitError
import dev.app.leaf.domain.errors.handleException
import dev.app.leaf.domain.interfaces.IInitLocalRepositoryGitAction
import kotlinx.coroutines.Dispatchers
import org.eclipse.jgit.api.Git
import java.io.File
import javax.inject.Inject

private const val INITIAL_BRANCH_NAME = "main"

class InitLocalRepositoryGitAction @Inject constructor() : IInitLocalRepositoryGitAction {
    // Returns the failure, such as a folder that can't be written, rather than throwing it, and closes the repository,
    // which the tab opens again (fork-only)
    override suspend operator fun invoke(repoDir: File): Either<Unit, GitError> = handleException(
        Dispatchers.IO,
        exceptionMapper = { GenericError(it.message.orEmpty(), it) },
    ) {
        Git.init()
            .setInitialBranch(INITIAL_BRANCH_NAME)
            .setDirectory(repoDir)
            .call()
            .close()
    }
}
