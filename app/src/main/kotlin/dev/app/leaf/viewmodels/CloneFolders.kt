// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.viewmodels

import java.io.File

/** The folders of [folder]'s path that don't exist yet, [folder] first: those that `mkdirs` creates for it. */
internal fun missingFolders(folder: File): List<File> =
    generateSequence(folder.absoluteFile) { it.parentFile }.takeWhile { !it.exists() }.toList()

/**
 * Removes the [folders] that the clone dialog created, innermost first, when the clone didn't complete. `delete`
 * removes a folder only when it's empty (and a link as a link), so one that holds something stays, such as a clone
 * whose submodules failed, and so do the folders above it.
 */
internal fun removeEmptyFolders(folders: List<File>) {
    folders.forEach { it.delete() }
}
