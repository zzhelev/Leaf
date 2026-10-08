package dev.app.leaf.data.git.tags

import dev.app.leaf.data.git.JGit
import dev.app.leaf.data.git.countCommitsOnlyOn
import dev.app.leaf.domain.errors.DeleteRefError
import dev.app.leaf.domain.errors.raiseError
import dev.app.leaf.domain.interfaces.IDeleteTagGitAction
import dev.app.leaf.domain.models.Tag
import javax.inject.Inject

class DeleteTagGitAction @Inject constructor(
    private val jgit: JGit,
) : IDeleteTagGitAction {
    override suspend operator fun invoke(repositoryPath: String, tag: Tag, force: Boolean) =
        jgit.provide(repositoryPath) { git ->
            if (!force) {
                val commitsOnlyOnTag = git.repository.countCommitsOnlyOn(tag.name)

                if (commitsOnlyOnTag > 0) {
                    raiseError(DeleteRefError.TagHasOwnCommits(tag.simpleName, commitsOnlyOnTag))
                }
            }

            git
                .tagDelete()
                .setTags(tag.name)
                .call()

            Unit
        }
}