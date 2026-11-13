// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.data.git.log

import org.eclipse.jgit.blame.BlameGenerator
import org.eclipse.jgit.blame.BlameResult
import org.eclipse.jgit.diff.RawText
import org.eclipse.jgit.dircache.DirCacheIterator
import org.eclipse.jgit.lib.Constants
import org.eclipse.jgit.lib.FileMode
import org.eclipse.jgit.lib.Repository
import org.eclipse.jgit.treewalk.FileTreeIterator
import org.eclipse.jgit.treewalk.TreeWalk
import org.eclipse.jgit.treewalk.filter.PathFilter

/**
 * The blame of [newPath], a file that the index moved from [oldPath] (fork-only, `docs/fork/status-renames.md`). JGit's
 * blame looks for [newPath] in HEAD, which doesn't have it, and gives every line as not committed. This one starts from
 * [oldPath] at HEAD, with the file on disk at [newPath] as the version not committed yet, as JGit's own blame pushes
 * HEAD and then the working file: the lines that the move kept show their commits. Null when HEAD has no [oldPath] or
 * the disk no file at [newPath], for the plain blame to answer.
 */
internal fun blameMovedFile(repository: Repository, newPath: String, oldPath: String): BlameResult? {
    val head = repository.resolve(Constants.HEAD) ?: return null
    val text = checkedInText(repository, newPath) ?: return null

    if (TreeWalk.forPath(repository, oldPath, repository.parseCommit(head).tree) == null) {
        return null
    }

    return BlameGenerator(repository, oldPath).use { generator ->
        generator.setFollowFileRenames(true)
        // Oldest first: HEAD, then what isn't committed, which takes HEAD as its parent
        generator.push(null, head)
        generator.push(null, text)

        BlameResult.create(generator)?.apply { computeAll() }
    }
}

/** The file at [path] on disk, as `git add` would store it (filters, line endings), as JGit's blame reads it. */
private fun checkedInText(repository: Repository, path: String): RawText? = TreeWalk(repository).use { walk ->
    walk.operationType = TreeWalk.OperationType.CHECKIN_OP
    val files = FileTreeIterator(repository)
    val filesTree = walk.addTree(files)
    val indexTree = walk.addTree(DirCacheIterator(repository.readDirCache()))
    files.setDirCacheIterator(walk, indexTree)
    walk.filter = PathFilter.create(path)
    walk.isRecursive = true

    while (walk.next()) {
        val file = walk.getTree(filesTree, FileTreeIterator::class.java) ?: continue

        if (walk.pathString == path && (file.entryRawMode and FileMode.TYPE_MASK) == FileMode.TYPE_FILE) {
            return@use file.openEntryStream().use { RawText(it.readBytes()) }
        }
    }

    null
}
