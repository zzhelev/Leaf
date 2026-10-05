package dev.app.leaf.domain.interfaces

import dev.app.leaf.domain.models.DiffResult
import dev.app.leaf.domain.models.SplitHunk

interface IGenerateSplitHunkFromDiffResultGitAction {
    operator fun invoke(diffFormat: DiffResult.Text): List<SplitHunk>
}