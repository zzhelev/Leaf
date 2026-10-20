// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.data.git.workspace

import dev.app.leaf.domain.errors.Either
import dev.app.leaf.domain.errors.GitError
import dev.app.leaf.domain.errors.mapOk
import dev.app.leaf.domain.interfaces.IGetFileRestorePlanGitAction
import dev.app.leaf.domain.restore.FileRestorePlan
import javax.inject.Inject

class GetFileRestorePlanGitAction @Inject constructor(
    private val planner: FileRestorePlanner,
) : IGetFileRestorePlanGitAction {
    override suspend fun invoke(
        repositoryPath: String,
        commit: String,
        filePath: String,
    ): Either<FileRestorePlan, GitError> = planner.plan(repositoryPath, commit, filePath).mapOk { it.plan }
}
