// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.domain.interfaces

import dev.app.leaf.domain.commitmessage.CommitTemplate
import dev.app.leaf.domain.errors.Either
import dev.app.leaf.domain.errors.GitError

interface IGetCommitTemplateGitAction {
    /**
     * The file that `commit.template` names for [repositoryPath], or null when it names none, the file can't be read,
     * or the repository has no working tree.
     */
    suspend operator fun invoke(repositoryPath: String): Either<CommitTemplate?, GitError>
}
