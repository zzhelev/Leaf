// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.data.network

import dev.app.leaf.data.git.TestGitCli
import dev.app.leaf.domain.network.ProxyChoice
import dev.app.leaf.domain.network.ProxyConfigEntry
import dev.app.leaf.domain.network.proxyFor
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.DisabledOnOs
import org.junit.jupiter.api.condition.OS
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.concurrent.TimeUnit

private const val A = "\$A"
private const val B = "\$B"
private const val HTTP = "http://lfs.invalid/team/repo.git/info/lfs"
private const val HTTPS = "https://lfs.invalid/team/repo.git/info/lfs"

private val PROXY_VARIABLES = listOf(
    "HTTP_PROXY", "http_proxy", "HTTPS_PROXY", "https_proxy", "ALL_PROXY", "all_proxy", "NO_PROXY", "no_proxy",
)

/**
 * Leaf's proxy for a request ([proxyFor]) against the one that git-lfs uses: git-lfs fetches an object from a server
 * whose name nothing resolves, with two recording proxies, A and B, named in the environment or in git's config, and
 * the proxy it went through, or neither, must be the one Leaf picks. Skipped without git-lfs.
 */
@DisabledOnOs(OS.WINDOWS)
class GitLfsProxyChoiceTest {
    @TempDir
    lateinit var tempDir: File

    private lateinit var proxyA: TestProxy
    private lateinit var proxyB: TestProxy
    private lateinit var repository: File

    private data class Case(
        val name: String,
        val url: String,
        val environment: Map<String, String> = emptyMap(),
        val config: List<Pair<String, String>> = emptyList(),
    )

    @BeforeEach
    fun setUp() {
        assumeTrue(gitLfsWorks(), "git-lfs isn't installed")

        proxyA = TestProxy()
        proxyB = TestProxy()
        repository = File(tempDir, "repository").apply { mkdirs() }
        val gitCli = TestGitCli(File(tempDir, "empty.gitconfig").apply { createNewFile() })

        // A pointer at HEAD, whose object the store lacks, so that git lfs fetch asks the server's batch API
        gitCli.run(repository, "init", "-q")
        File(repository, ".gitattributes").writeText("*.bin filter=lfs diff=lfs merge=lfs -text\n")
        File(repository, "a.bin").writeText(
            "version https://git-lfs.github.com/spec/v1\n" +
                "oid sha256:ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad\nsize 3\n"
        )
        gitCli.run(repository, "add", ".")
        gitCli.run(repository, "-c", "user.name=Test", "-c", "user.email=test@example.com", "commit", "-qm", "x")
    }

    @AfterEach
    fun tearDown() {
        if (::proxyA.isInitialized) {
            proxyA.close()
            proxyB.close()
        }
    }

    @Test
    fun `Leaf picks the proxy that git-lfs uses`() {
        val cases = listOf(
            Case("nothing", HTTP),
            Case("HTTPS_PROXY for http", HTTP, mapOf("HTTPS_PROXY" to A)),
            Case("HTTP_PROXY for https", HTTPS, mapOf("HTTP_PROXY" to A)),
            Case("HTTPS_PROXY first", HTTPS, mapOf("HTTPS_PROXY" to A, "HTTP_PROXY" to B)),
            Case("upper case first", HTTPS, mapOf("HTTPS_PROXY" to A, "https_proxy" to B)),
            Case("lower case", HTTP, mapOf("http_proxy" to B)),
            Case("ALL_PROXY", HTTP, mapOf("ALL_PROXY" to A, "all_proxy" to A)),
            Case("http.proxy first", HTTP, mapOf("HTTP_PROXY" to A), listOf("http.proxy" to B)),
            Case("empty http.proxy", HTTP, mapOf("HTTP_PROXY" to A), listOf("http.proxy" to "")),
            Case("http.proxy for https", HTTPS, mapOf("HTTPS_PROXY" to A), listOf("http.proxy" to B)),
            Case("URL first", HTTP, config = listOf("http.proxy" to A, "http.http://lfs.invalid.proxy" to B)),
            Case(
                "empty URL entry",
                HTTP,
                mapOf("HTTP_PROXY" to A),
                listOf("http.proxy" to B, "http.http://lfs.invalid.proxy" to ""),
            ),
            Case("wildcard", HTTP, config = listOf("http.proxy" to A, "http.http://*.invalid.proxy" to B)),
            Case(
                "exact over wildcard",
                HTTP,
                config = listOf("http.http://lfs.invalid.proxy" to A, "http.http://*.invalid.proxy" to B),
            ),
            Case("path", HTTP, config = listOf("http.proxy" to A, "http.http://lfs.invalid/team.proxy" to B)),
            Case("other path", HTTP, config = listOf("http.proxy" to A, "http.http://lfs.invalid/other.proxy" to B)),
            Case("repo for repo.git", HTTP, config = listOf("http.proxy" to A, "http.http://lfs.invalid/team/repo.proxy" to B)),
            Case("other scheme", HTTP, config = listOf("http.proxy" to A, "http.https://lfs.invalid.proxy" to B)),
            Case("other port", HTTP, config = listOf("http.proxy" to A, "http.http://lfs.invalid:8080.proxy" to B)),
            Case("user in key", HTTP, config = listOf("http.proxy" to A, "http.http://bob@lfs.invalid.proxy" to B)),
            Case("host case", HTTP, config = listOf("http.proxy" to A, "http.http://LFS.invalid.proxy" to B)),
            Case("NO_PROXY", HTTP, mapOf("HTTP_PROXY" to A, "NO_PROXY" to "lfs.invalid")),
            Case("NO_PROXY domain", HTTP, mapOf("HTTP_PROXY" to A, "NO_PROXY" to "invalid")),
            Case("NO_PROXY dot", HTTP, mapOf("HTTP_PROXY" to A, "NO_PROXY" to ".lfs.invalid")),
            Case("NO_PROXY star dot", HTTP, mapOf("HTTP_PROXY" to A, "NO_PROXY" to "*.invalid")),
            Case("NO_PROXY port", HTTP, mapOf("HTTP_PROXY" to A, "NO_PROXY" to "lfs.invalid:443")),
            Case("no_proxy", HTTP, mapOf("HTTP_PROXY" to A, "no_proxy" to "x, LFS.Invalid")),
            Case("NO_PROXY first", HTTP, mapOf("HTTP_PROXY" to A, "NO_PROXY" to "x", "no_proxy" to "lfs.invalid")),
            Case("NO_PROXY and http.proxy", HTTP, mapOf("NO_PROXY" to "lfs.invalid"), listOf("http.proxy" to B)),
            Case("loopback", "http://127.0.0.1:1/info/lfs", mapOf("HTTP_PROXY" to A)),
            Case("localhost", "http://localhost:1/info/lfs", mapOf("HTTP_PROXY" to A)),
        )

        val results = cases.map { case -> case.name to (gitLfsChoice(case) to leafChoice(case)) }
        val differing = results.filter { (_, choices) -> choices.first != choices.second }

        assertEquals(emptyList<Any>(), differing) { "git-lfs, then Leaf, in each case that differs" }
    }

    /** "A", "B" or "direct": the proxy that git-lfs's batch request went through. */
    private fun gitLfsChoice(case: Case): String {
        val before = proxyA.requests.size to proxyB.requests.size
        val variables = case.environment.mapValues { (_, value) -> named(value) }
        val config = case.config.flatMap { (key, value) -> listOf("-c", "$key=${named(value)}") }
        val command = listOf("git", "-c", "lfs.url=${case.url}", "-c", "lfs.transfer.maxretries=1") + config +
            listOf("lfs", "fetch")
        val process = ProcessBuilder(command)
            .directory(repository)
            .redirectErrorStream(true)
            .apply {
                environment().keys.removeAll(PROXY_VARIABLES)
                environment()["GIT_CONFIG_GLOBAL"] = File(tempDir, "empty.gitconfig").apply { createNewFile() }.path
                environment()["GIT_CONFIG_NOSYSTEM"] = "1"
                environment()["GIT_TERMINAL_PROMPT"] = "0"
                environment().putAll(variables)
            }
            .start()
        process.inputStream.readBytes()
        check(process.waitFor(60, TimeUnit.SECONDS)) { "git lfs fetch didn't end" }

        val throughA = proxyA.requests.size > before.first
        val throughB = proxyB.requests.size > before.second

        return when {
            throughA && throughB -> "both"
            throughA -> "A"
            throughB -> "B"
            else -> "direct"
        }
    }

    private fun leafChoice(case: Case): String {
        val entries = case.config.map { (key, value) ->
            val subsection = key.removePrefix("http.").removeSuffix("proxy").removeSuffix(".").ifEmpty { null }
            ProxyConfigEntry(subsection, named(value))
        }
        val choice = proxyFor("${case.url}/objects/batch", entries, case.environment.mapValues { named(it.value) })

        return when {
            choice == ProxyChoice.Direct -> "direct"
            choice is ProxyChoice.Http && choice.port == proxyA.port -> "A"
            choice is ProxyChoice.Http && choice.port == proxyB.port -> "B"
            else -> choice.toString()
        }
    }

    private fun named(value: String) = value.replace(A, proxyA.url).replace(B, proxyB.url)

    private fun gitLfsWorks(): Boolean = try {
        ProcessBuilder("git", "lfs", "version").redirectErrorStream(true).start().run {
            inputStream.readBytes()
            waitFor(30, TimeUnit.SECONDS) && exitValue() == 0
        }
    } catch (e: Exception) {
        false
    }
}
