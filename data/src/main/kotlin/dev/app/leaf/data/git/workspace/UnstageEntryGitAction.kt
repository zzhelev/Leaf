package dev.app.leaf.data.git.workspace

import dev.app.leaf.data.git.JGit
import dev.app.leaf.domain.interfaces.IUnstageEntryGitAction
import dev.app.leaf.domain.models.StatusEntry
import javax.inject.Inject

class UnstageEntryGitAction @Inject constructor(
    private val jgit: JGit,
) : IUnstageEntryGitAction {
    override suspend operator fun invoke(repositoryPath: String, statusEntry: StatusEntry) =
        jgit.provide(repositoryPath) { git ->
            // Fork-only: a rename's two paths, so that the move leaves the index whole
            git
                .reset()
                .apply { statusEntry.paths.forEach(::addPath) }
                .call()

            Unit
        }
}