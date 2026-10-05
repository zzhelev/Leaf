package dev.app.leaf.domain.interfaces

import org.eclipse.jgit.api.Git

interface IGetRebaseAmendCommitIdGitAction {
    suspend operator fun invoke(git: Git): String?
}