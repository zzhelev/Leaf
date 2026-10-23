package dev.example.branches

import kotlinx.coroutines.flow.Flow

/** Branches of a repository, newest first. */
@JvmInline
value class BranchName(val full: String) {
    val short: String get() = full.removePrefix("refs/heads/")
}

data class Branch(val name: BranchName, val ahead: Int = 0, val behind: Int = 0)

sealed interface Load {
    data object Loading : Load
    data class Loaded(val branches: List<Branch>) : Load
}

suspend fun describe(branch: Branch, flow: Flow<Load>): String {
    val counts = if (branch.ahead > 0) "↑${branch.ahead} ↓${branch.behind}" else "in sync"
    val raw = """
        |name: ${branch.name.short}
        |state: $counts
    """.trimMargin()
    return when {
        branch.behind > 1_000 -> "far behind: 0x${branch.behind.toString(16)}"
        else -> raw // multi-line
    }
}
