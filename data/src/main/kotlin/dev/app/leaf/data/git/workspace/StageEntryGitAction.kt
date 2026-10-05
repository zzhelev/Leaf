package dev.app.leaf.data.git.workspace

import dev.app.leaf.data.git.JGit
import dev.app.leaf.domain.interfaces.IStageEntryGitAction
import dev.app.leaf.domain.models.StatusEntry
import dev.app.leaf.domain.models.StatusType
import javax.inject.Inject

class StageEntryGitAction @Inject constructor(
    private val jgit: JGit,
) : IStageEntryGitAction {
    override suspend operator fun invoke(repositoryPath: String, statusEntry: StatusEntry) =
        jgit.provide(repositoryPath) { git ->
            git
                .add()
                .addFilepattern(statusEntry.filePath)
                .setUpdate(statusEntry.statusType == StatusType.REMOVED)
                .call()

            Unit
        }
}