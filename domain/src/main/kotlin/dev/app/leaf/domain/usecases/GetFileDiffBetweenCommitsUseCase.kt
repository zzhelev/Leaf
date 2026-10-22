// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.domain.usecases

import dev.app.leaf.domain.UseCaseExecutor
import dev.app.leaf.domain.interfaces.IGetFileDiffBetweenCommitsGitAction
import dev.app.leaf.domain.models.Commit
import org.eclipse.jgit.diff.DiffEntry
import javax.inject.Inject

/** Two versions of a file in its history, compared (fork-only, `docs/fork/compare.md`, 7.3). */
class GetFileDiffBetweenCommitsUseCase @Inject constructor(
    private val getFileDiffBetweenCommitsGitAction: IGetFileDiffBetweenCommitsGitAction,
    private val useCaseExecutor: UseCaseExecutor,
) {
    suspend operator fun invoke(path: String, a: Commit, b: Commit) = useCaseExecutor.execute<DiffEntry?> { repositoryPath ->
        getFileDiffBetweenCommitsGitAction(repositoryPath, path, a.hash, b.hash)
    }
}
