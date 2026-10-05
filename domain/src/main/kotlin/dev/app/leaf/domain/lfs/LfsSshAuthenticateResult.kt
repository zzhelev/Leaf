package dev.app.leaf.domain.lfs

import kotlinx.serialization.Serializable

@Serializable
data class LfsSshAuthenticateResult(
    val href: String,
    val header: Map<String, String>,
)