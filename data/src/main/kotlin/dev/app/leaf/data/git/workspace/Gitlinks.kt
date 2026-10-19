// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.data.git.workspace

import org.eclipse.jgit.api.Git
import org.eclipse.jgit.lib.FileMode
import java.io.File

/**
 * The paths among [paths] that the index has as a gitlink, the commit of a submodule. A repository without a
 * `.gitmodules` file has no submodule that Leaf could list, so its index isn't read again for them.
 */
internal fun gitlinkPaths(git: Git, paths: Collection<String>): Set<String> {
    val workTree = if (git.repository.isBare) null else git.repository.workTree

    if (paths.isEmpty() || workTree == null || !File(workTree, ".gitmodules").exists()) {
        return emptySet()
    }

    val index = git.repository.readDirCache()

    return paths.filterTo(HashSet()) { index.getEntry(it)?.fileMode == FileMode.GITLINK }
}
