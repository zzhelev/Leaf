package dev.app.leaf.domain.errors

import io.ktor.http.HttpStatusCode

sealed interface LfsError {
    data class HttpError(val code: HttpStatusCode) : LfsError

    /**
     * A download that isn't the LFS object [oid] (fork-only): it broke off, or the server sent other content. It has
     * [receivedSize] bytes instead of [expectedSize], or the SHA-256 [receivedOid].
     */
    data class CorruptDownload(
        val oid: String,
        val expectedSize: Long,
        val receivedSize: Long,
        val receivedOid: String? = null,
    ) : LfsError
}
