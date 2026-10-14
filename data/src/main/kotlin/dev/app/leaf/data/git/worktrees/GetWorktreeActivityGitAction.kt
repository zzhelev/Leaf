// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.data.git.worktrees

import dev.app.leaf.data.git.cli.GitCli
import dev.app.leaf.domain.errors.Either
import dev.app.leaf.domain.errors.GitError
import dev.app.leaf.domain.errors.bind
import dev.app.leaf.domain.errors.either
import dev.app.leaf.domain.interfaces.IGetWorktreeActivityGitAction
import dev.app.leaf.domain.worktrees.gitDirOf
import java.io.File
import javax.inject.Inject

/** The files in a worktree's git dir that git writes as it works: staging, commits, checkouts, resets. */
private val GIT_DIR_FILES = listOf("index", "HEAD", "logs/HEAD")

class GetWorktreeActivityGitAction @Inject constructor(
    private val gitCli: GitCli,
) : IGetWorktreeActivityGitAction {
    override suspend operator fun invoke(worktreePath: String, notBefore: Long?): Either<Long?, GitError> = either {
        val worktree = File(worktreePath)
        val gitDir = File(gitDirOf(worktreePath))
        val gitDirTime = GIT_DIR_FILES.lastModified(gitDir)

        // The cheap part first: it's enough to tell that the worktree changed since notBefore
        if (notBefore != null && gitDirTime != null && gitDirTime >= notBefore) {
            return@either Either.Ok(gitDirTime)
        }

        // GIT_OPTIONAL_LOCKS=0 (GitCli) keeps git status from writing the index, whose time is read here
        val args = listOf("status", "--porcelain=v1", "-z", "--no-renames", "--untracked-files=normal")
        val changedFilesTime = parseStatusPaths(gitCli.run(worktree, args).bind()).lastModified(worktree)

        Either.Ok(listOfNotNull(gitDirTime, changedFilesTime).maxOrNull())
    }
}

/** The newest time of these files in [folder], null when none has one. */
private fun List<String>.lastModified(folder: File): Long? = map { File(folder, it).lastModified() }
    .filter { it > 0 }
    .maxOrNull()

/**
 * The paths that `git status --porcelain=v1 -z --no-renames` lists, one per entry (`XY <path>`). An untracked folder
 * is listed as itself, with a final `/`.
 */
internal fun parseStatusPaths(output: String): List<String> = output
    .split('\u0000')
    .filter { it.length > 3 }
    .map { it.substring(3).removeSuffix("/") }
