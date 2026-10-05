package dev.app.leaf.data.git.workspace

import dev.app.leaf.data.git.JGit
import dev.app.leaf.domain.interfaces.IStageUntrackedFileGitAction
import org.eclipse.jgit.diff.DiffEntry
import javax.inject.Inject

class StageUntrackedFileGitAction @Inject constructor(
    private val jgit: JGit,
) : IStageUntrackedFileGitAction {
    override suspend operator fun invoke(repositoryPath: String) = jgit.provide(repositoryPath) { git ->
        val diffEntries = git
            .diff()
            .setShowNameAndStatusOnly(true)
            .call()

        val addedEntries = diffEntries.filter { it.changeType == DiffEntry.ChangeType.ADD }

        if (addedEntries.isNotEmpty()) {
            val addCommand = git
                .add()

            for (entry in addedEntries) {
                addCommand.addFilepattern(entry.newPath)
            }

            addCommand.call()
        }
    }
}