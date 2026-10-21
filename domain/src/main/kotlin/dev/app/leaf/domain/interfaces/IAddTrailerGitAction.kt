// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.domain.interfaces

import dev.app.leaf.domain.commitmessage.TrailerIfExists
import dev.app.leaf.domain.errors.Either
import dev.app.leaf.domain.errors.GitError

interface IAddTrailerGitAction {
    /**
     * [message] with [trailer] (`Key: value`) where git puts trailers, in the repository of [repositoryPath]: with the
     * message's trailers, before its comment lines at the end, or after a blank line. [ifExists] says when an equal
     * trailer leaves it out.
     */
    suspend operator fun invoke(
        repositoryPath: String,
        message: String,
        trailer: String,
        ifExists: TrailerIfExists = TrailerIfExists.ADD_IF_DIFFERENT,
    ): Either<String, GitError>
}
