// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.domain.interfaces

import dev.app.leaf.domain.lfs.LfsServer
import dev.app.leaf.domain.models.OperationType
import org.eclipse.jgit.lib.Repository

interface IGetLfsUrlGitAction {
    /**
     * The LFS server of [repository] for [operation] with the remote [remoteName] (`refs/remotes/<name>`), or with
     * the remote that git-lfs picks when it's null, as Leaf's built-in LFS client finds it. Null when there's none.
     */
    suspend operator fun invoke(
        repository: Repository,
        remoteName: String?,
        operation: OperationType = OperationType.DOWNLOAD,
    ): LfsServer?
}
