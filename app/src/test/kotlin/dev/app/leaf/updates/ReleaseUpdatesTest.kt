// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.updates

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import dev.app.leaf.data.network.createHttpClient
import dev.app.leaf.domain.network.ProxyChoice
import dev.app.leaf.releasemanifest.MANIFEST_FILE_NAME
import dev.app.leaf.releasemanifest.ReleaseFileKind
import dev.app.leaf.releasemanifest.TrustedKey
import dev.app.leaf.releasemanifest.signatureFileName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.DisabledOnOs
import org.junit.jupiter.api.condition.OS
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.net.InetAddress
import java.net.InetSocketAddress
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.seconds

/**
 * Checking a release's manifest and downloading its file (step 2 of docs/fork/auto-update.md), against a local server
 * that serves releases as GitHub's release folders do. Releases are signed as the release workflow signs them, with
 * `release-manifest/sign-manifest.sh` and keys that the real ssh-keygen makes.
 */
@DisabledOnOs(OS.WINDOWS)
class ReleaseUpdatesTest {
    @TempDir
    lateinit var dir: File

    private sealed interface Answer {
        data class Bytes(val bytes: ByteArray) : Answer
        data class Status(val code: Int) : Answer
        data class Redirect(val to: String) : Answer

        /** Sends half the bytes, then waits, so that a download can be cancelled in the middle. */
        data class Stalled(val bytes: ByteArray) : Answer

        /** Sends [chunk] again and again, without a length, until the client hangs up or the test ends. */
        data class Endless(val chunk: ByteArray) : Answer
    }

    private val server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
    private val answers = ConcurrentHashMap<String, Answer>()
    private val requests = CopyOnWriteArrayList<String>()

    /** Lets a stalled answer end, when the test does. */
    private val endStalled = CountDownLatch(1)
    private val httpClient = createHttpClient()

    private val version = "1.11.0"
    private val signManifest = File("../release-manifest/sign-manifest.sh").absoluteFile

    private lateinit var github: File
    private lateinit var trusted: List<TrustedKey>
    private lateinit var trustedFile: File
    private lateinit var files: File

    private val base get() = "http://127.0.0.1:${server.address.port}/download"
    private val updates get() = File(dir, "updates")

    @BeforeEach
    fun setUp() {
        assumeTrue(hasSshKeygen(), "ssh-keygen isn't installed")

        server.createContext("/") { exchange -> answer(exchange) }
        // A stalled answer mustn't hold the others up, nor the JVM once the tests end
        server.executor = Executors.newCachedThreadPool { runnable -> Thread(runnable).apply { isDaemon = true } }
        server.start()

        github = newKey("github")
        trusted = listOf(TrustedKey("github", publicKey(github)))
        trustedFile = File(dir, "trusted-release-keys").apply { writeText("github ${publicKey(github)}\n") }

        files = File(dir, "files").apply { mkdirs() }
        for (kind in ReleaseFileKind.entries) {
            File(files, kind.fileName(version)).writeText("${kind.key} ".repeat(5_000 * (kind.ordinal + 1)))
            serve(kind.fileName(version), File(files, kind.fileName(version)).readBytes())
        }
    }

    @AfterEach
    fun tearDown() {
        endStalled.countDown()
        server.stop(0)
        httpClient.close()
    }

    private fun answer(exchange: HttpExchange) {
        val path = exchange.requestURI.path
        requests += path

        exchange.use {
            when (val answer = answers[path] ?: Answer.Status(404)) {
                is Answer.Bytes -> {
                    exchange.sendResponseHeaders(200, answer.bytes.size.toLong())
                    exchange.responseBody.write(answer.bytes)
                }

                is Answer.Status -> exchange.sendResponseHeaders(answer.code, -1)
                is Answer.Redirect -> {
                    exchange.responseHeaders.add("Location", answer.to)
                    exchange.sendResponseHeaders(302, -1)
                }

                is Answer.Stalled -> {
                    exchange.sendResponseHeaders(200, answer.bytes.size.toLong())
                    exchange.responseBody.write(answer.bytes, 0, answer.bytes.size / 2)
                    exchange.responseBody.flush()
                    endStalled.await(30, TimeUnit.SECONDS)
                }

                is Answer.Endless -> {
                    exchange.sendResponseHeaders(200, 0)

                    try {
                        while (endStalled.count > 0) exchange.responseBody.write(answer.chunk)
                    } catch (e: java.io.IOException) {
                        // The client hung up
                    }
                }
            }
        }
    }

    private fun path(name: String, version: String = this.version) = "/download/leaf-$version/$name"

    private fun serve(name: String, bytes: ByteArray, version: String = this.version) {
        answers[path(name, version)] = Answer.Bytes(bytes)
    }

    /** Signs the release's files as the release workflow does, and serves the manifest and its signature. */
    private fun signedByTheWorkflow(code: Int = 38, key: File = github, name: String = "github") {
        val out = File(dir, "signed-$code-$name")
        val run = run(
            listOf(signManifest.path, version, code.toString(), files.path, key.path, name, out.path),
            environment = mapOf("LEAF_TRUSTED_RELEASE_KEYS" to trustedFile.path),
        )
        check(run.first == 0) { run.second }

        serve(MANIFEST_FILE_NAME, File(out, MANIFEST_FILE_NAME).readBytes())
        serve(signatureFileName(name), File(out, signatureFileName(name)).readBytes())
    }

    /** Serves [manifest] as the release's, signed with [key] as [name]. */
    private fun signedByHand(manifest: String, key: File = github, name: String = "github") {
        val file = File(dir, "manifest-${System.nanoTime()}.json").apply { writeText(manifest) }
        run(listOf("ssh-keygen", "-q", "-Y", "sign", "-n", "leaf-release", "-f", key.path, file.path))

        serve(MANIFEST_FILE_NAME, file.readBytes())
        serve(signatureFileName(name), File(file.path + ".sig").readBytes())
    }

    private fun releaseUpdates(
        kind: ReleaseFileKind? = ReleaseFileKind.MACOS_ARM64,
        currentVersionCode: Int = 37,
        trusted: List<TrustedKey> = this.trusted,
        proxyFor: suspend (String) -> ProxyChoice = { ProxyChoice.Direct },
    ) = ReleaseUpdates(
        httpClient = httpClient,
        updatesFolder = { updates },
        kind = kind,
        currentVersionCode = currentVersionCode,
        trusted = trusted,
        required = 1,
        fileUrl = { version, name -> "$base/leaf-$version/$name" },
        proxyFor = proxyFor,
    )

    private fun latest(version: String = this.version, code: Int = 38) =
        Update(appVersion = version, appCode = code, downloadUrl = "https://github.com/zzhelev/Leaf/releases/tag/leaf-$version")

    private fun released(kind: ReleaseFileKind) = File(files, kind.fileName(version))

    private suspend fun verified(updates: ReleaseUpdates = releaseUpdates()): ReleaseVerification.Verified =
        assertInstanceOf(ReleaseVerification.Verified::class.java, updates.verify(latest()))

    // Checking the manifest

    @Test
    fun `a release that the workflow signed checks out, with the file for this Leaf`(): Unit = runBlocking {
        signedByTheWorkflow()

        val verified = verified()

        val file = released(ReleaseFileKind.MACOS_ARM64)
        assertEquals(ReleaseFileKind.MACOS_ARM64, verified.kind)
        assertEquals(file.name, verified.file.name)
        assertEquals(file.length(), verified.file.size)
        assertEquals(sha256(file), verified.file.sha256)
        assertEquals("$base/leaf-$version/${file.name}", verified.url)
        assertEquals(38, verified.manifest.appCode)
        assertEquals(listOf(path(MANIFEST_FILE_NAME), path(signatureFileName("github"))), requests.toList())
    }

    @Test
    fun `each kind gets its own file`(): Unit = runBlocking {
        signedByTheWorkflow()

        for (kind in ReleaseFileKind.entries) {
            val verified = assertInstanceOf(
                ReleaseVerification.Verified::class.java,
                releaseUpdates(kind = kind).verify(latest()),
            )
            assertEquals(released(kind).name, verified.file.name, kind.key)
            assertEquals(sha256(released(kind)), verified.file.sha256, kind.key)
        }
    }

    @Test
    fun `a release without a manifest is downloaded by hand`(): Unit = runBlocking {
        assertEquals(ReleaseVerification.ByHand(ByHandReason.NO_MANIFEST), releaseUpdates().verify(latest()))
        assertEquals(listOf(path(MANIFEST_FILE_NAME)), requests.toList())
    }

    @Test
    fun `a Leaf that no release file is for, or that trusts no key, asks for nothing`(): Unit = runBlocking {
        signedByTheWorkflow()

        assertEquals(
            ReleaseVerification.ByHand(ByHandReason.NOT_FOR_THIS_LEAF),
            releaseUpdates(kind = null).verify(latest()),
        )
        assertEquals(
            ReleaseVerification.ByHand(ByHandReason.NO_TRUSTED_KEYS),
            releaseUpdates(trusted = emptyList()).verify(latest()),
        )
        assertEquals(emptyList<String>(), requests.toList())
    }

    @Test
    fun `a manifest that a trusted key didn't sign is refused`(): Unit = runBlocking {
        signedByTheWorkflow()
        answers.remove(path(signatureFileName("github")))
        assertRefused(releaseUpdates().verify(latest()), "isn't signed by enough of the keys")

        // sign-manifest.sh refuses a key that Leaf doesn't trust, so this one is signed by hand
        signedByHand(manifestJson(), key = newKey("stranger"), name = "github")
        assertRefused(releaseUpdates().verify(latest()), "isn't signed by enough of the keys")
    }

    @Test
    fun `a manifest changed after it was signed is refused`(): Unit = runBlocking {
        signedByTheWorkflow()
        val manifest = (answers.getValue(path(MANIFEST_FILE_NAME)) as Answer.Bytes).bytes.toString(Charsets.UTF_8)
        val file = released(ReleaseFileKind.MACOS_ARM64)
        serve(MANIFEST_FILE_NAME, manifest.replace(sha256(file), "0".repeat(64)).toByteArray())

        assertRefused(releaseUpdates().verify(latest()), "isn't signed by enough of the keys")
    }

    @Test
    fun `a release that isn't newer than this Leaf is refused`(): Unit = runBlocking {
        signedByTheWorkflow(code = 37)

        assertRefused(releaseUpdates().verify(latest(code = 37)), "which isn't newer than this Leaf's, 37")
    }

    @Test
    fun `a manifest must have latest_json's version and version code`(): Unit = runBlocking {
        signedByTheWorkflow(code = 38)
        assertRefused(releaseUpdates().verify(latest(code = 39)), "where the latest release's is 39")

        signedByHand(manifestJson(appVersion = "1.11.1"))
        assertRefused(releaseUpdates().verify(latest()), "is for version 1.11.1")
    }

    @Test
    fun `a version that isn't three numbers asks for nothing`(): Unit = runBlocking {
        for (bad in listOf("1.11", "1.11.0/../x", "1.11.0-beta", "")) {
            assertRefused(releaseUpdates().verify(latest(version = bad)), "isn't one that Leaf can update to")
        }
        assertEquals(emptyList<String>(), requests.toList())
    }

    @Test
    fun `a manifest of a later format is downloaded by hand`(): Unit = runBlocking {
        signedByHand(manifestJson(format = 2))

        assertEquals(ReleaseVerification.ByHand(ByHandReason.NEWER_FORMAT), releaseUpdates().verify(latest()))
    }

    @Test
    fun `a manifest without a file for this Leaf is downloaded by hand`(): Unit = runBlocking {
        signedByHand(manifestJson(kinds = listOf(ReleaseFileKind.MACOS_ARM64)))

        assertEquals(
            ReleaseVerification.ByHand(ByHandReason.NO_FILE_FOR_THIS_LEAF),
            releaseUpdates(kind = ReleaseFileKind.LINUX_ARM64_DEB).verify(latest()),
        )
    }

    @Test
    fun `a file listed under another name, or with a hash or size that can't be, is refused`(): Unit = runBlocking {
        for (manifest in listOf(
            manifestJson(name = "../../evil.dmg"),
            manifestJson(name = "Leaf-1.11.0-linux-amd64.deb"),
            manifestJson(sha256 = "AB".repeat(32)),
            manifestJson(sha256 = "ab"),
            manifestJson(size = 0),
            manifestJson(size = MAX_RELEASE_FILE_SIZE + 1),
        )) {
            signedByHand(manifest)
            assertRefused(releaseUpdates().verify(latest()), "in a way that Leaf can't use")
        }
    }

    @Test
    fun `a manifest larger than a manifest can be is refused`(): Unit = runBlocking {
        serve(MANIFEST_FILE_NAME, ByteArray(70_000) { ' '.code.toByte() })

        assertRefused(releaseUpdates().verify(latest()), "larger than a manifest can be")
    }

    @Test
    fun `a server that fails or can't be reached is a failure, not a refusal`(): Unit = runBlocking {
        answers[path(MANIFEST_FILE_NAME)] = Answer.Status(500)
        val failed = assertInstanceOf(ReleaseVerification.Failed::class.java, releaseUpdates().verify(latest()))
        assertTrue(failed.reason.contains("500"), failed.reason)

        signedByTheWorkflow()
        answers[path(signatureFileName("github"))] = Answer.Status(503)
        assertInstanceOf(ReleaseVerification.Failed::class.java, releaseUpdates().verify(latest()))

        val port = server.address.port
        server.stop(0)
        val unreachable = ReleaseUpdates(
            httpClient = httpClient,
            updatesFolder = { updates },
            kind = ReleaseFileKind.MACOS_ARM64,
            currentVersionCode = 37,
            trusted = trusted,
            fileUrl = { version, name -> "http://127.0.0.1:$port/download/leaf-$version/$name" },
        )
        val notReached = assertInstanceOf(ReleaseVerification.Failed::class.java, unreachable.verify(latest()))
        assertTrue(notReached.reason.startsWith("Couldn't reach 127.0.0.1"), notReached.reason)
    }

    @Test
    fun `each request goes through the proxy for its own address`(): Unit = runBlocking {
        signedByTheWorkflow()
        val asked = CopyOnWriteArrayList<String>()
        val updates = releaseUpdates(proxyFor = { url -> asked += url; ProxyChoice.Direct })

        updates.download(verified(updates))

        val file = released(ReleaseFileKind.MACOS_ARM64).name
        assertEquals(
            listOf(MANIFEST_FILE_NAME, signatureFileName("github"), file).map { "$base/leaf-$version/$it" },
            asked.toList(),
        )
    }

    // Downloading

    @Test
    fun `the file downloads into the updates folder, checked, with its progress`(): Unit = runBlocking {
        signedByTheWorkflow()
        val updates = releaseUpdates()
        val progress = CopyOnWriteArrayList<Pair<Long, Long>>()

        val download = updates.download(verified(updates)) { done, total -> progress += done to total }

        val file = released(ReleaseFileKind.MACOS_ARM64)
        val ready = assertInstanceOf(UpdateDownload.Ready::class.java, download)
        assertEquals(File(this@ReleaseUpdatesTest.updates, file.name), ready.file)
        assertArrayEquals(file.readBytes(), ready.file.readBytes())
        assertEquals(listOf(file.name), this@ReleaseUpdatesTest.updates.list()!!.toList())
        assertEquals(file.length() to file.length(), progress.last())
        assertEquals(progress.map { it.first }.sorted(), progress.map { it.first })
        assertTrue(progress.all { it.second == file.length() })
    }

    @Test
    fun `a file that isn't the one listed is deleted`(): Unit = runBlocking {
        signedByTheWorkflow()
        val updates = releaseUpdates()
        val verified = verified(updates)
        val name = verified.file.name
        val original = released(ReleaseFileKind.MACOS_ARM64).readBytes()

        serve(name, original.copyOf().also { it[it.size / 2] = (it[it.size / 2] + 1).toByte() })
        assertRefusedDownload(updates.download(verified), "isn't the file that its manifest lists")

        serve(name, original + "more".toByteArray())
        assertRefusedDownload(updates.download(verified), "is larger than its manifest says")

        serve(name, original.copyOf(original.size - 10))
        val short = assertInstanceOf(UpdateDownload.Failed::class.java, updates.download(verified))
        assertTrue(short.reason.contains("stopped at ${original.size - 10} of ${original.size} bytes"), short.reason)
        assertEquals(emptyList<String>(), this@ReleaseUpdatesTest.updates.list()!!.toList())

        answers[path(name)] = Answer.Status(404)
        val missing = assertInstanceOf(UpdateDownload.Failed::class.java, updates.download(verified))
        assertTrue(missing.reason.contains("404"), missing.reason)
        assertEquals(emptyList<String>(), this@ReleaseUpdatesTest.updates.list()!!.toList())
    }

    @Test
    fun `a file that doesn't end is cut off one byte past its size, not read on`(): Unit = runBlocking {
        signedByTheWorkflow()
        val updates = releaseUpdates()
        val verified = verified(updates)
        answers[path(verified.file.name)] = Answer.Endless(ByteArray(64 * 1024) { 'x'.code.toByte() })

        val download = withTimeout(10.seconds) { updates.download(verified) }

        assertRefusedDownload(download, "is larger than its manifest says")
    }

    @Test
    fun `a file downloaded before isn't downloaded again, and what an earlier update left goes`(): Unit = runBlocking {
        signedByTheWorkflow()
        updates.mkdirs()
        File(updates, "Leaf-1.10.0-macos-arm64.dmg").writeText("an older update")
        File(updates, "Leaf-1.10.0-macos-arm64.dmg.part").writeText("half of an older update")
        val updates = releaseUpdates()
        val verified = verified(updates)

        assertInstanceOf(UpdateDownload.Ready::class.java, updates.download(verified))
        assertEquals(listOf(verified.file.name), this@ReleaseUpdatesTest.updates.list()!!.toList())

        requests.clear()
        val again = assertInstanceOf(UpdateDownload.Ready::class.java, updates.download(verified))
        assertEquals(verified.file.name, again.file.name)
        assertEquals(emptyList<String>(), requests.toList())
    }

    @Test
    fun `a file whose earlier download doesn't match is downloaded again`(): Unit = runBlocking {
        signedByTheWorkflow()
        val updates = releaseUpdates()
        val verified = verified(updates)
        this@ReleaseUpdatesTest.updates.mkdirs()
        File(this@ReleaseUpdatesTest.updates, verified.file.name).writeText("not the file")

        val ready = assertInstanceOf(UpdateDownload.Ready::class.java, updates.download(verified))

        assertArrayEquals(released(ReleaseFileKind.MACOS_ARM64).readBytes(), ready.file.readBytes())
    }

    @Test
    fun `a redirect to the file servers is followed`(): Unit = runBlocking {
        signedByTheWorkflow()
        val updates = releaseUpdates()
        val verified = verified(updates)
        val name = verified.file.name
        answers["/objects/$name"] = answers.getValue(path(name))
        answers[path(name)] = Answer.Redirect("http://127.0.0.1:${server.address.port}/objects/$name")

        val ready = assertInstanceOf(UpdateDownload.Ready::class.java, updates.download(verified))

        assertArrayEquals(released(ReleaseFileKind.MACOS_ARM64).readBytes(), ready.file.readBytes())
        assertTrue(requests.contains("/objects/$name"))
    }

    @Test
    fun `cancelling a download deletes what was written`(): Unit = runBlocking {
        signedByTheWorkflow()
        val updates = releaseUpdates()
        val verified = verified(updates)
        answers[path(verified.file.name)] = Answer.Stalled(released(ReleaseFileKind.MACOS_ARM64).readBytes())
        val written = AtomicLong()

        val download = async(Dispatchers.Default) { updates.download(verified) { done, _ -> written.set(done) } }
        withTimeout(10.seconds) { while (written.get() == 0L) delay(10) }
        assertTrue(File(this@ReleaseUpdatesTest.updates, verified.file.name + ReleaseUpdates.PART_SUFFIX).exists())

        download.cancelAndJoin()

        assertEquals(emptyList<String>(), this@ReleaseUpdatesTest.updates.list()!!.toList())
    }

    // Helpers

    private fun manifestJson(
        format: Int = 1,
        appVersion: String = version,
        appCode: Int = 38,
        kinds: List<ReleaseFileKind> = ReleaseFileKind.entries,
        name: String? = null,
        sha256: String? = null,
        size: Long? = null,
    ): String {
        val entries = kinds.joinToString(",\n") { kind ->
            val file = released(kind)
            val isMac = kind == ReleaseFileKind.MACOS_ARM64

            """
            |        "${kind.key}": {
            |            "name": "${if (isMac && name != null) name else file.name}",
            |            "size": ${if (isMac && size != null) size else file.length()},
            |            "sha256": "${if (isMac && sha256 != null) sha256 else sha256(file)}"
            |        }
            """.trimMargin()
        }

        return """
            |{
            |    "format": $format,
            |    "appVersion": "$appVersion",
            |    "appCode": $appCode,
            |    "releasePage": "https://github.com/zzhelev/Leaf/releases/tag/leaf-$appVersion",
            |    "files": {
            |$entries
            |    }
            |}
            |
        """.trimMargin()
    }

    private fun assertRefused(verification: ReleaseVerification, says: String) {
        val refused = assertInstanceOf(ReleaseVerification.Refused::class.java, verification)
        assertTrue(refused.reason.contains(says), refused.reason)
    }

    private fun assertRefusedDownload(download: UpdateDownload, says: String) {
        val refused = assertInstanceOf(UpdateDownload.Refused::class.java, download)
        assertTrue(refused.reason.contains(says), refused.reason)
        assertEquals(emptyList<String>(), updates.list()!!.toList(), "what was downloaded is deleted")
    }

    private fun newKey(name: String): File {
        val key = File(dir, "key-$name")
        run(listOf("ssh-keygen", "-q", "-t", "ed25519", "-N", "", "-C", "test-$name", "-f", key.path))

        return key
    }

    private fun publicKey(key: File): String = File(key.path + ".pub").readText().trim()

    private fun run(command: List<String>, environment: Map<String, String> = emptyMap()): Pair<Int, String> {
        val process = ProcessBuilder(command)
            .directory(dir)
            .redirectErrorStream(true)
            .redirectInput(ProcessBuilder.Redirect.from(File("/dev/null")))
            .also {
                it.environment().remove("SSH_AUTH_SOCK")
                it.environment().putAll(environment)
            }
            .start()
        val output = process.inputStream.readAllBytes().toString(Charsets.UTF_8)
        check(process.waitFor(60, TimeUnit.SECONDS)) { "${command.first()} didn't end" }
        check(command.first() != "ssh-keygen" || process.exitValue() == 0) { output }

        return process.exitValue() to output
    }

    private fun hasSshKeygen(): Boolean = try {
        ProcessBuilder("ssh-keygen", "-?").redirectErrorStream(true).start().run {
            inputStream.readAllBytes()
            waitFor(10, TimeUnit.SECONDS)
        }
        true
    } catch (_: Exception) {
        false
    }

    private fun sha256(file: File): String =
        MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }
}
