package dev.app.leaf.domain.services

interface IGitProviderService {
    /**
     * Closes every cached repository except [repositoriesToKeep]. These are git dirs, as in
     * `RepositorySelectionState.Open.path`: a linked worktree's or a submodule's is not its working tree + `/.git`.
     */
    fun cleanupExcept(repositoriesToKeep: Set<String>)
}