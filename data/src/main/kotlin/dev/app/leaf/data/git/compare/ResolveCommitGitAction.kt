// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.data.git.compare

import dev.app.leaf.data.git.JGit
import dev.app.leaf.data.mappers.JGitCommitMapper
import dev.app.leaf.domain.errors.Either
import dev.app.leaf.domain.errors.GitError
import dev.app.leaf.domain.interfaces.IResolveCommitGitAction
import dev.app.leaf.domain.models.Commit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.eclipse.jgit.errors.AmbiguousObjectException
import org.eclipse.jgit.errors.IncorrectObjectTypeException
import org.eclipse.jgit.errors.RevisionSyntaxException
import org.eclipse.jgit.revwalk.RevWalk
import javax.inject.Inject

/**
 * Resolves what was typed in the list that picks a comparison's side (fork-only, `docs/fork/compare.md`, 7.1), as
 * [GetComparisonGitAction] resolves a side: `<revision>^{commit}`. What names no commit, a part of a hash that several
 * objects start with, or text that isn't a revision, is null rather than an error: the list asks as the user types.
 */
class ResolveCommitGitAction @Inject constructor(
    private val jgit: JGit,
    private val commitMapper: JGitCommitMapper,
) : IResolveCommitGitAction {
    override suspend operator fun invoke(repositoryPath: String, revision: String): Either<Commit?, GitError> =
        withContext(Dispatchers.IO) {
            jgit.provide(repositoryPath) { git ->
                val repository = git.repository
                val id = try {
                    repository.resolve("$revision^{commit}")
                } catch (e: RevisionSyntaxException) {
                    null
                } catch (e: AmbiguousObjectException) {
                    null
                } catch (e: IncorrectObjectTypeException) {
                    null
                }

                id?.let { RevWalk(repository).use { walk -> commitMapper.toDomain(walk.parseCommit(it)) } }
            }
        }
}
