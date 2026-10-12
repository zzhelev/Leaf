// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.data.git.conflicts

import dev.app.leaf.data.git.IsolatedSystemReader
import dev.app.leaf.data.git.TestGitCli
import dev.app.leaf.data.git.testJGit
import dev.app.leaf.data.git.workspace.GetStatusGitAction
import dev.app.leaf.domain.errors.Either
import dev.app.leaf.domain.models.ConflictVersion.FILE
import dev.app.leaf.domain.models.ConflictVersion.SUBMODULE
import dev.app.leaf.domain.models.ConflictVersion.SYMLINK
import dev.app.leaf.domain.models.FileConflict
import dev.app.leaf.domain.models.Status
import dev.app.leaf.domain.models.StatusType
import kotlinx.coroutines.runBlocking
import org.eclipse.jgit.util.SystemReader
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.DisabledOnOs
import org.junit.jupiter.api.condition.OS
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Files

/** The status lists each conflicted file with what each side has at its path, which the conflict actions need. */
class ConflictStatusTest {
    @TempDir
    lateinit var tempDir: File

    private val originalReader: SystemReader = SystemReader.getInstance()
    private val git by lazy { TestGitCli(File(tempDir, "config/global.gitconfig")) }
    private val getStatus = GetStatusGitAction(testJGit())

    private val repository by lazy { git.initRepository(File(tempDir, "repo")) }

    @BeforeEach
    fun isolateJGitConfig() {
        SystemReader.setInstance(IsolatedSystemReader(File(tempDir, "config"), originalReader))
    }

    @AfterEach
    fun restoreSystemReader() {
        SystemReader.setInstance(originalReader)
    }

    @Test
    fun `each conflicted file has the versions of both sides, null where a side deleted it`(): Unit = runBlocking {
        git.mergeWithConflicts(repository)

        assertEquals(
            mapOf(
                "added.txt" to FileConflict(ours = FILE, theirs = FILE),
                "dropped.txt" to FileConflict(ours = null, theirs = FILE),
                "kept.txt" to FileConflict(ours = FILE, theirs = null),
                "notes.txt" to FileConflict(ours = FILE, theirs = FILE),
            ),
            conflicts(),
        )
    }

    @Test
    fun `a submodule that both sides changed`(): Unit = runBlocking {
        git.run(repository, "update-index", "--add", "--cacheinfo", "160000,${"1".repeat(40)},sub")
        git.run(repository, "commit", "-m", "Base")
        git.run(repository, "branch", "feature")
        git.run(repository, "update-index", "--cacheinfo", "160000,${"2".repeat(40)},sub")
        git.run(repository, "commit", "-m", "Main")
        git.run(repository, "switch", "feature")
        git.run(repository, "update-index", "--cacheinfo", "160000,${"3".repeat(40)},sub")
        git.run(repository, "commit", "-m", "Feature")
        git.run(repository, "switch", "main")
        git.runFailing(repository, "merge", "feature")

        assertEquals(mapOf("sub" to FileConflict(ours = SUBMODULE, theirs = SUBMODULE)), conflicts())
    }

    @Test
    fun `a file and a submodule at the same path are two conflicts, as git renames one of them`(): Unit =
        runBlocking {
            git.run(repository, "commit", "--allow-empty", "-m", "Base")
            git.run(repository, "branch", "feature")
            git.run(repository, "update-index", "--add", "--cacheinfo", "160000,${"1".repeat(40)},both")
            git.run(repository, "commit", "-m", "Main")
            git.run(repository, "switch", "feature")
            File(repository, "both").writeText("feature's file\n")
            git.run(repository, "add", "both")
            git.run(repository, "commit", "-m", "Feature")
            git.run(repository, "switch", "main")
            git.runFailing(repository, "merge", "feature")

            assertEquals(
                mapOf(
                    "both" to FileConflict(ours = SUBMODULE, theirs = null),
                    "both~feature" to FileConflict(ours = null, theirs = FILE),
                ),
                conflicts(),
            )
        }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    fun `a symbolic link that both sides changed`(): Unit = runBlocking {
        linkTo("base")
        git.run(repository, "add", "link")
        git.run(repository, "commit", "-m", "Base")
        git.run(repository, "branch", "feature")
        linkTo("main")
        git.run(repository, "commit", "-am", "Main")
        git.run(repository, "switch", "feature")
        linkTo("feature")
        git.run(repository, "commit", "-am", "Feature")
        git.run(repository, "switch", "main")
        git.runFailing(repository, "merge", "feature")

        assertEquals(mapOf("link" to FileConflict(ours = SYMLINK, theirs = SYMLINK)), conflicts())
    }

    @Test
    fun `entries without conflicts have none`(): Unit = runBlocking {
        git.mergeWithConflicts(repository)
        File(repository, "README.md").appendText("More\n")

        val status = status()

        assertEquals(null, status.unstaged.single { it.filePath == "README.md" }.conflict)
        assertTrue(status.staged.all { it.conflict == null })
    }

    private fun linkTo(target: String) {
        val link = File(repository, "link").toPath()

        Files.deleteIfExists(link)
        Files.createSymbolicLink(link, File(target).toPath())
    }

    private suspend fun status(): Status = (getStatus(File(repository, ".git").path, emptyList()) as Either.Ok).value

    private suspend fun conflicts() = status().unstaged
        .filter { it.statusType == StatusType.CONFLICTING }
        .associate { it.filePath to it.conflict }
}
