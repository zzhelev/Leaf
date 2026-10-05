package dev.app.leaf.data.git.log

import dev.app.leaf.data.git.JGit
import dev.app.leaf.domain.interfaces.ICherryPickCommitGitAction
import dev.app.leaf.domain.models.Commit
import javax.inject.Inject

class CherryPickCommitGitAction @Inject constructor(
    private val jgit: JGit,
) : ICherryPickCommitGitAction {
    override suspend operator fun invoke(repositoryPath: String, commit: Commit) = jgit.provide(repositoryPath) { git ->
        val base =
            git.repository.resolve(commit.hash) ?: throw Exception("Commit ${commit.hash} not found")

        git.cherryPick()
            .include(base)
            .call()

        Unit
    }
}