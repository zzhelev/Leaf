// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.domain.interfaces

import dev.app.leaf.domain.errors.Either
import dev.app.leaf.domain.errors.GitError
import dev.app.leaf.domain.models.RemoteRefs

interface IListRemoteRefsGitAction {
    /**
     * Asks the remote at [url] for its branches and tags, once, as `git ls-remote` does. It needs no repository, so
     * the Clone dialog can use it. Credentials and host keys are asked through the usual dialogs.
     */
    suspend operator fun invoke(url: String): Either<RemoteRefs, GitError>
}
