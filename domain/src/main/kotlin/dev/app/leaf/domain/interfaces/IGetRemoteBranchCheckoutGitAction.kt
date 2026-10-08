// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.domain.interfaces

import dev.app.leaf.domain.errors.Either
import dev.app.leaf.domain.errors.GitError
import dev.app.leaf.domain.models.Branch
import dev.app.leaf.domain.models.RemoteBranchCheckout

interface IGetRemoteBranchCheckoutGitAction {
    suspend operator fun invoke(repositoryPath: String, remoteBranch: Branch): Either<RemoteBranchCheckout, GitError>
}
