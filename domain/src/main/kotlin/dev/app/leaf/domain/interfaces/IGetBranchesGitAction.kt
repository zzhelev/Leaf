package dev.app.leaf.domain.interfaces

import dev.app.leaf.domain.errors.AppError
import dev.app.leaf.domain.errors.Either
import dev.app.leaf.domain.models.Branch
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.lib.Ref

interface IGetBranchesGitAction {
    // TODO after refactor remove this overload
    suspend operator fun invoke(git: Git): Either<List<Branch>, AppError>

    suspend operator fun invoke(repository: String): Either<List<Branch>, AppError>
}