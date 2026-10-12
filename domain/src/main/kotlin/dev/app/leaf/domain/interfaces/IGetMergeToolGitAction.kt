// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.domain.interfaces

import dev.app.leaf.domain.errors.Either
import dev.app.leaf.domain.errors.GitError

interface IGetMergeToolGitAction {
    /** The merge tool that `git mergetool --gui` runs (`merge.guitool`, then `merge.tool`), or null when none is set. */
    suspend operator fun invoke(repositoryPath: String): Either<String?, GitError>
}
