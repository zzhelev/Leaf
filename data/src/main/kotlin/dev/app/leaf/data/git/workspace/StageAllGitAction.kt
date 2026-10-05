package dev.app.leaf.data.git.workspace

import dev.app.leaf.data.git.JGit
import dev.app.leaf.domain.errors.AppError
import dev.app.leaf.domain.errors.Either
import dev.app.leaf.domain.errors.bind
import dev.app.leaf.domain.errors.either
import dev.app.leaf.domain.interfaces.IStageAllGitAction
import dev.app.leaf.domain.models.StatusEntry
import dev.app.leaf.domain.models.StatusType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.eclipse.jgit.api.Git
import javax.inject.Inject


class StageAllGitAction @Inject constructor(
    private val getStatusGitAction: GetStatusGitAction,
    private val jgit: JGit,
) : IStageAllGitAction {
    override suspend operator fun invoke(repositoryPath: String, entries: List<StatusEntry>?) = either {
        val status = getStatusGitAction(repositoryPath).bind()

        jgit.provide(repositoryPath) { git ->
            val unstaged = status.unstaged
                .run {
                    if (entries != null) {
                        this.filter { entries.contains(it) }
                    } else {
                        this
                    }
                }


            addAllExceptNew(git, unstaged.filter { it.statusType != StatusType.ADDED })
            addNewFiles(git, unstaged.filter { it.statusType == StatusType.ADDED })
        }
    }

    /**
     * The setUpdate flag of the addCommand adds deleted files but not newly added when active
     */
    private fun addAllExceptNew(git: Git, allExceptNew: List<StatusEntry>) {
        if (allExceptNew.isEmpty())
            return

        val addCommand = git
            .add()

        for (entry in allExceptNew) {
            addCommand.addFilepattern(entry.filePath)
        }

        addCommand.setUpdate(true)

        addCommand.call()
    }

    private fun addNewFiles(git: Git, newFiles: List<StatusEntry>) {
        if (newFiles.isEmpty())
            return

        val addCommand = git
            .add()

        for (path in newFiles) {
            addCommand.addFilepattern(path.filePath)
        }

        addCommand.setUpdate(false)

        addCommand.call()
    }
}