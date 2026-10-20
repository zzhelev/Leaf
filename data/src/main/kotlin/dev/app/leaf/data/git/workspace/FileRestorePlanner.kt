// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.data.git.workspace

import dev.app.leaf.data.git.JGit
import dev.app.leaf.data.git.cli.GitCli
import dev.app.leaf.domain.errors.Either
import dev.app.leaf.domain.errors.GenericError
import dev.app.leaf.domain.errors.GitError
import dev.app.leaf.domain.errors.RestoreFileError
import dev.app.leaf.domain.errors.bind
import dev.app.leaf.domain.errors.either
import dev.app.leaf.domain.errors.raiseError
import dev.app.leaf.domain.restore.FileRestorePlan
import dev.app.leaf.domain.restore.currentFileOf
import dev.app.leaf.domain.restore.isConflictedStatus
import org.eclipse.jgit.lib.Constants
import org.eclipse.jgit.lib.ObjectId
import org.eclipse.jgit.revwalk.RevWalk
import org.eclipse.jgit.treewalk.TreeWalk
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import javax.inject.Inject

/** A [FileRestorePlan], and the working tree that git runs in. */
class PlannedRestore(val workTree: File, val plan: FileRestorePlan)

/**
 * Looks at restoring a file's version from a commit before anything is written (fork-only). It's what the confirmation
 * says, and what the restore checks again just before it runs, as the dialog may have been open for a while.
 *
 * `git restore --source` says nothing in front of the cases it refuses here: probed with git 2.54, it replaces a folder
 * at the path with the file and deletes the untracked files in it, replaces a file where a folder of the path goes,
 * and overwrites a conflicted file. A commit without the file is git's own error, but it's told in Leaf's words.
 */
class FileRestorePlanner @Inject constructor(
    private val jgit: JGit,
    private val gitCli: GitCli,
) {
    suspend fun plan(repositoryPath: String, commit: String, filePath: String): Either<PlannedRestore, GitError> = either {
        val (workTree, inCommit) = jgit.provide(repositoryPath) { git ->
            val repository = git.repository

            if (repository.isBare) {
                raiseError(RestoreFileError.NoWorkTree)
            }

            if (!isPathInRepository(filePath)) {
                raiseError(GenericError("\"$filePath\" isn't a path in the repository."))
            }

            val tree = RevWalk(repository).use { walk -> walk.parseCommit(ObjectId.fromString(commit)).tree }
            // A file or a link: a folder, or a submodule, isn't something that restore writes as a file
            val isFile = TreeWalk.forPath(repository, filePath, tree)?.use {
                it.getFileMode(0).objectType == Constants.OBJ_BLOB
            } ?: false

            repository.workTree to isFile
        }.bind()

        val file = File(workTree, filePath)
        val exists = Files.exists(file.toPath(), LinkOption.NOFOLLOW_LINKS)
        // What is on disk is looked at first: git refuses a path beyond a link, and has no status to give for it
        val placeRefusal = when {
            !inCommit -> RestoreFileError.NotInCommit(filePath, commit)
            else -> blockingParent(workTree, filePath) ?: folderInPlace(file, filePath)
        }
        val statusLine = if (placeRefusal == null) statusLineOf(workTree, filePath).bind() else null
        val (current, hasStaged) = currentFileOf(statusLine, exists)
        val refusal = placeRefusal ?: RestoreFileError.Conflicted(filePath).takeIf { isConflictedStatus(statusLine) }

        Either.Ok(PlannedRestore(workTree, FileRestorePlan(filePath, commit, current, hasStaged, refusal)))
    }

    /** The line `git status --porcelain=v1 -z` prints for [filePath] alone, or null when it prints none. */
    private suspend fun statusLineOf(workTree: File, filePath: String): Either<String?, GitError> = either {
        val output = gitCli.run(
            workTree,
            listOf(
                "--literal-pathspecs", "status", "--porcelain=v1", "-z", "--no-renames", "--ignored=traditional",
                "--untracked-files=all", "--", filePath,
            ),
        ).bind()

        Either.Ok(output.split('\u0000').firstOrNull { it.length > 3 && it.substring(3) == filePath })
    }

    /** A file or a link where a folder of [filePath] goes, which git replaces with the folder. */
    private fun blockingParent(workTree: File, filePath: String): RestoreFileError? {
        val parts = filePath.split('/').dropLast(1)
        var folder = workTree.toPath()

        for ((index, part) in parts.withIndex()) {
            folder = folder.resolve(part)

            if (!Files.exists(folder, LinkOption.NOFOLLOW_LINKS)) {
                return null
            }

            if (!Files.isDirectory(folder, LinkOption.NOFOLLOW_LINKS)) {
                return RestoreFileError.ParentNotFolder(filePath, parts.take(index + 1).joinToString("/"))
            }
        }

        return null
    }

    /** A folder where the file goes, which git deletes with whatever untracked files are in it. A link isn't one. */
    private fun folderInPlace(file: File, filePath: String): RestoreFileError? =
        RestoreFileError.FolderInPlace(filePath).takeIf { Files.isDirectory(file.toPath(), LinkOption.NOFOLLOW_LINKS) }
}

/** A path under the repository's root: no empty, `.` or `..` parts, and not absolute. */
internal fun isPathInRepository(path: String): Boolean =
    path.isNotBlank() && !path.startsWith("/") && !path.contains('\\') && path.split('/').none { it.isEmpty() || it == "." || it == ".." }
