// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.data.git.conflicts

import org.eclipse.jgit.attributes.Attributes
import org.eclipse.jgit.dircache.DirCacheIterator
import org.eclipse.jgit.lib.CoreConfig.EolStreamType
import org.eclipse.jgit.lib.Repository
import org.eclipse.jgit.treewalk.FileTreeIterator
import org.eclipse.jgit.treewalk.TreeWalk
import org.eclipse.jgit.treewalk.TreeWalk.OperationType
import org.eclipse.jgit.treewalk.WorkingTreeOptions
import org.eclipse.jgit.treewalk.filter.PathFilter
import org.eclipse.jgit.util.io.EolStreamTypeUtil

/** What a conflicted file's git attributes say about merging it and writing it. */
internal data class ConflictFileAttributes(
    /** The `conflict-marker-size` attribute, or git's 7. */
    val markerSize: Int,

    /** `-merge` or `merge=binary` (the `binary` macro sets `-merge`): git merges it as binary, by taking ours. */
    val mergesAsBinary: Boolean,

    /** How a checkout converts its line endings (`core.autocrlf`, `core.eol`, the `text` and `eol` attributes). */
    val checkoutLineEndings: EolStreamType,
)

/**
 * Reads [path]'s attributes from the `.gitattributes` files of the index and the working tree, `info/attributes` and
 * `core.attributesFile`, as a checkout does.
 */
internal fun conflictFileAttributes(repository: Repository, path: String): ConflictFileAttributes {
    val attributes = attributesOf(repository, path)

    return ConflictFileAttributes(
        markerSize = attributes.getValue("conflict-marker-size")?.toIntOrNull()?.takeIf { it > 0 }
            ?: DEFAULT_CONFLICT_MARKER_SIZE,
        mergesAsBinary = attributes.isUnset("merge") || attributes.getValue("merge") == "binary",
        checkoutLineEndings = EolStreamTypeUtil.detectStreamType(
            OperationType.CHECKOUT_OP,
            repository.config.get(WorkingTreeOptions.KEY),
            attributes,
        ),
    )
}

private fun attributesOf(repository: Repository, path: String): Attributes = TreeWalk(repository).use { walk ->
    walk.operationType = OperationType.CHECKOUT_OP
    // The index has the path even when the working tree doesn't, as when the file was deleted
    walk.addTree(DirCacheIterator(repository.readDirCache()))

    if (!repository.isBare) {
        walk.addTree(FileTreeIterator(repository))
    }

    walk.filter = PathFilter.create(path)
    walk.isRecursive = true

    while (walk.next()) {
        if (walk.pathString == path) {
            return walk.attributes
        }
    }

    Attributes()
}
