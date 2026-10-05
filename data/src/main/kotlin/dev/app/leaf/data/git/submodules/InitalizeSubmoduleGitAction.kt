package dev.app.leaf.data.git.submodules

import dev.app.leaf.data.git.JGit
import dev.app.leaf.domain.interfaces.IInitializeSubmoduleGitAction
import javax.inject.Inject

class InitializeSubmoduleGitAction @Inject constructor(
    private val jgit: JGit,
) : IInitializeSubmoduleGitAction {
    override suspend operator fun invoke(repositoryPath: String, path: String) = jgit.provide(repositoryPath) { git ->
        git.submoduleInit()
            .addPath(path)
            .call()

        Unit
    }
}