package dev.app.leaf.domain.interfaces

import org.eclipse.jgit.api.Git

interface IGetSpecificCommitMessageGitAction {
    suspend operator fun invoke(git: Git, commitId: String): String
}