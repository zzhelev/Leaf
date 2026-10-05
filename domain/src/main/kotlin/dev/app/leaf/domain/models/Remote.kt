package dev.app.leaf.domain.models

data class Remote(
    val name: String,
    val fetchUri: String,
    val pushUri: String,
)

fun newRemoteWrapper(): Remote {
    return Remote(
        name = "",
        fetchUri = "",
        pushUri = "",
    )
}