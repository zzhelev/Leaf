package dev.app.leaf.domain.interfaces

import dev.app.leaf.domain.errors.Either
import dev.app.leaf.domain.errors.GitError
import dev.app.leaf.domain.models.Hunk
import dev.app.leaf.domain.models.Line
import org.eclipse.jgit.diff.DiffEntry

interface IStageHunkLineGitAction {
    suspend operator fun invoke(repositoryPath: String, diffEntry: DiffEntry, hunk: Hunk, line: Line): Either<Unit, GitError>
}