// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.domain.interfaces

import dev.app.leaf.domain.errors.Either
import dev.app.leaf.domain.errors.GitError
import dev.app.leaf.domain.models.RenamedFile

interface IGetRenamedFilesGitAction {
    /** The files that each side renamed differently, from the index: each is three conflicted paths. */
    suspend operator fun invoke(repositoryPath: String): Either<List<RenamedFile>, GitError>
}
