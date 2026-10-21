// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.domain.interfaces

import dev.app.leaf.domain.commitmessage.MessageCleanup
import dev.app.leaf.domain.errors.Either
import dev.app.leaf.domain.errors.GitError

interface IGetCommitMessageCleanupGitAction {
    /** How git cleans up a commit message in the repository of [repositoryPath]: its `commit.cleanup` and comment prefix. */
    suspend operator fun invoke(repositoryPath: String): Either<MessageCleanup, GitError>
}
