// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.data.git.conflicts

import dev.app.leaf.data.git.JGit
import dev.app.leaf.data.git.cli.GitCli
import dev.app.leaf.domain.errors.ConflictError
import dev.app.leaf.domain.errors.Either
import dev.app.leaf.domain.errors.GitError
import dev.app.leaf.domain.errors.bind
import dev.app.leaf.domain.errors.either
import dev.app.leaf.domain.errors.raiseError
import dev.app.leaf.domain.interfaces.IResolveConflictGitAction
import dev.app.leaf.domain.models.ConflictSide
import org.eclipse.jgit.dircache.DirCacheEntry
import org.eclipse.jgit.lib.FileMode
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import javax.inject.Inject

/**
 * Resolves conflicts by taking one side's version, as git's own advice does:
 * - a file that the side has: `git checkout --ours|--theirs`, then `git add`. To keep the working file, as it is, only
 *   `git add`, where it's there;
 * - a file that the side deleted: `git rm`;
 * - a submodule: `git update-index --cacheinfo` with the side's commit. `git add` would stage the commit that the
 *   submodule has checked out, whichever side that is. Its working tree is left alone, as after `git add`.
 *
 * Paths go to git with `--literal-pathspecs`, so that a name such as `[ab].txt` isn't taken as a pattern.
 */
class ResolveConflictGitAction @Inject constructor(
    private val jgit: JGit,
    private val gitCli: GitCli,
) : IResolveConflictGitAction {
    override suspend fun invoke(
        repositoryPath: String,
        filePaths: List<String>,
        side: ConflictSide,
        keepWorkingFile: Boolean,
    ): Either<Unit, GitError> = either {
        val (workTree, index) = jgit.provide(repositoryPath) { git ->
            git.repository.workTree to git.repository.readDirCache()
        }.bind()

        val notConflicted = filePaths.filter { index.fileConflict(it) == null }

        if (notConflicted.isNotEmpty()) {
            raiseError(ConflictError.NotConflicted(notConflicted))
        }

        val sideEntries = filePaths.associateWith { index.conflictEntry(it, side) }

        val deleted = filePaths.filter { sideEntries[it] == null }
        val submodules = filePaths.mapNotNull { path -> sideEntries[path]?.takeIf { it.fileMode == FileMode.GITLINK } }
        val files = filePaths - deleted.toSet() - submodules.map { it.pathString }.toSet()
        // A folder in the file's place isn't the file: git add would stage what's in it
        val checkedOut = if (keepWorkingFile) {
            files.filterNot { path ->
                val file = File(workTree, path).toPath()

                Files.exists(file, LinkOption.NOFOLLOW_LINKS) && !Files.isDirectory(file, LinkOption.NOFOLLOW_LINKS)
            }
        } else {
            files
        }

        if (checkedOut.isNotEmpty()) {
            gitCli.run(workTree, listOf("--literal-pathspecs", "checkout", side.checkoutOption, "--") + checkedOut).bind()
        }

        if (files.isNotEmpty()) {
            gitCli.run(workTree, listOf("--literal-pathspecs", "add", "--") + files).bind()
        }

        if (deleted.isNotEmpty()) {
            gitCli.run(workTree, listOf("--literal-pathspecs", "rm", "--quiet", "--") + deleted).bind()
        }

        for (submodule in submodules) {
            gitCli.run(workTree, listOf("update-index", "--cacheinfo", submodule.cacheInfo())).bind()
        }

        Either.Ok(Unit)
    }
}

internal val ConflictSide.checkoutOption
    get() = when (this) {
        ConflictSide.OURS -> "--ours"
        ConflictSide.THEIRS -> "--theirs"
    }

/** The entry as `update-index --cacheinfo` takes it, `<mode>,<object>,<path>`; the path can have commas. */
private fun DirCacheEntry.cacheInfo() = "${Integer.toOctalString(rawMode)},${objectId.name},$pathString"
