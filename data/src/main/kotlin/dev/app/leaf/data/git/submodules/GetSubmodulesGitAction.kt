package dev.app.leaf.data.git.submodules

import dev.app.leaf.data.git.JGit
import dev.app.leaf.data.mappers.JGitSubmoduleMapper
import dev.app.leaf.domain.interfaces.IGetSubmodulesGitAction
import javax.inject.Inject

class GetSubmodulesGitAction @Inject constructor(
    private val jgit: JGit,
    private val submoduleMapper: JGitSubmoduleMapper,
) : IGetSubmodulesGitAction {
    override suspend operator fun invoke(repositoryPath: String) = jgit.provide(repositoryPath) { git ->
        val submodules = git
            .submoduleStatus()
            .call()

        submodules
            .mapValues { submodule ->
                submoduleMapper.toDomain(submodule.value)
            }
            .toMap()
    }
}