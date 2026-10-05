package dev.app.leaf.domain.interfaces

import dev.app.leaf.domain.lfs.LfsSshAuthenticateResult
import dev.app.leaf.domain.models.OperationType

interface IAuthenticateLfsServerWithSshGitAction {
    suspend operator fun invoke(
        lfsServerUrl: String,
        operationType: OperationType,
    ): LfsSshAuthenticateResult
}