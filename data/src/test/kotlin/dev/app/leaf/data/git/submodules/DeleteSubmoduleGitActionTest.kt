// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.data.git.submodules

import dev.app.leaf.data.git.IsolatedSystemReader
import dev.app.leaf.data.git.TestGitCli
import dev.app.leaf.data.git.symlinkTo
import dev.app.leaf.data.git.testJGit
import dev.app.leaf.domain.errors.Either
import kotlinx.coroutines.runBlocking
import org.eclipse.jgit.util.FileUtils
import org.eclipse.jgit.util.SystemReader
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.DisabledOnOs
import org.junit.jupiter.api.condition.OS
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.LinkOption
import kotlin.io.path.isSymbolicLink
import kotlin.io.path.notExists

/**
 * Deleting a submodule removes its folder and its git dir. Kotlin's deleteRecursively, which it used before, follows
 * symbolic links: it emptied the folder that a link in either of them pointed to, even outside the repository.
 */
@DisabledOnOs(OS.WINDOWS) // Creating symbolic links needs Developer Mode or admin rights on Windows
class DeleteSubmoduleGitActionTest {
    @TempDir
    lateinit var tempDir: File

    private val originalReader: SystemReader = SystemReader.getInstance()
    private val git by lazy { TestGitCli(File(tempDir, "config/global.gitconfig")) }
    private val deleteSubmodule = DeleteSubmoduleGitAction(testJGit())

    @BeforeEach
    fun isolateJGitConfig() {
        SystemReader.setInstance(IsolatedSystemReader(File(tempDir, "config"), originalReader))
    }

    @AfterEach
    fun restoreSystemReader() {
        SystemReader.setInstance(originalReader)
    }

    @Test
    fun `links in the submodule are deleted, not the files they point to`(): Unit = runBlocking {
        val outside = folderWithFile("outside")
        val superproject = superprojectWithSubmodule(linkInLibrary = outside)
        val submodule = File(superproject, "lib")
        File(submodule, "untracked-link").symlinkTo(outside)
        File(submodule, "folder/link").symlinkTo(outside)

        assertTrue(File(submodule, "tracked-link").toPath().isSymbolicLink(), "git checked out the library's link")

        assertInstanceOf(Either.Ok::class.java, deleteSubmodule(gitDir(superproject), "lib"))

        assertTrue(submodule.toPath().notExists(LinkOption.NOFOLLOW_LINKS), "The submodule's folder is deleted")
        assertEquals("keep", File(outside, "keep.txt").readText())
        assertFalse(git.run(superproject, "ls-files").lines().contains("lib"), "The submodule is unstaged")
    }

    @Test
    fun `a link in the submodule's git dir is deleted, not the folder it points to`(): Unit = runBlocking {
        val sharedHooks = folderWithFile("shared-hooks")
        val superproject = superprojectWithSubmodule(linkInLibrary = null)
        val moduleDir = File(superproject, ".git/modules/lib")

        // Hooks shared between repositories, with a link in place of the hooks folder
        val hooks = File(moduleDir, "hooks")
        FileUtils.delete(hooks, FileUtils.RECURSIVE)
        hooks.symlinkTo(sharedHooks)

        assertInstanceOf(Either.Ok::class.java, deleteSubmodule(gitDir(superproject), "lib"))

        assertTrue(moduleDir.toPath().notExists(LinkOption.NOFOLLOW_LINKS), "The submodule's git dir is deleted")
        assertEquals("keep", File(sharedHooks, "keep.txt").readText())
    }

    /**
     * `super`, with the submodule `lib`. With [linkInLibrary], the library has the committed link `tracked-link` to
     * it, which git checks out in the submodule as it is.
     */
    private fun superprojectWithSubmodule(linkInLibrary: File?): File {
        val library = git.initRepository(File(tempDir, "library"))

        if (linkInLibrary != null) {
            File(library, "tracked-link").symlinkTo(linkInLibrary)
            git.run(library, "add", ".")
            git.run(library, "commit", "-m", "Link")
        }

        val superproject = git.initRepository(File(tempDir, "super"))
        git.run(superproject, "submodule", "add", "file://${library.absolutePath}", "lib")
        git.run(superproject, "commit", "-m", "Submodule")

        return superproject
    }

    private fun folderWithFile(name: String): File {
        val folder = File(tempDir, name).apply { mkdirs() }
        File(folder, "keep.txt").writeText("keep")

        return folder
    }

    private fun gitDir(repository: File) = File(repository, ".git").absolutePath
}
