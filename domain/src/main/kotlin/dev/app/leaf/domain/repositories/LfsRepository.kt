package dev.app.leaf.domain.repositories

import dev.app.leaf.domain.errors.Either
import dev.app.leaf.domain.errors.LfsError
import dev.app.leaf.domain.lfs.LfsObjectBatch
import dev.app.leaf.domain.lfs.LfsObjects
import dev.app.leaf.domain.lfs.LfsPrepareUploadObjectBatch
import dev.app.leaf.domain.models.OperationType
import java.nio.file.Path

/**
 * Requests to a Git LFS server. `sslVerify` is git's `http.sslVerify` for the request's URL: when false, the server's TLS
 * certificate isn't checked.
 */
interface LfsRepository {
    suspend fun postBatchObjects(
        remoteUrl: String,
        lfsPrepareUploadObjectBatch: LfsPrepareUploadObjectBatch,
        headers: Map<String, String>,
        username: String?,
        password: String?,
        sslVerify: Boolean,
    ): Either<LfsObjects, LfsError>

    suspend fun uploadObject(
        uploadUrl: String,
        oid: String,
        file: Path,
        size: Long,
        headers: Map<String, String>,
        username: String?,
        password: String?,
        sslVerify: Boolean,
    ): Either<Unit, LfsError>

    suspend fun verify(
        url: String,
        oid: String,
        size: Long,
        headers: Map<String, String>,
        username: String?,
        password: String?,
        sslVerify: Boolean,
    ): Either<Unit, LfsError>

    suspend fun downloadObject(
        downloadUrl: String,
        outPath: Path,
        headers: Map<String, String>,
        username: String?,
        password: String?,
        sslVerify: Boolean,
    ): Either<Unit, LfsError>

    suspend fun getLfsObjects(
        lfsServerUrl: String,
        operationType: OperationType,
        branch: String,
        objects: List<LfsObjectBatch>,
        username: String?,
        password: String?,
        headers: Map<String, String>,
        sslVerify: Boolean,
    ): Either<LfsObjects, LfsError>
}