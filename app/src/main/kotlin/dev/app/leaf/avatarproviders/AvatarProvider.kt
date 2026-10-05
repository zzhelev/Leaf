package dev.app.leaf.avatarproviders

interface AvatarProvider {
    fun getAvatarUrl(hashedEmail: String): String?
}