package dev.app.leaf.data.git.workspace

import dev.app.leaf.data.git.JGit
import dev.app.leaf.domain.interfaces.IUnstageAllGitAction
import dev.app.leaf.domain.models.StatusEntry
import javax.inject.Inject

class UnstageAllGitAction @Inject constructor(
    private val jgit: JGit,
) : IUnstageAllGitAction {
    override suspend operator fun invoke(repositoryPath: String, entries: List<StatusEntry>?) =
        jgit.provide(repositoryPath) { git ->
            git
                .reset()
                .apply {
                    entries?.forEach { entry ->
                        addPath(entry.filePath)
                    }
                }
                .call()

            Unit
        }
}