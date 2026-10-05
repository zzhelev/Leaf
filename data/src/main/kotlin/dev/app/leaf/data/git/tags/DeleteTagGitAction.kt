package dev.app.leaf.data.git.tags

import dev.app.leaf.data.git.JGit
import dev.app.leaf.domain.interfaces.IDeleteTagGitAction
import dev.app.leaf.domain.models.Tag
import javax.inject.Inject

class DeleteTagGitAction @Inject constructor(
    private val jgit: JGit,
) : IDeleteTagGitAction {
    override suspend operator fun invoke(repositoryPath: String, tag: Tag) = jgit.provide(repositoryPath) { git ->
        git
            .tagDelete()
            .setTags(tag.name)
            .call()

        Unit
    }
}