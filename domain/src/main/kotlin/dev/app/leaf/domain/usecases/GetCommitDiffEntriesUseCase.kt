package dev.app.leaf.domain.usecases

import dev.app.leaf.domain.UseCaseExecutor
import dev.app.leaf.domain.errors.Either
import dev.app.leaf.domain.errors.bind
import dev.app.leaf.domain.interfaces.IGetCommitDiffEntriesGitAction
import dev.app.leaf.domain.interfaces.IGetCommitFromHashGitAction
import dev.app.leaf.domain.models.Commit
import kotlinx.coroutines.delay
import org.eclipse.jgit.diff.DiffEntry
import javax.inject.Inject

class GetCommitDiffEntriesUseCase @Inject constructor(
    private val getCommitDiffEntriesGitAction: IGetCommitDiffEntriesGitAction,
    private val getCommitFromHashGitAction: IGetCommitFromHashGitAction,
    private val useCaseExecutor: UseCaseExecutor,
) {
    suspend operator fun invoke(commit: Commit) = useCaseExecutor.execute<List<DiffEntry>>(
    ) { repositoryPath ->
        // TODO Restore stashes change loading. IIRC only stashes have 3 parents, usually.
        val entries = getCommitDiffEntriesGitAction(repositoryPath, commit).bind().toMutableList()

        if (commit.parentCount == 3) {
            var untrackedFilesCommit: Commit? = null

            for (hash in commit.parentsHashes) {
                val parentCommit = getCommitFromHashGitAction(repositoryPath, hash).bind() ?: continue

                if (parentCommit.message.startsWith("untracked files on") && parentCommit.parentCount == 0) {
                    untrackedFilesCommit = parentCommit
                    break
                }
            }

            if (untrackedFilesCommit != null) {
                val untrackedFilesChanges = getCommitDiffEntriesGitAction(repositoryPath, untrackedFilesCommit).bind()

                if (untrackedFilesChanges.all { it.changeType == DiffEntry.ChangeType.ADD }) { // All files should be new
                    entries.addAll(untrackedFilesChanges)
                }
            }
        }

        Either.Ok(entries)
    }
}