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

    /** No LFS server was found for the repository (fork-only): no `lfs.url` in `.lfsconfig`, and no remote. */
    data object NoServer : LfsError

    /**
     * The batch API answered with an error for the object (fork-only), such as `404 Object does not exist`. [code] is
     * the HTTP status that the server gave the object.
     */
    data class ObjectRefused(val code: Int?, val message: String) : LfsError

    /** The batch API answered without an error, and without a way to download the object (fork-only). */
    data object NoDownloadOffered : LfsError

    /** The request failed before the server answered (fork-only): a connection that failed, ssh that failed. */
    data class RequestFailed(val message: String) : LfsError

    /** The user closed the dialog that asked for the LFS server's credentials (fork-only). */
    data object CredentialsDenied : LfsError
}
