// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.data.git.cherrypick

import dev.app.leaf.data.git.cli.GitCli
import dev.app.leaf.domain.cherrypick.BranchAhead
import dev.app.leaf.domain.cherrypick.BranchesAhead
import dev.app.leaf.domain.errors.Either
import dev.app.leaf.domain.errors.GitCliError
import dev.app.leaf.domain.errors.GitError
import dev.app.leaf.domain.errors.mapOk
import dev.app.leaf.domain.interfaces.IGetBranchesAheadGitAction
import java.io.File
import javax.inject.Inject

/** The refs whose commits the branch menus offer to cherry-pick: local and remote branches. */
private val BRANCH_REFS = listOf("refs/heads", "refs/remotes")

/** Each branch, how many commits it has and HEAD doesn't (and the other way round), and its tip's parents. */
private const val COUNTED_FORMAT = "--format=%(refname)%00%(ahead-behind:HEAD)%00%(parent)"

/** Each branch that has commits HEAD doesn't, without a count, which git before 2.41 can't give. */
private const val UNCOUNTED_FORMAT = "--format=%(refname)%00%(parent)"

/**
 * Counts the commits that each local and remote branch has and HEAD lacks (fork-only), for the branch menus'
 * cherry-pick item, with one `git for-each-ref` for all of them: `%(ahead-behind:HEAD)` walks the history once, with
 * the commit graph when there is one. git before 2.41 doesn't know it, and then `--no-merged=HEAD` tells which
 * branches have such commits, without how many. Branches with none are left out.
 *
 * HEAD is the worktree's, as git reads it from the git dir: a linked worktree has its own.
 */
class GetBranchesAheadGitAction @Inject constructor(
    private val gitCli: GitCli,
) : IGetBranchesAheadGitAction {
    override suspend operator fun invoke(repositoryPath: String): Either<BranchesAhead, GitError> {
        val gitDir = File(repositoryPath)
        val gitDirArgument = "--git-dir=${gitDir.absolutePath}"
        val counted = gitCli.run(gitDir, listOf(gitDirArgument, "for-each-ref", COUNTED_FORMAT) + BRANCH_REFS)

        val error = when (counted) {
            is Either.Ok -> return Either.Ok(parseCountedBranches(counted.value))
            is Either.Err -> counted.error
        }

        return when {
            error.isUnknownAtom() -> gitCli
                .run(gitDir, listOf(gitDirArgument, "for-each-ref", "--no-merged=HEAD", UNCOUNTED_FORMAT) + BRANCH_REFS)
                .mapOk { parseUncountedBranches(it) }

            // A repository without commits yet: HEAD names a branch that doesn't exist, and no branch has commits
            error.isMissingHead() -> Either.Ok(BranchesAhead())
            else -> Either.Err(error)
        }
    }
}

private fun GitCliError.isUnknownAtom(): Boolean =
    this is GitCliError.CommandFailed && "ahead-behind" in stderr

private fun GitCliError.isMissingHead(): Boolean =
    this is GitCliError.CommandFailed && ("'HEAD'" in stderr || "HEAD" in stderr && "not a valid" in stderr)

/** `<ref>\0<ahead> <behind>\0<parents>` lines, as [COUNTED_FORMAT] prints them. */
internal fun parseCountedBranches(output: String): BranchesAhead {
    val byRef = output.lineSequence().mapNotNull { line ->
        val fields = line.split('\u0000')
        val ahead = fields.getOrNull(1)?.substringBefore(' ')?.toIntOrNull() ?: return@mapNotNull null

        if (fields.size < 3 || ahead <= 0) {
            null
        } else {
            fields[0] to BranchAhead(ahead, tipIsMerge = fields[2].isMerge())
        }
    }.toMap()

    return BranchesAhead(byRef)
}

/** `<ref>\0<parents>` lines, as [UNCOUNTED_FORMAT] prints them. */
internal fun parseUncountedBranches(output: String): BranchesAhead {
    val byRef = output.lineSequence().mapNotNull { line ->
        val fields = line.split('\u0000')

        if (fields.size < 2 || fields[0].isEmpty()) {
            null
        } else {
            fields[0] to BranchAhead(count = null, tipIsMerge = fields[1].isMerge())
        }
    }.toMap()

    return BranchesAhead(byRef)
}

private fun String.isMerge(): Boolean = trim().split(' ').count { it.isNotEmpty() } > 1
