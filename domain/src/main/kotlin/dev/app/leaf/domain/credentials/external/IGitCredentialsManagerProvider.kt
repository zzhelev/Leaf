package dev.app.leaf.domain.credentials.external

interface IGitCredentialsManagerProvider {
    fun loadPath(): String?
}