package dev.app.leaf.data.git.log

import dev.app.leaf.data.git.JGit
import dev.app.leaf.data.git.cli.remote.PartialCloneFetch
import dev.app.leaf.data.git.cli.remote.fileVersions
import dev.app.leaf.domain.interfaces.IBlameFileGitAction
import javax.inject.Inject

class BlameFileGitAction @Inject constructor(
    private val jgit: JGit,
    private val partialClone: PartialCloneFetch,
) : IBlameFileGitAction {
    // Fork-only: in a partial clone, git downloads the file's versions that JGit doesn't find, and blame runs again
    override suspend fun invoke(
        repositoryPath: String,
        filePath: String,
        movedFrom: String?,
    ) = partialClone.retryingMissing(repositoryPath, alsoNeeded = { fileVersions(it, movedFrom ?: filePath) }) {
        blame(repositoryPath, filePath, movedFrom)
    }

    private suspend fun blame(
        repositoryPath: String,
        filePath: String,
        movedFrom: String?,
    ) = jgit.provide(repositoryPath) { git ->
        // Fork-only: a staged move's new path has no history, so its lines come from the old one
        movedFrom?.let { blameMovedFile(git.repository, filePath, it) }
            ?: git.blame()
                .setFilePath(filePath)
                .setFollowFileRenames(true)
                .call()
            ?: throw Exception("File is no longer present in the workspace and can't be blamed")
    }
}
