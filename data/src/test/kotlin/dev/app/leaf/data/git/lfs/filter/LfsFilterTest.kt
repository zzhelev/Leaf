// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.data.git.lfs.filter

import dev.app.leaf.data.git.IsolatedSystemReader
import dev.app.leaf.data.git.TestGitCli
import dev.app.leaf.data.git.cli.askpass.answeringDialogs
import dev.app.leaf.data.git.cli.askpass.builtAskpassHelper
import dev.app.leaf.data.git.cli.operations.testGitCliOperations
import dev.app.leaf.data.git.cli.remote.FakeLfsServer
import dev.app.leaf.data.git.cli.remote.GIT_LFS_FILTERS
import dev.app.leaf.data.git.cli.remote.TestRemoteCommand
import dev.app.leaf.data.git.cli.remote.pathWithoutGitLfs
import dev.app.leaf.data.git.lfs.lfsPointerText
import dev.app.leaf.data.git.testJGit
import dev.app.leaf.domain.errors.Either
import dev.app.leaf.domain.errors.LfsError
import dev.app.leaf.domain.errors.LfsFileFailure
import dev.app.leaf.domain.errors.LfsFilterError
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
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
private const val A2 = "a.bin, version 2\n"
private const val A3 = "a.bin, version 3\n"
private const val B1 = "b.bin, version 1\n"
private const val C1 = "c.bin, version 1\n"

/**
 * Leaf's built-in LFS client as git's LFS filter, through the built askpass helper: the git commands that Leaf runs
 * check LFS files out and stage them as with git-lfs, where git-lfs isn't set up. The LFS objects are on
 * [FakeLfsServer], and git runs with a PATH without git-lfs unless a test says otherwise. Skipped without the helper.
 */
@DisabledOnOs(OS.WINDOWS)
// git waits for the filter's answers, so a broken conversation would hang rather than fail
@Timeout(value = 60, unit = TimeUnit.SECONDS)
class LfsFilterTest {
    @TempDir
    lateinit var tempDir: File

    private val originalReader: SystemReader = SystemReader.getInstance()
    private val jgit = testJGit()

    private lateinit var helper: File
    private lateinit var globalConfig: File
    private lateinit var git: TestGitCli
    private lateinit var server: FakeLfsServer
    private lateinit var serverStorage: File
    private lateinit var withoutGitLfs: String
    private lateinit var repo: File

    @BeforeEach
    fun setUp() {
        val built = builtAskpassHelper()
        assumeTrue(built != null) { "leaf-askpass isn't built, run ./gradlew :app:rustTasks" }
        helper = built!!

        SystemReader.setInstance(IsolatedSystemReader(File(tempDir, "jgit-config"), originalReader))
        globalConfig = File(tempDir, "test.gitconfig").apply {
            writeText("[user]\n\tname = Leaf Test\n\temail = test@example.invalid\n")
        }
        git = TestGitCli(globalConfig)
        serverStorage = File(tempDir, "lfs-server")
        server = FakeLfsServer(serverStorage)
        withoutGitLfs = pathWithoutGitLfs(File(tempDir, "bin").apply { mkdirs() })

        repo = File(tempDir, "repo")
        git.run(tempDir, "init", "-q", "-b", "main", repo.path)
        File(repo, ".gitattributes").writeText("*.bin filter=lfs diff=lfs merge=lfs -text\n")
        git.run(repo, "config", "lfs.url", server.url)
        commit("Start", "README.md" to "LFS test\n", "a.bin" to A1)
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
    fun `a merge writes LFS files through Leaf's filter, downloading them in one batch`(): Unit = runBlocking {
        branchWithChanges()
        val remote = remote()
        checkOutThroughLeaf(remote)
        server.requests.clear()

        val result = testGitCliOperations(jgit, remote.gitCli).run(gitDir(), listOf("merge", "--no-ff", "--no-edit", "feature"))

        assertEquals(Either.Ok(false), result)
        assertEquals(A2, File(repo, "a.bin").readText())
        assertEquals(B1, File(repo, "b.bin").readText())
        assertEquals(pointer(A2), git.run(repo, "cat-file", "-p", ":a.bin"))
        assertEquals("", leafGit(remote, "status", "--porcelain"))
        assertEquals(1, server.requests.count { it.startsWith("POST /objects/batch") }, "${server.requests}")
        assertTrue(File(repo, ".git/lfs/objects/${objectPath(B1)}").isFile)
    }

    @Test
    fun `a download that fails stops git, and the error names the file and why`(): Unit = runBlocking {
        branchWithChanges()
        val remote = remote()
        checkOutThroughLeaf(remote)
        File(serverStorage, oid(B1)).delete()

        val result = testGitCliOperations(jgit, remote.gitCli).run(gitDir(), listOf("merge", "--no-ff", "--no-edit", "feature"))

        val error = assertInstanceOf(LfsFilterError::class.java, (result as Either.Err).error)
        assertEquals(listOf(LfsFileFailure("b.bin", LfsError.ObjectRefused(404, "Object does not exist"))), error.files)
        assertEquals(git.run(repo, "rev-parse", "main"), git.run(repo, "rev-parse", "HEAD"))
    }

    @Test
    fun `with git-lfs set up, git-lfs does the work and Leaf adds no filter`(): Unit = runBlocking {
        val realGitLfs = realGitLfs()
        assumeTrue(realGitLfs != null) { "git-lfs isn't installed" }
        globalConfig.appendText(GIT_LFS_FILTERS)
        branchWithChanges()
        val remote = remote(path = "${File(realGitLfs!!).parent}:/usr/bin:/bin")
        server.requests.clear()

        val result = testGitCliOperations(jgit, remote.gitCli).run(gitDir(), listOf("merge", "--no-ff", "--no-edit", "feature"))

        assertEquals(Either.Ok(false), result)
        assertEquals(B1, File(repo, "b.bin").readText())
        assertTrue(server.requests.isNotEmpty())
        assertTrue(server.requests.all { "git-lfs/" in it }, "${server.requests}")
    }

    @Test
    fun `a fast-forward, a cherry-pick and a revert write LFS files through Leaf's filter`(): Unit = runBlocking {
        branchWithChanges()
        val remote = remote()
        checkOutThroughLeaf(remote)
        val operations = testGitCliOperations(jgit, remote.gitCli)

        assertEquals(Either.Ok(false), operations.run(gitDir(), listOf("cherry-pick", "feature")))
        assertEquals(A2, File(repo, "a.bin").readText())
        assertEquals(B1, File(repo, "b.bin").readText())

        assertEquals(Either.Ok(false), operations.run(gitDir(), listOf("revert", "--no-edit", "HEAD")))
        assertEquals(A1, File(repo, "a.bin").readText())
        assertFalse(File(repo, "b.bin").exists())

        // Back to the commit that feature starts from
        git.run(repo, "switch", "-q", "-c", "behind", "main~3")
        checkOutThroughLeaf(remote)
        assertEquals(Either.Ok(false), operations.run(gitDir(), listOf("merge", "--ff-only", "feature")))
        assertEquals(A2, File(repo, "a.bin").readText())
        assertEquals("", leafGit(remote, "status", "--porcelain"))
    }

    @Test
    fun `a rebase writes LFS files through Leaf's filter, and stops at conflicts in a pointer as with git-lfs`(): Unit =
        runBlocking {
            branchWithChanges()
            commit("Main changes a.bin", "a.bin" to A3)
            val remote = remote()
            checkOutThroughLeaf(remote, a = A3)
            val operations = testGitCliOperations(jgit, remote.gitCli)

            assertEquals(Either.Ok(true), operations.run(gitDir(), listOf("rebase", "feature")))
            // git merges the two pointers' text, which isn't a pointer, so it's written as it is
            val conflicted = File(repo, "a.bin").readText()
            assertTrue(conflicted.startsWith("version https://git-lfs.github.com/spec/v1\n<<<<<<< "), conflicted)
            assertEquals(B1, File(repo, "b.bin").readText())

            File(repo, "a.bin").writeText(A3)
            leafGit(remote, "add", "a.bin")
            assertEquals(Either.Ok(false), operations.run(gitDir(), listOf("rebase", "--continue")))

            assertEquals(A3, File(repo, "a.bin").readText())
            assertEquals(pointer(A3), git.run(repo, "cat-file", "-p", "HEAD:a.bin"))
            assertEquals("", leafGit(remote, "status", "--porcelain"))
        }

    @Test
    fun `a pull, merging or rebasing, writes the LFS files that it brings through Leaf's filter`(): Unit = runBlocking {
        val origin = File(tempDir, "origin.git")
        git.run(tempDir, "init", "-q", "--bare", origin.path)
        git.run(repo, "remote", "add", "origin", origin.path)
        git.run(repo, "push", "-q", "origin", "main")
        val other = File(tempDir, "other")
        git.run(tempDir, "clone", "-q", origin.path, other.path)

        File(other, "b.bin").writeText(pointer(B1))
        File(serverStorage, oid(B1)).writeText(B1)
        git.run(other, "add", "-A")
        git.run(other, "commit", "-q", "-m", "Add b.bin")
        git.run(other, "push", "-q", "origin", "main")

        val remote = remote()
        checkOutThroughLeaf(remote)
        val operations = testGitCliOperations(jgit, remote.gitCli)

        assertEquals(Either.Ok(false), operations.run(gitDir(), listOf("pull", "--no-rebase", "origin", "main")))
        assertEquals(B1, File(repo, "b.bin").readText())

        File(other, "c.bin").writeText(pointer(C1))
        File(serverStorage, oid(C1)).writeText(C1)
        git.run(other, "add", "-A")
        git.run(other, "commit", "-q", "-m", "Add c.bin")
        git.run(other, "push", "-q", "origin", "main")
        File(repo, "README.md").writeText("LFS test, locally\n")
        leafGit(remote, "commit", "-q", "-a", "-m", "Local change")

        assertEquals(Either.Ok(false), operations.run(gitDir(), listOf("pull", "--rebase", "origin", "main")))
        assertEquals(C1, File(repo, "c.bin").readText())
        assertEquals(A1, File(repo, "a.bin").readText())
        assertEquals("", leafGit(remote, "status", "--porcelain"))
    }

    @Test
    fun `a submodule's LFS files come through Leaf's filter, into the submodule's own store`(): Unit = runBlocking {
        globalConfig.appendText("[protocol \"file\"]\n\tallow = always\n")
        // The submodule finds its server in its .lfsconfig, as the repository's own config isn't cloned
        File(repo, ".lfsconfig").writeText("[lfs]\n\turl = ${server.url}\n")
        commit("Add .lfsconfig")
        val superproject = File(tempDir, "super")
        git.run(tempDir, "init", "-q", "-b", "main", superproject.path)
        git.run(superproject, "submodule", "add", "-q", repo.path, "sub")
        git.run(superproject, "commit", "-q", "-m", "Add sub")
        val clone = File(tempDir, "super-clone")
        git.run(tempDir, "clone", "-q", superproject.path, clone.path)

        val result = remote().command.run(clone, listOf("submodule", "update", "--init", "--recursive"))

        assertEquals(0, (result as Either.Ok).value.exitCode, result.value.stderr)
        assertEquals(A1, File(clone, "sub/a.bin").readText())
        assertTrue(File(clone, ".git/modules/sub/lfs/objects/${objectPath(A1)}").isFile)
    }

    @Test
    fun `with git-lfs's filter in the config but no git-lfs to run, Leaf's filter does the work`(): Unit =
        runBlocking {
            globalConfig.appendText(GIT_LFS_FILTERS)
            branchWithChanges()
            val remote = remote()

            assertEquals(
                Either.Ok(false),
                testGitCliOperations(jgit, remote.gitCli).run(gitDir(), listOf("merge", "--no-edit", "feature")),
            )
            assertEquals(B1, File(repo, "b.bin").readText())
        }

    @Test
    fun `with git-lfs installed but not set up, Leaf's filter does the work`(): Unit = runBlocking {
        val realGitLfs = realGitLfs()
        assumeTrue(realGitLfs != null) { "git-lfs isn't installed" }
        branchWithChanges()
        val remote = remote(path = "${File(realGitLfs!!).parent}:/usr/bin:/bin")
        server.requests.clear()

        assertEquals(
            Either.Ok(false),
            testGitCliOperations(jgit, remote.gitCli).run(gitDir(), listOf("merge", "--no-edit", "feature")),
        )
        assertEquals(B1, File(repo, "b.bin").readText())
        assertTrue(server.requests.none { "git-lfs/" in it }, "${server.requests}")
    }

    @Test
    fun `with lfs skipdownloaderrors, a file whose object can't be downloaded is written as its pointer`(): Unit =
        runBlocking {
            branchWithChanges()
            File(serverStorage, oid(B1)).delete()
            git.run(repo, "config", "lfs.skipdownloaderrors", "true")
            val remote = remote()

            assertEquals(
                Either.Ok(false),
                testGitCliOperations(jgit, remote.gitCli).run(gitDir(), listOf("merge", "--no-edit", "feature")),
            )
            assertEquals(A2, File(repo, "a.bin").readText())
            assertEquals(pointer(B1), File(repo, "b.bin").readText())
        }

    @Test
    fun `with GIT_LFS_SKIP_SMUDGE, files are written as their pointers`(): Unit = runBlocking {
        branchWithChanges()
        val remote = TestRemoteCommand(
            helper,
            globalConfig,
            shellVariables = mapOf("PATH" to withoutGitLfs, "GIT_LFS_SKIP_SMUDGE" to "1"),
            jgit = jgit,
        )
        server.requests.clear()

        leafGit(remote, "merge", "--no-edit", "feature")

        assertEquals(pointer(B1), File(repo, "b.bin").readText())
        assertEquals(emptyList<String>(), server.requests)
    }

    @Test
    fun `staging stores the file in the LFS store and its pointer in git`(): Unit = runBlocking {
        val remote = remote()
        checkOutThroughLeaf(remote)
        File(repo, "a.bin").writeText(A2)

        leafGit(remote, "add", "a.bin")

        assertEquals(pointer(A2), git.run(repo, "cat-file", "-p", ":a.bin"))
        assertEquals(A2, File(repo, ".git/lfs/objects/${objectPath(A2)}").readText())
    }

    @Test
    fun `the server's credentials are asked for once, through Leaf's dialog`(): Unit = runBlocking {
        server.credentials = "leaf" to "s3cret"
        branchWithChanges()
        val remote = remote()
        val operations = testGitCliOperations(jgit, remote.gitCli, remote.credentialsStateManager)

        val (result, dialogs) = remote.credentialsStateManager.answeringDialogs({ lfsCredentialsAccepted("leaf", "s3cret") }) {
            operations.run(gitDir(), listOf("merge", "--no-edit", "feature"))
        }

        assertEquals(Either.Ok(false), result)
        assertEquals(1, dialogs.size)
        assertEquals(B1, File(repo, "b.bin").readText())
    }

    @Test
    fun `closing the credentials dialog fails the files, and the error says so`(): Unit = runBlocking {
        server.credentials = "leaf" to "s3cret"
        branchWithChanges()
        val remote = remote()
        val operations = testGitCliOperations(jgit, remote.gitCli, remote.credentialsStateManager)

        val (result, _) = remote.credentialsStateManager.answeringDialogs {
            operations.run(gitDir(), listOf("merge", "--no-edit", "feature"))
        }

        val error = assertInstanceOf(LfsFilterError::class.java, (result as Either.Err).error)
        assertEquals(setOf(LfsError.CredentialsDenied), error.files.map { it.error }.toSet())
        assertTrue(error.files.any { it.path == "b.bin" }, "${error.files}")
    }

    @Test
    fun `downloads show as the command's progress`(): Unit = runBlocking {
        branchWithChanges()
        val remote = remote()

        leafGit(remote, "merge", "--no-edit", "feature")

        val stages = remote.progress.mapNotNull { it?.stage }
        assertTrue(stages.any { it.startsWith("Downloading LFS objects") }, "$stages")
    }

    @Test
    fun `cancelling while a download waits stops git, and leaves no partial object`(): Unit = runBlocking {
        branchWithChanges()
        val remote = remote()
        server.downloadGate = CountDownLatch(1)

        val merge = launch(Dispatchers.IO) { remote.command.run(repo, listOf("merge", "--no-edit", "feature")) }
        withTimeout(30_000) {
            while (server.downloadsWaiting.get() == 0) delay(20)
        }
        merge.cancel()
        withTimeout(10_000) { merge.join() }
        server.downloadGate?.countDown()

        val store = File(repo, ".git/lfs/objects")
        val leftOver = store.walkTopDown().filter { it.isFile && it.name.endsWith(".tmp") }.toList()
        assertEquals(emptyList<File>(), leftOver)
        assertFalse(File(repo, ".git/index.lock").exists())
    }

    @Test
    fun `in a linked worktree, objects go to the common git dir's store`(): Unit = runBlocking {
        branchWithChanges()
        val worktree = File(tempDir, "wt")
        git.run(repo, "worktree", "add", "-q", "-b", "agent", worktree.path, "main")
        val remote = remote()

        val result = remote.command.run(worktree, listOf("merge", "--no-edit", "feature"))

        assertEquals(0, (result as Either.Ok).value.exitCode, result.value.stderr)
        assertEquals(B1, File(worktree, "b.bin").readText())
        assertTrue(File(repo, ".git/lfs/objects/${objectPath(B1)}").isFile)
        assertFalse(File(repo, ".git/worktrees/wt/lfs").exists())
    }

    @Test
    fun `a file touched since its checkout doesn't count as a local change`(): Unit = runBlocking {
        branchWithChanges()
        val remote = remote()
        checkOutThroughLeaf(remote)
        File(repo, "a.bin").setLastModified(System.currentTimeMillis() + 60_000)

        assertEquals(
            Either.Ok(false),
            testGitCliOperations(jgit, remote.gitCli).run(gitDir(), listOf("merge", "--no-edit", "feature")),
        )
        assertEquals(A2, File(repo, "a.bin").readText())
        assertEquals(pointer(A2), git.run(repo, "cat-file", "-p", ":a.bin"))
    }

    @Test
    fun `the working files, the index and the store are byte for byte what git-lfs leaves`(): Unit = runBlocking {
        val realGitLfs = realGitLfs()
        assumeTrue(realGitLfs != null) { "git-lfs isn't installed" }
        branchWithChanges()
        commit("More", "c.bin" to C1)
        // git-lfs keeps an empty file empty, not as a pointer
        File(repo, "empty.bin").writeText("")
        git.run(repo, "add", "-A")
        git.run(repo, "commit", "-q", "-m", "Empty LFS file")

        val withGitLfs = File(tempDir, "with-git-lfs")
        git.run(tempDir, "clone", "-q", repo.path, withGitLfs.path)
        git.run(withGitLfs, "config", "lfs.url", server.url)
        val leaf = File(tempDir, "with-leaf")
        git.run(tempDir, "clone", "-q", repo.path, leaf.path)
        git.run(leaf, "config", "lfs.url", server.url)

        val gitLfsPath = "${File(realGitLfs!!).parent}:/usr/bin:/bin"
        for ((key, value) in listOf(
            "filter.lfs.process" to "git-lfs filter-process",
            "filter.lfs.smudge" to "git-lfs smudge -- %f",
            "filter.lfs.clean" to "git-lfs clean -- %f",
            "filter.lfs.required" to "true",
        )) {
            git.run(withGitLfs, "config", key, value)
        }

        for (copy in listOf(withGitLfs, leaf)) {
            val remote = remote(path = if (copy == withGitLfs) gitLfsPath else withoutGitLfs)
            copy.listFiles { file -> file.name.endsWith(".bin") }!!.forEach { it.delete() }
            assertEquals(0, (remote.command.run(copy, listOf("checkout", "--", ".")) as Either.Ok).value.exitCode)
            val merge = remote.command.run(copy, listOf("merge", "--no-edit", "origin/feature")) as Either.Ok
            assertEquals(0, merge.value.exitCode, merge.value.stderr)
            File(copy, "a.bin").writeText("edited\n")
            assertEquals(0, (remote.command.run(copy, listOf("add", "a.bin")) as Either.Ok).value.exitCode)
        }

        for (name in listOf("a.bin", "b.bin", "c.bin", "empty.bin", "README.md")) {
            assertEquals(File(withGitLfs, name).readText(), File(leaf, name).readText(), name)
        }
        assertEquals(git.run(withGitLfs, "ls-files", "-s"), git.run(leaf, "ls-files", "-s"))
        assertEquals(storeObjects(withGitLfs), storeObjects(leaf))
    }

    /** `feature` changes `a.bin` and adds `b.bin`, and `main` changes `README.md`, so that a merge is no fast-forward. */
    private fun branchWithChanges() {
        git.run(repo, "switch", "-q", "-c", "feature")
        commit("Feature", "a.bin" to A2, "b.bin" to B1)
        git.run(repo, "switch", "-q", "main")
        commit("Main", "README.md" to "LFS test, on main\n")
    }

    /**
     * Commits [files] with git, which has no LFS filter here: an LFS file goes in as its pointer, and its content to
     * the server.
     */
    private fun commit(message: String, vararg files: Pair<String, String>) {
        for ((name, content) in files) {
            if (name.endsWith(".bin")) {
                File(repo, name).writeText(pointer(content))
                File(serverStorage, oid(content)).writeText(content)
            } else {
                File(repo, name).writeText(content)
            }
        }

        git.run(repo, "add", "-A")
        git.run(repo, "commit", "-q", "-m", message)
    }

    /**
     * Writes the working tree's LFS files again through Leaf's filter, as Leaf's clone leaves them, `a.bin` as [a]. git
     * outside Leaf has no LFS filter here, so from then on the tests change the working tree through Leaf.
     */
    private suspend fun checkOutThroughLeaf(remote: TestRemoteCommand, a: String = A1) {
        repo.listFiles { file -> file.name.endsWith(".bin") }!!.forEach { it.delete() }
        leafGit(remote, "checkout", "--", ".")
        assertEquals(a, File(repo, "a.bin").readText())
    }

    private suspend fun leafGit(remote: TestRemoteCommand, vararg args: String): String {
        val result = remote.command.run(repo, args.toList())
        val output = (result as Either.Ok).value
        assertEquals(0, output.exitCode, output.stderr)

        return output.stdout
    }

    private fun remote(path: String = withoutGitLfs) =
        TestRemoteCommand(helper, globalConfig, shellVariables = mapOf("PATH" to path), jgit = jgit)

    private fun gitDir() = File(repo, ".git").path

    private fun realGitLfs(): String? = ProcessBuilder("/bin/sh", "-c", "command -v git-lfs").start()
        .let { process -> process.inputStream.readBytes().decodeToString().trim().also { process.waitFor() } }
        .ifEmpty { null }

    private fun pointer(content: String) = lfsPointerText(LongObjectId.fromString(oid(content)), content.length.toLong())

    private fun oid(content: String) =
        MessageDigest.getInstance("SHA-256").digest(content.toByteArray()).joinToString("") { "%02x".format(it) }

    private fun objectPath(content: String) = oid(content).let { "${it.substring(0, 2)}/${it.substring(2, 4)}/$it" }

    /** The objects in [repository]'s LFS store, by path, with their content. */
    private fun storeObjects(repository: File): Map<String, String> {
        val objects = File(repository, ".git/lfs/objects")

        return objects.walkTopDown().filter { it.isFile }.associate { it.relativeTo(objects).path to it.readText() }
    }
}
