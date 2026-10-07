package dev.app.leaf.domain.services

interface IGitProviderService {
    fun cleanupExcept(repositoriesToKeep: Set<String>)
}