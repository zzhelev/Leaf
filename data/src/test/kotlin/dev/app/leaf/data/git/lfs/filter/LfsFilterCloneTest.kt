// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.data.git.lfs.filter

import dev.app.leaf.data.git.IsolatedSystemReader
import dev.app.leaf.data.git.TestGitCli
import dev.app.leaf.data.git.cli.askpass.builtAskpassHelper
import dev.app.leaf.data.git.cli.remote.FakeLfsServer
import dev.app.leaf.data.git.cli.remote.GitCliCloneRepositoryGitAction
import dev.app.leaf.data.git.cli.remote.TestRemoteCommand
import dev.app.leaf.data.git.cli.remote.pathWithoutGitLfs
import dev.app.leaf.data.git.lfs.lfsPointerText
import dev.app.leaf.data.git.testJGit
import dev.app.leaf.domain.errors.CloneCheckoutError
import dev.app.leaf.domain.errors.Either
import dev.app.leaf.domain.errors.LfsFilterError
import dev.app.leaf.domain.models.CloneState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.eclipse.jgit.lfs.lib.LongObjectId
import org.eclipse.jgit.util.SystemReader
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.condition.DisabledOnOs
import org.junit.jupiter.api.condition.OS
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

private const val A1 = "a.bin, version 1\n"
private const val B1 = "b.bin, version 1\n"

/**
 * A clone's LFS files, which `git clone` checks out through Leaf's LFS filter where git-lfs isn't set up (fork-only,
 * stage 5 of `docs/fork/lfs-filter.md`). Until then git cloned with `--no-checkout`, and JGit checked the files out
 * with Leaf's built-in LFS, one download at a time. The source repository's `.lfsconfig` names [FakeLfsServer], which
 * has the objects, and git runs with a PATH without git-lfs. Skipped without the helper.
 */
@DisabledOnOs(OS.WINDOWS)
// git waits for the filter's answers, so a broken conversation would hang rather than fail
@Timeout(value = 60, unit = TimeUnit.SECONDS)
class LfsFilterCloneTest {
    @TempDir
    lateinit var tempDir: File

    private val originalReader: SystemReader = SystemReader.getInstance()
    private val jgit = testJGit()

    private lateinit var git: TestGitCli
    private lateinit var server: FakeLfsServer
    private lateinit var serverStorage: File
    private lateinit var source: File
    private lateinit var remote: TestRemoteCommand
    private lateinit var destination: File

    @BeforeEach
    fun setUp() {
        val helper = builtAskpassHelper()
        assumeTrue(helper != null) { "leaf-askpass isn't built, run ./gradlew :app:rustTasks" }

        SystemReader.setInstance(IsolatedSystemReader(File(tempDir, "jgit-config"), originalReader))
        val globalConfig = File(tempDir, "test.gitconfig").apply {
            writeText("[user]\n\tname = Leaf Test\n\temail = test@example.invalid\n")
        }
        git = TestGitCli(globalConfig)
        serverStorage = File(tempDir, "lfs-server")
        server = FakeLfsServer(serverStorage)
        val withoutGitLfs = pathWithoutGitLfs(File(tempDir, "bin").apply { mkdirs() })
        // The clone's JGit, as the app has one: the filter opens the clone's repository in it
        remote = TestRemoteCommand(helper!!, globalConfig, shellVariables = mapOf("PATH" to withoutGitLfs), jgit = jgit)

        source = File(tempDir, "source")
        git.run(tempDir, "init", "-q", "-b", "main", source.path)
        File(source, ".gitattributes").writeText("*.bin filter=lfs diff=lfs merge=lfs -text\n")
        File(source, ".lfsconfig").writeText("[lfs]\n\turl = ${server.url}\n")
        commit("Start", "README.md" to "LFS test\n", "a.bin" to A1, "b.bin" to B1)
        destination = File(tempDir, "clones/clone")
        destination.parentFile.mkdirs()
    }

    @AfterEach
    fun tearDown() {
        if (::server.isInitialized) {
            server.downloadGate?.countDown()
            server.close()
        }
        SystemReader.setInstance(originalReader)
    }

    @Test
    fun `a clone checks its LFS files out through Leaf's filter, in one batch`(): Unit = runBlocking {
        val states = clone()
        val onTheTab = remote.progress.toList()

        assertEquals(CloneState.Completed(destination), states.last()) { "$states" }
        assertEquals(A1, File(destination, "a.bin").readText())
        assertEquals(B1, File(destination, "b.bin").readText())
        // git staged nothing but the pointers, and the files match the index through Leaf's filter
        assertEquals(pointer(B1), git.run(destination, "cat-file", "blob", ":b.bin"))
        assertEquals("", statusThroughLeaf())
        assertTrue(File(destination, ".git/lfs/objects/${objectPath(A1)}").isFile)
        assertEquals(1, server.requests.count { it.startsWith("POST /objects/batch") }) { "${server.requests}" }
        // The clone's dialog shows the downloads, and the tab's processing screen gets nothing
        assertTrue(states.any { it is CloneState.Cloning && it.taskName.startsWith("Downloading LFS objects") }) {
            "$states"
        }
        assertEquals(emptyList<Any?>(), onTheTab)
    }

    @Test
    fun `a clone whose LFS files can't be downloaded fails, names them, and leaves no folder`(): Unit = runBlocking {
        File(serverStorage, oid(B1)).delete()

        val states = clone()

        val error = (states.last() as CloneState.Fail).reason
        assertInstanceOf(CloneCheckoutError::class.java, error) { "$error" }
        val filterError = (error as CloneCheckoutError).error
        assertInstanceOf(LfsFilterError::class.java, filterError) { "$filterError" }
        assertEquals(listOf("clone/b.bin"), (filterError as LfsFilterError).files.map { it.path })
        assertFalse(destination.exists())
        // Nor the repository that the filter opened, which a clone made again there would get
        assertFalse(jgit.hasRepositoriesIn(destination))
    }

    @Test
    fun `a chosen empty folder is left empty after a failed checkout, and a clone made again there works`(): Unit =
        runBlocking {
            destination.mkdirs()
            val missing = File(serverStorage, oid(B1))
            missing.delete()

            assertInstanceOf(CloneState.Fail::class.java, clone().last())
            assertEquals(emptyList<String>(), destination.list()!!.toList())

            missing.writeText(B1)
            val states = clone()

            assertEquals(CloneState.Completed(destination), states.last()) { "$states" }
            assertEquals(B1, File(destination, "b.bin").readText())
            assertEquals("", statusThroughLeaf())
        }

    @Test
    fun `cancelling a clone while an LFS file downloads leaves no folder`(): Unit = runBlocking {
        server.downloadGate = CountDownLatch(1)

        val clone = launch(Dispatchers.IO) { clone() }
        withTimeout(30_000) {
            while (server.downloadsWaiting.get() == 0) delay(20)
        }
        clone.cancel()
        withTimeout(10_000) { clone.join() }
        server.downloadGate?.countDown()

        assertFalse(destination.exists())
        assertFalse(jgit.hasRepositoriesIn(destination))
    }

    /** `git status`, with Leaf's filter: git outside Leaf has none here, and sees the files as changed (P5). */
    private suspend fun statusThroughLeaf(): String {
        val output = (remote.command.run(destination, listOf("status", "--porcelain")) as Either.Ok).value
        assertEquals(0, output.exitCode, output.stderr)

        return output.stdout
    }

    private suspend fun clone(): List<CloneState> =
        GitCliCloneRepositoryGitAction(jgit, remote.command)(destination, "file://${source.absolutePath}", false)
            .toList()

    /**
     * Commits [files] with git, which has no LFS filter here: an LFS file goes in as its pointer, and its content to
     * the server.
     */
    private fun commit(message: String, vararg files: Pair<String, String>) {
        for ((name, content) in files) {
            if (name.endsWith(".bin")) {
                File(source, name).writeText(pointer(content))
                File(serverStorage, oid(content)).writeText(content)
            } else {
                File(source, name).writeText(content)
            }
        }

        git.run(source, "add", "-A")
        git.run(source, "commit", "-q", "-m", message)
    }

    private fun pointer(content: String) =
        lfsPointerText(LongObjectId.fromString(oid(content)), content.length.toLong())

    private fun oid(content: String) =
        MessageDigest.getInstance("SHA-256").digest(content.toByteArray()).joinToString("") { "%02x".format(it) }

    private fun objectPath(content: String) = oid(content).let { "${it.substring(0, 2)}/${it.substring(2, 4)}/$it" }
}
