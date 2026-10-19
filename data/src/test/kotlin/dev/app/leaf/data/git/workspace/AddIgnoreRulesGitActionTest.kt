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
import dev.app.leaf.domain.models.StatusType
import dev.app.leaf.domain.ignore.IgnoreKind
import dev.app.leaf.domain.ignore.IgnorePlace
import dev.app.leaf.domain.ignore.IgnoreRequest
import dev.app.leaf.domain.ignore.customRules
import dev.app.leaf.domain.ignore.extensionOf
import dev.app.leaf.domain.ignore.ignoreExtensionRule
import dev.app.leaf.domain.ignore.ignoreFileRule
import dev.app.leaf.domain.ignore.ignoreFolderRule
import dev.app.leaf.domain.ignore.ignoreOptions
import kotlinx.coroutines.runBlocking
import org.eclipse.jgit.util.SystemReader
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertArrayEquals
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
import java.nio.file.LinkOption
import kotlin.random.Random

/**
 * "Ignore" in the Unstaged menus: the rules it writes, checked against git itself. After a rule is written,
 * `git ls-files --others --exclude-standard` and Leaf's own status (JGit) must list the same files, and none that the
 * rule was not meant for is gone.
 */
@DisabledOnOs(OS.WINDOWS) // File names with trailing spaces and tabs, and symbolic links
class AddIgnoreRulesGitActionTest {
    @TempDir
    lateinit var tempDir: File

    private val originalReader: SystemReader = SystemReader.getInstance()
    private val git by lazy { TestGitCli(File(tempDir, "config/global.gitconfig")) }
    private val jgit = testJGit()
    private val shellVariables by lazy {
        mapOf("GIT_CONFIG_GLOBAL" to File(tempDir, "config/global.gitconfig").path, "GIT_CONFIG_NOSYSTEM" to "1")
    }
    private val targets by lazy {
        IgnoreTargets(GlobalIgnoreFile(testGitCli(shellVariables), LoginShellEnvironment { shellVariables }))
    }
    private val addRules by lazy { AddIgnoreRulesGitAction(jgit, targets) }
    private val getContext by lazy { GetIgnoreContextGitAction(jgit, targets) }
    private val getStatus = GetStatusGitAction(jgit)

    @BeforeEach
    fun isolateConfig() {
        val config = File(tempDir, "config").apply { mkdirs() }
        val noGlobalIgnore = File(config, "global-ignore").apply { writeText("") }
        val settings = "[core]\n\texcludesFile = ${noGlobalIgnore.path}\n"

        // git reads the first file, JGit the second: neither may read the developer's own ignore file
        File(config, "global.gitconfig").writeText(settings)
        File(config, "user.gitconfig").writeText(settings)

        SystemReader.setInstance(IsolatedSystemReader(config, originalReader))
    }

    @AfterEach
    fun restoreSystemReader() {
        SystemReader.setInstance(originalReader)
    }

    // ---- The rules, against git ----

    @Test
    fun `a file rule ignores that file and no other of the same name or start`(): Unit = runBlocking {
        val repository = repository()
        val files = create(
            repository,
            "build/out.log", "out.log", "other/build/out.log", "build/out.log2", "build/out.log.bak", "xbuild/out.log",
        )

        add(repository, IgnorePlace.GITIGNORE, listOf(ignoreFileRule("build/out.log")))

        assertIgnored(repository, files, setOf("build/out.log"))
    }

    @Test
    fun `a file at the root is ignored there only`(): Unit = runBlocking {
        val repository = repository()
        val files = create(repository, "c.log", "d/c.log")

        add(repository, IgnorePlace.GITIGNORE, listOf(ignoreFileRule("c.log")))

        assertIgnored(repository, files, setOf("c.log"))
    }

    @Test
    fun `a folder rule ignores everything under that folder and nothing beside it`(): Unit = runBlocking {
        val repository = repository()
        val files = create(
            repository,
            "a/b/x", "a/b/y/z", "a/b2/x", "x/a/b/x", "a/bfile", "a/x", "b/x",
        )

        add(repository, IgnorePlace.GITIGNORE, listOf(ignoreFolderRule("a/b")!!))

        assertIgnored(repository, files, setOf("a/b/x", "a/b/y/z"))
    }

    @Test
    fun `an extension rule ignores that extension at any depth and nothing else`(): Unit = runBlocking {
        val repository = repository()
        val files = create(
            repository, "x.log", "d/e/y.log", "log", "x.logx", "x.log.txt", "xlog", "d/log", "x_log", "x.txt",
        )

        add(repository, IgnorePlace.GITIGNORE, listOf(ignoreExtensionRule("log")))

        assertIgnored(repository, files, setOf("x.log", "d/e/y.log"))
    }

    @Test
    fun `names with spaces and special characters are ignored one at a time`(): Unit = runBlocking {
        val repository = repository()
        val names = listOf(
            "a b", " lead", "trail ", "trail  ", "#hash", "!bang", "st*r", "q?", "br[ac]", "br]", "back\\slash",
            "tab\tname", "é.txt", "日本語.md", "a=b", "dir with space/file name.txt", "[abc]/x", "x/#h", "!x/y",
            "-dash", "~tilde", "\\", "**", "a\\*b", "{x}", "x,y", "a.b.c", "a.", ".dot", "..dots", "a#b", "a!b",
            "[", "]", "?", "*", "*.txt", "\\#h", "\\ ", "a\\", "x y/ ",
            "dir /f", "dir \t/f", "ws\u000B", "ws\u000C", "ws\u2003", "ws\u3000", "ws\u00A0", "ws\u001F",
            "mid\u000Bdle", "trail \u2003", "d\u2003/f", "\t", "\t\t", " \t ",
        )
        val files = create(repository, *names.toTypedArray())

        for (name in names) {
            File(repository, ".gitignore").delete()

            add(repository, IgnorePlace.GITIGNORE, listOf(ignoreFileRule(name)))

            assertIgnored(repository, files, setOf(name), "file rule for ${name.show()}")
        }
    }

    @Test
    fun `folders with special characters are ignored one at a time`(): Unit = runBlocking {
        val repository = repository()
        val folders = listOf(
            "a b", " lead", "trail ", "#hash", "!bang", "st*r", "br[ac]", "back\\slash", "é", "x/y z", "[abc]",
            "a.b", "*", "tab\t", "em\u2003", "x/trail ", "\\",
        )
        val files = create(repository, *folders.flatMap { listOf("$it/f", "$it/g/h") }.toTypedArray(), "a b2/f")

        for (folder in folders) {
            File(repository, ".gitignore").delete()

            add(repository, IgnorePlace.GITIGNORE, listOf(ignoreFolderRule(folder)!!))

            // A folder inside another one of the list is covered with it
            val expected = files.filter { it.startsWith("$folder/") }.toSet()

            assertIgnored(repository, files, expected, "folder rule for ${folder.show()}")
        }
    }

    @Test
    fun `extensions with special characters are ignored one at a time`(): Unit = runBlocking {
        val repository = repository()
        val names = listOf("x.a b", "x.a*b", "x.#h", "x.a ", "x.[a]", "x.a\\", "x.!", "y.a b", "z.é", "x.a?b", "x.?", "x.ab")
        val files = create(repository, *names.toTypedArray())

        for (name in names) {
            val extension = extensionOf(name)!!

            File(repository, ".gitignore").delete()

            add(repository, IgnorePlace.GITIGNORE, listOf(ignoreExtensionRule(extension)))

            assertIgnored(
                repository,
                files,
                files.filter { it.endsWith(".$extension") }.toSet(),
                "extension rule for ${extension.show()}",
            )
        }
    }

    @Test
    fun `random names are ignored one at a time, as git reads the rule`(): Unit = runBlocking {
        val repository = repository()
        val random = Random(175)
        val alphabet = " #!*?[]\\\t-é日ab."
        val names = generateSequence { String(CharArray(random.nextInt(1, 7)) { alphabet[random.nextInt(alphabet.length)] }) }
            .filter { it != "." && it != ".." }
            .distinct()
            .take(120)
            .toList()
        val files = create(repository, *names.toTypedArray())

        for (name in names) {
            File(repository, ".gitignore").delete()

            add(repository, IgnorePlace.GITIGNORE, listOf(ignoreFileRule(name)))

            assertIgnored(repository, files, setOf(name), "file rule for ${name.show()}")
        }

        // Their extensions, and their folders (the same names as folders, with a file in them)
        for (name in names.filter { extensionOf(it) != null }.take(40)) {
            val extension = extensionOf(name)!!

            File(repository, ".gitignore").delete()

            add(repository, IgnorePlace.GITIGNORE, listOf(ignoreExtensionRule(extension)))

            assertIgnored(
                repository,
                files,
                files.filter { it.endsWith(".$extension") }.toSet(),
                "extension rule for ${extension.show()}",
            )
        }
    }

    @Test
    fun `selected files are ignored through the options the dialog offers, and no others`(): Unit = runBlocking {
        val repository = repository()
        val files = create(
            repository,
            "a/b/x.log", "a/y.log", "a/b/z.tmp", "c/w.log", "v.log", "a/keep.txt", "c/keep.md", "keep.log.txt", "a/b2/q.log",
        )
        val selected = listOf("a/b/x.log", "a/y.log", "a/b/z.tmp", "c/w.log", "v.log")
        val options = ignoreOptions(IgnoreRequest.Files(selected)).associateBy { it.kind }

        add(repository, IgnorePlace.GITIGNORE, options.getValue(IgnoreKind.FILES).rules)
        assertIgnored(repository, files, selected.toSet(), "files")

        File(repository, ".gitignore").delete()
        add(repository, IgnorePlace.GITIGNORE, options.getValue(IgnoreKind.FOLDERS).rules)
        assertIgnored(repository, files, files.filter { it.startsWith("a/") || it.startsWith("c/") }.toSet(), "folders")

        File(repository, ".gitignore").delete()
        add(repository, IgnorePlace.GITIGNORE, options.getValue(IgnoreKind.EXTENSIONS).rules)
        assertIgnored(repository, files, files.filter { it.endsWith(".log") || it.endsWith(".tmp") }.toSet(), "extensions")
    }

    @Test
    fun `a custom pattern is written as typed`(): Unit = runBlocking {
        val repository = repository()
        val files = create(repository, "build/a.log", "build/sub/b.log", "build/c.txt", "other/d.log")

        add(repository, IgnorePlace.GITIGNORE, customRules("/build/*.log\n\n"))

        assertIgnored(repository, files, setOf("build/a.log"))
        assertEquals("/build/*.log\n", File(repository, ".gitignore").readText())
    }

    // ---- The ignore file ----

    @Test
    fun `a missing gitignore is created with one rule per line`(): Unit = runBlocking {
        val repository = repository()
        val files = create(repository, "a.log", "b.txt")

        add(repository, IgnorePlace.GITIGNORE, listOf("*.log", "/b.txt"))

        assertEquals("*.log\n/b.txt\n", File(repository, ".gitignore").readText())
        assertIgnored(repository, files, setOf("a.log", "b.txt"))
    }

    @Test
    fun `a gitignore without a final newline gets a new line first and keeps what it has`(): Unit = runBlocking {
        val repository = repository()
        val files = create(repository, "a.log", "keep.txt")
        File(repository, ".gitignore").writeText("node_modules")

        add(repository, IgnorePlace.GITIGNORE, listOf("*.log"))

        assertEquals("node_modules\n*.log\n", File(repository, ".gitignore").readText())
        assertIgnored(repository, files, setOf("a.log"))
    }

    @Test
    fun `a gitignore with CRLF gets CRLF lines and no bare line feed`(): Unit = runBlocking {
        val repository = repository()
        val files = create(repository, "a.log", "b.tmp", "keep.txt")
        File(repository, ".gitignore").writeBytes("# build\r\nnode_modules\r\n".toByteArray())

        add(repository, IgnorePlace.GITIGNORE, listOf("*.log", "/b.tmp"))

        val bytes = File(repository, ".gitignore").readBytes()

        assertEquals("# build\r\nnode_modules\r\n*.log\r\n/b.tmp\r\n", String(bytes))
        assertEquals(bytes.count { it == '\n'.code.toByte() }, bytes.count { it == '\r'.code.toByte() })
        assertIgnored(repository, files, setOf("a.log", "b.tmp"))
    }

    @Test
    fun `a gitignore with CRLF and no final newline gets one before the new rule`(): Unit = runBlocking {
        val repository = repository()
        val files = create(repository, "a.log", "keep.txt")
        File(repository, ".gitignore").writeBytes("node_modules\r\nbuild".toByteArray())

        add(repository, IgnorePlace.GITIGNORE, listOf("*.log"))

        assertEquals("node_modules\r\nbuild\r\n*.log\r\n", String(File(repository, ".gitignore").readBytes()))
        assertIgnored(repository, files, setOf("a.log"))
    }

    @Test
    fun `a gitignore with LF stays LF`(): Unit = runBlocking {
        val repository = repository()
        create(repository, "a.log")
        File(repository, ".gitignore").writeText("x\ny\n")

        add(repository, IgnorePlace.GITIGNORE, listOf("*.log"))

        assertEquals("x\ny\n*.log\n", File(repository, ".gitignore").readText())
    }

    @Test
    fun `a rule the file already has isn't added again, with LF or CRLF`(): Unit = runBlocking {
        val repository = repository()
        val gitignore = File(repository, ".gitignore")
        create(repository, "a.log")

        gitignore.writeBytes("/a.log\r\nother".toByteArray())
        val before = gitignore.readBytes()

        add(repository, IgnorePlace.GITIGNORE, listOf("/a.log", "other"))

        assertArrayEquals(before, gitignore.readBytes(), "Nothing to add, so nothing is written")

        gitignore.writeText("/a.log")
        add(repository, IgnorePlace.GITIGNORE, listOf("/a.log"))

        assertEquals("/a.log", gitignore.readText())
    }

    @Test
    fun `a rule that only a comment or another rule resembles is still added`(): Unit = runBlocking {
        val repository = repository()
        val files = create(repository, "a.log", "b.log")
        File(repository, ".gitignore").writeText("# /a.log\n!/b.log\n")

        add(repository, IgnorePlace.GITIGNORE, listOf("/a.log", "/b.log"))

        assertEquals("# /a.log\n!/b.log\n/a.log\n/b.log\n", File(repository, ".gitignore").readText())
        // The new rule comes after the negation, so it wins
        assertIgnored(repository, files, setOf("a.log", "b.log"))
    }

    @Test
    fun `the same rule twice in one call is written once`(): Unit = runBlocking {
        val repository = repository()
        create(repository, "a.log")

        add(repository, IgnorePlace.GITIGNORE, listOf("*.log", "*.log"))

        assertEquals("*.log\n", File(repository, ".gitignore").readText())
    }

    @Test
    fun `no rules write nothing and create no file`(): Unit = runBlocking {
        val repository = repository()

        add(repository, IgnorePlace.GITIGNORE, emptyList())

        assertFalse(File(repository, ".gitignore").exists())
    }

    @Test
    fun `bytes that aren't text, a byte order mark among them, stay as they are`(): Unit = runBlocking {
        val repository = repository()
        val files = create(repository, "a.log", "keep.txt")
        val existing = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + "# ".toByteArray() +
                byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + "\n".toByteArray()
        File(repository, ".gitignore").writeBytes(existing)

        add(repository, IgnorePlace.GITIGNORE, listOf("*.log"))

        assertArrayEquals(existing + "*.log\n".toByteArray(), File(repository, ".gitignore").readBytes())
        assertIgnored(repository, files, setOf("a.log"))
    }

    @Test
    fun `non-ASCII rules are written as UTF-8`(): Unit = runBlocking {
        val repository = repository()
        val files = create(repository, "日本語/é.txt", "日本語2/é.txt")

        add(repository, IgnorePlace.GITIGNORE, listOf(ignoreFolderRule("日本語")!!))

        assertArrayEquals("/日本語/\n".toByteArray(Charsets.UTF_8), File(repository, ".gitignore").readBytes())
        assertIgnored(repository, files, setOf("日本語/é.txt"))
    }

    // ---- Never through a link, never to something that isn't a file ----

    @Test
    fun `a symbolic gitignore isn't written through, and its target stays as it is`(): Unit = runBlocking {
        val repository = repository()
        val target = File(tempDir, "outside/real.ignore").apply { parentFile.mkdirs(); writeText("keep\n") }
        val link = File(repository, ".gitignore").symlinkTo(target)

        val result = addRules(gitDir(repository), IgnorePlace.GITIGNORE, listOf("*.log"))

        val error = assertInstanceOf(IgnoreError.FileNotWritable::class.java, assertInstanceOf(Either.Err::class.java, result).error)
        assertEquals(IgnoreError.Reason.SYMBOLIC_LINK, error.reason)
        assertEquals(".gitignore", File(error.path).name)
        assertEquals("keep\n", target.readText())
        assertTrue(Files.isSymbolicLink(link.toPath()))
    }

    @Test
    fun `a gitignore that is a folder is refused`(): Unit = runBlocking {
        val repository = repository()
        File(repository, ".gitignore").mkdirs()

        val result = addRules(gitDir(repository), IgnorePlace.GITIGNORE, listOf("*.log"))

        val error = assertInstanceOf(IgnoreError.FileNotWritable::class.java, assertInstanceOf(Either.Err::class.java, result).error)
        assertEquals(IgnoreError.Reason.NOT_A_FILE, error.reason)
    }

    @Test
    fun `a dangling symbolic gitignore is refused too`(): Unit = runBlocking {
        val repository = repository()
        File(repository, ".gitignore").symlinkTo(File(tempDir, "nowhere"))

        val result = addRules(gitDir(repository), IgnorePlace.GITIGNORE, listOf("*.log"))

        assertInstanceOf(IgnoreError.FileNotWritable::class.java, assertInstanceOf(Either.Err::class.java, result).error)
        assertFalse(File(tempDir, "nowhere").exists())
    }

    // ---- info/exclude, and linked worktrees ----

    @Test
    fun `the local exclude file gets the rules and gitignore is left alone`(): Unit = runBlocking {
        val repository = repository()
        val files = create(repository, "a.log", "keep.txt")
        val exclude = File(repository, ".git/info/exclude")
        val before = exclude.readText()

        add(repository, IgnorePlace.EXCLUDE, listOf("*.log"))

        assertEquals(before + "*.log\n", exclude.readText())
        assertFalse(File(repository, ".gitignore").exists())
        assertIgnored(repository, files, setOf("a.log"))
    }

    @Test
    fun `the exclude file and its folder are created when the repository has none`(): Unit = runBlocking {
        val repository = repository()
        val files = create(repository, "a.log")
        File(repository, ".git/info").deleteRecursively()

        add(repository, IgnorePlace.EXCLUDE, listOf("*.log"))

        assertEquals("*.log\n", File(repository, ".git/info/exclude").readText())
        assertIgnored(repository, files, setOf("a.log"))
    }

    @Test
    fun `a symbolic exclude file is refused`(): Unit = runBlocking {
        val repository = repository()
        val target = File(tempDir, "outside/exclude").apply { parentFile.mkdirs(); writeText("keep\n") }
        File(repository, ".git/info/exclude").apply { delete() }.symlinkTo(target)

        val result = addRules(gitDir(repository), IgnorePlace.EXCLUDE, listOf("*.log"))

        assertInstanceOf(IgnoreError.FileNotWritable::class.java, assertInstanceOf(Either.Err::class.java, result).error)
        assertEquals("keep\n", target.readText())
    }

    @Test
    fun `in a linked worktree gitignore is the worktree's own and the exclude file is shared`(): Unit = runBlocking {
        val main = repository()
        git.run(main, "worktree", "add", "-b", "agent", File(tempDir, "wt").path)
        val worktree = File(tempDir, "wt")
        val worktreeGitDir = File(main, ".git/worktrees/wt").absolutePath
        val mainFiles = create(main, "junk.log", "keep.txt")
        val worktreeFiles = create(worktree, "junk.log", "keep.txt")

        assertInstanceOf(Either.Ok::class.java, addRules(worktreeGitDir, IgnorePlace.GITIGNORE, listOf("*.log")))

        assertEquals("*.log\n", File(worktree, ".gitignore").readText())
        assertFalse(File(main, ".gitignore").exists(), "The main worktree's gitignore isn't touched")
        assertIgnored(worktree, worktreeFiles, setOf("junk.log"), gitDir = worktreeGitDir)
        assertEquals(mainFiles.toSet(), visibleToGit(main), "The main worktree still lists its file")

        File(worktree, ".gitignore").delete()

        assertInstanceOf(Either.Ok::class.java, addRules(worktreeGitDir, IgnorePlace.EXCLUDE, listOf("*.log")))

        assertTrue(File(main, ".git/info/exclude").readText().endsWith("*.log\n"))
        assertIgnored(worktree, worktreeFiles, setOf("junk.log"), gitDir = worktreeGitDir)
        assertIgnored(main, mainFiles, setOf("junk.log"))
    }

    // ---- What the dialog reads ----

    @Test
    fun `the context has each file's lines and the place used last, which is gitignore at first`(): Unit = runBlocking {
        val repository = repository()
        File(repository, ".gitignore").writeBytes("a\r\nb\r\n".toByteArray())

        val context = context(repository)

        assertEquals(IgnorePlace.GITIGNORE, context.remembered)
        assertEquals(listOf("a", "b"), context.files.getValue(IgnorePlace.GITIGNORE).lines)
        assertEquals(File(repository, ".gitignore").canonicalPath, File(context.files.getValue(IgnorePlace.GITIGNORE).path).canonicalPath)
        assertTrue(context.files.getValue(IgnorePlace.EXCLUDE).lines.all { it.startsWith("#") || it.isEmpty() })
        assertEquals(File(repository, ".git/info/exclude").canonicalPath, File(context.files.getValue(IgnorePlace.EXCLUDE).path).canonicalPath)
    }

    @Test
    fun `the place used last is remembered for the repository, and its other settings stay`(): Unit = runBlocking {
        val repository = repository()
        val config = File(repository, ".git/leaf")
        config.writeText("[signoff]\n\tname = Kept\n")
        create(repository, "a.log")

        add(repository, IgnorePlace.EXCLUDE, listOf("*.log"))

        assertEquals(IgnorePlace.EXCLUDE, context(repository).remembered)
        assertTrue(config.readText().contains("name = Kept"))

        add(repository, IgnorePlace.GITIGNORE, listOf("*.log"))

        assertEquals(IgnorePlace.GITIGNORE, context(repository).remembered)
    }

    @Test
    fun `a rule that was added but not to the place shown stays out of the lines of the other place`(): Unit = runBlocking {
        val repository = repository()
        create(repository, "a.log")

        add(repository, IgnorePlace.GITIGNORE, listOf("*.log"))

        val context = context(repository)

        assertEquals(listOf("*.log"), context.files.getValue(IgnorePlace.GITIGNORE).lines)
        assertFalse("*.log" in context.files.getValue(IgnorePlace.EXCLUDE).lines)
    }

    @Test
    fun `the context says why a file can't be written`(): Unit = runBlocking {
        val repository = repository()
        File(repository, ".gitignore").symlinkTo(File(tempDir, "nowhere"))

        val file = context(repository).files.getValue(IgnorePlace.GITIGNORE)

        assertInstanceOf(IgnoreError.FileNotWritable::class.java, file.problem)
        assertEquals(emptyList<String>(), file.lines)
    }

    // ---- Helpers ----

    private fun repository(): File = git.initRepository(File(tempDir, "repo"))

    private fun gitDir(repository: File) = File(repository, ".git").absolutePath

    /** Creates a file with each of [paths], relative to [repository], and returns the paths. */
    private fun create(repository: File, vararg paths: String): List<String> {
        for (path in paths) {
            File(repository, path).apply { parentFile.mkdirs() }.writeText("x")
        }

        return paths.toList()
    }

    private suspend fun add(repository: File, place: IgnorePlace, rules: List<String>) {
        val result = addRules(gitDir(repository), place, rules)

        check(result is Either.Ok) { "Adding $rules failed: $result" }
    }

    private suspend fun context(repository: File): dev.app.leaf.domain.ignore.IgnoreContext {
        val result = getContext(gitDir(repository))

        check(result is Either.Ok) { "Reading the context failed: $result" }

        return result.value
    }

    /** The untracked files that git lists: those that no ignore file covers. */
    private fun visibleToGit(repository: File): Set<String> = git
        .run(repository, "-c", "core.quotePath=false", "ls-files", "--others", "--exclude-standard", "-z")
        .split('\u0000')
        .filter { it.isNotEmpty() }
        .toSet()

    /** The untracked files that Leaf's status lists, as JGit reads the ignore files. */
    private suspend fun visibleToJGit(gitDir: String): Set<String> {
        val status = getStatus(gitDir)

        check(status is Either.Ok) { "Status failed: $status" }

        return status.value.unstaged.filter { it.statusType == StatusType.ADDED }.map { it.filePath }.toSet()
    }

    /**
     * Checks that of the [created] files exactly [expected] are ignored, for git and for Leaf's status, which must list
     * the same files. The gitignore itself, when the rules created it, is an untracked file too.
     */
    private suspend fun assertIgnored(
        repository: File,
        created: Collection<String>,
        expected: Set<String>,
        message: String = "",
        gitDir: String = gitDir(repository),
    ) {
        val wanted = created.toSet() - expected
        val ignoreFile = setOf(".gitignore")

        assertEquals(wanted, visibleToGit(repository) - ignoreFile, "git $message")
        assertEquals(wanted, visibleToJGit(gitDir) - ignoreFile, "JGit $message")
    }

    /** A name with its control characters visible, for the failure message. */
    private fun String.show() = "\"" + replace("\t", "\\t") + "\""
}
