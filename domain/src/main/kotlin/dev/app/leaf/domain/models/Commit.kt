package dev.app.leaf.domain.models

import androidx.compose.runtime.Immutable

@Immutable
data class Commit(
    val hash: String,
    val message: String,
    val committer: Identity,
    val author: Identity,
    val date: Long,
    val parentsHashes: List<String>,
    /** When the author wrote the change. [date] is when it was committed, which a rebase or an amend changes. */
    val authorDate: Long = date,
) {
    val parentCount = parentsHashes.count()

    val shortHash: String
        get() = this.hash.orEmpty().take(7)

    val shortMessage: String
        get() = this.message
            .trimStart()
            .replace("\r\n", "\n")
            .takeWhile { it != '\n' }
}