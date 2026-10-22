// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.data.git.diff

import dev.app.leaf.data.git.IsolatedSystemReader
import dev.app.leaf.data.git.TestGitCli
import dev.app.leaf.data.git.cli.remote.testPartialCloneFetch
import dev.app.leaf.data.git.testJGit
import dev.app.leaf.domain.errors.Either
import kotlinx.coroutines.runBlocking
import org.eclipse.jgit.diff.DiffEntry
import org.eclipse.jgit.diff.DiffEntry.ChangeType
import org.eclipse.jgit.util.SystemReader
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.DisabledOnOs
import org.junit.jupiter.api.condition.OS
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * Two versions of one file from its history (`docs/fork/compare.md`, 7.3), each pair checked against
 * `git diff --name-status A B -- <path>` and the blob ids that git gives for the path in each commit.
 */
class GetFileDiffBetweenCommitsGitActionTest {
    @TempDir
    lateinit var tempDir: File

    private val originalReader: SystemReader = SystemReader.getInstance()
    private val git by lazy { TestGitCli(File(tempDir, "config/global.gitconfig")) }
    private val jgit = testJGit()
    private val action by lazy { GetFileDiffBetweenCommitsGitAction(jgit, testPartialCloneFetch(jgit)) }

    private val repo by lazy { File(tempDir, "repo") }
    private val gitDir by lazy { File(repo, ".git").path }

    /** The commits, by name, in the order they were made. */
    private val commits = linkedMapOf<String, String>()

    @BeforeEach
    fun createRepository() {
        SystemReader.setInstance(IsolatedSystemReader(File(tempDir, "config"), originalReader))

        repo.mkdirs()
        git.run(repo, "init", "-q")
        write("other.txt", "other\n")
        commit("before")

        write("src/App.kt", "fun main() {}\n")
        commit("added")

        write("src/App.kt", "fun main() {\n    println(1)\n}\n")
        commit("first edit")

        write("other.txt", "other, edited\n")
        commit("elsewhere")

        write("src/App.kt", "fun main() {\n    println(2)\n}\n")
        commit("second edit")

        // Back to the first edit's version
        write("src/App.kt", "fun main() {\n    println(1)\n}\n")
        commit("reverted")

        File(repo, "src/App.kt").delete()
        commit("deleted")

        File(repo, "src/App.kt").mkdirs()
        write("src/App.kt/Inner.kt", "class Inner\n")
        commit("a folder in its place")
    }

    @AfterEach
    fun restoreReader() {
        SystemReader.setInstance(originalReader)
    }

    @Test
    fun `two edits apart, either way round, as git compares them`(): Unit = runBlocking {
        assertSameAsGit("added", "second edit", ChangeType.MODIFY)
        assertSameAsGit("second edit", "added", ChangeType.MODIFY)
        assertSameAsGit("first edit", "second edit", ChangeType.MODIFY)
    }

    @Test
    fun `from before the file to a version of it, and from a version to its deletion`(): Unit = runBlocking {
        assertSameAsGit("before", "first edit", ChangeType.ADD)
        assertSameAsGit("first edit", "deleted", ChangeType.DELETE)
    }

    @Test
    fun `the same version in both is no change, as git shows none`(): Unit = runBlocking {
        assertSameAsGit("first edit", "reverted", null)
        assertSameAsGit("before", "deleted", null)
    }

    @Test
    fun `a folder in the file's place shows the file deleted`(): Unit = runBlocking {
        assertSameAsGit("second edit", "a folder in its place", ChangeType.DELETE)
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    fun `a mode change of the same content is a change`(): Unit = runBlocking {
        git.run(repo, "update-index", "--chmod=+x", "other.txt")
        git.run(repo, "commit", "-q", "-m", "executable")
        commits["executable"] = git.run(repo, "rev-parse", "HEAD").trim()

        val entry = assertSameAsGit("elsewhere", "executable", ChangeType.MODIFY, path = "other.txt")!!

        assertEquals(entry.oldId, entry.newId)
    }

    @Test
    fun `a commit that doesn't exist is an error`(): Unit = runBlocking {
        val result = action(gitDir, "src/App.kt", commits.getValue("added"), "0123456789012345678901234567890123456789")

        assertInstanceOf(Either.Err::class.java, result)
    }

    /** The action's entry for [path] from [a] to [b], which must be what git shows, with git's blob ids. */
    private suspend fun assertSameAsGit(
        a: String,
        b: String,
        expected: ChangeType?,
        path: String = "src/App.kt",
    ): DiffEntry? {
        val aId = commits.getValue(a)
        val bId = commits.getValue(b)
        val gitStatus = git.run(repo, "diff", "--no-renames", "--name-status", aId, bId, "--", path).trim()
        val entry = (action(gitDir, path, aId, bId) as Either.Ok).value

        // git's line for the path itself: a folder in its place lists the folder's files too
        val gitLine = gitStatus.lines().firstOrNull { it.endsWith("\t$path") }

        assertEquals(expected?.let { "${it.name.first()}\t$path" }, gitLine, "git diff $a $b")

        if (expected == null) {
            assertNull(entry, "$a → $b")
            return null
        }

        entry!!
        assertEquals(expected, entry.changeType, "$a → $b")
        assertEquals(blobOf(aId, path), entry.oldId.name().takeIf { entry.changeType != ChangeType.ADD })
        assertEquals(blobOf(bId, path), entry.newId.name().takeIf { entry.changeType != ChangeType.DELETE })

        return entry
    }

    /** The blob of [path] in [commit], or null where it isn't a file there. */
    private fun blobOf(commit: String, path: String): String? {
        val line = git.run(repo, "ls-tree", commit, "--", path).trim()

        return line.takeIf { it.isNotEmpty() && it.split(" ")[1] == "blob" }?.split(" ")?.get(2)?.substringBefore('\t')
    }

    private fun write(path: String, text: String) {
        val file = File(repo, path)
        file.parentFile.mkdirs()
        file.writeText(text)
    }

    private fun commit(name: String) {
        git.run(repo, "add", "-A")
        git.run(repo, "commit", "-q", "--allow-empty", "-m", name)
        commits[name] = git.run(repo, "rev-parse", "HEAD").trim()
    }
}
