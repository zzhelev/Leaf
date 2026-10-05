// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.domain.gitcli

import dev.app.leaf.domain.errors.Either
import dev.app.leaf.domain.errors.GitCliError

interface IGitExecutableLocator {
    /**
     * Returns the git CLI binary to use: [configuredPath] if set, otherwise the first supported one found in the
     * well-known install locations or PATH.
     */
    suspend fun locate(configuredPath: String?): Either<GitExecutable, GitCliError>
}
