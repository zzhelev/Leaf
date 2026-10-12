// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.data.git.worktrees

import dev.app.leaf.common.printLog
import dev.app.leaf.domain.errors.Either
import dev.app.leaf.domain.errors.GenericError
import dev.app.leaf.domain.errors.GitError
import dev.app.leaf.domain.interfaces.ICopyWorktreeFilesGitAction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import javax.inject.Inject

private const val TAG = "CopyWorktreeFiles"

class CopyWorktreeFilesGitAction @Inject constructor() : ICopyWorktreeFilesGitAction {
    override suspend operator fun invoke(from: String, to: String, files: List<String>): Either<Unit, GitError> =
        withContext(Dispatchers.IO) {
            try {
                val source = Path.of(from).toRealPath()
                val target = Path.of(to).toRealPath()
                var copied = 0

                for (file in files) {
                    if (copy(source, target, file)) copied++
                }

                printLog(TAG, "Copied $copied of ${files.size} files from $source to $target")
                Either.Ok(Unit)
            } catch (e: IOException) {
                Either.Err(GenericError("Couldn't copy the ignored files into $to: ${e.message}", e))
            }
        }

    /**
     * Copies one file, unless it's a symbolic link, it's missing, or it's already in the new worktree, as Codex does.
     * Neither side may lead out of its worktree, through `..` or a linked folder.
     */
    private fun copy(source: Path, target: Path, file: String): Boolean {
        val from = source.resolve(file).normalize()
        val to = target.resolve(file).normalize()

        if (!from.startsWith(source) || !to.startsWith(target)) return false
        if (Files.isSymbolicLink(from) || !Files.isRegularFile(from, LinkOption.NOFOLLOW_LINKS)) return false
        if (!from.toRealPath().startsWith(source)) return false
        if (Files.exists(to, LinkOption.NOFOLLOW_LINKS)) return false

        val folder = to.parent
        Files.createDirectories(folder)

        if (!folder.toRealPath().startsWith(target)) return false

        Files.copy(from, to, StandardCopyOption.COPY_ATTRIBUTES)

        return true
    }
}
