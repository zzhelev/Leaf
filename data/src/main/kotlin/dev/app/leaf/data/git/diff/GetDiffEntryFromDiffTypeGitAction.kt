package dev.app.leaf.data.git.diff

import dev.app.leaf.domain.models.DiffType
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.diff.DiffEntry
import javax.inject.Inject

interface IGetDiffEntryFromDiffTypeGitAction {
    suspend operator fun invoke(git: Git, diffType: DiffType): DiffEntry
}

class GetDiffEntryFromDiffTypeGitAction @Inject constructor(
    private val getDiffEntryFromStatusEntryGitAction: GetDiffEntryFromStatusEntryGitAction,
) : IGetDiffEntryFromDiffTypeGitAction {
    // Fork-only: the entry alone. This used to format the entry's diff and scan the working tree, which nothing read:
    // that read both blobs, which a partial clone may not have, and the index even for a commit's file, which JGit
    // can't read when git wrote it split or sparse
    override suspend operator fun invoke(git: Git, diffType: DiffType): DiffEntry = when (diffType) {
        is DiffType.CommitDiff -> {
            diffType.diffEntry
        }

        is DiffType.UncommittedDiff -> {
            getDiffEntryFromStatusEntryGitAction(git, diffType.isStagedDiff, diffType.statusEntry)
        }
    }
}