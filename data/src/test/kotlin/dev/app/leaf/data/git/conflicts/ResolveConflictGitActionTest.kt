// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.data.git.conflicts

import dev.app.leaf.data.git.IsolatedSystemReader
import dev.app.leaf.data.git.TestGitCli
import dev.app.leaf.data.git.testGitCli
import dev.app.leaf.data.git.testJGit
import dev.app.leaf.domain.errors.ConflictError
import dev.app.leaf.domain.errors.Either
import dev.app.leaf.domain.errors.GitError
import dev.app.leaf.domain.models.ConflictSide
import kotlinx.coroutines.runBlocking
import org.eclipse.jgit.util.SystemReader
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * "Use ours" and "Use theirs": the file as that side has it, staged, or its deletion when that side deleted it. Each
 * case is compared with what git's own commands leave in a copy of the repository.
 */
class ResolveConflictGitActionTest {
    @TempDir
    lateinit var tempDir: File

    private val originalReader: SystemReader = SystemReader.getInstance()
    private val globalConfig by lazy { File(tempDir, "config/global.gitconfig") }
    private val git by lazy { TestGitCli(globalConfig) }
    private val isolatedGit by lazy { mapOf("GIT_CONFIG_GLOBAL" to globalConfig.path, "GIT_CONFIG_NOSYSTEM" to "1") }
    private val resolveConflict by lazy { ResolveConflictGitAction(testJGit(), testGitCli(isolatedGit)) }

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
    fun `ours takes our version of a file that both sides changed, and stages it`(): Unit = runBlocking {
        git.mergeWithConflicts(repository)

        assertEquals(Either.Ok(Unit), resolve(ConflictSide.OURS, "notes.txt"))

        assertEquals(MAIN_NOTES, File(repository, "notes.txt").readText())
        assertEquals(listOf(0), git.stages(repository, "notes.txt"))
        assertEquals(MAIN_NOTES, git.staged(repository, "notes.txt"))
    }

    @Test
    fun `theirs takes their version of a file that both sides changed, and stages it`(): Unit = runBlocking {
        git.mergeWithConflicts(repository)

        assertEquals(Either.Ok(Unit), resolve(ConflictSide.THEIRS, "notes.txt"))

        assertEquals(FEATURE_NOTES, File(repository, "notes.txt").readText())
        assertEquals(FEATURE_NOTES, git.staged(repository, "notes.txt"))
    }

    @Test
    fun `edits made to the conflicted file are replaced by the chosen side`(): Unit = runBlocking {
        git.mergeWithConflicts(repository)
        File(repository, "notes.txt").writeText("half resolved\n")

        resolve(ConflictSide.OURS, "notes.txt")

        assertEquals(MAIN_NOTES, File(repository, "notes.txt").readText())
    }

    @Test
    fun `a file that they deleted is kept with ours, and deleted with theirs`(): Unit = runBlocking {
        git.mergeWithConflicts(repository)

        resolve(ConflictSide.OURS, "kept.txt")

        assertEquals("main's kept\n", git.staged(repository, "kept.txt"))
        assertEquals("main's kept\n", File(repository, "kept.txt").readText())

        git.run(repository, "merge", "--abort")
        git.runFailing(repository, "merge", "feature")

        resolve(ConflictSide.THEIRS, "kept.txt")

        assertEquals(emptyList<Int>(), git.stages(repository, "kept.txt"))
        assertFalse(File(repository, "kept.txt").exists())
    }

    @Test
    fun `a file that we deleted is deleted with ours, and kept with theirs`(): Unit = runBlocking {
        git.mergeWithConflicts(repository)

        resolve(ConflictSide.OURS, "dropped.txt")

        assertEquals(emptyList<Int>(), git.stages(repository, "dropped.txt"))
        assertFalse(File(repository, "dropped.txt").exists())

        git.run(repository, "merge", "--abort")
        git.runFailing(repository, "merge", "feature")

        resolve(ConflictSide.THEIRS, "dropped.txt")

        assertEquals("feature's dropped\n", git.staged(repository, "dropped.txt"))
        assertEquals("feature's dropped\n", File(repository, "dropped.txt").readText())
    }

    @Test
    fun `keeping the working file stages it as it is, edits included, as git mergetool keeps a modified file`(): Unit =
        runBlocking {
            git.mergeWithConflicts(repository)
            File(repository, "dropped.txt").appendText("edited\n")

            resolve(ConflictSide.THEIRS, "dropped.txt", keepWorkingFile = true)

            assertEquals("feature's dropped\nedited\n", git.staged(repository, "dropped.txt"))
            assertEquals("feature's dropped\nedited\n", File(repository, "dropped.txt").readText())
        }

    @Test
    fun `keeping a working file that isn't there takes the side's version, and the deleting side still deletes`(): Unit =
        runBlocking {
            git.mergeWithConflicts(repository)
            File(repository, "dropped.txt").delete()

            resolve(ConflictSide.THEIRS, "dropped.txt", keepWorkingFile = true)
            resolve(ConflictSide.THEIRS, "kept.txt", keepWorkingFile = true)

            assertEquals("feature's dropped\n", git.staged(repository, "dropped.txt"))
            assertEquals("feature's dropped\n", File(repository, "dropped.txt").readText())
            assertEquals(emptyList<Int>(), git.stages(repository, "kept.txt"))
            assertFalse(File(repository, "kept.txt").exists())
        }

    @Test
    fun `a folder in the kept file's place isn't kept, and the side's version is checked out, as Use theirs does`(): Unit =
        runBlocking {
            git.mergeWithConflicts(repository)
            File(repository, "dropped.txt").delete()
            File(repository, "dropped.txt/inner.txt").apply { parentFile.mkdirs() }.writeText("inner\n")

            resolve(ConflictSide.THEIRS, "dropped.txt", keepWorkingFile = true)

            assertEquals("feature's dropped\n", git.staged(repository, "dropped.txt"))
            assertEquals("", git.run(repository, "ls-files", "dropped.txt/inner.txt"))
        }

    @Test
    fun `the index is the one that git's own commands leave`(): Unit = runBlocking {
        git.mergeWithConflicts(repository)
        val copy = File(tempDir, "copy")
        repository.copyRecursively(copy)

        resolve(ConflictSide.THEIRS, "notes.txt", "kept.txt", "dropped.txt", "added.txt")

        git.run(copy, "checkout", "--theirs", "--", "notes.txt", "dropped.txt", "added.txt")
        git.run(copy, "add", "--", "notes.txt", "dropped.txt", "added.txt")
        git.run(copy, "rm", "--", "kept.txt")

        assertEquals(git.run(copy, "ls-files", "--stage"), git.run(repository, "ls-files", "--stage"))
        assertEquals(git.run(copy, "status", "--porcelain"), git.run(repository, "status", "--porcelain"))
        assertEquals("", git.run(repository, "diff", "--name-only", "--diff-filter=U"))
    }

    @Test
    fun `a name that would be a pattern resolves that file only`(): Unit = runBlocking {
        git.mergeWithConflictsIn(repository, "[ab].txt", "a.txt")
        File(repository, "a.txt").writeText("half resolved\n")

        resolve(ConflictSide.THEIRS, "[ab].txt")

        assertEquals(listOf(0), git.stages(repository, "[ab].txt"))
        assertEquals(listOf(1, 2, 3), git.stages(repository, "a.txt"))
        assertEquals("half resolved\n", File(repository, "a.txt").readText())
    }

    @Test
    fun `a submodule takes the side's commit, which git add wouldn't, as the submodule isn't checked out`(): Unit =
        runBlocking {
            val (ours, theirs) = submoduleConflict()

            resolve(ConflictSide.THEIRS, "sub")

            assertEquals("160000 $theirs 0\tsub", git.run(repository, "ls-files", "--stage", "--", "sub").trim())

            git.run(repository, "merge", "--abort")
            git.runFailing(repository, "merge", "feature")

            resolve(ConflictSide.OURS, "sub")

            assertEquals("160000 $ours 0\tsub", git.run(repository, "ls-files", "--stage", "--", "sub").trim())
        }

    @Test
    fun `a file without conflicts stops it before anything changes`(): Unit = runBlocking {
        git.mergeWithConflicts(repository)
        git.run(repository, "add", "notes.txt")

        val result = resolve(ConflictSide.OURS, "added.txt", "notes.txt")

        assertEquals(Either.Err(ConflictError.NotConflicted(listOf("notes.txt"))), result)
        assertEquals(listOf(2, 3), git.stages(repository, "added.txt"))
    }

    @Test
    fun `in a linked worktree, the worktree's conflicts are resolved`(): Unit = runBlocking {
        git.run(repository, "branch", "work")
        val worktree = File(tempDir, "work-tree")
        git.run(repository, "worktree", "add", worktree.path, "work")
        git.mergeWithConflicts(worktree)

        val result = resolveConflict(
            File(repository, ".git/worktrees/work-tree").path,
            listOf("notes.txt"),
            ConflictSide.THEIRS,
        )

        assertEquals(Either.Ok(Unit), result)
        assertEquals(FEATURE_NOTES, git.staged(worktree, "notes.txt"))
        assertEquals(listOf(1, 2), git.stages(worktree, "kept.txt"))
        assertEquals("", git.run(repository, "status", "--porcelain"))
    }

    private suspend fun resolve(
        side: ConflictSide,
        vararg paths: String,
        keepWorkingFile: Boolean = false,
    ): Either<Unit, GitError> = resolveConflict(File(repository, ".git").path, paths.toList(), side, keepWorkingFile)

    /**
     * A conflict in the submodule `sub`, whose commits main and feature changed in different ways. The submodule isn't
     * there (its commits are made up), so git can't merge it. Returns ours and theirs.
     */
    private fun submoduleConflict(): Pair<String, String> {
        val (base, ours, theirs) = listOf("1", "2", "3").map { it.repeat(40) }

        git.run(repository, "update-index", "--add", "--cacheinfo", "160000,$base,sub")
        git.run(repository, "commit", "-m", "Base")
        git.run(repository, "branch", "feature")
        git.run(repository, "update-index", "--cacheinfo", "160000,$ours,sub")
        git.run(repository, "commit", "-m", "Main")
        git.run(repository, "switch", "feature")
        git.run(repository, "update-index", "--cacheinfo", "160000,$theirs,sub")
        git.run(repository, "commit", "-m", "Feature")
        git.run(repository, "switch", "main")
        git.runFailing(repository, "merge", "feature")

        return ours to theirs
    }
}
