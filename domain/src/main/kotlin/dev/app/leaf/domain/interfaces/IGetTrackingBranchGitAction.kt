package dev.app.leaf.domain.interfaces

import dev.app.leaf.domain.errors.Either
import dev.app.leaf.domain.errors.GitError
import dev.app.leaf.domain.models.Branch
import dev.app.leaf.domain.models.TrackingBranch
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.lib.Ref

interface IGetTrackingBranchGitAction {
    suspend operator fun invoke(repositoryPath: String, branch: Branch): Either<TrackingBranch?, GitError>
    suspend operator fun invoke(repositoryPath: String, refName: String): Either<TrackingBranch?, GitError>
}