package dev.app.leaf.data.git.remote_operations

import dev.app.leaf.data.git.JGit
import dev.app.leaf.data.git.stash.DeleteStashGitAction
import dev.app.leaf.data.git.stash.SnapshotStashCreateCommand
import dev.app.leaf.data.git.workspace.CheckHasUncommittedChangesGitAction
import dev.app.leaf.data.mappers.JGitCommitMapper
import dev.app.leaf.domain.errors.bind
import dev.app.leaf.domain.interfaces.IPullBranchGitAction
import dev.app.leaf.domain.models.Branch
import dev.app.leaf.domain.models.Commit
import dev.app.leaf.domain.models.PullType
import org.eclipse.jgit.lib.ConfigConstants
import org.eclipse.jgit.lib.Repository
import org.eclipse.jgit.transport.CredentialsProvider
import javax.inject.Inject

class PullBranchGitAction @Inject constructor(
    private val checkHasUncommittedChangesGitAction: CheckHasUncommittedChangesGitAction,
    private val handleTransportGitAction: HandleTransportGitAction,
    private val hasPullResultConflictsGitAction: HasPullResultConflictsGitAction,
    private val deleteStashGitAction: DeleteStashGitAction,
    private val commitMapper: JGitCommitMapper,
    private val jgit: JGit,
) : IPullBranchGitAction {
    override suspend operator fun invoke(
        repositoryPath: String,
        pullType: PullType,
        mergeAutoStash: Boolean,
        remoteBranch: Branch?,
        automaticStashDescription: String,
    ) = jgit.provide(repositoryPath) { git ->
        useBuiltinLfs(git.repository) {
            val pullWithRebase = when (pullType) {
                PullType.REBASE -> true
                else -> false
            }

            val pullWithMerge = !pullWithRebase
            var backupStash: Commit? = null

            // TODO Move this logic to domain layer like in MergeBranchUseCase
            if (mergeAutoStash && pullWithMerge) {
                val hasUncommitedChanges = checkHasUncommittedChangesGitAction(repositoryPath).bind()
                if (hasUncommitedChanges) {
                    val snapshotStashCreateCommand = SnapshotStashCreateCommand(
                        repository = git.repository,
                        workingDirectoryMessage = automaticStashDescription,
                        includeUntracked = true
                    )

                    backupStash = snapshotStashCreateCommand.call()?.let { commitMapper.toDomain(it) }
                }
            }

            val pullHasConflicts = handleTransportGitAction(repositoryPath) {
                val pullResult = git
                    .pull()
                    .setTransportConfigCallback { this.handleTransport(it) }
                    .setRebase(pullWithRebase)
                    .run {
                        if (remoteBranch != null) {
                            this.setRemote(remoteBranch.remoteName)
                                .setRemoteBranchName(remoteBranch.simpleName)
                        } else {
                            this
                        }
                    }
                    .setCredentialsProvider(CredentialsProvider.getDefault())
                    .call()

                return@handleTransportGitAction hasPullResultConflictsGitAction(pullWithRebase, pullResult)
            }.bind()

            if (!pullHasConflicts && backupStash != null) {
                deleteStashGitAction(git.repository.directory.absolutePath, backupStash)
            }

            pullHasConflicts
        }
    }
}

inline fun <R> useBuiltinLfs(
    repository: Repository,
    callback: () -> R,
): R {
    val lfsSubsection = "lfs"

    val names = repository.config.getNames(
        ConfigConstants.CONFIG_FILTER_SECTION,
        lfsSubsection,
    )

    // Check if it was set before (if using egit) to restore its value later
    val hadBuiltinLfsOriginalValueSet = names.contains(ConfigConstants.CONFIG_KEY_USEJGITBUILTIN)

    val builtinLfsOriginalValue = repository.config.getBoolean(
        ConfigConstants.CONFIG_FILTER_SECTION,
        lfsSubsection,
        ConfigConstants.CONFIG_KEY_USEJGITBUILTIN,
        false,
    )

    repository.config.setBoolean(
        ConfigConstants.CONFIG_FILTER_SECTION,
        lfsSubsection,
        ConfigConstants.CONFIG_KEY_USEJGITBUILTIN,
        true,
    )

    // Restored whatever happens: the next config.save() of the cached repository would write the value to the config
    // file (fork-only)
    try {
        return callback()
    } finally {
        if (hadBuiltinLfsOriginalValueSet) {
            repository.config.setBoolean(
                ConfigConstants.CONFIG_FILTER_SECTION,
                lfsSubsection,
                ConfigConstants.CONFIG_KEY_USEJGITBUILTIN,
                builtinLfsOriginalValue,
            )
        } else {
            repository.config.unset(
                ConfigConstants.CONFIG_FILTER_SECTION,
                lfsSubsection,
                ConfigConstants.CONFIG_KEY_USEJGITBUILTIN,
            )
        }
    }
}
