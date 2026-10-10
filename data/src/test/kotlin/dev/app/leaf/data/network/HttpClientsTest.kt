// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.data.network

import com.sun.net.httpserver.HttpsConfigurator
import com.sun.net.httpserver.HttpsServer
import dev.app.leaf.data.git.IsolatedSystemReader
import dev.app.leaf.data.git.credentials.CredentialHelpers
import dev.app.leaf.data.git.lfs.DownloadLfsObjectGitAction
import dev.app.leaf.data.git.lfs.GetLfsObjectsGitAction
import dev.app.leaf.data.git.lfs.ProvideLfsCredentialsGitAction
import dev.app.leaf.data.git.testAppSettings
import dev.app.leaf.data.git.testGitCli
import dev.app.leaf.data.repositories.CredentialsCacheRepository
import dev.app.leaf.data.repositories.LfsNetworkDataSource
import dev.app.leaf.data.repositories.NetworkLfsRepository
import dev.app.leaf.data.shell.LoginShellEnvironment
import dev.app.leaf.domain.ShellManager
import dev.app.leaf.domain.credentials.CredentialsStateManager
import dev.app.leaf.domain.errors.Either
import dev.app.leaf.domain.lfs.Actions
import dev.app.leaf.domain.lfs.LfsObject
import dev.app.leaf.domain.lfs.LfsObjectBatch
import dev.app.leaf.domain.lfs.LfsObjects
import dev.app.leaf.domain.lfs.LfsServer
import dev.app.leaf.domain.lfs.RemoteObjectAccessInfo
import dev.app.leaf.domain.models.OperationType
import dev.app.leaf.domain.network.NetworkConstants
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.lfs.Lfs
import org.eclipse.jgit.lfs.lib.Constants
import org.eclipse.jgit.lfs.lib.LongObjectId
import org.eclipse.jgit.lib.Config
import org.eclipse.jgit.util.SystemReader
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.file.Files
import java.security.KeyStore
import java.security.GeneralSecurityException
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext

private const val KEY_STORE_PASSWORD = "leaf-test"
private const val BATCH_RESPONSE = """{"objects":[]}"""

/** TLS for Leaf's own HTTP requests, against a local HTTPS server whose certificate no trust store holds. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class HttpClientsTest {
    @TempDir
    lateinit var tempDir: File

    private val serverDir: File = Files.createTempDirectory("leaf-https").toFile()
    private lateinit var server: HttpsServer
    private lateinit var serverUrl: String

    private val originalReader: SystemReader = SystemReader.getInstance()
    private lateinit var git: Git

    @BeforeAll
    fun startServer() {
        server = HttpsServer.create(InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0)
        server.httpsConfigurator = HttpsConfigurator(selfSignedSslContext())
        server.createContext("/") { exchange ->
            val body = BATCH_RESPONSE.toByteArray()
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.start()
        serverUrl = "https://127.0.0.1:${server.address.port}"
    }

    @AfterAll
    fun stopServer() {
        server.stop(0)
        serverDir.deleteRecursively()
    }

    @BeforeEach
    fun createRepository() {
        // The whole class shares the temp folder, so each test makes its own repository in it
        val testDir = Files.createTempDirectory(tempDir.toPath(), "test").toFile()
        SystemReader.setInstance(IsolatedSystemReader(File(testDir, "config"), originalReader))
        git = Git.init().setDirectory(File(testDir, "repository")).call()
    }

    @AfterEach
    fun restoreSystemReader() {
        git.close()
        SystemReader.setInstance(originalReader)
    }

    @Test
    fun `the default client refuses a certificate it can't verify`(): Unit = runBlocking {
        createHttpClient().use { client ->
            assertRefusesCertificate { client.get(serverUrl) }
        }
    }

    @Test
    fun `the client without TLS verification accepts it`(): Unit = runBlocking {
        createHttpClientWithoutTlsVerification().use { client ->
            assertEquals(HttpStatusCode.OK, client.get(serverUrl).status)
        }
    }

    @Test
    fun `LFS requests check the certificate unless sslVerify is false`(): Unit = runBlocking {
        val lfs = NetworkLfsRepository(LfsNetworkDataSource(createHttpClient()))

        suspend fun postBatch(sslVerify: Boolean) =
            lfs.getLfsObjects(serverUrl, OperationType.DOWNLOAD, "main", emptyList(), null, null, emptyMap(), sslVerify)

        assertRefusesCertificate { postBatch(sslVerify = true) }
        assertEquals(Either.Ok(LfsObjects(arrayListOf())), postBatch(sslVerify = false))
    }

    @Test
    fun `sslVerify is true by default`() {
        assertTrue(Config().isSslVerify("https://example.com/team/project.git/info/lfs"))
    }

    @Test
    fun `http sslVerify applies to every URL`() {
        val config = config("[http]\n\tsslVerify = false\n")

        assertFalse(config.isSslVerify("https://example.com/team/project.git/info/lfs"))
    }

    @Test
    fun `http url sslVerify applies to that URL only, and beats http sslVerify`() {
        val config = config(
            """
            [http]
            	sslVerify = false
            [http "https://lfs.example.com"]
            	sslVerify = true
            [http "https://self-signed.example.com/team"]
            	sslVerify = false
            """.trimIndent()
        )

        assertTrue(config.isSslVerify("https://lfs.example.com/team/project.git/info/lfs"))
        assertFalse(config.isSslVerify("https://self-signed.example.com/team/project.git/info/lfs/objects/abc"))
        assertFalse(config.isSslVerify("https://other.example.com/x"))
    }

    @Test
    fun `an LFS action reads sslVerify for its URL from the repository's config`(): Unit = runBlocking {
        val action = GetLfsObjectsGitAction(lfsRepository(), provideLfsCredentials())

        suspend fun getObjects() = action(
            git.repository, lfsServer, OperationType.DOWNLOAD, "main", listOf(LfsObjectBatch("0".repeat(64), 1)), headers,
        )

        assertRefusesCertificate { getObjects() }

        git.repository.config.apply {
            setBoolean("http", "https://other.example.com", "sslVerify", false)
            save()
        }
        assertRefusesCertificate { getObjects() }

        git.repository.config.apply {
            setBoolean("http", serverUrl, "sslVerify", false)
            save()
        }
        assertEquals(Either.Ok(LfsObjects(arrayListOf())), getObjects())
    }

    @Test
    fun `an LFS download reads sslVerify for the download URL, not the server's`(): Unit = runBlocking {
        val action = DownloadLfsObjectGitAction(lfsRepository(), provideLfsCredentials())
        // Downloads are checked against it
        val oid = LongObjectId.fromRaw(Constants.newMessageDigest().digest(BATCH_RESPONSE.toByteArray()))
        val lfsObject = LfsObject(
            oid = oid.name(),
            size = BATCH_RESPONSE.length.toLong(),
            actions = Actions(download = RemoteObjectAccessInfo("$serverUrl/objects/${oid.name()}", headers)),
        )

        suspend fun download() = action(git.repository, lfsServer, lfsObject, oid)

        git.repository.config.apply {
            setBoolean("http", lfsServer.url, "sslVerify", false)
            save()
        }
        assertRefusesCertificate { download() }

        git.repository.config.apply {
            setBoolean("http", "$serverUrl/objects", "sslVerify", false)
            save()
        }
        assertEquals(Either.Ok(Unit), download())

        assertEquals(BATCH_RESPONSE, Lfs(git.repository).getMediaFile(oid).toFile().readText())
    }

    private val lfsServer by lazy { LfsServer("$serverUrl/team/project.git/info/lfs", null) }

    // With a token from the server, the actions need no credentials
    private val headers = mapOf(NetworkConstants.AUTH_HEADER to "Bearer token")

    private fun lfsRepository() = NetworkLfsRepository(LfsNetworkDataSource(createHttpClient()))

    private fun provideLfsCredentials() = ProvideLfsCredentialsGitAction(
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

    private fun config(text: String) = Config().apply { fromText(text) }

    private suspend fun assertRefusesCertificate(request: suspend () -> Any?) {
        val exception = try {
            request()
            null
        } catch (e: Exception) {
            e
        }
        assertNotNull(exception) { "The request succeeded" }
        val causes = generateSequence<Throwable>(exception) { it.cause }.toList()

        assertTrue(causes.any { it is GeneralSecurityException }) {
            "Expected a certificate error, got: ${causes.joinToString(" <- ") { "${it.javaClass.name}: ${it.message}" }}"
        }
    }

    /** A key pair with a self-signed certificate for 127.0.0.1, made with the JDK's keytool. */
    private fun selfSignedSslContext(): SSLContext {
        val keyStoreFile = File(serverDir, "server.p12")
        val keytool = File(System.getProperty("java.home"), "bin/keytool").path
        val process = ProcessBuilder(
            keytool, "-genkeypair", "-alias", "server", "-keyalg", "RSA", "-keysize", "2048",
            "-dname", "CN=127.0.0.1", "-ext", "SAN=ip:127.0.0.1", "-validity", "2",
            "-storetype", "PKCS12", "-keystore", keyStoreFile.path,
            "-storepass", KEY_STORE_PASSWORD, "-keypass", KEY_STORE_PASSWORD,
        ).redirectErrorStream(true).start()
        val output = process.inputStream.readBytes().decodeToString()
        check(process.waitFor() == 0) { "keytool failed: $output" }

        val keyStore = KeyStore.getInstance("PKCS12")
        keyStoreFile.inputStream().use { keyStore.load(it, KEY_STORE_PASSWORD.toCharArray()) }

        val keyManagers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
            .apply { init(keyStore, KEY_STORE_PASSWORD.toCharArray()) }
            .keyManagers

        return SSLContext.getInstance("TLS").apply { init(keyManagers, null, null) }
    }
}
