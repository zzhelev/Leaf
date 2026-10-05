package dev.app.leaf.data.git.workspace

import dev.app.leaf.data.git.JGit
import dev.app.leaf.domain.interfaces.IStageByDirectoryGitAction
import javax.inject.Inject

class StageByDirectoryGitAction @Inject constructor(
    private val jgit: JGit,
) : IStageByDirectoryGitAction {
    override suspend operator fun invoke(repositoryPath: String, dir: String) = jgit.provide(repositoryPath) { git ->
        git
            .add()
            .addFilepattern(dir)
            .call()

        Unit
    }
}
