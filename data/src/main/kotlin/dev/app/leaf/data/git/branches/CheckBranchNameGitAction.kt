// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.data.git.branches

import dev.app.leaf.data.git.cli.GitCli
import dev.app.leaf.domain.errors.Either
import dev.app.leaf.domain.errors.GitError
import dev.app.leaf.domain.errors.mapOk
import dev.app.leaf.domain.interfaces.ICheckBranchNameGitAction
import java.io.File
import javax.inject.Inject

class CheckBranchNameGitAction @Inject constructor(
    private val gitCli: GitCli,
) : ICheckBranchNameGitAction {
    override suspend operator fun invoke(repositoryPath: String, name: String): Either<Boolean, GitError> {
        // git reads an argument that starts with - as an option
        if (name.startsWith("-")) return Either.Ok(false)

        // --branch expands @{-1} to the branch checked out before, which isn't a name to create
        return gitCli.execute(File(repositoryPath), listOf("check-ref-format", "--branch", name)).mapOk { output ->
            output.exitCode == 0 && output.stdout.trim() == name
        }
    }
}
