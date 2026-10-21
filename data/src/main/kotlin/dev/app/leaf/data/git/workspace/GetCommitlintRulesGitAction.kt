// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.data.git.workspace

import dev.app.leaf.data.git.JGit
import dev.app.leaf.domain.commitmessage.COMMITLINT_CONFIG_FILES
import dev.app.leaf.domain.commitmessage.CommitlintRules
import dev.app.leaf.domain.commitmessage.parseCommitlintConfig
import dev.app.leaf.domain.errors.Either
import dev.app.leaf.domain.errors.GitError
import dev.app.leaf.domain.interfaces.IGetCommitlintRulesGitAction
import java.io.File
import javax.inject.Inject

/** A config larger than this isn't one that a person wrote, and isn't read. */
private const val MAX_CONFIG_SIZE = 1024 * 1024

/**
 * Reads the commitlint config of a worktree (fork-only): the first of [COMMITLINT_CONFIG_FILES] in the root of its
 * working tree, as commitlint looks for it, a `package.json` only with a `commitlint` key. A bare repository has none.
 */
class GetCommitlintRulesGitAction @Inject constructor(
    private val jgit: JGit,
) : IGetCommitlintRulesGitAction {
    override suspend fun invoke(repositoryPath: String): Either<CommitlintRules?, GitError> =
        jgit.provide(repositoryPath) { git ->
            val workTree = if (git.repository.isBare) null else git.repository.workTree

            workTree?.let(::commitlintRulesIn)
        }
}

internal fun commitlintRulesIn(workTree: File): CommitlintRules? = COMMITLINT_CONFIG_FILES.firstNotNullOfOrNull { name ->
    File(workTree, name)
        .takeIf { it.isFile && it.length() <= MAX_CONFIG_SIZE }
        ?.let { file -> parseCommitlintConfig(name, file.readText()) }
}
