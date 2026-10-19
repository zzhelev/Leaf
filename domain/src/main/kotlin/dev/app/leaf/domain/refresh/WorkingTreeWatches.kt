// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.domain.refresh

import dev.app.leaf.domain.interfaces.IFileChangesWatcher
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption

private const val GIT_FOLDER = ".git"

/**
 * The folders of a tab's working tree that the file watcher watches (fork-only), and the plan they follow
 * ([workingTreeWatchPlan]), kept up to date with what git ignores.
 *
 * A folder that is watched with everything under it ([WatchedFolder.isRecursive]) can't leave a folder out, so one that
 * becomes ignored later, because its rule was added in Leaf or in an editor, or because a build made it, would have
 * every file written in it refresh the tab, until the tab was opened again. [onStatus] plans again whenever the ignored
 * folders of a status are not the ones that the registrations were planned for: it takes back the registrations that
 * the plan no longer has, and a folder whose rule is removed is watched again.
 *
 * @param isLeftOut folders that are never watched, besides `.git`: the linked worktrees' folders.
 */
class WorkingTreeWatches(
    private val watcher: IFileChangesWatcher,
    private val workingTree: File,
    private val isLeftOut: (File) -> Boolean,
) {
    private val lock = Any()
    private val prefix = workingTree.absolutePath + File.separator

    /** What the watcher was given for the working tree: each folder, and whether all under it is watched too. */
    private val registered = LinkedHashMap<File, Boolean>()

    /** The ignored folders that the registrations were planned for, or null until the whole tree was planned. */
    private var plannedFor: Set<String>? = null

    /** The ignored paths of the last status that [onStatus] saw, to leave out the ones that changed nothing. */
    private var lastSeen: Set<String>? = null

    /** The ignored folders of the newest status seen, which a plan that began earlier may not have. */
    private var newestFolders: Set<String>? = null

    /**
     * Watches the whole working tree, as [ignored], the paths that git ignores in the status, says. At the start and
     * after lost changes. Watching a folder again changes nothing, and brings back one that was deleted and made again.
     */
    fun watchAll(ignored: Collection<String>) {
        synchronized(lock) { watchAllLocked(ignored) }
    }

    private fun watchAllLocked(ignored: Collection<String>) {
        val isFirst = plannedFor == null

        plan(ignoredFolders(ignored))

        // A status that came in while the first one was read may be newer
        val newest = newestFolders

        if (isFirst && newest != null && newest != plannedFor) {
            plan(newest)
        }
    }

    /** Plans again when [ignored], the paths that git ignores in a status, has other folders than the plan was for. */
    fun onStatus(ignored: Collection<String>) {
        synchronized(lock) { onStatusLocked(ignored) }
    }

    private fun onStatusLocked(ignored: Collection<String>) {
        val paths = ignored.toSet()

        if (paths == lastSeen) {
            return
        }

        lastSeen = paths

        val folders = ignoredFolders(paths)

        newestFolders = folders

        // The first plan, made from its own status, will see it
        if (plannedFor != null && folders != plannedFor) {
            plan(folders)
        }
    }

    /**
     * Watches [folder], created after the plan, as [ignored], the ignored paths of its status, says. Under a folder that
     * is watched as a whole it needs nothing.
     */
    fun watchNew(folder: File, ignored: Collection<String>) {
        synchronized(lock) { watchNewLocked(folder, ignored) }
    }

    private fun watchNewLocked(folder: File, ignored: Collection<String>) {
        if (registered.any { (watched, isRecursive) -> isRecursive && folder.isUnder(watched) }) {
            return
        }

        for (watched in workingTreeWatchPlan(folder, isLeftOutBy(ignoredFolders(ignored)))) {
            registered[watched.folder] = watched.isRecursive
            watcher.addPathToWatch(watched.folder.absolutePath, watched.isRecursive)
        }
    }

    /** Stops watching [path], a folder that was deleted, and the folders in it. */
    fun unwatch(path: String) {
        synchronized(lock) { unwatchLocked(path) }
    }

    private fun unwatchLocked(path: String) {
        watcher.removePathFromWatch(path)

        val folder = File(path)

        for (watched in registered.keys.filter { it.isUnder(folder) }) {
            if (watched != folder) {
                watcher.removePathFromWatch(watched.path)
            }

            registered.remove(watched)
        }
    }

    private fun plan(ignoredFolders: Set<String>) {
        val plan = workingTreeWatchPlan(workingTree, isLeftOutBy(ignoredFolders))
            .associate { it.folder to it.isRecursive }

        // A registration that the plan doesn't have, or has the other way, goes before the others come: watching
        // a folder as a whole while the folders in it are watched too would deliver each change twice, and
        // every registration counts against macOS's limit
        for ((folder, isRecursive) in registered.entries.toList()) {
            if (plan[folder] != isRecursive) {
                watcher.removePathFromWatch(folder.path)
            }
        }

        for ((folder, isRecursive) in plan) {
            watcher.addPathToWatch(folder.absolutePath, isRecursive)
        }

        registered.clear()
        registered += plan
        plannedFor = ignoredFolders
    }

    /** The folders among [ignored], which hold files as well: the plan only leaves folders out. */
    private fun ignoredFolders(ignored: Collection<String>): Set<String> = ignored.filterTo(HashSet()) {
        Files.isDirectory(File(workingTree, it).toPath(), LinkOption.NOFOLLOW_LINKS)
    }

    private fun isLeftOutBy(ignoredFolders: Set<String>): (File) -> Boolean = { folder ->
        val relativePath = folder.absolutePath.removePrefix(prefix)

        relativePath in ignoredFolders || relativePath == GIT_FOLDER || isLeftOut(folder)
    }
}

private fun File.isUnder(folder: File): Boolean = toPath().startsWith(folder.toPath())
