package dev.app.leaf.domain.repositories

// TODO do we want to use this?
interface IGitProvidersRepository {
    fun initialize()
    fun cleanup()
}