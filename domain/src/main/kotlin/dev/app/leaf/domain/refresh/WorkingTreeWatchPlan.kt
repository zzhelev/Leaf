// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.domain.refresh

import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption

/** A folder of the working tree that the file watcher watches, and whether everything under it is watched too. */
data class WatchedFolder(val folder: File, val isRecursive: Boolean)

/**
 * The folders of the working tree under [root] to watch, with as few registrations as possible.
 *
 * macOS gives the JDK's watch service one FSEvents stream per registration, and the system stops delivering events
 * past about 250 streams in all, without an error. Registering each of a large repository's folders left the ones
 * registered last unwatched, in every tab at once, so a file created in one of them never refreshed anything.
 *
 * A folder with nothing in its subtree to leave out (ignored folders, `.git`, other worktrees' folders, as
 * [isLeftOut] says) is watched as a whole, with one registration. A folder that has such a subfolder is watched alone,
 * and the plan goes on with the folders in it.
 *
 * Links aren't followed, as a registration of a whole folder doesn't follow them either.
 */
fun workingTreeWatchPlan(root: File, isLeftOut: (File) -> Boolean): List<WatchedFolder> =
    planFolder(root, isLeftOut).folders

/** What to watch in a folder, and whether the whole folder could be watched with one registration. */
private class FolderPlan(val isWhole: Boolean, val folders: List<WatchedFolder>)

private fun planFolder(folder: File, isLeftOut: (File) -> Boolean): FolderPlan {
    val children = try {
        folder.listFiles { child -> Files.isDirectory(child.toPath(), LinkOption.NOFOLLOW_LINKS) }
    } catch (e: SecurityException) {
        null
    }.orEmpty().sortedBy { it.name }

    var isWhole = true
    val childPlans = mutableListOf<FolderPlan>()

    for (child in children) {
        if (isLeftOut(child)) {
            isWhole = false
        } else {
            val childPlan = planFolder(child, isLeftOut)
            isWhole = isWhole && childPlan.isWhole
            childPlans += childPlan
        }
    }

    return if (isWhole) {
        FolderPlan(isWhole = true, folders = listOf(WatchedFolder(folder, isRecursive = true)))
    } else {
        FolderPlan(
            isWhole = false,
            folders = listOf(WatchedFolder(folder, isRecursive = false)) + childPlans.flatMap { it.folders },
        )
    }
}
