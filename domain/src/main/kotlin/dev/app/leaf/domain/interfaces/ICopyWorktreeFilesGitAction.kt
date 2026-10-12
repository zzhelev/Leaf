// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.domain.interfaces

import dev.app.leaf.domain.errors.Either
import dev.app.leaf.domain.errors.GitError

interface ICopyWorktreeFilesGitAction {
    /**
     * Copies [files], paths relative to the worktree in [from], into the worktree in [to], creating their folders. A
     * file that's already in [to], one that's gone from [from], or a symbolic link, isn't copied.
     */
    suspend operator fun invoke(from: String, to: String, files: List<String>): Either<Unit, GitError>
}
