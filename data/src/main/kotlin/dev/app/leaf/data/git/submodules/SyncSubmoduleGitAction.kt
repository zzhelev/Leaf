package dev.app.leaf.data.git.submodules

import dev.app.leaf.data.git.JGit
import dev.app.leaf.domain.interfaces.ISyncSubmoduleGitAction
import javax.inject.Inject

class SyncSubmoduleGitAction @Inject constructor(
    private val jgit: JGit,
) : ISyncSubmoduleGitAction {
    override suspend operator fun invoke(
        repositoryPath: String,
        path: String,
    ) = jgit.provide(repositoryPath) { git ->
        git.submoduleSync()
            .addPath(path)
            .call()

        Unit
    }
}