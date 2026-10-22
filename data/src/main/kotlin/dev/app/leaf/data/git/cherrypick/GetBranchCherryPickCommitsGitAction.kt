// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.data.git.cherrypick

import dev.app.leaf.data.git.JGit
import dev.app.leaf.data.git.cli.GitCli
import dev.app.leaf.data.git.cli.remote.promisorRemotes
import dev.app.leaf.domain.cherrypick.CherryPickCandidate
import dev.app.leaf.domain.cherrypick.CherryPickLeftOut
import dev.app.leaf.domain.errors.Either
import dev.app.leaf.domain.errors.GenericError
import dev.app.leaf.domain.errors.GitError
import dev.app.leaf.domain.errors.bind
import dev.app.leaf.domain.errors.either
import dev.app.leaf.domain.interfaces.BranchCherryPickCommits
import dev.app.leaf.domain.interfaces.IGetBranchCherryPickCommitsGitAction
import dev.app.leaf.domain.models.Branch
import dev.app.leaf.domain.models.Commit
import dev.app.leaf.domain.models.Identity
import java.io.File
import javax.inject.Inject

/** `git log --cherry-mark`'s mark of a commit whose changes the other side already has. */
private const val EQUIVALENT_MARK = "="

/** The fields of each commit, a line each, its message last. */
private const val COMMIT_FORMAT = "--format=%m%n%H%n%P%n%an%n%ae%n%at%n%cn%n%ce%n%ct%n%B"

private const val FIELDS_BEFORE_MESSAGE = 9

/**
 * Lists the commits of a branch that HEAD lacks (fork-only), oldest first in an order where parents come first
 * (`--topo-order --reverse`), the order `git cherry-pick` applies them in. Merge commits are marked, and so are commits
 * whose changes HEAD already has, which `--cherry-mark` finds by their patch ids. A partial clone skips that: patch ids
 * read both sides' files, which git would download.
 *
 * The branch is named by its full ref, so that no tag or file of the same name stands in for it.
 */
class GetBranchCherryPickCommitsGitAction @Inject constructor(
    private val gitCli: GitCli,
    private val jgit: JGit,
) : IGetBranchCherryPickCommitsGitAction {
    override suspend operator fun invoke(
        repositoryPath: String,
        branch: Branch,
        limit: Int,
    ): Either<BranchCherryPickCommits, GitError> = either {
        val gitDir = File(repositoryPath)
        val gitDirArgument = "--git-dir=${gitDir.absolutePath}"
        val countArgs = listOf(gitDirArgument, "rev-list", "--count", "HEAD..${branch.name}", "--")
        val countOutput = gitCli.run(gitDir, countArgs).bind()
        val count = countOutput.trim().toIntOrNull()
            ?: return@either Either.Err(GenericError("Unexpected output of git rev-list: $countOutput"))

        if (count > limit) {
            return@either Either.Ok(BranchCherryPickCommits(emptyList(), count))
        }

        val isPartialClone = jgit.provide(repositoryPath) { git -> promisorRemotes(git.repository).isNotEmpty() }.bind()
        val range = if (isPartialClone) {
            listOf("HEAD..${branch.name}")
        } else {
            listOf("--cherry-mark", "--right-only", "HEAD...${branch.name}")
        }
        val args = listOf(
            gitDirArgument, "log", "--no-color", "--no-decorate", "--no-show-signature", "--no-mailmap",
            "--topo-order", "--reverse", "-z", COMMIT_FORMAT,
        ) + range + "--"
        val output = gitCli.run(gitDir, args).bind()

        Either.Ok(BranchCherryPickCommits(parseCherryPickCandidates(output), count))
    }
}

/** The commits that `git log -z` printed in [COMMIT_FORMAT], with what leaves them out. */
internal fun parseCherryPickCandidates(output: String): List<CherryPickCandidate> =
    output.split('\u0000').filter { it.isNotBlank() }.mapNotNull { record ->
        val lines = record.trimStart('\n').split('\n')

        if (lines.size < FIELDS_BEFORE_MESSAGE) {
            return@mapNotNull null
        }

        val parents = lines[2].split(' ').filter { it.isNotEmpty() }
        val commit = Commit(
            hash = lines[1],
            message = lines.drop(FIELDS_BEFORE_MESSAGE).joinToString("\n").trimEnd(),
            committer = Identity(lines[6], lines[7]),
            author = Identity(lines[3], lines[4]),
            date = lines[8].toLongOrNull() ?: 0,
            parentsHashes = parents,
            authorDate = lines[5].toLongOrNull() ?: 0,
        )
        val leftOut = when {
            parents.size > 1 -> CherryPickLeftOut.MERGE
            lines[0] == EQUIVALENT_MARK -> CherryPickLeftOut.ALREADY_APPLIED
            else -> null
        }

        CherryPickCandidate(commit, leftOut)
    }
