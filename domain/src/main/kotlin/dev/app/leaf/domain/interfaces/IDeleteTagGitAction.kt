package dev.app.leaf.domain.interfaces

import dev.app.leaf.domain.errors.Either
import dev.app.leaf.domain.errors.GitError
import dev.app.leaf.domain.models.Tag
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.lib.Ref

interface IDeleteTagGitAction {
    /**
     * Deletes [tag]. Without [force], a tag that is the only ref on some commits is kept, and the result is
     * [dev.app.leaf.domain.errors.DeleteRefError.TagHasOwnCommits].
     */
    suspend operator fun invoke(repositoryPath: String, tag: Tag, force: Boolean): Either<Unit, GitError>
}