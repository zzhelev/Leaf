package dev.app.leaf.domain.interfaces

import dev.app.leaf.domain.errors.AppError
import dev.app.leaf.domain.errors.Either
import dev.app.leaf.domain.models.Branch
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.lib.Ref

interface IGetCurrentBranchGitAction {
    suspend operator fun invoke(git: Git): Either<Branch?, AppError>

    suspend operator fun invoke(path: String): Either<Branch?, AppError>
}