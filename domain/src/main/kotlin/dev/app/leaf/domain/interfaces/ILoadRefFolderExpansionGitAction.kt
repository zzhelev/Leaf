// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.domain.interfaces

import dev.app.leaf.domain.errors.Either
import dev.app.leaf.domain.errors.GitError
import dev.app.leaf.domain.sorting.RefFolderExpansion

interface ILoadRefFolderExpansionGitAction {
    suspend operator fun invoke(repositoryPath: String): Either<RefFolderExpansion, GitError>
}
