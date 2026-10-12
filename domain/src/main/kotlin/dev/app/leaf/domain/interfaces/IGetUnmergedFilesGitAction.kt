// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.domain.interfaces

import dev.app.leaf.domain.errors.Either
import dev.app.leaf.domain.errors.GitError

interface IGetUnmergedFilesGitAction {
    /** The paths with conflicts in the index of the worktree in [folder], as `git ls-files --unmerged` lists them. */
    suspend operator fun invoke(folder: String): Either<List<String>, GitError>
}
