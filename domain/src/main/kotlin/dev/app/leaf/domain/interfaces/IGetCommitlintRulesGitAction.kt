// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.domain.interfaces

import dev.app.leaf.domain.commitmessage.CommitlintRules
import dev.app.leaf.domain.errors.Either
import dev.app.leaf.domain.errors.GitError

interface IGetCommitlintRulesGitAction {
    /** The commitlint config in the working tree of [repositoryPath], or null when there's none (or no working tree). */
    suspend operator fun invoke(repositoryPath: String): Either<CommitlintRules?, GitError>
}
