// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.data.git.diff

import dev.app.leaf.data.git.JGit
import dev.app.leaf.data.git.cli.remote.PartialCloneFetch
import dev.app.leaf.domain.errors.Either
import dev.app.leaf.domain.errors.GitError
import dev.app.leaf.domain.interfaces.IGetFileDiffBetweenCommitsGitAction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.eclipse.jgit.diff.DiffEntry
import org.eclipse.jgit.revwalk.RevWalk
import org.eclipse.jgit.treewalk.TreeWalk
import org.eclipse.jgit.treewalk.filter.PathFilter
import javax.inject.Inject

/**
 * One file's change between two commits (fork-only, `docs/fork/compare.md`, 7.3), for two versions in file history. It
 * walks the path in both trees only, and reads no blob: the diff pane reads them by id, as for any commit's file.
 */
class GetFileDiffBetweenCommitsGitAction @Inject constructor(
    private val jgit: JGit,
    private val partialClone: PartialCloneFetch,
) : IGetFileDiffBetweenCommitsGitAction {
    override suspend operator fun invoke(
        repositoryPath: String,
        path: String,
        a: String,
        b: String,
    ): Either<DiffEntry?, GitError> = withContext(Dispatchers.IO) {
        // A treeless clone may lack the trees, which git downloads
        partialClone.retryingMissing(repositoryPath) { fileDiff(repositoryPath, path, a, b) }
    }

    private suspend fun fileDiff(repositoryPath: String, path: String, a: String, b: String) =
        jgit.provide(repositoryPath) { git ->
            val repository = git.repository

            RevWalk(repository).use { walk ->
                TreeWalk(repository).use { treeWalk ->
                    treeWalk.addTree(walk.parseCommit(repository.resolve("$a^{commit}")).tree)
                    treeWalk.addTree(walk.parseCommit(repository.resolve("$b^{commit}")).tree)
                    treeWalk.isRecursive = true
                    treeWalk.filter = PathFilter.create(path)

                    // A folder at the path in one of them lists its files too: the file's own entry is the one
                    DiffEntry.scan(treeWalk).firstOrNull { it.oldPath == path || it.newPath == path }
                }
            }
        }
}
