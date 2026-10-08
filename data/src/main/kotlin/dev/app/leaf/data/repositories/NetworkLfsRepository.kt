package dev.app.leaf.data.repositories

import dev.app.leaf.domain.errors.Either
import dev.app.leaf.domain.errors.LfsError
import dev.app.leaf.domain.lfs.LfsObjectBatch
import dev.app.leaf.domain.lfs.LfsObjects
import dev.app.leaf.domain.lfs.LfsPrepareUploadObjectBatch
import dev.app.leaf.domain.lfs.LfsRef
import dev.app.leaf.domain.models.OperationType
import dev.app.leaf.domain.repositories.LfsRepository
import javax.inject.Inject

private const val TAG = "LfsRepository"

class NetworkLfsRepository @Inject constructor(
    private val lfsNetworkDataSource: LfsNetworkDataSource,
) : LfsRepository by lfsNetworkDataSource {
    override suspend fun getLfsObjects(
        lfsServerUrl: String,
        operationType: OperationType,
        branch: String,
        objects: List<LfsObjectBatch>,
        username: String?,
        password: String?,
        headers: Map<String, String>,
        sslVerify: Boolean,
    ): Either<LfsObjects, LfsError> {
        return postBatchObjects(
            lfsServerUrl,
            createLfsPrepareUploadObjectBatch(
                operationType,
                branch = branch,
                objects = objects,
            ),
            headers = headers,
            username,
            password,
            sslVerify,
        )
    }

    private fun createLfsPrepareUploadObjectBatch(
        operation: OperationType,
        algo: String = "sha256",
        branch: String,
        objects: List<LfsObjectBatch>,
    ): LfsPrepareUploadObjectBatch {
        return LfsPrepareUploadObjectBatch(
            operation = operation.value,
            objects = objects,
            transfers = listOf(
                "basic",
                // TODO Add support for standalone files and SSH once they are stable https://github.com/git-lfs/git-lfs/blob/main/docs/api/README.md
                "lfs-standalone-file",
                "ssh",
            ),
            ref = LfsRef(branch),
            hashAlgo = algo,
        )
    }
}


