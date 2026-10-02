package com.jetpackduba.gitnuro.domain.gitcli

import com.jetpackduba.gitnuro.domain.errors.Either
import com.jetpackduba.gitnuro.domain.errors.GitCliError

interface IGitExecutableLocator {
    /**
     * Returns the git CLI binary to use: [configuredPath] if set, otherwise the first supported one found in the
     * well-known install locations or PATH.
     */
    suspend fun locate(configuredPath: String?): Either<GitExecutable, GitCliError>
}
