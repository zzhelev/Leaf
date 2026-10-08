package dev.app.leaf.data.repositories

import dev.app.leaf.data.network.createHttpClientWithoutTlsVerification
import dev.app.leaf.domain.errors.Either
import dev.app.leaf.domain.network.NetworkConstants
import dev.app.leaf.domain.errors.LfsError
import dev.app.leaf.domain.lfs.LfsObjectBatch
import dev.app.leaf.domain.lfs.LfsObjects
import dev.app.leaf.domain.lfs.LfsPrepareUploadObjectBatch
import dev.app.leaf.domain.models.OperationType
import dev.app.leaf.domain.repositories.LfsRepository
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.util.cio.*
import io.ktor.utils.io.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.io.readByteArray
import kotlinx.serialization.json.Json
import java.nio.file.Path
import javax.inject.Inject
import kotlin.collections.iterator

private const val DEFAULT_ACCEPT_TYPE = "application/vnd.git-lfs+json"

class LfsNetworkDataSource @Inject constructor(
    private val client: HttpClient,
) : LfsRepository {
    private val json = Json { ignoreUnknownKeys = true }

    // Created only for a server whose http.sslVerify is false
    private val clientWithoutTlsVerification by lazy { createHttpClientWithoutTlsVerification() }

    private fun client(sslVerify: Boolean) = if (sslVerify) client else clientWithoutTlsVerification

    override suspend fun postBatchObjects(
        remoteUrl: String,
        lfsPrepareUploadObjectBatch: LfsPrepareUploadObjectBatch,
        headers: Map<String, String>,
        username: String?,
        password: String?,
        sslVerify: Boolean,
    ): Either<LfsObjects, LfsError> {
        val response = client(sslVerify).post("${remoteUrl.removeSuffix("/")}/objects/batch") {
            setHeadersAndBasicAuth(headers, username, password)

            this.contentType(ContentType("application", "vnd.git-lfs+json"))

            setBody(json.encodeToString(lfsPrepareUploadObjectBatch))
        }

        return if (response.status != HttpStatusCode.OK) {
            Either.Err(LfsError.HttpError(response.status))
        } else {
            Either.Ok(json.decodeFromString(response.bodyAsText()))
        }
    }

    override suspend fun uploadObject(
        uploadUrl: String,
        oid: String,
        file: Path,
        size: Long,
        headers: Map<String, String>,
        username: String?,
        password: String?,
        sslVerify: Boolean,
    ): Either<Unit, LfsError> {
        val response = client(sslVerify).put(uploadUrl) {
            setHeadersAndBasicAuth(headers, username, password)

            this.headers[NetworkConstants.CONTENT_LENGTH_HEADER] = size.toString()

            setBody(file.readChannel())
        }

        return if (response.status != HttpStatusCode.OK) {
            Either.Err(LfsError.HttpError(response.status))
        } else {
            Either.Ok(Unit)
        }
    }

    override suspend fun verify(
        url: String,
        oid: String,
        size: Long,
        headers: Map<String, String>,
        username: String?,
        password: String?,
        sslVerify: Boolean,
    ): Either<Unit, LfsError> {
        val response = client(sslVerify).post(url) {
            setHeadersAndBasicAuth(headers, username, password)

            val body = LfsObjectBatch(oid, size)
            setBody(json.encodeToString(body))
        }

        return if (response.status != HttpStatusCode.OK) {
            Either.Err(LfsError.HttpError(response.status))
        } else {
            Either.Ok(Unit)
        }
    }

    override suspend fun downloadObject(
        downloadUrl: String,
        outPath: Path,
        headers: Map<String, String>,
        username: String?,
        password: String?,
        sslVerify: Boolean,
    ): Either<Unit, LfsError> {
        val response = client(sslVerify).get(downloadUrl) {
            setHeadersAndBasicAuth(headers, username, password)
        }

        if (response.status != HttpStatusCode.OK) {
            return Either.Err(LfsError.HttpError(response.status))
        }

        val channel: ByteReadChannel = response.body()
        val file = outPath.toFile()

        withContext(Dispatchers.IO) {
            file.parentFile?.mkdirs()
            file.createNewFile()
        }

        while (!channel.isClosedForRead) {
            val packet = channel.readRemaining(DEFAULT_BUFFER_SIZE.toLong())
            while (!packet.exhausted()) {
                val bytes = packet.readByteArray()
                file.appendBytes(bytes)
            }
        }

        return Either.Ok(Unit)
    }

    override suspend fun getLfsObjects(
        lfsServerUrl: String,
        operationType: OperationType,
        branch: String,
        objects: List<dev.app.leaf.domain.lfs.LfsObjectBatch>,
        username: String?,
        password: String?,
        headers: Map<String, String>,
        sslVerify: Boolean,
    ): Either<dev.app.leaf.domain.lfs.LfsObjects, LfsError> {
        TODO("Not yet implemented")
    }

    private fun HttpRequestBuilder.setHeadersAndBasicAuth(
        newHeaders: Map<String, String>,
        user: String?,
        password: String?,
    ) {
        // Some headers should not be included because Ktor already sets them and adding them twice makes it crash
        val excludedHeaders = listOf(
            "Transfer-Encoding"
        )

        val filteredNewHeaders = newHeaders.filter { it.key !in excludedHeaders }

        for (header in filteredNewHeaders) {
            header(header.key, header.value)
        }

        this.headers {
            if (
                !headers.contains(NetworkConstants.AUTH_HEADER) &&
                (user != null && password != null)
            ) {
                basicAuth(user, password)
            }

            if (!this.contains(NetworkConstants.ACCEPT_HEADER)) {
                this[NetworkConstants.ACCEPT_HEADER] = DEFAULT_ACCEPT_TYPE
            }
        }
    }
}