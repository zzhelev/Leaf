package dev.app.leaf.domain.interfaces

import dev.app.leaf.domain.lfs.LfsSshAuthenticateResult
import dev.app.leaf.domain.models.OperationType
import org.eclipse.jgit.lib.Repository

interface IAuthenticateLfsServerWithSshGitAction {
    suspend operator fun invoke(
        repository: Repository,
        lfsServerUrl: String,
        operationType: OperationType,
    ): LfsSshAuthenticateResult
}