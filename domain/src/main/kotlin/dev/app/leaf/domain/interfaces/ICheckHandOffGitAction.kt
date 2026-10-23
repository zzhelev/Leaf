// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.domain.interfaces

import dev.app.leaf.domain.errors.Either
import dev.app.leaf.domain.errors.GitError
import dev.app.leaf.domain.models.ServerHandOff

/**
 * Asks the server what it did with [ServerHandOff] after the user stopped waiting for it (fork-only), and when it did
 * what it was handed, does here what git would have done after it: moves or removes the remote-tracking branch, and
 * sets the upstream that the push set.
 */
interface ICheckHandOffGitAction {
    suspend operator fun invoke(repositoryPath: String, handOff: ServerHandOff): Either<HandOffOutcome, GitError>
}

sealed interface HandOffOutcome {
    /** The server has the pushed commit, or no longer has the deleted branch. */
    data object Done : HandOffOutcome

    /** It hasn't (yet): it has [now] on the branch, or no such branch when null. */
    data class NotDone(val now: String?) : HandOffOutcome

    /** The remote pushes to several URLs, and one of them can't say what the others did. */
    data object CantTell : HandOffOutcome
}
