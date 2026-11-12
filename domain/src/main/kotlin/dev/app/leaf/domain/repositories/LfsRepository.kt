package dev.app.leaf.domain.repositories

import dev.app.leaf.domain.errors.Either
import dev.app.leaf.domain.errors.LfsError
import dev.app.leaf.domain.lfs.LfsObjectBatch
import dev.app.leaf.domain.lfs.LfsObjects
import dev.app.leaf.domain.lfs.LfsPrepareUploadObjectBatch
import dev.app.leaf.domain.models.OperationType
import dev.app.leaf.domain.network.HttpRequestSettings
import java.nio.file.Path

/**
 * Requests to a Git LFS server. `settings` are for the request's URL: git's `http.sslVerify` (when false, the server's
 * TLS certificate isn't checked) and the proxy.
 */
interface LfsRepository {
    suspend fun postBatchObjects(
        remoteUrl: String,
        lfsPrepareUploadObjectBatch: LfsPrepareUploadObjectBatch,
        headers: Map<String, String>,
        username: String?,
        password: String?,
        settings: HttpRequestSettings,
    ): Either<LfsObjects, LfsError>

    suspend fun uploadObject(
        uploadUrl: String,
        oid: String,
        file: Path,
        size: Long,
        headers: Map<String, String>,
        username: String?,
        password: String?,
        settings: HttpRequestSettings,
    ): Either<Unit, LfsError>

    suspend fun verify(
        url: String,
        oid: String,
        size: Long,
        headers: Map<String, String>,
        username: String?,
        password: String?,
        settings: HttpRequestSettings,
    ): Either<Unit, LfsError>

    /** [onProgress] gets the bytes written to [outPath] so far (fork-only). */
    suspend fun downloadObject(
        downloadUrl: String,
        outPath: Path,
        headers: Map<String, String>,
        username: String?,
        password: String?,
        settings: HttpRequestSettings,
        onProgress: (Long) -> Unit = {},
    ): Either<Unit, LfsError>

    suspend fun getLfsObjects(
        lfsServerUrl: String,
        operationType: OperationType,
        branch: String,
        objects: List<LfsObjectBatch>,
        username: String?,
        password: String?,
        headers: Map<String, String>,
        settings: HttpRequestSettings,
    ): Either<LfsObjects, LfsError>
}