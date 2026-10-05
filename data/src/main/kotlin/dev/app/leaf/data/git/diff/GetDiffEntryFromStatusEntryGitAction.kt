package dev.app.leaf.data.git.diff

import dev.app.leaf.domain.exceptions.MissingDiffEntryException
import dev.app.leaf.data.git.branches.GetCurrentBranchGitAction
import dev.app.leaf.data.git.repository.GetRepositoryStateGitAction
import dev.app.leaf.domain.errors.okOrNull
import dev.app.leaf.domain.interfaces.IGetDiffEntryFromStatusEntryGitAction
import dev.app.leaf.domain.models.StatusEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.treewalk.EmptyTreeIterator
import org.eclipse.jgit.treewalk.filter.PathFilter
import javax.inject.Inject

class GetDiffEntryFromStatusEntryGitAction @Inject constructor(
    private val getRepositoryStateGitAction: GetRepositoryStateGitAction,
    private val getCurrentBranchGitAction: GetCurrentBranchGitAction,
) : IGetDiffEntryFromStatusEntryGitAction {
    override suspend operator fun invoke(
        git: Git,
        isCached: Boolean,
        statusEntry: StatusEntry,
    ) = withContext(Dispatchers.IO) {
        val firstDiffEntry = git.diff()
            .setPathFilter(PathFilter.create(statusEntry.filePath))
            .setCached(isCached).apply {
                val repositoryState = getRepositoryStateGitAction(git.repository.directory.absolutePath).okOrNull()!!
                if (
                    getCurrentBranchGitAction(git).okOrNull() == null &&
                    !repositoryState.isMerging &&
                    !repositoryState.isRebasing &&
                    isCached
                ) {
                    setOldTree(EmptyTreeIterator()) // Required if the repository is empty
                }
            }
            .call()
            .firstOrNull()
            ?: throw MissingDiffEntryException("Diff entry not found")

        return@withContext firstDiffEntry
    }
}