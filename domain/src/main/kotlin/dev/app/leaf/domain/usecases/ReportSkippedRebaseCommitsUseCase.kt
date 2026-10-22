// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.domain.usecases

import dev.app.leaf.common.extensions.TAG
import dev.app.leaf.common.printError
import dev.app.leaf.domain.errors.Either
import dev.app.leaf.domain.interfaces.IGetCommitsOnHeadGitAction
import dev.app.leaf.domain.rebase.SkippedCommitsNotice
import dev.app.leaf.domain.rebase.SkippedRebaseCommits
import dev.app.leaf.domain.repositories.RepositoryStateRepository
import javax.inject.Inject

/**
 * Once the rebase in a worktree has ended, tells which commits Skip left out of it (fork-only): those that HEAD doesn't
 * contain. A rebase that was aborted put the branch back, with them, so it says nothing. The refresh of the repository
 * state runs it, however the rebase ended: in Leaf, in a terminal or by an agent.
 */
class ReportSkippedRebaseCommitsUseCase @Inject constructor(
    private val skippedRebaseCommits: SkippedRebaseCommits,
    private val getCommitsOnHeadGitAction: IGetCommitsOnHeadGitAction,
    private val repositoryStateRepository: RepositoryStateRepository,
) {
    suspend operator fun invoke(gitDir: String) {
        val skipped = skippedRebaseCommits.take(gitDir).distinctBy { it.commit }

        if (skipped.isEmpty()) {
            return
        }

        val onHead = when (val result = getCommitsOnHeadGitAction(gitDir, skipped.map { it.commit })) {
            is Either.Ok -> result.value
            is Either.Err -> {
                // Saying nothing would hide what was left out: better to list them all
                printError(TAG, "Couldn't tell which skipped commits the branch has: ${result.error}")
                emptySet()
            }
        }
        val leftOut = skipped.filter { it.commit !in onHead }

        if (leftOut.isNotEmpty()) {
            repositoryStateRepository.addSkippedCommitsNotice(SkippedCommitsNotice(leftOut.first().branch, leftOut))
        }
    }
}
