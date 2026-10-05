package dev.app.leaf.domain.models

data class Tag(
    val commitHash: String,
    val hash: String,
    val name: String,
) {
    val simpleName: String
        get() = name.removePrefix("refs/tags/")
}