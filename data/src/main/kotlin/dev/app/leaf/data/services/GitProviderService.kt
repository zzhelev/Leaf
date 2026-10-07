package dev.app.leaf.data.services

import dev.app.leaf.data.git.JGit
import dev.app.leaf.domain.services.IGitProviderService
import javax.inject.Inject

class GitProviderService @Inject constructor(
    private val jgit: JGit,
) : IGitProviderService {
    override fun cleanupExcept(repositoriesToKeep: Set<String>) {
        jgit.cleanupExcept(repositoriesToKeep.map { "$it/.git" }.toSet())
    }
}