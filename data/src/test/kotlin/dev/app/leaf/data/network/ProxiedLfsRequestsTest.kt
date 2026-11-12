// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.data.network

import com.sun.net.httpserver.HttpsConfigurator
import com.sun.net.httpserver.HttpsServer
import dev.app.leaf.data.git.IsolatedSystemReader
import dev.app.leaf.data.git.cli.remote.FakeLfsServer
import dev.app.leaf.data.git.credentials.CredentialHelpers
import dev.app.leaf.data.git.lfs.DownloadLfsObjectGitAction
import dev.app.leaf.data.git.lfs.GetLfsObjectsGitAction
import dev.app.leaf.data.git.lfs.ProvideLfsCredentialsGitAction
import dev.app.leaf.data.git.lfs.UploadLfsObjectGitAction
import dev.app.leaf.data.git.lfs.lfsStore
import dev.app.leaf.data.git.testAppSettings
import dev.app.leaf.data.git.testGitCli
import dev.app.leaf.data.repositories.CredentialsCacheRepository
import dev.app.leaf.data.repositories.LfsNetworkDataSource
import dev.app.leaf.data.repositories.NetworkLfsRepository
import dev.app.leaf.data.shell.LoginShellEnvironment
import dev.app.leaf.domain.ShellManager
import dev.app.leaf.domain.credentials.CredentialsStateManager
import dev.app.leaf.domain.errors.Either
import dev.app.leaf.domain.errors.LfsError
import dev.app.leaf.domain.lfs.Actions
import dev.app.leaf.domain.lfs.LfsObject
import dev.app.leaf.domain.lfs.LfsObjectBatch
import dev.app.leaf.domain.lfs.LfsObjects
import dev.app.leaf.domain.lfs.LfsServer
import dev.app.leaf.domain.lfs.RemoteObjectAccessInfo
import dev.app.leaf.domain.models.OperationType
import dev.app.leaf.domain.network.ProxyChoice
import dev.app.leaf.domain.network.UnusableProxyReason
import io.ktor.http.*
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.lfs.lib.Constants
import org.eclipse.jgit.lfs.lib.LongObjectId
import org.eclipse.jgit.util.SystemReader
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.time.Duration.Companion.seconds

private const val CONTENT = "an LFS object's content"
private const val LFS_HOST = "lfs.invalid"

/**
 * The built-in LFS client's requests through a proxy, end to end, to servers on names that nothing resolves: a request
 * that reaches its server went through the proxy, which routes the name.
 */
class ProxiedLfsRequestsTest {
    @TempDir
    lateinit var tempDir: File

    private val originalReader: SystemReader = SystemReader.getInstance()
    private lateinit var git: Git
    private lateinit var lfsServer: FakeLfsServer
    private val closeables = mutableListOf<AutoCloseable>()

    private val oid = LongObjectId.fromRaw(Constants.newMessageDigest().digest(CONTENT.toByteArray()))
    private val serverUrl = "http://$LFS_HOST/lfs"

    @BeforeEach
    fun setUp() {
        SystemReader.setInstance(IsolatedSystemReader(File(tempDir, "config"), originalReader))
        git = Git.init().setDirectory(File(tempDir, "repository")).call()
        lfsServer = FakeLfsServer(File(tempDir, "storage"))
        lfsServer.actionsUrl = serverUrl
        File(tempDir, "storage/${oid.name()}").writeText(CONTENT)
    }

    @AfterEach
    fun tearDown() {
        closeables.reversed().forEach { it.close() }
        lfsServer.close()
        git.close()
        SystemReader.setInstance(originalReader)
    }

    private fun <T : AutoCloseable> T.closedAfter(): T = also { closeables += it }

    private fun lfsServerRoute() = mapOf("$LFS_HOST:80" to InetSocketAddress("127.0.0.1", lfsServer.port))

    @Test
    fun `an LFS download goes through HTTP_PROXY`(): Unit = runBlocking {
        val proxy = TestProxy(lfsServerRoute()).closedAfter()

        assertEquals(Either.Ok(Unit), download(mapOf("HTTP_PROXY" to proxy.url)))

        assertEquals(CONTENT, git.repository.lfsStore().mediaFile(oid).toFile().readText())
        assertEquals(
            listOf("POST $serverUrl/objects/batch", "GET $serverUrl/objects/${oid.name()}"),
            proxy.requests.map { it.toString() },
        )
        assertTrue(proxy.requests.all { it.proxyAuthorization == null })
    }

    @Test
    fun `the proxy's user name and password go to the proxy with each plain HTTP request`(): Unit = runBlocking {
        val proxy = TestProxy(lfsServerRoute(), credentials = "us@er:p:ss").closedAfter()
        val environment = mapOf("HTTP_PROXY" to "http://us%40er:p%3Ass@127.0.0.1:${proxy.port}")

        assertEquals(Either.Ok(Unit), download(environment))

        assertEquals(2, proxy.requests.size)
        assertTrue(proxy.requests.all { it.proxyAuthorization == TestProxy.basic("us@er:p:ss") })
    }

    @Test
    fun `HTTPS goes through a tunnel, and only its CONNECT has the proxy's password`(): Unit = runBlocking {
        val seen = CopyOnWriteArrayList<Pair<String, String?>>()
        val httpsServer = httpsBatchServer(seen)
        val proxy = TestProxy(
            mapOf("$LFS_HOST:443" to InetSocketAddress("127.0.0.1", httpsServer.address.port)),
            credentials = "user:pw",
        ).closedAfter()
        disableSslVerify("https://$LFS_HOST")

        val result = getObjects(
            mapOf("HTTPS_PROXY" to "http://user:pw@127.0.0.1:${proxy.port}"),
            "https://$LFS_HOST/lfs",
        )

        assertEquals(Either.Ok(LfsObjects(arrayListOf())), result)
        assertEquals(listOf("CONNECT $LFS_HOST:443"), proxy.requests.map { it.toString() })
        assertEquals(TestProxy.basic("user:pw"), proxy.requests.single().proxyAuthorization)
        // In origin form, without the proxy's password
        assertEquals(listOf("/lfs/objects/batch" to null), seen)
    }

    @Test
    fun `a proxy that refuses the password gets one try`(): Unit = runBlocking {
        val proxy = TestProxy(lfsServerRoute(), credentials = "user:right").closedAfter()
        val environment = mapOf("HTTP_PROXY" to "http://user:wrong@127.0.0.1:${proxy.port}")

        assertEquals(Either.Err(LfsError.HttpError(HttpStatusCode.ProxyAuthenticationRequired)), getObjects(environment))
        assertEquals(1, proxy.requests.size)
    }

    @Test
    fun `a tunnel that the proxy refuses fails after one try`(): Unit = runBlocking {
        val proxy = TestProxy(credentials = "user:right").closedAfter()
        val environment = mapOf("HTTPS_PROXY" to "http://user:wrong@127.0.0.1:${proxy.port}")

        // A proxy that keeps asking would keep a retry going: OkHttp gives up only after minutes
        assertThrows<IOException> { withTimeout(30.seconds) { getObjects(environment, "https://$LFS_HOST/lfs") } }
        assertEquals(listOf("CONNECT $LFS_HOST:443"), proxy.requests.map { it.toString() })
    }

    @Test
    fun `an upload through a proxy says its length`(): Unit = runBlocking {
        val proxy = TestProxy(lfsServerRoute()).closedAfter()
        val uploaded = "a new LFS object"
        val uploadedOid = LongObjectId.fromRaw(Constants.newMessageDigest().digest(uploaded.toByteArray()))
        val mediaFile = git.repository.lfsStore().mediaFile(uploadedOid)
        Files.createDirectories(mediaFile.parent)
        Files.writeString(mediaFile, uploaded)

        val lfsObject = LfsObject(
            oid = uploadedOid.name(),
            size = uploaded.length.toLong(),
            actions = Actions(upload = RemoteObjectAccessInfo("$serverUrl/objects/${uploadedOid.name()}")),
        )
        val action = UploadLfsObjectGitAction(lfsRepository(), credentials(), proxySettings(mapOf("HTTP_PROXY" to proxy.url)))

        assertEquals(Either.Ok(Unit), action(git.repository, LfsServer(serverUrl, null), lfsObject, uploadedOid))

        val put = proxy.requests.single()
        assertEquals("PUT $serverUrl/objects/${uploadedOid.name()}", put.toString())
        assertEquals(uploaded.length.toString(), put.headers["content-length"])
        assertNull(put.headers["transfer-encoding"])
        assertEquals(uploaded, File(tempDir, "storage/${uploadedOid.name()}").readText())
    }

    @Test
    fun `a SOCKS5 proxy, which looks the server's name up`(): Unit = runBlocking {
        val socks = TestSocksServer(lfsServerRoute()).closedAfter()

        assertEquals(Either.Ok(Unit), download(mapOf("HTTP_PROXY" to "socks5://127.0.0.1:${socks.port}")))

        assertTrue(socks.connections.isNotEmpty())
        assertTrue(socks.connections.all { it == "$LFS_HOST:80" }, socks.connections.toString())
    }

    @Test
    fun `a loopback server goes directly, whatever the proxy`(): Unit = runBlocking {
        val proxy = TestProxy().closedAfter()
        lfsServer.actionsUrl = null

        assertEquals(Either.Ok(Unit), download(mapOf("HTTP_PROXY" to proxy.url), lfsServer.url))
        assertEquals(emptyList<ProxiedRequest>(), proxy.requests.toList())
    }

    @Test
    fun `NO_PROXY sends the request directly`(): Unit = runBlocking {
        val proxy = TestProxy(lfsServerRoute()).closedAfter()

        // Nothing resolves the name, so going directly fails
        assertThrows<Exception> { getObjects(mapOf("HTTP_PROXY" to proxy.url, "NO_PROXY" to ".invalid")) }
        assertEquals(emptyList<ProxiedRequest>(), proxy.requests.toList())
    }

    @Test
    fun `git's proxy for the URL comes from the repository's config`(): Unit = runBlocking {
        val proxy = TestProxy(lfsServerRoute()).closedAfter()
        val unused = TestProxy().closedAfter()
        git.repository.config.apply {
            setString("http", "http://$LFS_HOST", "proxy", proxy.url)
            save()
        }

        assertEquals(Either.Ok(Unit), download(mapOf("HTTP_PROXY" to unused.url)))
        assertEquals(2, proxy.requests.size)
        assertEquals(emptyList<ProxiedRequest>(), unused.requests.toList())
    }

    @Test
    fun `each request takes the proxy for its own URL`(): Unit = runBlocking {
        val objectsHost = "objects.invalid"
        val batchProxy = TestProxy(lfsServerRoute()).closedAfter()
        val objectsProxy = TestProxy(mapOf("$objectsHost:80" to InetSocketAddress("127.0.0.1", lfsServer.port)))
            .closedAfter()
        lfsServer.actionsUrl = "http://$objectsHost/lfs"
        git.repository.config.apply {
            setString("http", "http://$objectsHost", "proxy", objectsProxy.url)
            save()
        }

        assertEquals(Either.Ok(Unit), download(mapOf("HTTP_PROXY" to batchProxy.url)))
        assertEquals(listOf("POST $serverUrl/objects/batch"), batchProxy.requests.map { it.toString() })
        assertEquals(
            listOf("GET http://$objectsHost/lfs/objects/${oid.name()}"),
            objectsProxy.requests.map { it.toString() },
        )
    }

    @Test
    fun `a proxy that Leaf can't use stops the request`(): Unit = runBlocking {
        val proxy = TestProxy(lfsServerRoute()).closedAfter()

        for ((value, reason) in listOf(
            "https://127.0.0.1:${proxy.port}" to UnusableProxyReason.HTTPS_PROXY,
            "socks5://user:pw@127.0.0.1:${proxy.port}" to UnusableProxyReason.SOCKS_CREDENTIALS,
        )) {
            val result = getObjects(mapOf("HTTP_PROXY" to value))
            val scheme = value.substringBefore("://")

            assertEquals(
                Either.Err(
                    LfsError.UnusableProxy(ProxyChoice.Unusable("$scheme://127.0.0.1:${proxy.port}", "HTTP_PROXY", reason))
                ),
                result,
            )
        }

        assertEquals(emptyList<ProxiedRequest>(), proxy.requests.toList())
    }

    @Test
    fun `no password reaches an error or a log line`(): Unit = runBlocking {
        val proxy = TestProxy(credentials = "user:right").closedAfter()
        val environment = mapOf("HTTPS_PROXY" to "http://user:s3cret-pw@127.0.0.1:${proxy.port}")
        val settings = proxySettings(environment).forRequest(git.repository.config, "https://$LFS_HOST/lfs")
        val error = runCatching { getObjects(environment, "https://$LFS_HOST/lfs") }.exceptionOrNull()

        assertFalse("s3cret-pw" in settings.toString(), settings.toString())
        val messages = generateSequence(error) { it.cause }.map { it.toString() }.toList()
        assertTrue(messages.isNotEmpty())
        assertTrue(messages.none { "s3cret-pw" in it }, messages.toString())
    }

    private suspend fun getObjects(
        environment: Map<String, String>,
        url: String = serverUrl,
    ): Either<LfsObjects, LfsError> = GetLfsObjectsGitAction(lfsRepository(), credentials(), proxySettings(environment))(
        git.repository,
        LfsServer(url, null),
        OperationType.DOWNLOAD,
        "main",
        listOf(LfsObjectBatch(oid.name(), CONTENT.length.toLong())),
        emptyMap(),
    )

    /** The batch API, then the object's download, as the LFS filter does them. */
    private suspend fun download(environment: Map<String, String>, url: String = serverUrl): Either<Unit, LfsError> {
        val objects = when (val result = getObjects(environment, url)) {
            is Either.Ok -> result.value.objects
            is Either.Err -> return result
        }
        val action = DownloadLfsObjectGitAction(lfsRepository(), credentials(), proxySettings(environment))

        return action(git.repository, LfsServer(url, null), objects.single(), oid)
    }

    private fun disableSslVerify(url: String) {
        git.repository.config.apply {
            setBoolean("http", url, "sslVerify", false)
            save()
        }
    }

    /** An HTTPS server whose batch API answers no objects, noting each request's path and `Proxy-Authorization`. */
    private fun httpsBatchServer(seen: MutableList<Pair<String, String?>>): HttpsServer {
        val server = HttpsServer.create(InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0)
        server.httpsConfigurator = HttpsConfigurator(selfSignedSslContext(File(tempDir, "tls").apply { mkdirs() }))
        server.createContext("/") { exchange ->
            seen += exchange.requestURI.toString() to exchange.requestHeaders.getFirst("Proxy-Authorization")
            val body = """{"objects":[]}""".toByteArray()
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.start()
        closeables += AutoCloseable { server.stop(0) }

        return server
    }

    private fun proxySettings(environment: Map<String, String>) =
        ProxySettings(environment = { environment }, userConfigEntries = { emptyList() })

    private fun lfsRepository() = NetworkLfsRepository(LfsNetworkDataSource(createHttpClient()))

    private fun credentials() = ProvideLfsCredentialsGitAction(
        credentialsCacheRepository = CredentialsCacheRepository(),
        credentialsStateManager = CredentialsStateManager(),
        credentialHelpers = CredentialHelpers(
            shellManager = ShellManager(),
            gitCredentialsManagerProvider = mockk(relaxed = true),
            loginShellEnvironment = LoginShellEnvironment { emptyMap() },
            gitCli = testGitCli(),
        ),
        appSettingsService = testAppSettings(),
    )
}
