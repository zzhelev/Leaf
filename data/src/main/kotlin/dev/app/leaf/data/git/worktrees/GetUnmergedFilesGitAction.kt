// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.data.git.worktrees

import dev.app.leaf.data.git.cli.GitCli
import dev.app.leaf.domain.errors.Either
import dev.app.leaf.domain.errors.GitError
import dev.app.leaf.domain.errors.mapOk
import dev.app.leaf.domain.interfaces.IGetUnmergedFilesGitAction
import java.io.File
import javax.inject.Inject

class GetUnmergedFilesGitAction @Inject constructor(
    private val gitCli: GitCli,
) : IGetUnmergedFilesGitAction {
    override suspend operator fun invoke(folder: String): Either<List<String>, GitError> {
        return gitCli.run(File(folder), listOf("ls-files", "--unmerged", "-z"))
            .mapOk { output -> unmergedPaths(output) }
    }
}

/**
 * The paths in `git ls-files --unmerged -z`'s [output], once each: git lists a line per stage that a path has,
 * `<mode> <object> <stage>\t<path>`, each ended with NUL.
 */
internal fun unmergedPaths(output: String): List<String> = output
    .split('\u0000')
    .filter { it.isNotEmpty() }
    .map { it.substringAfter('\t') }
    .distinct()
