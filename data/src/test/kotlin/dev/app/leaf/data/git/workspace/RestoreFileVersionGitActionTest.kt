// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.data.git.workspace

import dev.app.leaf.data.git.IsolatedSystemReader
import dev.app.leaf.data.git.TestGitCli
import dev.app.leaf.data.git.cli.askpass.builtAskpassHelper
import dev.app.leaf.data.git.cli.remote.TestRemoteCommand
import dev.app.leaf.data.git.symlinkTo
import dev.app.leaf.data.git.testJGit
import dev.app.leaf.data.log.GetFileCommitsAction
import dev.app.leaf.data.mappers.JGitCommitMapper
import dev.app.leaf.data.mappers.JGitIdentityMapper
import dev.app.leaf.domain.errors.Either
import dev.app.leaf.domain.errors.GitError
import dev.app.leaf.domain.errors.RestoreFileError
import dev.app.leaf.domain.restore.CurrentFile
import dev.app.leaf.domain.restore.FileRestorePlan
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.eclipse.jgit.util.FileUtils
import org.eclipse.jgit.util.SystemReader
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.DisabledOnOs
import org.junit.jupiter.api.condition.OS
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import kotlin.time.Duration.Companion.seconds

/**
 * "Restore this version of the file" in file history (fork-only): a file as a commit had it, written into the working
 * tree with `git restore --source=<commit> --worktree`. Each result is compared with plain git on a copy of the same
 * repository, and the cases where plain git deletes, empties or overwrites something it shouldn't are the ones that
 * Leaf refuses or puts right (probed with git 2.54, see `docs/fork/CHANGELOG.md`). Skipped without the askpass helper.
 */
@DisabledOnOs(OS.WINDOWS)
class RestoreFileVersionGitActionTest {
    @TempDir
    lateinit var tempDir: File

    private val originalReader: SystemReader = SystemReader.getInstance()
    private val jgit = testJGit()

    private lateinit var git: TestGitCli
    private lateinit var restoreAction: RestoreFileVersionGitAction
    private lateinit var planAction: GetFileRestorePlanGitAction

    @BeforeEach
    fun setUp() {
        val helper = builtAskpassHelper()
        assumeTrue(helper != null) { "leaf-askpass isn't built, run ./gradlew :app:rustTasks" }
        SystemReader.setInstance(IsolatedSystemReader(File(tempDir, "jgit-config"), originalReader))

        val globalConfig = File(tempDir, "empty.gitconfig").apply { createNewFile() }
        git = TestGitCli(globalConfig)

        val remote = TestRemoteCommand(helper!!, globalConfig, jgit = jgit)
        val planner = FileRestorePlanner(jgit, remote.gitCli)
        restoreAction = RestoreFileVersionGitAction(planner, remote.command)
        planAction = GetFileRestorePlanGitAction(planner)
    }

    @AfterEach
    fun restoreSystemReader() {
        SystemReader.setInstance(originalReader)
    }

    @Test
    fun `writes the file as the commit had it, and leaves the index alone`(): Unit = runBlocking {
        val repo = repository()
        val first = commit(repo, "v1", "a.txt" to "version 1\n")
        commit(repo, "v2", "a.txt" to "version 2\n")
        File(repo, "a.txt").writeText("staged\n")
        git.run(repo, "add", "a.txt")
        File(repo, "a.txt").writeText("unstaged\n")

        assertSameAsGit(repo, first, "a.txt")

        assertEquals("version 1\n", File(repo, "a.txt").readText())
        assertEquals("staged\n", git.run(repo, "show", ":a.txt"), "what was staged stays staged")
        assertEquals("MM a.txt\n", git.run(repo, "status", "--porcelain"))
    }

    @Test
    fun `a file that was deleted comes back untracked, as with git`(): Unit = runBlocking {
        val repo = repository()
        val first = commit(repo, "add", "a.txt" to "version 1\n")
        git.run(repo, "rm", "-q", "a.txt")
        git.run(repo, "commit", "-m", "delete")

        assertSameAsGit(repo, first, "a.txt")

        assertEquals("?? a.txt\n", git.run(repo, "status", "--porcelain"))
    }

    @Test
    fun `a binary file and its executable bit come back as they were`(): Unit = runBlocking {
        val repo = repository()
        val bytes = byteArrayOf(0, 1, 2, 'b'.code.toByte(), 'i'.code.toByte(), 'n'.code.toByte(), -1, -2)
        File(repo, "run.sh").writeText("#!/bin/sh\necho hi\n")
        File(repo, "data.bin").writeBytes(bytes)
        File(repo, "run.sh").setExecutable(true)
        git.run(repo, "add", "-A")
        git.run(repo, "commit", "-m", "binary and script")
        val first = git.run(repo, "rev-parse", "HEAD").trim()
        File(repo, "run.sh").setExecutable(false)
        File(repo, "run.sh").writeText("changed\n")
        File(repo, "data.bin").writeBytes(byteArrayOf(9, 9))

        assertSameAsGit(repo, first, "run.sh")
        assertSameAsGit(repo, first, "data.bin")

        assertTrue(File(repo, "run.sh").canExecute())
        assertTrue(bytes.contentEquals(File(repo, "data.bin").readBytes()))
    }

    @Test
    fun `a name with spaces, non-ASCII letters or pattern characters is that file only`(): Unit = runBlocking {
        val repo = repository()
        val first = commit(
            repo, "names",
            "sp ace é.txt" to "v1\n",
            "[ab].txt" to "v1\n",
            "a.txt" to "v1\n",
            "b.txt" to "v1\n",
        )
        commit(
            repo, "edit",
            "sp ace é.txt" to "v2\n",
            "[ab].txt" to "v2\n",
            "a.txt" to "v2\n",
            "b.txt" to "v2\n",
        )

        assertSameAsGit(repo, first, "[ab].txt")
        assertSameAsGit(repo, first, "sp ace é.txt")

        assertEquals("v1\n", File(repo, "[ab].txt").readText())
        assertEquals("v2\n", File(repo, "a.txt").readText(), "[ab] isn't a pattern for a.txt and b.txt")
        assertEquals("v2\n", File(repo, "b.txt").readText())
    }

    @Test
    fun `a file in a folder is restored with its folders, which may be gone`(): Unit = runBlocking {
        val repo = repository()
        val first = commit(repo, "nested", "src/deep/a.txt" to "v1\n")
        commit(repo, "edit", "src/deep/a.txt" to "v2\n")
        FileUtils.delete(File(repo, "src"), FileUtils.RECURSIVE)

        assertSameAsGit(repo, first, "src/deep/a.txt")
    }

    @Test
    fun `a commit that deleted the file has nothing to restore, and nothing changes`(): Unit = runBlocking {
        val repo = repository()
        commit(repo, "add", "a.txt" to "version 1\n")
        git.run(repo, "rm", "-q", "a.txt")
        git.run(repo, "commit", "-m", "delete")
        val deleting = git.run(repo, "rev-parse", "HEAD").trim()
        File(repo, "a.txt").writeText("my new file\n")

        val plan = plan(repo, deleting, "a.txt")

        assertEquals(RestoreFileError.NotInCommit("a.txt", deleting), plan.refusal)
        assertEquals(RestoreFileError.NotInCommit("a.txt", deleting), restoreError(repo, deleting, "a.txt"))
        assertEquals("my new file\n", File(repo, "a.txt").readText())
        assertNoBackupLeft(repo)
    }

    @Test
    fun `file history stops at the rename, so the old name has nothing at the commit that moved it`(): Unit =
        runBlocking {
            val repo = repository()
            val added = commit(repo, "add old", "old.txt" to "one\ntwo\nthree\nfour\nfive\nsix\n")
            val edited = commit(repo, "edit old", "old.txt" to "one\ntwo\nthree\nfour\nfive\nsix\nseven\n")
            git.run(repo, "mv", "old.txt", "new.txt")
            git.run(repo, "commit", "-m", "rename")
            val renamed = git.run(repo, "rev-parse", "HEAD").trim()
            val after = commit(repo, "edit new", "new.txt" to "one\ntwo\nthree\nfour\nfive\nsix\nseven\neight\n")

            // What the history lists (JGit's log of a path doesn't follow renames), and what the menu is offered on
            val getFileCommits = GetFileCommitsAction(JGitCommitMapper(JGitIdentityMapper()), jgit)
            suspend fun historyOf(path: String) = (getFileCommits(gitDir(repo), path) as Either.Ok).value.map { it.hash }

            assertEquals(listOf(after, renamed), historyOf("new.txt"))
            assertEquals(listOf(renamed, edited, added), historyOf("old.txt"))

            // The rename commit has the file under its new name, which is the oldest row of that history
            assertSameAsGit(repo, renamed, "new.txt")
            // Under the old name it's the newest row, and the commit has no such file
            assertEquals(RestoreFileError.NotInCommit("old.txt", renamed), restoreError(repo, renamed, "old.txt"))
            // The older rows of the old name restore into it, which isn't in the working tree any more
            assertSameAsGit(repo, edited, "old.txt")
            assertEquals("one\ntwo\nthree\nfour\nfive\nsix\nseven\n", File(repo, "old.txt").readText())
        }

    @Test
    fun `the plan says what the file is now`(): Unit = runBlocking {
        val repo = repository()
        val first = commit(
            repo, "files",
            "clean.txt" to "v1\n",
            "staged.txt" to "v1\n",
            "unstaged.txt" to "v1\n",
            "both.txt" to "v1\n",
            "gone.txt" to "v1\n",
        )
        commit(
            repo, "edit",
            "clean.txt" to "v2\n",
            "staged.txt" to "v2\n",
            "unstaged.txt" to "v2\n",
            "both.txt" to "v2\n",
            "gone.txt" to "v2\n",
        )
        File(repo, "staged.txt").writeText("s\n")
        git.run(repo, "add", "staged.txt")
        File(repo, "unstaged.txt").writeText("u\n")
        File(repo, "both.txt").writeText("s\n")
        git.run(repo, "add", "both.txt")
        File(repo, "both.txt").writeText("u\n")
        File(repo, "gone.txt").delete()

        suspend fun check(path: String, current: CurrentFile, staged: Boolean) {
            val plan = plan(repo, first, path)
            assertEquals(current to staged, plan.current to plan.hasStagedChanges, path)
        }

        check("clean.txt", CurrentFile.UNCHANGED, false)
        check("staged.txt", CurrentFile.STAGED_ONLY, true)
        check("unstaged.txt", CurrentFile.UNSTAGED_CHANGES, false)
        check("both.txt", CurrentFile.UNSTAGED_CHANGES, true)
        check("gone.txt", CurrentFile.MISSING, false)
    }

    @Test
    fun `the plan tells a new file and an ignored one from tracked ones, as both lose their content`(): Unit =
        runBlocking {
            val repo = repository()
            File(repo, ".gitignore").writeText("*.log\nbuild/\n")
            File(repo, "debug.log").writeText("v1\n")
            File(repo, "new.txt").writeText("v1\n")
            File(repo, "build").mkdirs()
            File(repo, "build/out.txt").writeText("v1\n")
            git.run(repo, "add", "-f", "-A")
            git.run(repo, "commit", "-m", "files")
            val first = git.run(repo, "rev-parse", "HEAD").trim()
            git.run(repo, "rm", "-q", "--cached", "debug.log", "new.txt", "build/out.txt")
            git.run(repo, "commit", "-m", "untrack")
            File(repo, "debug.log").writeText("mine\n")
            File(repo, "new.txt").writeText("mine\n")
            File(repo, "build").mkdirs()
            File(repo, "build/out.txt").writeText("mine\n")

            assertEquals(CurrentFile.IGNORED, plan(repo, first, "debug.log").current)
            assertEquals(CurrentFile.UNTRACKED, plan(repo, first, "new.txt").current)
            assertEquals(CurrentFile.IGNORED, plan(repo, first, "build/out.txt").current)
            assertNull(plan(repo, first, "debug.log").refusal)

            assertSameAsGit(repo, first, "debug.log")
            assertEquals("v1\n", File(repo, "debug.log").readText())
        }

    @Test
    fun `a file with conflicts is refused, as git would write over its markers without a word`(): Unit = runBlocking {
        val repo = repository()
        val base = commit(repo, "base", "a.txt" to "base\n")
        git.run(repo, "checkout", "-b", "other")
        commit(repo, "other", "a.txt" to "other\n")
        git.run(repo, "checkout", "main")
        commit(repo, "main", "a.txt" to "main\n")
        git.runFailing(repo, "merge", "other")
        val conflicted = File(repo, "a.txt").readText()
        assertTrue("<<<<<<<" in conflicted)

        assertEquals(RestoreFileError.Conflicted("a.txt"), plan(repo, base, "a.txt").refusal)
        assertEquals(RestoreFileError.Conflicted("a.txt"), restoreError(repo, base, "a.txt"))
        assertEquals(conflicted, File(repo, "a.txt").readText())
    }

    @Test
    fun `a folder where the file goes is refused, as git would delete it with its untracked files`(): Unit =
        runBlocking {
            val repo = repository()
            val first = commit(repo, "add", "a.txt" to "v1\n")
            commit(repo, "edit", "a.txt" to "v2\n")
            File(repo, "a.txt").delete()
            File(repo, "a.txt").mkdirs()
            File(repo, "a.txt/mine.txt").writeText("untracked work\n")

            assertEquals(RestoreFileError.FolderInPlace("a.txt"), plan(repo, first, "a.txt").refusal)
            assertEquals(RestoreFileError.FolderInPlace("a.txt"), restoreError(repo, first, "a.txt"))
            assertEquals("untracked work\n", File(repo, "a.txt/mine.txt").readText())
        }

    @Test
    fun `a file or a link where a folder of the path goes is refused, as git would replace it`(): Unit = runBlocking {
        val repo = repository()
        val first = commit(repo, "nested", "dir/a.txt" to "v1\n")
        git.run(repo, "rm", "-q", "-r", "dir")
        File(repo, "dir").writeText("a tracked file now\n")
        git.run(repo, "add", "dir")
        git.run(repo, "commit", "-m", "dir is a file")
        File(repo, "dir").appendText("with my edits\n")

        assertEquals(RestoreFileError.ParentNotFolder("dir/a.txt", "dir"), plan(repo, first, "dir/a.txt").refusal)
        assertEquals(RestoreFileError.ParentNotFolder("dir/a.txt", "dir"), restoreError(repo, first, "dir/a.txt"))
        assertEquals("a tracked file now\nwith my edits\n", File(repo, "dir").readText())

        // A link to a folder is replaced by git too
        val elsewhere = File(tempDir, "elsewhere").apply { mkdirs() }
        File(repo, "dir").delete()
        File(repo, "dir").symlinkTo(elsewhere)

        assertEquals(RestoreFileError.ParentNotFolder("dir/a.txt", "dir"), restoreError(repo, first, "dir/a.txt"))
        assertTrue(Files.isSymbolicLink(File(repo, "dir").toPath()))
        assertEquals(emptyList<String>(), elsewhere.list().orEmpty().toList())
    }

    @Test
    fun `a link in the file's place is replaced by the file, and what it pointed at is left alone`(): Unit =
        runBlocking {
            val repo = repository()
            val first = commit(repo, "add", "a.txt" to "v1\n")
            commit(repo, "edit", "a.txt" to "v2\n")
            val target = File(tempDir, "target.txt").apply { writeText("not mine to change\n") }
            File(repo, "a.txt").delete()
            File(repo, "a.txt").symlinkTo(target)

            assertNull(plan(repo, first, "a.txt").refusal)
            restore(repo, first, "a.txt")

            assertFalse(Files.isSymbolicLink(File(repo, "a.txt").toPath()))
            assertEquals("v1\n", File(repo, "a.txt").readText())
            assertEquals("not mine to change\n", target.readText())
        }

    @Test
    fun `a path outside the repository is refused`(): Unit = runBlocking {
        val repo = repository()
        val first = commit(repo, "add", "a.txt" to "v1\n")

        for (path in listOf("../a.txt", "/etc/hosts", "dir/../a.txt", "", "a//b")) {
            assertInstanceOf(Either.Err::class.java, restoreAction(gitDir(repo), first, path), path)
        }
    }

    @Test
    fun `a linked worktree restores in its own folder`(): Unit = runBlocking {
        val repo = repository()
        val first = commit(repo, "add", "a.txt" to "version 1\n")
        commit(repo, "edit", "a.txt" to "version 2\n")
        val linked = File(tempDir, "linked")
        git.run(repo, "worktree", "add", "-b", "agent", linked.absolutePath, "main")
        File(linked, "a.txt").writeText("agent's edit\n")
        val linkedGitDir = File(git.run(linked, "rev-parse", "--absolute-git-dir").trim())

        assertEquals(CurrentFile.UNSTAGED_CHANGES, (planAction(linkedGitDir.path, first, "a.txt") as Either.Ok).value.current)
        assertInstanceOf(Either.Ok::class.java, restoreAction(linkedGitDir.path, first, "a.txt"))

        assertEquals("version 1\n", File(linked, "a.txt").readText())
        assertEquals("version 2\n", File(repo, "a.txt").readText(), "the main worktree's file is untouched")
        assertEquals(" M a.txt\n", git.run(linked, "status", "--porcelain"))
        assertNoBackupLeftIn(linkedGitDir)
    }

    @Test
    fun `a bare repository has no working tree to restore into`(): Unit = runBlocking {
        val repo = repository()
        val first = commit(repo, "add", "a.txt" to "v1\n")
        val bare = File(tempDir, "bare.git")
        git.run(tempDir, "clone", "--bare", repo.absolutePath, bare.absolutePath)

        assertEquals(RestoreFileError.NoWorkTree, (planAction(bare.path, first, "a.txt") as Either.Err).error)
        assertEquals(RestoreFileError.NoWorkTree, (restoreAction(bare.path, first, "a.txt") as Either.Err).error)
    }

    @Test
    fun `when git fails the file is put back, where git alone leaves it deleted`(): Unit = runBlocking {
        val repo = repository()
        git.run(repo, "config", "filter.boom.clean", "cat")
        git.run(repo, "config", "filter.boom.smudge", "false")
        git.run(repo, "config", "filter.boom.required", "true")
        val first = commit(repo, "add", ".gitattributes" to "*.dat filter=boom\n", "a.dat" to "version 1\n")
        commit(repo, "edit", "a.dat" to "version 2\n")
        File(repo, "a.dat").setExecutable(true)
        File(repo, "a.dat").writeText("my unsaved work\n")

        // What plain git does with a smudge filter that fails
        val twin = File(tempDir, "twin")
        copyKeepingModes(repo, twin)
        git.runFailing(twin, "--literal-pathspecs", "restore", "--source=$first", "--worktree", "--", "a.dat")
        assertFalse(File(twin, "a.dat").exists(), "git alone deletes the file")

        val error = restoreError(repo, first, "a.dat")

        assertInstanceOf(RestoreFileError.GitFailed::class.java, error)
        assertNull((error as RestoreFileError.GitFailed).keptAt)
        assertEquals("my unsaved work\n", File(repo, "a.dat").readText())
        assertTrue(File(repo, "a.dat").canExecute(), "with its mode")
        assertNoBackupLeft(repo)
    }

    @Test
    fun `a link in the file's place goes back as a link when git fails`(): Unit = runBlocking {
        val repo = repository()
        git.run(repo, "config", "filter.boom.clean", "cat")
        git.run(repo, "config", "filter.boom.smudge", "false")
        git.run(repo, "config", "filter.boom.required", "true")
        val first = commit(repo, "add", ".gitattributes" to "*.dat filter=boom\n", "a.dat" to "version 1\n")
        val target = File(tempDir, "target.txt").apply { writeText("not mine to change\n") }
        File(repo, "a.dat").delete()
        File(repo, "a.dat").symlinkTo(target)

        assertInstanceOf(RestoreFileError.GitFailed::class.java, restoreError(repo, first, "a.dat"))

        assertTrue(Files.isSymbolicLink(File(repo, "a.dat").toPath()), "it's still a link")
        assertEquals(target.toPath(), Files.readSymbolicLink(File(repo, "a.dat").toPath()))
        assertEquals("not mine to change\n", target.readText())
        assertNoBackupLeft(repo)
    }

    @Test
    fun `a file that was not there stays not there when git fails`(): Unit = runBlocking {
        val repo = repository()
        git.run(repo, "config", "filter.boom.clean", "cat")
        git.run(repo, "config", "filter.boom.smudge", "false")
        git.run(repo, "config", "filter.boom.required", "true")
        val first = commit(repo, "add", ".gitattributes" to "*.dat filter=boom\n", "a.dat" to "version 1\n")
        File(repo, "a.dat").delete()

        assertInstanceOf(RestoreFileError.GitFailed::class.java, restoreError(repo, first, "a.dat"))

        assertFalse(File(repo, "a.dat").exists())
        assertNoBackupLeft(repo)
    }

    @Test
    fun `cancelling puts the file back`(): Unit = runBlocking {
        val repo = repository()
        git.run(repo, "config", "filter.slow.smudge", "sleep 30; cat")
        git.run(repo, "config", "filter.slow.clean", "cat")
        git.run(repo, "config", "filter.slow.required", "true")
        val first = commit(repo, "add", ".gitattributes" to "*.slow filter=slow\n", "a.slow" to "version 1\n")
        commit(repo, "edit", "a.slow" to "version 2\n")
        File(repo, "a.slow").writeText("my unsaved work\n")

        val running = async(Dispatchers.IO) { restoreAction(gitDir(repo), first, "a.slow") }
        // The file is out of the way while git waits for its filter
        withTimeout(20.seconds) {
            while (File(repo, "a.slow").exists()) {
                delay(50)
            }
        }
        running.cancelAndJoin()

        assertEquals("my unsaved work\n", File(repo, "a.slow").readText())
        assertNoBackupLeft(repo)
    }

    @Test
    fun `a Git LFS file is written as its content, not as the pointer`(): Unit = runBlocking {
        assumeTrue(hasGitLfs()) { "git-lfs isn't installed" }
        val repo = repository()
        git.run(repo, "lfs", "install", "--local")
        git.run(repo, "lfs", "track", "*.bin")
        val first = commit(repo, "add", "big.bin" to "x".repeat(3000))
        commit(repo, "edit", "big.bin" to "y".repeat(3000))
        assertTrue(git.run(repo, "show", "$first:big.bin").startsWith("version https://git-lfs"), "the tree has a pointer")

        assertSameAsGit(repo, first, "big.bin")

        assertEquals("x".repeat(3000), File(repo, "big.bin").readText())
    }

    @Test
    fun `a Git LFS file whose object can't be had is put back, where git alone deletes it`(): Unit = runBlocking {
        assumeTrue(hasGitLfs()) { "git-lfs isn't installed" }
        val repo = repository()
        git.run(repo, "lfs", "install", "--local")
        git.run(repo, "lfs", "track", "*.bin")
        val first = commit(repo, "add", "big.bin" to "x".repeat(3000))
        commit(repo, "edit", "big.bin" to "y".repeat(3000))
        FileUtils.delete(File(repo, ".git/lfs/objects"), FileUtils.RECURSIVE)
        File(repo, "big.bin").writeText("my unsaved work\n")

        val error = restoreError(repo, first, "big.bin")

        assertInstanceOf(RestoreFileError.GitFailed::class.java, error)
        assertEquals("my unsaved work\n", File(repo, "big.bin").readText())
        assertNoBackupLeft(repo)
    }

    @Test
    fun `a partial clone downloads the version it lacks`(): Unit = runBlocking {
        val (source, clone, versions) = partialClone()
        val before = missingObjects(clone)

        File(clone, "f.txt").writeText("my unsaved work\n")
        assertInstanceOf(Either.Ok::class.java, restoreAction(gitDir(clone), versions[0], "f.txt"))

        assertEquals("version 1\n", File(clone, "f.txt").readText())
        assertTrue(missingObjects(clone) < before, "the blob was downloaded")
        assertNoBackupLeft(clone)
        assertTrue(source.exists())
    }

    @Test
    fun `a partial clone that can't download it keeps the file, where git alone empties it`(): Unit = runBlocking {
        val (source, clone, versions) = partialClone()
        File(clone, "f.txt").writeText("my unsaved work\n")
        assertTrue(source.renameTo(File(tempDir, "source-gone")))

        // What plain git does when it can't download the blob
        val twin = File(tempDir, "twin")
        copyKeepingModes(clone, twin)
        git.runFailing(twin, "--literal-pathspecs", "restore", "--source=${versions[0]}", "--worktree", "--", "f.txt")
        assertEquals(0L, File(twin, "f.txt").length(), "git alone empties the file")

        val error = restoreError(clone, versions[0], "f.txt")

        assertInstanceOf(RestoreFileError.GitFailed::class.java, error)
        assertEquals("my unsaved work\n", File(clone, "f.txt").readText())
        assertNoBackupLeft(clone)
    }

    private fun repository(): File {
        val repo = File(tempDir, "repo").apply { mkdirs() }
        git.run(repo, "init")

        return repo
    }

    /** Writes [files], commits them, and returns the commit's hash. */
    private fun commit(repo: File, message: String, vararg files: Pair<String, String>): String {
        for ((path, content) in files) {
            File(repo, path).apply { parentFile.mkdirs() }.writeText(content)
        }

        git.run(repo, "add", "-A")
        git.run(repo, "commit", "-m", message)

        return git.run(repo, "rev-parse", "HEAD").trim()
    }

    private fun gitDir(repo: File) = File(repo, ".git").absolutePath

    private suspend fun plan(repo: File, commit: String, path: String): FileRestorePlan =
        (planAction(gitDir(repo), commit, path) as Either.Ok).value

    private suspend fun restore(repo: File, commit: String, path: String) {
        val result = restoreAction(gitDir(repo), commit, path)

        assertInstanceOf(Either.Ok::class.java, result, result.toString())
    }

    private suspend fun restoreError(repo: File, commit: String, path: String): GitError =
        (restoreAction(gitDir(repo), commit, path) as Either.Err).error

    /**
     * Restores in [repo] and in a copy that git restores alone, and checks that the two end up the same: the files'
     * bytes and modes, the status and the index.
     */
    private suspend fun assertSameAsGit(repo: File, commit: String, path: String) {
        val twin = File(tempDir, "twin-${System.nanoTime()}")
        copyKeepingModes(repo, twin)

        git.run(twin, "--literal-pathspecs", "restore", "--source=$commit", "--worktree", "--", path)
        restore(repo, commit, path)

        assertEquals(snapshot(twin), snapshot(repo), path)
        assertEquals(git.run(twin, "status", "--porcelain=v1", "-z"), git.run(repo, "status", "--porcelain=v1", "-z"))
        assertEquals(git.run(twin, "ls-files", "-s"), git.run(repo, "ls-files", "-s"))
        assertNoBackupLeft(repo)
    }

    /** A copy of [from] with the files' modes and links, which Kotlin's copyRecursively loses. */
    private fun copyKeepingModes(from: File, to: File) {
        val process = ProcessBuilder("cp", "-Rp", from.absolutePath, to.absolutePath).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()

        check(process.waitFor() == 0) { "cp failed: $output" }
    }

    /** Every file outside `.git`, by path: its bytes, whether it's executable, and where a link points. */
    private fun snapshot(root: File): Map<String, String> = root.walkTopDown()
        .onEnter { it.name != ".git" }
        .filter { it.isFile || Files.isSymbolicLink(it.toPath()) }
        .associate { file ->
            file.relativeTo(root).path to when {
                Files.isSymbolicLink(file.toPath()) -> "link to ${Files.readSymbolicLink(file.toPath())}"
                else -> "${file.readBytes().contentHashCode()} ${file.canExecute()}"
            }
        }

    private fun assertNoBackupLeft(repo: File) = assertNoBackupLeftIn(File(repo, ".git"))

    private fun assertNoBackupLeftIn(gitDir: File) {
        assertFalse(
            Files.exists(File(gitDir, RESTORE_BACKUP_FOLDER).toPath(), LinkOption.NOFOLLOW_LINKS),
            "the original waits in ${File(gitDir, RESTORE_BACKUP_FOLDER)}",
        )
    }

    private fun hasGitLfs(): Boolean = try {
        val process = ProcessBuilder("git", "lfs", "version").redirectErrorStream(true).start()
        process.inputStream.readBytes()
        process.waitFor() == 0
    } catch (_: Exception) {
        false
    }

    /** A source with a file in three versions, and a `--filter=blob:none` clone that has only the newest. */
    private fun partialClone(): Triple<File, File, List<String>> {
        val source = File(tempDir, "source").apply { mkdirs() }
        git.run(source, "init")
        git.run(source, "config", "uploadpack.allowFilter", "true")
        val versions = (1..3).map { commit(source, "version $it", "f.txt" to "version $it\n") }
        git.run(tempDir, "clone", "--filter=blob:none", "file://${source.absolutePath}", "clone")

        return Triple(source, File(tempDir, "clone"), versions)
    }

    /** How many objects the clone's commits and trees name that it doesn't have. */
    private fun missingObjects(clone: File): Int =
        git.run(clone, "rev-list", "--objects", "--missing=print", "--all").lines().count { it.startsWith("?") }
}
