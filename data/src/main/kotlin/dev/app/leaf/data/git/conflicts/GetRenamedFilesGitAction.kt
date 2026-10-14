// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.data.git.conflicts

import dev.app.leaf.data.git.JGit
import dev.app.leaf.domain.errors.Either
import dev.app.leaf.domain.errors.GitError
import dev.app.leaf.domain.interfaces.IGetRenamedFilesGitAction
import dev.app.leaf.domain.models.RenamedFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject

class GetRenamedFilesGitAction @Inject constructor(
    private val jgit: JGit,
) : IGetRenamedFilesGitAction {
    override suspend fun invoke(repositoryPath: String): Either<List<RenamedFile>, GitError> =
        withContext(Dispatchers.IO) {
            jgit.provide(repositoryPath) { git -> renamedFiles(git.repository, git.repository.readDirCache()) }
        }
}
