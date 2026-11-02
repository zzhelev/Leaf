// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.data.git.lfs.filter

import dev.app.leaf.data.git.IsolatedSystemReader
import dev.app.leaf.data.git.TestGitCli
import dev.app.leaf.data.git.cli.askpass.builtAskpassHelper
import dev.app.leaf.data.git.cli.operations.GitCliCherryPickCommitGitAction
import dev.app.leaf.data.git.cli.operations.GitCliCherryPickCommitsGitAction
import dev.app.leaf.data.git.cli.operations.GitCliMergeBranchGitAction
import dev.app.leaf.data.git.cli.operations.GitCliRebaseBranchGitAction
import dev.app.leaf.data.git.cli.operations.GitCliRevertCommitGitAction
import dev.app.leaf.data.git.cli.operations.testGitCliOperations
import dev.app.leaf.data.git.cli.remote.FakeLfsServer
import dev.app.leaf.data.git.cli.remote.GIT_LFS_FILTERS
import dev.app.leaf.data.git.cli.remote.TestRemoteCommand
import dev.app.leaf.data.git.cli.remote.pathWithoutGitLfs
import dev.app.leaf.data.git.lfs.lfsPointerText
import dev.app.leaf.data.git.rewrite.CheckRebasePlanGitAction
import dev.app.leaf.data.git.rewrite.StartRebasePlanGitAction
import dev.app.leaf.data.git.testJGit
import dev.app.leaf.data.repositories.InMemoryRepositoryStateRepository
import dev.app.leaf.domain.errors.Either
import dev.app.leaf.domain.errors.LfsDownloadFirstError
import dev.app.leaf.domain.errors.LfsError
import dev.app.leaf.domain.errors.LfsFileFailure
import dev.app.leaf.domain.models.Branch
import dev.app.leaf.domain.models.Commit
import dev.app.leaf.domain.models.Identity
import dev.app.leaf.domain.models.TaskProgress
import dev.app.leaf.domain.rewrite.BranchLine
import dev.app.leaf.domain.rewrite.OntoBase
import dev.app.leaf.domain.rewrite.PlanAction
import dev.app.leaf.domain.rewrite.PlanRebaseOutcome
import dev.app.leaf.domain.rewrite.RebasePlan
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.eclipse.jgit.lfs.lib.LongObjectId
import org.eclipse.jgit.lib.ObjectId
import org.eclipse.jgit.storage.file.FileRepositoryBuilder
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
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

private const val A1 = "a.bin, version 1\n"
private const val A2 = "a.bin, version 2\n"
private const val B1 = "b.bin, version 1\n"
private const val C1 = "c.bin, version 1\n"

/**
 * The LFS objects that a merge, cherry-pick, revert or rebase (a commit editor's too) needs, downloaded before git
 * starts, where Leaf's LFS filter applies (stage 2): a download that fails then changes nothing, where during git's
 * checkout it stopped git halfway. The objects are on [FakeLfsServer], and git runs without git-lfs unless a test says
 * otherwise. Skipped without the askpass helper.
 */
@DisabledOnOs(OS.WINDOWS)
@Timeout(value = 60, unit = TimeUnit.SECONDS)
class LfsDownloadFirstTest {
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
    fun `a merge downloads its objects first, in one batch, and git finds them in the store`(): Unit = runBlocking {
        branchWithChanges()
        val remote = remote()
        checkOutThroughLeaf(remote)
        server.requests.clear()

        val result = merge(remote)

        assertEquals(Either.Ok(false), result)
        assertEquals(A2, File(repo, "a.bin").readText())
        assertEquals(B1, File(repo, "b.bin").readText())
        assertEquals(1, batchRequests(), "${server.requests}")
        assertEquals("", leafGit(remote, "status", "--porcelain"))
    }

    @Test
    fun `a download that fails stops the merge before git starts, and nothing changes`(): Unit = runBlocking {
        branchWithChanges()
        val remote = remote()
        checkOutThroughLeaf(remote)
        File(serverStorage, oid(B1)).delete()
        val head = git.run(repo, "rev-parse", "HEAD")

        val result = merge(remote)

        val error = assertInstanceOf(LfsDownloadFirstError::class.java, (result as Either.Err).error)
        assertEquals(listOf(LfsFileFailure("b.bin", LfsError.ObjectRefused(404, "Object does not exist"))), error.files)
        assertNothingChanged(remote, head)
    }

    @Test
    fun `a cherry-pick, several cherry-picks and a revert download first too`(): Unit = runBlocking {
        branchWithChanges()
        val feature = git.run(repo, "rev-parse", "feature").trim()
        val remote = remote()
        checkOutThroughLeaf(remote)
        File(serverStorage, oid(B1)).delete()
        val head = git.run(repo, "rev-parse", "HEAD")
        val operations = testGitCliOperations(jgit, remote.gitCli)

        val picked = GitCliCherryPickCommitGitAction(operations)(gitDir(), commit(feature))
        assertEquals(listOf("b.bin"), (picked.error() as LfsDownloadFirstError).files.map { it.path })
        assertNothingChanged(remote, head)

        val pickedAll = GitCliCherryPickCommitsGitAction(operations)(gitDir(), listOf(feature), noCommit = false)
        assertEquals(listOf("b.bin"), (pickedAll.error() as LfsDownloadFirstError).files.map { it.path })
        assertNothingChanged(remote, head)

        // Reverting a commit that deleted c.bin needs c.bin's object, which no checkout downloaded. git outside Leaf
        // has no filter here, so only c.bin is staged: a.bin has its content in the working tree
        File(repo, "c.bin").writeText(pointer(C1))
        git.run(repo, "add", "c.bin")
        git.run(repo, "commit", "-q", "-m", "Add c")
        git.run(repo, "rm", "-q", "c.bin")
        git.run(repo, "commit", "-q", "-m", "Delete c")
        val deletion = git.run(repo, "rev-parse", "HEAD").trim()
        File(serverStorage, oid(C1)).delete()

        val reverted = GitCliRevertCommitGitAction(operations)(gitDir(), commit(deletion))
        assertEquals(listOf("c.bin"), (reverted.error() as LfsDownloadFirstError).files.map { it.path })
        assertNothingChanged(remote, deletion + "\n")
    }

    @Test
    fun `a rebase downloads what it checks out and replays first`(): Unit = runBlocking {
        branchWithChanges()
        val remote = remote()
        checkOutThroughLeaf(remote)
        File(serverStorage, oid(B1)).delete()
        val head = git.run(repo, "rev-parse", "HEAD")
        val rebase = GitCliRebaseBranchGitAction(testGitCliOperations(jgit, remote.gitCli))

        val refused = rebase(gitDir(), branch("feature"))

        assertEquals(listOf("b.bin"), (refused.error() as LfsDownloadFirstError).files.map { it.path })
        assertNothingChanged(remote, head)
        assertFalse(File(repo, ".git/rebase-merge").exists())

        File(serverStorage, oid(B1)).writeText(B1)
        server.requests.clear()

        assertEquals(Either.Ok(false), rebase(gitDir(), branch("feature")))
        assertEquals(B1, File(repo, "b.bin").readText())
        assertEquals(1, batchRequests(), "${server.requests}")
    }

    @Test
    fun `a commit editor's plan downloads what its rebase checks out and replays first`(): Unit = runBlocking {
        branchWithChanges()
        commit("Add c", "c.bin" to C1)
        // feature is checked out nowhere, so the plan's rebase runs here, from another branch
        git.run(repo, "switch", "-q", "-c", "other", "main~2")
        val remote = remote()
        checkOutThroughLeaf(remote)
        File(serverStorage, oid(B1)).delete()
        File(serverStorage, oid(C1)).delete()
        val head = git.run(repo, "rev-parse", "HEAD")
        val startPlan = StartRebasePlanGitAction(
            jgit,
            remote.command,
            testGitCliOperations(jgit, remote.gitCli),
            CheckRebasePlanGitAction(jgit, remote.gitCli),
        )

        // The first checkout brings main's c.bin, and replaying feature's commit its b.bin
        val refused = startPlan(gitDir(), featureEditedOntoMain(), autostash = false)

        val files = (refused.error() as LfsDownloadFirstError).files.map { it.path }
        assertEquals(setOf("b.bin", "c.bin"), files.toSet())
        assertNothingChanged(remote, head)
        assertFalse(File(repo, "c.bin").exists())
        assertFalse(File(repo, ".git/rebase-merge").exists())

        File(serverStorage, oid(B1)).writeText(B1)
        File(serverStorage, oid(C1)).writeText(C1)
        server.requests.clear()

        val outcome = (startPlan(gitDir(), featureEditedOntoMain(), autostash = false) as Either.Ok).value

        assertEquals(false, (outcome as PlanRebaseOutcome.Stopped).atConflicts)
        assertEquals(1, batchRequests(), "${server.requests}")
        assertEquals(listOf(A2, B1, C1), listOf("a.bin", "b.bin", "c.bin").map { File(repo, it).readText() })
        assertEquals("", leafGit(remote, "status", "--porcelain"))
    }

    @Test
    fun `a file that only looks like a pointer, at a path without the LFS filter, isn't downloaded`(): Unit =
        runBlocking {
            git.run(repo, "switch", "-q", "-c", "feature")
            // A pointer whose object no server has, as a test fixture would be
            File(repo, "fixture.txt").writeText(pointer("nowhere\n"))
            git.run(repo, "add", "fixture.txt")
            git.run(repo, "commit", "-q", "-m", "Fixture")
            git.run(repo, "switch", "-q", "main")
            val remote = remote()
            checkOutThroughLeaf(remote)
            server.requests.clear()

            assertEquals(Either.Ok(false), merge(remote))
            assertEquals(pointer("nowhere\n"), File(repo, "fixture.txt").readText())
            assertEquals(0, batchRequests(), "${server.requests}")
        }

    @Test
    fun `two files with the same content download their object once, and both are named when it fails`(): Unit =
        runBlocking {
            git.run(repo, "switch", "-q", "-c", "feature")
            commit("Copies", "b.bin" to B1, "copy.bin" to B1)
            git.run(repo, "switch", "-q", "main")
            val remote = remote()
            checkOutThroughLeaf(remote)
            File(serverStorage, oid(B1)).delete()

            val refused = merge(remote)

            assertEquals(listOf("b.bin", "copy.bin"), (refused.error() as LfsDownloadFirstError).files.map { it.path })

            File(serverStorage, oid(B1)).writeText(B1)
            server.requests.clear()

            assertEquals(Either.Ok(false), merge(remote))
            assertEquals(B1, File(repo, "copy.bin").readText())
            assertEquals(1, server.requests.count { it.startsWith("GET /objects/${oid(B1)}") }, "${server.requests}")
        }

    @Test
    fun `objects already in the store aren't asked for`(): Unit = runBlocking {
        branchWithChanges()
        val remote = remote()
        checkOutThroughLeaf(remote)
        // The filter downloads feature's objects as it checks them out
        leafGit(remote, "switch", "-q", "feature")
        leafGit(remote, "switch", "-q", "main")
        server.requests.clear()

        assertEquals(Either.Ok(false), merge(remote))
        assertEquals(0, batchRequests(), "${server.requests}")
    }

    @Test
    fun `with git-lfs set up, git-lfs downloads, and Leaf asks the server nothing`(): Unit = runBlocking {
        val realGitLfs = ProcessBuilder("/bin/sh", "-c", "command -v git-lfs").start()
            .let { process -> process.inputStream.readBytes().decodeToString().trim().also { process.waitFor() } }
        assumeTrue(realGitLfs.isNotEmpty()) { "git-lfs isn't installed" }
        globalConfig.appendText(GIT_LFS_FILTERS)
        branchWithChanges()
        val remote = remote(path = "${File(realGitLfs).parent}:/usr/bin:/bin")
        server.requests.clear()

        assertEquals(Either.Ok(false), merge(remote))
        assertEquals(B1, File(repo, "b.bin").readText())
        assertTrue(server.requests.isNotEmpty())
        assertTrue(server.requests.all { "git-lfs/" in it }, "${server.requests}")
    }

    @Test
    fun `the download shows its progress, and Cancel stops it before git starts`(): Unit = runBlocking {
        branchWithChanges()
        val repositoryState = InMemoryRepositoryStateRepository()
        val stages = Collections.synchronizedList(mutableListOf<String>())
        val remote = remote()
        checkOutThroughLeaf(remote)
        val head = git.run(repo, "rev-parse", "HEAD")
        server.downloadGate = CountDownLatch(1)
        val operations = testGitCliOperations(jgit, remote.gitCli, repositoryState = repositoryState)

        val watch = launch { repositoryState.taskProgress.collect { progress -> progress?.stage?.let(stages::add) } }
        val merge = launch(Dispatchers.IO) {
            GitCliMergeBranchGitAction(operations)(gitDir(), branch("feature"), fastForward = false)
        }
        withTimeout(30_000) {
            while (server.downloadsWaiting.get() == 0) delay(20)
        }

        assertEquals(TaskProgress("Downloading LFS objects (1 of 2)", 0), repositoryState.taskProgress.value)

        merge.cancel()
        withTimeout(10_000) { merge.join() }
        server.downloadGate?.countDown()
        watch.cancel()

        assertTrue(stages.isNotEmpty())
        assertEquals(null, repositoryState.taskProgress.value)
        val leftOver = File(repo, ".git/lfs").walkTopDown().filter { it.isFile && it.name.endsWith(".tmp") }.toList()
        assertEquals(emptyList<File>(), leftOver)
        // git never ran: a merge writes ORIG_HEAD first
        assertFalse(File(repo, ".git/ORIG_HEAD").exists())
        assertEquals(head, git.run(repo, "rev-parse", "HEAD"))
    }

    @Test
    fun `the files to download are pointers at paths that their own tree's attributes give the LFS filter`() {
        File(repo, ".gitattributes").writeText("*.bin filter=lfs diff=lfs merge=lfs -text\n")
        File(repo, "sub").mkdirs()
        File(repo, "sub/.gitattributes").writeText("*.bin -filter\n*.dat filter=lfs\n")
        File(repo, ".git/info").mkdirs()
        File(repo, ".git/info/attributes").writeText("*.raw filter=lfs\n")
        val files = mapOf(
            "a.bin" to pointer(A2),
            "sub/b.bin" to pointer(B1),
            "sub/c.dat" to pointer(C1),
            "d.raw" to pointer("d\n"),
            "e.txt" to pointer("e\n"),
            // Extension lines go between the version and the oid
            "f.bin" to "version https://git-lfs.github.com/spec/v1\next-0-foo sha256:${oid("x")}\n" +
                "oid sha256:${oid("f\n")}\nsize 2\n",
            "g.bin" to "not a pointer\n",
            "h.bin" to pointer("h\n").padEnd(2000, ' '),
        )
        for ((path, content) in files) File(repo, path).writeText(content)
        git.run(repo, "add", "-A")
        git.run(repo, "commit", "-q", "-m", "Pointers")
        val commit = git.run(repo, "rev-parse", "HEAD").trim()

        val found = FileRepositoryBuilder().setGitDir(File(repo, ".git")).build().use { repository ->
            val tree = repository.parseCommit(ObjectId.fromString(commit)).tree
            lfsFilesToDownload(repository, listOf(listOf(null, tree))).map { it.path }.toSet()
        }

        // git's own answer for the same commit, with info/attributes, for the paths that are pointers. The store is
        // empty, as nothing was checked out through Leaf
        val pointers = arrayOf("a.bin", "sub/b.bin", "sub/c.dat", "d.raw", "e.txt")
        val lfsByGit = git.run(repo, "check-attr", "--source=$commit", "filter", "--", *pointers)
            .lines().filter { it.endsWith(": filter: lfs") }.map { it.substringBefore(":") }.toSet()
        assertEquals(setOf("a.bin", "sub/c.dat", "d.raw"), lfsByGit)
        assertEquals(lfsByGit, found)
    }

    @Test
    fun `a pointer whose blob the repository lacks is left out, and the others still count`() {
        File(repo, "x.bin").writeText(pointer("x\n"))
        File(repo, "y.bin").writeText(pointer("y\n"))
        git.run(repo, "add", "-A")
        git.run(repo, "commit", "-q", "-m", "Two pointers")
        val commit = git.run(repo, "rev-parse", "HEAD").trim()
        // As a partial clone lacks it
        val blob = git.run(repo, "rev-parse", "HEAD:x.bin").trim()
        File(repo, ".git/objects/${blob.take(2)}/${blob.drop(2)}").delete()

        val found = FileRepositoryBuilder().setGitDir(File(repo, ".git")).build().use { repository ->
            val tree = repository.parseCommit(ObjectId.fromString(commit)).tree
            lfsFilesToDownload(repository, listOf(listOf(null, tree))).map { it.path }
        }

        assertEquals(listOf("a.bin", "y.bin"), found.sorted())
    }

    private suspend fun merge(remote: TestRemoteCommand) =
        GitCliMergeBranchGitAction(testGitCliOperations(jgit, remote.gitCli))(gitDir(), branch("feature"), false)

    /** HEAD at [head], no merge, cherry-pick or revert started, and the working tree as git committed it. */
    private suspend fun assertNothingChanged(remote: TestRemoteCommand, head: String) {
        assertEquals(head, git.run(repo, "rev-parse", "HEAD"))
        assertFalse(File(repo, ".git/MERGE_HEAD").exists())
        assertFalse(File(repo, ".git/CHERRY_PICK_HEAD").exists())
        assertFalse(File(repo, ".git/REVERT_HEAD").exists())
        assertEquals("", leafGit(remote, "status", "--porcelain"))
        assertFalse(File(repo, "b.bin").exists())
    }

    /** `feature` changes `a.bin` and adds `b.bin`, and `main` changes `README.md`: a merge is no fast-forward. */
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

    /** A commit editor's plan that moves feature's one commit onto main's tip, and stops there for an edit. */
    private fun featureEditedOntoMain(): RebasePlan {
        val feature = commit(git.run(repo, "rev-parse", "feature").trim())
            .copy(parentsHashes = listOf(git.run(repo, "rev-parse", "feature^").trim()))
        val line = BranchLine("refs/heads/feature", "refs/heads/main", listOf(feature))

        return RebasePlan.of(line, listOf(feature))
            .withAction(feature.hash, PlanAction.EDIT)
            .withOnto(OntoBase("refs/heads/main", git.run(repo, "rev-parse", "main").trim()))
    }

    /** Writes the working tree's LFS files again through Leaf's filter, as Leaf's clone leaves them. */
    private suspend fun checkOutThroughLeaf(remote: TestRemoteCommand) {
        repo.listFiles { file -> file.name.endsWith(".bin") }!!.forEach { it.delete() }
        leafGit(remote, "checkout", "--", ".")
        assertEquals(A1, File(repo, "a.bin").readText())
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

    private fun batchRequests() = server.requests.count { it.startsWith("POST /objects/batch") }

    private fun branch(name: String) =
        Branch(git.run(repo, "rev-parse", name).trim(), "refs/heads/$name", isLocal = true)

    private fun commit(hash: String) = Commit(
        hash = hash,
        message = "Commit",
        committer = Identity("Leaf Test", "test@example.invalid"),
        author = Identity("Leaf Test", "test@example.invalid"),
        date = 1_760_000_000,
        parentsHashes = emptyList(),
    )

    private fun pointer(content: String) =
        lfsPointerText(LongObjectId.fromString(oid(content)), content.length.toLong())

    private fun oid(content: String) =
        MessageDigest.getInstance("SHA-256").digest(content.toByteArray()).joinToString("") { "%02x".format(it) }
}

private fun Either<*, *>.error(): Any? = (this as? Either.Err)?.error
