// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.domain.interfaces

import dev.app.leaf.domain.errors.Either
import dev.app.leaf.domain.errors.GitError

interface IPersistSquashMessageGitAction {
    /**
     * Writes [message] to `SQUASH_MSG` in the git dir [repositoryPath], where a squash merge keeps the message of the
     * commit that ends it, or removes the file when [message] is null.
     */
    suspend operator fun invoke(repositoryPath: String, message: String?): Either<Unit, GitError>
}
