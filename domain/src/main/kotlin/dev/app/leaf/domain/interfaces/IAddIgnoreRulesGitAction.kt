// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.domain.interfaces

import dev.app.leaf.domain.errors.Either
import dev.app.leaf.domain.errors.GitError
import dev.app.leaf.domain.ignore.IgnorePlace

interface IAddIgnoreRulesGitAction {
    /**
     * Appends [rules] to the ignore file of [place], without those it already has, and remembers [place] for the
     * repository. Each rule is one line.
     */
    suspend operator fun invoke(repositoryPath: String, place: IgnorePlace, rules: List<String>): Either<Unit, GitError>
}
