package dev.app.leaf.data.git.workspace

import dev.app.leaf.domain.errors.HunkAction
import dev.app.leaf.domain.interfaces.IUnstageHunkLineGitAction
import dev.app.leaf.domain.models.Hunk
import dev.app.leaf.domain.models.Line
import org.eclipse.jgit.diff.DiffEntry
import javax.inject.Inject

class UnstageHunkLineGitAction @Inject constructor(
    private val applyHunkGitAction: ApplyHunkGitAction,
) : IUnstageHunkLineGitAction {
    override suspend operator fun invoke(repositoryPath: String, diffEntry: DiffEntry, hunk: Hunk, line: Line) =
        applyHunkGitAction(repositoryPath, diffEntry, hunk, HunkAction.Unstage) { isSameLine(it, line) }
}
