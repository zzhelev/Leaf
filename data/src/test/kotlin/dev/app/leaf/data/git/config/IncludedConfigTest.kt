// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.data.git.config

import dev.app.leaf.data.git.signers.configOf
import dev.app.leaf.data.git.symlinkTo
import dev.app.leaf.data.git.testGitCli
import dev.app.leaf.data.shell.LoginShellEnvironment
import dev.app.leaf.domain.errors.Either
import kotlinx.coroutines.runBlocking
import org.eclipse.jgit.storage.file.FileRepositoryBuilder
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.DisabledOnOs
import org.junit.jupiter.api.condition.OS
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * What git reads through `includeIf` ([GitIncludedConfig]), from git's own list of a repository's config: git is the
 * reference for which files apply and where their paths lead.
 */
@DisabledOnOs(OS.WINDOWS)
class IncludedConfigTest {
    @TempDir
    lateinit var tempDir: File

    // git matches includeIf's gitdir against the real path, which on macOS starts with /private
    private val root by lazy { tempDir.canonicalFile }
    private val home by lazy { File(root, "home").apply { mkdirs() } }
    private val globalConfig by lazy { File(home, ".gitconfig") }

    /** What the login shell adds: git reads [globalConfig] and no system config, and `~` is [home]. */
    private val shellVariables by lazy {
        mapOf(
            "GIT_CONFIG_GLOBAL" to globalConfig.path,
            "GIT_CONFIG_NOSYSTEM" to "1",
            "HOME" to home.path,
            "XDG_CONFIG_HOME" to File(root, "xdg").path,
        )
    }

    private val gitCli by lazy { testGitCli(shellVariables) }
    private val source by lazy { GitIncludedConfig(gitCli, LoginShellEnvironment { shellVariables }) }

    @Test
    fun `has the entries of the files that includeIf names, and none that JGit reads itself`() {
        val work = file("work.gitconfig", "[user]\n\tname = Work Name\n")
        val plain = file("plain.gitconfig", "[alias]\n\tst = status\n")
        val other = file("other.gitconfig", "[user]\n\temail = other@example.com\n")
        globalConfig.writeText(
            "[user]\n\tname = Personal Name\n" +
                "[include]\n\tpath = ${plain.path}\n" +
                includeIf("gitdir:${File(root, "work").path}/", work) +
                includeIf("gitdir:${File(root, "other").path}/", other)
        )
        val repository = initRepository(File(root, "work/repo"))

        assertEquals(listOf("user.name=Work Name"), entries(repository))
        assertEquals("Work Name", git(repository, "config", "user.name").trim())
    }

    @Test
    fun `has what a file that includeIf names includes in turn`() {
        file("nested.gitconfig", "[user]\n\temail = work@example.com\n")
        val work = file("work.gitconfig", "[include]\n\tpath = nested.gitconfig\n[user]\n\tname = Work Name\n")
        globalConfig.writeText(includeIf("gitdir:${File(root, "work").path}/", work))
        val repository = initRepository(File(root, "work/repo"))

        assertEquals(listOf("user.email=work@example.com", "user.name=Work Name"), entries(repository))
    }

    @Test
    fun `has what includeIf brings into the repository's own config, for its branch`() {
        val repository = initRepository(File(root, "repo"))
        file("release.gitconfig", "[user]\n\temail = release@example.com\n")
        File(repository, ".git/config").appendText(includeIf("onbranch:release/**", "../../release.gitconfig"))

        assertEquals(emptyList<String>(), entries(repository))

        git(repository, "switch", "-q", "-c", "release/1")
        assertEquals(listOf("user.email=release@example.com"), entries(repository))
    }

    @Test
    fun `finds included files where git does, from ~ and next to a config file that is a symbolic link`() {
        val dotfiles = File(root, "dotfiles")
        file("dotfiles/gitconfig", includeIf("gitdir:${File(root, "work").path}/", "work.gitconfig"))
        globalConfig.symlinkTo(File(dotfiles, "gitconfig"))
        // git looks for a relative path next to the file it read, ~/.gitconfig, and not in the link's target folder
        File(home, "work.gitconfig").writeText("[user]\n\tname = Next To The Link\n")
        File(dotfiles, "work.gitconfig").writeText("[user]\n\tname = In The Target Folder\n")
        File(dotfiles, "gitconfig").appendText(includeIf("gitdir:${File(root, "work").path}/", "~/tilde.gitconfig"))
        File(home, "tilde.gitconfig").writeText("[user]\n\temail = tilde@example.com\n")
        val repository = initRepository(File(root, "work/repo"))

        assertEquals(listOf("user.name=Next To The Link", "user.email=tilde@example.com"), entries(repository))
        assertEquals("Next To The Link", git(repository, "config", "user.name").trim())
    }

    @Test
    fun `a value that the user's config sets again after the include stays the last one, as in git`() {
        val work = file("work.gitconfig", "[user]\n\temail = work@example.com\n\tname = Work Name\n")
        globalConfig.writeText(
            includeIf("gitdir:${File(root, "work").path}/", work) + "[user]\n\temail = personal@example.com\n"
        )
        val repository = initRepository(File(root, "work/repo"))

        val config = configOf(read(repository).entries)

        assertEquals(git(repository, "config", "user.email").trim(), config.getString("user", null, "email"))
        assertEquals("personal@example.com", config.getString("user", null, "email"))
        assertEquals("Work Name", config.getString("user", null, "name"))
    }

    @Test
    fun `watches what git read, the files that includes name, and the repository's HEAD and config`() {
        val work = file("work.gitconfig", "[user]\n\tname = Work Name\n")
        val missing = File(root, "not-yet.gitconfig")
        globalConfig.writeText(
            includeIf("gitdir:${File(root, "work").path}/", work) +
                includeIf("gitdir:${File(root, "work").path}/", missing)
        )
        val repository = initRepository(File(root, "work/repo"))

        val files = read(repository).files.map { it.canonicalPath }.toSet()

        val gitDir = File(repository, ".git")
        val expected = listOf(globalConfig, work, missing, File(gitDir, "HEAD"), File(gitDir, "config"))

        for (file in expected) {
            assertTrue(file.canonicalPath in files, "$file isn't watched: $files")
        }
    }

    @Test
    fun `without a usable git, has no entries and still watches the repository's files`() {
        val work = file("work.gitconfig", "[user]\n\tname = Work Name\n")
        globalConfig.writeText(includeIf("gitdir:${File(root, "work").path}/", work))
        val repository = initRepository(File(root, "work/repo"))
        val withoutGit = testGitCli(shellVariables, configuredPath = File(root, "missing/git").path)

        val included = read(repository, GitIncludedConfig(withoutGit, LoginShellEnvironment { shellVariables }))

        assertEquals(emptyList<String>(), included.entries.map { "${it.key}=${it.value}" })
        assertTrue(File(repository, ".git/HEAD").canonicalPath in included.files.map { it.canonicalPath })
    }

    @Test
    fun `reads origins that git printed relative from the folder that it ran in`() {
        val runDirectory = File(root, "repo")
        val entries = listOf(
            OriginEntry("local", "file:.git/config", "core.bare", "false"),
            OriginEntry("local", "file:.git/config", "includeif.onbranch:main.path", "../../main.gitconfig"),
            OriginEntry("local", "file:.git/../../main.gitconfig", "user.name", "On Main"),
            OriginEntry("local", "file:.git/config", "core.filemode", "true"),
        )

        val included = includedConfig(entries, runDirectory, home.path)

        assertEquals(listOf("user.name=On Main"), included.entries.map { "${it.key}=${it.value}" })
        assertEquals(
            listOf(File(runDirectory, ".git/config"), File(runDirectory, ".git/../../main.gitconfig")),
            included.files,
        )
    }

    private fun includeIf(condition: String, file: File) = includeIf(condition, file.path)

    private fun includeIf(condition: String, path: String) = "[includeIf \"$condition\"]\n\tpath = $path\n"

    private fun file(name: String, content: String) = File(root, name).apply {
        parentFile.mkdirs()
        writeText(content)
    }

    private fun read(repository: File, source: IncludedConfigSource = this.source): IncludedConfig {
        return FileRepositoryBuilder().setGitDir(File(repository, ".git")).setMustExist(true).build().use {
            runBlocking { source.read(it) }
        }
    }

    private fun entries(repository: File) = read(repository).entries.map { "${it.key}=${it.value}" }

    private fun initRepository(directory: File): File {
        directory.mkdirs()
        git(directory, "init", "-q", "-b", "main")

        return directory
    }

    /** Runs git as Leaf runs it, with [shellVariables]. */
    private fun git(directory: File, vararg args: String): String = runBlocking {
        when (val result = gitCli.run(directory, args.toList())) {
            is Either.Ok -> result.value
            is Either.Err -> error("git ${args.joinToString(" ")} failed: ${result.error}")
        }
    }
}
