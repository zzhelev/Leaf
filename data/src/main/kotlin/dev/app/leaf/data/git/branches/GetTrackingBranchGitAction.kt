package dev.app.leaf.data.git.branches

import dev.app.leaf.data.git.JGit
import dev.app.leaf.domain.BranchesConstants
import dev.app.leaf.domain.errors.Either
import dev.app.leaf.domain.errors.GitError
import dev.app.leaf.domain.interfaces.IGetTrackingBranchGitAction
import dev.app.leaf.domain.models.Branch
import dev.app.leaf.domain.models.TrackingBranch
import org.eclipse.jgit.lib.Config
import org.eclipse.jgit.lib.Repository
import javax.inject.Inject

class GetTrackingBranchGitAction @Inject constructor(
    private val jgit: JGit,
) : IGetTrackingBranchGitAction {
    override suspend operator fun invoke(repositoryPath: String, branch: Branch): Either<TrackingBranch?, GitError> {
        return this.invoke(repositoryPath, branch.simpleName)
    }

    override suspend operator fun invoke(repositoryPath: String, refName: String) =
        jgit.provide(repositoryPath) { git ->
            val repository: Repository = git.repository

            val config: Config = repository.config
            val remote: String? = config.getString("branch", refName, "remote")
            val branch: String? = config.getString("branch", refName, "merge")

            if (remote != null && branch != null) {
                TrackingBranch(remote, branch.removePrefix(BranchesConstants.UPSTREAM_BRANCH_CONFIG_PREFIX))
            } else {
                null
            }
        }
}

