package dev.app.leaf.data.git.tags

import dev.app.leaf.data.git.JGit
import dev.app.leaf.data.mappers.JGitTagMapper
import dev.app.leaf.domain.interfaces.IGetTagsGitAction
import javax.inject.Inject

class GetTagsGitAction @Inject constructor(
    private val tagMapper: JGitTagMapper,
    private val jgit: JGit,
) : IGetTagsGitAction {
    override suspend operator fun invoke(repositoryPath: String) = jgit.provide(repositoryPath) { git ->
        git
            .tagList()
            .call()
            .mapNotNull { tag ->
                val tag = if (!tag.isPeeled) {
                    git.repository.refDatabase.peel(tag)
                } else {
                    tag
                }

                tagMapper.toDomain(tag)
            }
    }
}