package dev.app.leaf.data.git.workspace

import dev.app.leaf.domain.errors.HunkAction
import dev.app.leaf.domain.interfaces.IResetHunkGitAction
import dev.app.leaf.domain.models.Hunk
import org.eclipse.jgit.diff.DiffEntry
import javax.inject.Inject

class ResetHunkGitAction @Inject constructor(
    private val applyHunkGitAction: ApplyHunkGitAction,
) : IResetHunkGitAction {
    override suspend operator fun invoke(repositoryPath: String, diffEntry: DiffEntry, hunk: Hunk) =
        applyHunkGitAction(repositoryPath, diffEntry, hunk, HunkAction.Discard) { true }
}
