package dev.app.leaf.data.git.branches

import dev.app.leaf.data.git.JGit
import dev.app.leaf.domain.BranchesConstants
import dev.app.leaf.domain.errors.Either
import dev.app.leaf.domain.errors.GitError
import dev.app.leaf.domain.interfaces.ISetTrackingBranchGitAction
import dev.app.leaf.domain.models.Branch
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.lib.Repository
import org.eclipse.jgit.lib.StoredConfig
import javax.inject.Inject

class SetTrackingBranchGitAction @Inject constructor(
    private val jgit: JGit,
) : ISetTrackingBranchGitAction {
    override suspend operator fun invoke(repositoryPath: String, branch: Branch, remoteName: String?, remoteBranch: Branch?): Either<Unit, GitError> {
        return invoke(repositoryPath, branch.simpleName, remoteName, remoteBranch?.simpleName)
    }

    override suspend operator fun invoke(repositoryPath: String, refName: String, remoteName: String?, remoteBranchName: String?) = jgit.provide(repositoryPath) { git ->
        val repository: Repository = git.repository
        val config: StoredConfig = repository.config

        if (remoteName == null || remoteBranchName == null) {
            config.unset("branch", refName, "remote")
            config.unset("branch", refName, "merge")
        } else {
            config.setString("branch", refName, "remote", remoteName)
            config.setString(
                "branch",
                refName,
                "merge",
                BranchesConstants.UPSTREAM_BRANCH_CONFIG_PREFIX + remoteBranchName
            )
        }

        config.save()
    }
}
