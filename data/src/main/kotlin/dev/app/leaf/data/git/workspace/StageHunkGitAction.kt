package dev.app.leaf.data.git.workspace

import dev.app.leaf.domain.errors.HunkAction
import dev.app.leaf.domain.interfaces.IStageHunkGitAction
import dev.app.leaf.domain.models.Hunk
import org.eclipse.jgit.diff.DiffEntry
import javax.inject.Inject

class StageHunkGitAction @Inject constructor(
    private val applyHunkGitAction: ApplyHunkGitAction,
) : IStageHunkGitAction {
    override suspend operator fun invoke(repositoryPath: String, diffEntry: DiffEntry, hunk: Hunk) =
        applyHunkGitAction(repositoryPath, diffEntry, hunk, HunkAction.Stage) { true }
}
