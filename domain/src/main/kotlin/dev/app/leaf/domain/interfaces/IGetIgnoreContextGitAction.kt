// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.domain.interfaces

import dev.app.leaf.domain.errors.Either
import dev.app.leaf.domain.errors.GitError
import dev.app.leaf.domain.ignore.IgnoreContext

interface IGetIgnoreContextGitAction {
    /** The place that ignore rules went to last in this repository, and the lines of each place's file. */
    suspend operator fun invoke(repositoryPath: String): Either<IgnoreContext, GitError>
}
