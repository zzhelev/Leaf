// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.data.git.workspace

import dev.app.leaf.data.git.IsolatedSystemReader
import dev.app.leaf.data.git.TestGitCli
import dev.app.leaf.data.git.symlinkTo
import dev.app.leaf.data.git.testGitCli
import dev.app.leaf.data.git.testJGit
import dev.app.leaf.data.shell.LoginShellEnvironment
import dev.app.leaf.domain.errors.Either
import dev.app.leaf.domain.errors.IgnoreError
import dev.app.leaf.domain.ignore.IgnoreContext
import dev.app.leaf.domain.ignore.IgnorePlace
import dev.app.leaf.domain.models.StatusType
import kotlinx.coroutines.runBlocking
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
import java.nio.file.Files

/**
 * The global ignore list, the third place of "Ignore…" (fork-only): the file that git reads for every repository, found
 * as git finds it, and checked against git and Leaf's own status like the places of the repository.
 */
@DisabledOnOs(OS.WINDOWS)
class AddGlobalIgnoreRulesGitActionTest {
    @TempDir
    lateinit var tempDir: File

    private val originalReader: SystemReader = SystemReader.getInstance()
    private val config by lazy { File(tempDir, "config") }
    private val gitConfig by lazy { File(config, "global.gitconfig") }
    private val git by lazy { TestGitCli(gitConfig) }
    private val home by lazy { File(tempDir, "home").apply { mkdirs() } }
    private val jgit = testJGit()
    private val getStatus = GetStatusGitAction(jgit)

    /** The global list that the user's config names, which most tests use. */
    private val globalList by lazy { File(tempDir, "dotfiles/global-ignore") }

    @BeforeEach
    fun isolateConfig() {
        config.mkdirs()
        useExcludesFile(globalList.path)
    }

    @AfterEach
    fun restoreSystemReader() {
        SystemReader.setInstance(originalReader)
    }

    @Test
    fun `rules go to the file that core excludesFile names, and every repository ignores them`(): Unit = runBlocking {
        val first = repository("first")
        val second = repository("second")
        val firstFiles = create(first, "a.log", "keep.txt")
        val secondFiles = create(second, "b.log", "sub/c.log", "keep.txt")

        add(first, IgnorePlace.GLOBAL, listOf("*.log"))

        assertEquals("*.log\n", globalList.readText(), "The folder of the file is created too")
        assertFalse(File(first, ".gitignore").exists())
        assertIgnored(first, firstFiles, setOf("a.log"))
        assertIgnored(second, secondFiles, setOf("b.log", "sub/c.log"))
    }

    @Test
    fun `an existing global list keeps what it has, and gets a rule that is not there yet`(): Unit = runBlocking {
        val repository = repository("repo")
        val files = create(repository, "a.log", "b.tmp", "keep.txt")
        globalList.parentFile.mkdirs()
        globalList.writeBytes("*~\r\n.DS_Store".toByteArray())

        add(repository, IgnorePlace.GLOBAL, listOf("*.log", ".DS_Store", "*.tmp"))

        assertEquals("*~\r\n.DS_Store\r\n*.log\r\n*.tmp\r\n", String(globalList.readBytes()))
        assertIgnored(repository, files, setOf("a.log", "b.tmp"))
    }

    @Test
    fun `a tilde in the setting is the home that git runs with`(): Unit = runBlocking {
        useExcludesFile("~/dotfiles-ignore")
        val repository = repository("repo")
        val files = create(repository, "a.log", "keep.txt")

        add(repository, IgnorePlace.GLOBAL, listOf("*.log"))

        assertEquals("*.log\n", File(home, "dotfiles-ignore").readText())
        // JGit expands the tilde with the real home, so only git is asked here
        assertEquals(files.toSet() - "a.log", visibleToGit(repository, mapOf("HOME" to home.path)) - ".gitignore")
    }

    @Test
    fun `without the setting the rules go to git's default file in the XDG config home`(): Unit = runBlocking {
        useExcludesFile(null)
        val xdg = File(tempDir, "xdg")
        val env = mapOf("XDG_CONFIG_HOME" to xdg.path)
        val repository = repository("repo")
        val files = create(repository, "a.log", "keep.txt")

        add(repository, IgnorePlace.GLOBAL, listOf("*.log"), env)

        assertEquals("*.log\n", File(xdg, "git/ignore").readText())
        assertIgnored(repository, files, setOf("a.log"), env = env)
    }

    @Test
    fun `without the setting or an XDG config home the default file is in dot config of the home`(): Unit = runBlocking {
        useExcludesFile(null)
        val repository = repository("repo")
        val files = create(repository, "a.log", "keep.txt")

        add(repository, IgnorePlace.GLOBAL, listOf("*.log"))

        assertEquals("*.log\n", File(home, ".config/git/ignore").readText())
        assertEquals(files.toSet() - "a.log", visibleToGit(repository, mapOf("HOME" to home.path)) - ".gitignore")
    }

    @Test
    fun `a global list that is a link to a file, as dotfile managers make, is written through`(): Unit = runBlocking {
        val repository = repository("repo")
        val files = create(repository, "a.log", "keep.txt")
        val target = File(tempDir, "dotfiles-repo/ignore").apply { parentFile.mkdirs(); writeText("*~\n") }
        globalList.symlinkTo(target)

        add(repository, IgnorePlace.GLOBAL, listOf("*.log"))

        assertEquals("*~\n*.log\n", target.readText())
        assertTrue(Files.isSymbolicLink(globalList.toPath()), "The link stays a link")
        assertIgnored(repository, files, setOf("a.log"))
    }

    @Test
    fun `a global list that is a link to nothing, or a folder, or a relative path is refused`(): Unit = runBlocking {
        val repository = repository("repo")

        globalList.symlinkTo(File(tempDir, "nowhere"))
        assertRefused(repository, IgnoreError.Reason.NOT_A_FILE)
        assertFalse(File(tempDir, "nowhere").exists())

        globalList.delete()
        globalList.mkdirs()
        assertRefused(repository, IgnoreError.Reason.NOT_A_FILE)

        useExcludesFile("relative/ignore")
        assertRefused(repository, IgnoreError.Reason.NOT_ABSOLUTE)
        assertFalse(File(repository, "relative").exists(), "Nothing is made where the path would lead from the repository")
    }

    @Test
    fun `a repository can't point Leaf at a file by setting core excludesFile itself`(): Unit = runBlocking {
        val repository = repository("repo")
        val elsewhere = File(tempDir, "elsewhere/important").apply { parentFile.mkdirs(); writeText("keep\n") }
        git.run(repository, "config", "core.excludesFile", elsewhere.path)

        val result = addRules().invoke(gitDir(repository), IgnorePlace.GLOBAL, listOf("*.log"))

        val error = assertInstanceOf(IgnoreError.GlobalListOverridden::class.java, assertInstanceOf(Either.Err::class.java, result).error)
        assertEquals(elsewhere.path, error.path)
        assertEquals("keep\n", elsewhere.readText())
        assertFalse(globalList.exists())

        // The dialog reads the same problem, and the file git doesn't read here is not shown as usable
        assertInstanceOf(IgnoreError.GlobalListOverridden::class.java, context(repository).files.getValue(IgnorePlace.GLOBAL).problem)
    }

    @Test
    fun `the same setting in the repository's config as in the global one is fine`(): Unit = runBlocking {
        val repository = repository("repo")
        val files = create(repository, "a.log", "keep.txt")
        git.run(repository, "config", "core.excludesFile", globalList.path)

        add(repository, IgnorePlace.GLOBAL, listOf("*.log"))

        assertIgnored(repository, files, setOf("a.log"))
    }

    @Test
    fun `the global list is never remembered as the place of the repository`(): Unit = runBlocking {
        val repository = repository("repo")
        create(repository, "a.log")

        assertEquals(IgnorePlace.GITIGNORE, context(repository).remembered)

        add(repository, IgnorePlace.EXCLUDE, listOf("*.tmp"))
        add(repository, IgnorePlace.GLOBAL, listOf("*.log"))

        assertEquals(IgnorePlace.EXCLUDE, context(repository).remembered)
    }

    @Test
    fun `the context has the global list's path and lines`(): Unit = runBlocking {
        val repository = repository("repo")
        globalList.parentFile.mkdirs()
        globalList.writeText("*~\n.DS_Store\n")

        val file = context(repository).files.getValue(IgnorePlace.GLOBAL)

        assertEquals(globalList.canonicalPath, File(file.path).canonicalPath)
        assertEquals(listOf("*~", ".DS_Store"), file.lines)
        assertEquals(null, file.problem)
    }

    // ---- Helpers ----

    /** Sets `core.excludesFile` for git and for JGit, which read different files, or removes it with null. */
    private fun useExcludesFile(path: String?) {
        val settings = if (path == null) "" else "[core]\n\texcludesFile = $path\n"

        gitConfig.writeText(settings)
        File(config, "user.gitconfig").writeText(settings)
        SystemReader.setInstance(IsolatedSystemReader(config, originalReader))
    }

    private fun repository(name: String): File = git.initRepository(File(tempDir, name))

    private fun gitDir(repository: File) = File(repository, ".git").absolutePath

    private fun create(repository: File, vararg paths: String): List<String> {
        for (path in paths) {
            File(repository, path).apply { parentFile.mkdirs() }.writeText("x")
        }

        return paths.toList()
    }

    private fun addRules(env: Map<String, String> = emptyMap()): AddIgnoreRulesGitAction = AddIgnoreRulesGitAction(jgit, targets(env))

    private fun targets(env: Map<String, String>): IgnoreTargets {
        val variables = mapOf(
            "GIT_CONFIG_GLOBAL" to gitConfig.path,
            "GIT_CONFIG_NOSYSTEM" to "1",
            "HOME" to home.path,
            // An empty XDG_CONFIG_HOME counts as unset, and hides the one the machine has (CI sets it), which
            // the default file would otherwise be written into, ignoring `*.log` in every test that follows
            "XDG_CONFIG_HOME" to "",
        ) + env

        return IgnoreTargets(GlobalIgnoreFile(testGitCli(variables), LoginShellEnvironment { variables }))
    }

    private suspend fun add(repository: File, place: IgnorePlace, rules: List<String>, env: Map<String, String> = emptyMap()) {
        val result = addRules(env).invoke(gitDir(repository), place, rules)

        check(result is Either.Ok) { "Adding $rules failed: $result" }
    }

    private suspend fun assertRefused(repository: File, reason: IgnoreError.Reason) {
        val result = addRules().invoke(gitDir(repository), IgnorePlace.GLOBAL, listOf("*.log"))
        val error = assertInstanceOf(IgnoreError.FileNotWritable::class.java, assertInstanceOf(Either.Err::class.java, result).error)

        assertEquals(reason, error.reason)
    }

    private suspend fun context(repository: File): IgnoreContext {
        val result = GetIgnoreContextGitAction(jgit, targets(emptyMap())).invoke(gitDir(repository))

        check(result is Either.Ok) { "Reading the context failed: $result" }

        return result.value
    }

    private fun visibleToGit(repository: File, env: Map<String, String> = emptyMap()): Set<String> = git
        // An empty XDG_CONFIG_HOME hides the machine's own, as in targets()
        .run(repository, mapOf("XDG_CONFIG_HOME" to "") + env, "-c", "core.quotePath=false", "ls-files", "--others", "--exclude-standard", "-z")
        .split('\u0000')
        .filter { it.isNotEmpty() }
        .toSet()

    private suspend fun visibleToJGit(repository: File, env: Map<String, String>): Set<String> {
        // JGit finds git's default file through the environment variable that the reader gives it
        val reader = object : SystemReader.Delegate(SystemReader.getInstance()) {
            override fun getenv(variable: String?): String? = env[variable] ?: super.getenv(variable)
        }
        val before = SystemReader.getInstance()
        SystemReader.setInstance(reader)

        try {
            val status = getStatus(gitDir(repository))

            check(status is Either.Ok) { "Status failed: $status" }

            return status.value.unstaged.filter { it.statusType == StatusType.ADDED }.map { it.filePath }.toSet()
        } finally {
            SystemReader.setInstance(before)
        }
    }

    private suspend fun assertIgnored(
        repository: File,
        created: Collection<String>,
        expected: Set<String>,
        env: Map<String, String> = emptyMap(),
    ) {
        val wanted = created.toSet() - expected
        val ignoreFile = setOf(".gitignore")

        assertEquals(wanted, visibleToGit(repository, env) - ignoreFile, "git")
        assertEquals(wanted, visibleToJGit(repository, env) - ignoreFile, "JGit")
    }
}
