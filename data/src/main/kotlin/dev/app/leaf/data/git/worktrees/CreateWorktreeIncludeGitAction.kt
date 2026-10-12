// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.data.git.worktrees

import dev.app.leaf.domain.errors.Either
import dev.app.leaf.domain.errors.GenericError
import dev.app.leaf.domain.errors.GitError
import dev.app.leaf.domain.interfaces.ICreateWorktreeIncludeGitAction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import javax.inject.Inject

/** What a new `.worktreeinclude` says: how it works, and examples to uncomment. */
internal val WORKTREE_INCLUDE_TEMPLATE = """
    |# Gitignored files to copy into new worktrees, one pattern per line, in .gitignore syntax.
    |# A file is copied only when git ignores it and a pattern here matches it.
    |# Leaf, Claude Code, Codex and Conductor read this file.
    |#
    |# .env
    |# .env.local
    |# local.properties
    |""".trimMargin()

class CreateWorktreeIncludeGitAction @Inject constructor() : ICreateWorktreeIncludeGitAction {
    override suspend operator fun invoke(mainWorktreePath: String): Either<String, GitError> =
        withContext(Dispatchers.IO) {
            val file = File(mainWorktreePath, WORKTREE_INCLUDE_FILE)

            try {
                if (!file.exists()) {
                    file.writeText(WORKTREE_INCLUDE_TEMPLATE)
                }

                Either.Ok(file.path)
            } catch (e: IOException) {
                Either.Err(GenericError("Couldn't create ${file.path}: ${e.message}", e))
            }
        }
}
