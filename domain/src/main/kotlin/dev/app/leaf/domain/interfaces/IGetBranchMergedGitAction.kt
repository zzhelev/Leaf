// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.domain.interfaces

import dev.app.leaf.domain.errors.Either
import dev.app.leaf.domain.errors.GitError
import dev.app.leaf.domain.models.BranchMerged

interface IGetBranchMergedGitAction {
    /**
     * Tells whether [branch] is merged into [base] (full names, `refs/heads/x`): its tip is in [base], or merging it
     * would change nothing, as after a squash merge.
     */
    suspend operator fun invoke(repositoryPath: String, base: String, branch: String): Either<BranchMerged, GitError>
}
