package dev.app.leaf.domain.interfaces

import dev.app.leaf.domain.errors.Either
import dev.app.leaf.domain.errors.GitError
import dev.app.leaf.domain.models.DiffResult
import dev.app.leaf.domain.models.DiffType
import org.eclipse.jgit.api.Git

interface IFormatDiffGitAction {
    suspend operator fun invoke(
        repositoryPath: String,
        diffType: DiffType,
        isDisplayFullFile: Boolean,
    ): Either<DiffResult, GitError>
}