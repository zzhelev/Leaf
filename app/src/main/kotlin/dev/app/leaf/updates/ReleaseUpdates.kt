// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.updates

import dev.app.leaf.AppConstants
import dev.app.leaf.common.printError
import dev.app.leaf.common.printLog
import dev.app.leaf.data.network.ProxiedHttpClients
import dev.app.leaf.data.network.ProxySettings
import dev.app.leaf.data.network.UnusableProxyException
import dev.app.leaf.domain.AppFilesManager
import dev.app.leaf.domain.network.ProxyChoice
import dev.app.leaf.releasemanifest.MANIFEST_FILE_NAME
import dev.app.leaf.releasemanifest.MANIFEST_FORMAT
import dev.app.leaf.releasemanifest.REQUIRED_SIGNATURES
import dev.app.leaf.releasemanifest.ReleaseFile
import dev.app.leaf.releasemanifest.ReleaseFileKind
import dev.app.leaf.releasemanifest.ReleaseManifest
import dev.app.leaf.releasemanifest.TRUSTED_RELEASE_KEYS
import dev.app.leaf.releasemanifest.TrustedKey
import dev.app.leaf.releasemanifest.checkReleaseSignatures
import dev.app.leaf.releasemanifest.isReleaseVersion
import dev.app.leaf.releasemanifest.releaseFileUrl
import dev.app.leaf.releasemanifest.signatureFileName
import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.utils.io.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.io.readByteArray
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.io.IOException
import java.net.UnknownHostException
import java.nio.channels.UnresolvedAddressException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "ReleaseUpdates"

/** The most that a manifest may take. A release's is about 1.3 KB. */
private const val MAX_MANIFEST_BYTES = 64 * 1024

/** The most that a signature may take. One is about 300 bytes. */
private const val MAX_SIGNATURE_BYTES = 8 * 1024

/** The largest release file that Leaf downloads. 1.10.0's largest is 182 MB. */
const val MAX_RELEASE_FILE_SIZE = 1L shl 30

private const val DOWNLOAD_BUFFER_SIZE = 64 * 1024

private val SHA256_HEX = Regex("[0-9a-f]{64}")

/** What checking a release's manifest found, before anything is downloaded (docs/fork/auto-update.md, section 2). */
sealed interface ReleaseVerification {
    /** The manifest checks out, and lists [file] for this Leaf ([kind]), to download from [url]. */
    data class Verified(
        val manifest: ReleaseManifest,
        val kind: ReleaseFileKind,
        val file: ReleaseFile,
        val url: String,
    ) : ReleaseVerification

    /** Leaf can't update itself to this release, which the user downloads from its page, as before. */
    data class ByHand(val reason: ByHandReason) : ReleaseVerification

    /** The manifest didn't check out, so nothing is downloaded. [reason] says why, for the user. */
    data class Refused(val reason: String) : ReleaseVerification

    /** The manifest couldn't be read: the network, a proxy, the server. [reason] says why, for the user. */
    data class Failed(val reason: String) : ReleaseVerification
}

enum class ByHandReason {
    /** A dev run, or a system that no release file is for. */
    NOT_FOR_THIS_LEAF,

    /** This Leaf trusts no key, so no manifest can check out. */
    NO_TRUSTED_KEYS,

    /** The release has no manifest: one from before signing, or not signed yet. */
    NO_MANIFEST,

    /** The manifest is of a later format than this Leaf reads. */
    NEWER_FORMAT,

    /** The manifest lists no file for this Leaf's kind. */
    NO_FILE_FOR_THIS_LEAF,
}

/** What downloading a verified release file came to. */
sealed interface UpdateDownload {
    /** [file] is the release file, with the manifest's size and SHA-256. */
    data class Ready(val file: File) : UpdateDownload

    /** What was downloaded isn't what the manifest lists, and was deleted. [reason] says why, for the user. */
    data class Refused(val reason: String) : UpdateDownload

    /** The download didn't finish: the network, a proxy, the server. [reason] says why, for the user. */
    data class Failed(val reason: String) : UpdateDownload
}

/** A small file of a release, as fetched. */
private sealed interface Fetched {
    class Bytes(val bytes: ByteArray) : Fetched
    data object NotFound : Fetched
    data object TooLarge : Fetched
    data class Failed(val reason: String) : Fetched
}

/**
 * Finds a release's signed manifest, checks it, and downloads the file that updates this Leaf (fork-only,
 * docs/fork/auto-update.md, step 2). It installs nothing.
 *
 * The files come from [fileUrl], a release's folder on GitHub, through Leaf's own clients and the proxy that git-lfs
 * would use for their address. A redirect, which GitHub sends to its file servers, is followed with the same client.
 */
@Singleton
class ReleaseUpdates(
    private val httpClient: HttpClient,
    private val updatesFolder: () -> File,
    private val kind: ReleaseFileKind?,
    private val currentVersionCode: Int,
    private val trusted: List<TrustedKey> = TRUSTED_RELEASE_KEYS,
    private val required: Int = REQUIRED_SIGNATURES,
    private val fileUrl: (version: String, fileName: String) -> String = ::releaseFileUrl,
    private val proxyFor: suspend (url: String) -> ProxyChoice = { ProxyChoice.Direct },
    private val proxiedClients: ProxiedHttpClients = ProxiedHttpClients(),
) {
    @Inject
    constructor(
        httpClient: HttpClient,
        appFilesManager: AppFilesManager,
        proxySettings: ProxySettings,
        proxiedClients: ProxiedHttpClients,
    ) : this(
        httpClient = httpClient,
        updatesFolder = { File(appFilesManager.getAppFolder(), UPDATES_FOLDER_NAME) },
        kind = releaseFileKindOf(runningLeaf()),
        currentVersionCode = AppConstants.APP_VERSION_CODE,
        proxyFor = proxySettings::forRequestWithoutRepository,
        proxiedClients = proxiedClients,
    )

    /**
     * Checks the manifest of [update], the latest release as `latest.json` names it: its address comes from the
     * version, at least [required] of the [trusted] keys must have signed it, it must be the same version and version
     * code as `latest.json`'s, newer than this Leaf, and list a file for this Leaf.
     */
    suspend fun verify(update: Update): ReleaseVerification {
        val kind = kind ?: return ReleaseVerification.ByHand(ByHandReason.NOT_FOR_THIS_LEAF)
        val version = update.appVersion

        // The version goes into the addresses
        if (!isReleaseVersion(version)) {
            return refused("The latest release's version, \"$version\", isn't one that Leaf can update to.")
        }

        if (trusted.isEmpty()) {
            return ReleaseVerification.ByHand(ByHandReason.NO_TRUSTED_KEYS)
        }

        val bytes = when (val manifest = fetch(fileUrl(version, MANIFEST_FILE_NAME), MAX_MANIFEST_BYTES)) {
            is Fetched.Bytes -> manifest.bytes
            Fetched.NotFound -> return ReleaseVerification.ByHand(ByHandReason.NO_MANIFEST)
            Fetched.TooLarge -> return refused("Leaf $version's $MANIFEST_FILE_NAME is larger than a manifest can be.")
            is Fetched.Failed -> return ReleaseVerification.Failed(manifest.reason)
        }

        val signatures = trusted.associate { key ->
            key.name to when (val signature = fetch(fileUrl(version, signatureFileName(key.name)), MAX_SIGNATURE_BYTES)) {
                is Fetched.Bytes -> signature.bytes
                Fetched.NotFound, Fetched.TooLarge -> null
                is Fetched.Failed -> return ReleaseVerification.Failed(signature.reason)
            }
        }
        val check = checkReleaseSignatures(bytes, signatures, trusted, required)

        if (!check.isEnough) {
            printError(TAG, "Leaf $version's signatures: ${check.problems}")

            return refused(
                "Leaf $version's $MANIFEST_FILE_NAME isn't signed by enough of the keys that this Leaf trusts " +
                    "(${check.signedBy.size} of ${check.required}), so Leaf didn't download it."
            )
        }

        // Signed: what it says can be read
        val text = bytes.toString(Charsets.UTF_8)
        val format = try {
            Json.parseToJsonElement(text).jsonObject["format"]?.jsonPrimitive?.intOrNull
        } catch (e: IllegalArgumentException) {
            null
        }

        if (format != null && format > MANIFEST_FORMAT) {
            return ReleaseVerification.ByHand(ByHandReason.NEWER_FORMAT)
        }

        val manifest = try {
            ReleaseManifest.parse(bytes)
        } catch (e: SerializationException) {
            return refused("Leaf $version's $MANIFEST_FILE_NAME can't be read.")
        } catch (e: IllegalArgumentException) {
            return refused("Leaf $version's $MANIFEST_FILE_NAME can't be read.")
        }

        return when {
            manifest.format != MANIFEST_FORMAT -> refused("Leaf $version's $MANIFEST_FILE_NAME can't be read.")
            manifest.appVersion != version ->
                refused("Leaf $version's $MANIFEST_FILE_NAME is for version ${manifest.appVersion}.")

            manifest.appCode != update.appCode -> refused(
                "Leaf $version's $MANIFEST_FILE_NAME has the version code ${manifest.appCode}, where the latest " +
                    "release's is ${update.appCode}."
            )

            manifest.appCode <= currentVersionCode -> refused(
                "Leaf $version's $MANIFEST_FILE_NAME has the version code ${manifest.appCode}, which isn't newer " +
                    "than this Leaf's, $currentVersionCode."
            )

            else -> fileFor(manifest, kind)
        }
    }

    private fun fileFor(manifest: ReleaseManifest, kind: ReleaseFileKind): ReleaseVerification {
        val file = manifest.fileFor(kind) ?: return ReleaseVerification.ByHand(ByHandReason.NO_FILE_FOR_THIS_LEAF)
        val version = manifest.appVersion

        // The name goes into the address and the updates folder
        return if (
            file.name != kind.fileName(version) || !SHA256_HEX.matches(file.sha256) || file.size <= 0 ||
            file.size > MAX_RELEASE_FILE_SIZE
        ) {
            refused("Leaf $version's $MANIFEST_FILE_NAME lists ${kind.fileName(version)} in a way that Leaf can't use.")
        } else {
            printLog(TAG, "Leaf $version's manifest checks out: ${file.name}, ${file.size} bytes")
            ReleaseVerification.Verified(manifest, kind, file, fileUrl(version, file.name))
        }
    }

    /**
     * Downloads [verified]'s file into the updates folder, which keeps only that file. It's written as `<name>.part`,
     * never past the manifest's size, and renamed once its size and SHA-256 are the manifest's; otherwise it's deleted.
     * A file that is there already, complete, isn't downloaded again. [onProgress] gets the bytes written so far and
     * the file's size. Cancelling deletes what was written.
     */
    suspend fun download(
        verified: ReleaseVerification.Verified,
        onProgress: (downloaded: Long, total: Long) -> Unit = { _, _ -> },
    ): UpdateDownload {
        val file = verified.file
        val folder = updatesFolder()
        val target = File(folder, file.name)
        val part = File(folder, file.name + PART_SUFFIX)

        withContext(Dispatchers.IO) {
            Files.createDirectories(folder.toPath())
            removeOtherFiles(folder, keep = setOf(target.name))
        }

        if (withContext(Dispatchers.IO) { target.isFile && target.length() == file.size && sha256Of(target) == file.sha256 }) {
            printLog(TAG, "${file.name} is downloaded already")
            onProgress(file.size, file.size)

            return UpdateDownload.Ready(target)
        }

        var isReady = false

        try {
            val result = try {
                downloadTo(part, verified, onProgress)
            } catch (e: Exception) {
                currentCoroutineContext().ensureActive()
                printError(TAG, "Downloading ${verified.url} failed: $e")

                UpdateDownload.Failed(failureReason(verified.url, e))
            }

            if (result is UpdateDownload.Ready) {
                withContext(Dispatchers.IO) {
                    Files.move(part.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
                }
                isReady = true

                return UpdateDownload.Ready(target)
            }

            return result
        } finally {
            if (!isReady) {
                withContext(NonCancellable + Dispatchers.IO) { part.delete() }
            }
        }
    }

    private suspend fun downloadTo(
        part: File,
        verified: ReleaseVerification.Verified,
        onProgress: (downloaded: Long, total: Long) -> Unit,
    ): UpdateDownload {
        val file = verified.file
        val url = verified.url
        printLog(TAG, "Downloading $url")

        return clientFor(url).prepareGet(url).execute { response ->
            if (response.status != HttpStatusCode.OK) {
                return@execute UpdateDownload.Failed("${Url(url).host} answered ${response.status} for ${file.name}.")
            }

            val channel = response.bodyAsChannel()
            val digest = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(DOWNLOAD_BUFFER_SIZE)
            var written = 0L

            withContext(Dispatchers.IO) {
                Files.newOutputStream(part.toPath()).use { output ->
                    while (true) {
                        // One byte past the size is enough to tell that the file is larger
                        val wanted = minOf(buffer.size.toLong(), file.size + 1 - written).toInt()

                        if (wanted <= 0) break

                        val read = channel.readAvailable(buffer, 0, wanted)

                        if (read < 0) break

                        output.write(buffer, 0, read)
                        digest.update(buffer, 0, read)
                        written += read
                        onProgress(minOf(written, file.size), file.size)
                    }
                }
            }

            val sha256 = digest.digest().toHex()

            when {
                written > file.size -> refusedDownload("${file.name} is larger than its manifest says, so Leaf deleted it.")
                written < file.size -> UpdateDownload.Failed(
                    "The download of ${file.name} stopped at ${written} of ${file.size} bytes."
                )

                sha256 != file.sha256 -> refusedDownload(
                    "${file.name} isn't the file that its manifest lists (its SHA-256 differs), so Leaf deleted it."
                )

                else -> {
                    printLog(TAG, "Downloaded ${file.name}, ${file.size} bytes, SHA-256 $sha256")
                    UpdateDownload.Ready(part)
                }
            }
        }
    }

    /** [url]'s body, up to [maxBytes]: a release's manifest or a signature. */
    private suspend fun fetch(url: String, maxBytes: Int): Fetched = try {
        clientFor(url).prepareGet(url).execute { response ->
            when {
                response.status == HttpStatusCode.NotFound -> Fetched.NotFound
                response.status != HttpStatusCode.OK -> Fetched.Failed("${Url(url).host} answered ${response.status}.")
                else -> {
                    val channel = response.bodyAsChannel()
                    val bytes = channel.readRemaining(maxBytes + 1L).readByteArray()

                    if (bytes.size > maxBytes) Fetched.TooLarge else Fetched.Bytes(bytes)
                }
            }
        }
    } catch (e: Exception) {
        currentCoroutineContext().ensureActive()
        printError(TAG, "Fetching $url failed: $e")

        Fetched.Failed(failureReason(url, e))
    }

    private suspend fun clientFor(url: String): HttpClient =
        proxiedClients.clientFor(proxyFor(url), sslVerify = true) ?: httpClient

    private fun refused(reason: String): ReleaseVerification.Refused {
        printError(TAG, reason)

        return ReleaseVerification.Refused(reason)
    }

    private fun refusedDownload(reason: String): UpdateDownload.Refused {
        printError(TAG, reason)

        return UpdateDownload.Refused(reason)
    }

    companion object {
        /** In Leaf's app data folder, which quitting doesn't clear, as it does `tmp`. */
        const val UPDATES_FOLDER_NAME = "updates"

        const val PART_SUFFIX = ".part"
    }
}

private fun failureReason(url: String, e: Exception): String {
    val host = Url(url).host

    return when (e) {
        is UnusableProxyException -> "Couldn't use the proxy ${e.proxy.proxy} that ${e.proxy.source} names."
        is UnresolvedAddressException, is UnknownHostException -> "Couldn't find the address of $host."
        is IOException -> "Couldn't reach $host: ${e.message ?: e::class.simpleName}."
        else -> "Couldn't reach $host: ${e.message ?: e::class.simpleName}."
    }
}

/** Deletes the files in [folder] but [keep], and their `.part` files: what an earlier update left. */
private fun removeOtherFiles(folder: File, keep: Set<String>) {
    val kept = keep + keep.map { it + ReleaseUpdates.PART_SUFFIX }

    folder.listFiles()
        ?.filter { it.name !in kept && Files.isRegularFile(it.toPath(), LinkOption.NOFOLLOW_LINKS) }
        ?.forEach { it.delete() }
}

private fun sha256Of(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")

    file.inputStream().use { input ->
        val buffer = ByteArray(DOWNLOAD_BUFFER_SIZE)

        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            digest.update(buffer, 0, read)
        }
    }

    return digest.digest().toHex()
}

private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
