package dev.app.leaf.domain.interfaces

import org.eclipse.jgit.api.Git

interface IInitializeAllSubmodulesGitAction {
    suspend operator fun invoke(git: Git): Unit
}