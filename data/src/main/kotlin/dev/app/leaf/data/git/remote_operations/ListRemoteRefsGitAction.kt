// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.data.git.remote_operations

import dev.app.leaf.domain.errors.Either
import dev.app.leaf.domain.errors.GitError
import dev.app.leaf.domain.interfaces.IListRemoteRefsGitAction
import dev.app.leaf.domain.models.RemoteRefs
import dev.app.leaf.domain.models.remoteRefsOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.lib.Constants
import javax.inject.Inject

/**
 * Lists a remote's branches and tags with JGit's ls-remote, when git can't run (see
 * `SelectingListRemoteRefsGitAction`). The default branch is what HEAD points to when the server says it, and none
 * otherwise: JGit would have to guess it from the object ids.
 */
class ListRemoteRefsGitAction @Inject constructor(
    private val handleTransportGitAction: HandleTransportGitAction,
) : IListRemoteRefsGitAction {
    override suspend fun invoke(url: String): Either<RemoteRefs, GitError> = handleTransportGitAction(null) {
        val refs = withContext(Dispatchers.IO) {
            Git.lsRemoteRepository()
                .setRemote(url)
                .setTransportConfigCallback { handleTransport(it) }
                .callAsMap()
        }
        val head = refs[Constants.HEAD]

        remoteRefsOf(
            headTarget = head?.takeIf { it.isSymbolic }?.target?.name,
            refNames = refs.keys,
        )
    }
}
