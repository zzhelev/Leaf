// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.data.git.workspace

import dev.app.leaf.data.git.IsolatedSystemReader
import dev.app.leaf.data.git.RawFileManager
import dev.app.leaf.data.git.author.GitIdentities
import dev.app.leaf.data.git.config.GitIncludedConfig
import dev.app.leaf.data.git.branches.GetBranchesGitAction
import dev.app.leaf.data.git.branches.GetCurrentBranchGitAction
import dev.app.leaf.data.git.cli.remote.testPartialCloneFetch
import dev.app.leaf.data.git.diff.CanGenerateTextDiffGitAction
import dev.app.leaf.data.git.diff.FormatDiffGitAction
import dev.app.leaf.data.git.diff.FormatHunksGitAction
import dev.app.leaf.data.git.diff.GetDiffContentGitAction
import dev.app.leaf.data.git.diff.GetDiffEntryFromDiffTypeGitAction
import dev.app.leaf.data.git.diff.GetDiffEntryFromStatusEntryGitAction
import dev.app.leaf.data.git.diff.TextDiffFromDiffLinesGitAction
import dev.app.leaf.data.git.repository.GetRepositoryStateGitAction
import dev.app.leaf.data.git.signers.GitSigningConfigs
import dev.app.leaf.data.git.sparse.testSparseCheckoutGit
import dev.app.leaf.data.git.submodules.GetSubmodulesGitAction
import dev.app.leaf.data.git.testGitCli
import dev.app.leaf.data.git.testJGit
import dev.app.leaf.data.git.writeExecutable
import dev.app.leaf.data.mappers.JGitBranchMapper
import dev.app.leaf.data.mappers.JGitCommitMapper
import dev.app.leaf.data.mappers.JGitIdentityMapper
import dev.app.leaf.data.mappers.JGitSubmoduleMapper
import dev.app.leaf.data.mappers.RepositoryStateMapper
import dev.app.leaf.data.shell.LoginShellEnvironment
import dev.app.leaf.domain.AppFilesManager
import dev.app.leaf.domain.TempFilesManager
import dev.app.leaf.domain.errors.Either
import dev.app.leaf.domain.models.DiffResult
import dev.app.leaf.domain.models.DiffType
import dev.app.leaf.domain.models.EntryType
import dev.app.leaf.domain.models.LineType
import dev.app.leaf.domain.models.Status
import dev.app.leaf.domain.models.StatusEntry
import dev.app.leaf.domain.models.StatusType
import dev.app.leaf.domain.models.isIntentToAddFile
import dev.app.leaf.domain.sorting.allDiscard
import kotlinx.coroutines.runBlocking
import org.eclipse.jgit.diff.DiffEntry
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

/**
 * Files added with `git add -N`, intent to add (see IntentToAdd), against git 2.54: Leaf's status lists them as git
 * status does, Leaf's commit and amend leave them out of the commit and keep them in the index as git commit does, and
 * staging them gives what git add gives.
 */
@DisabledOnOs(OS.WINDOWS)
class IntentToAddTest {
    @TempDir
    lateinit var tempDir: File

    private val originalReader: SystemReader = SystemReader.getInstance()
    private lateinit var reader: IsolatedSystemReader

    private val root by lazy { tempDir.canonicalFile }

    /** The user's config: JGit's user config, and git's global config. */
    private val globalConfig by lazy { reader.userConfigFile.apply { parentFile.mkdirs() } }

    private val shellVariables by lazy {
        mapOf(
            "GIT_CONFIG_GLOBAL" to globalConfig.path,
            "GIT_CONFIG_NOSYSTEM" to "1",
            "HOME" to File(root, "home").path,
            "XDG_CONFIG_HOME" to File(root, "xdg").path,
            "LC_ALL" to "C",
        )
    }

    private val gitCli by lazy { testGitCli(shellVariables) }
    // Repositories open as Leaf opens them, as IncludedConfigRepository, whose commit leaves intent-to-add files out
    private val jgit by lazy {
        testJGit(shellVariables, includedConfig = GitIncludedConfig(gitCli, LoginShellEnvironment { shellVariables }))
    }

    private val getStatus by lazy { GetStatusGitAction(jgit) }

    private val commit by lazy {
        DoCommitGitAction(
            GetRepositoryStateGitAction(jgit, RepositoryStateMapper()),
            JGitCommitMapper(JGitIdentityMapper()),
            JGitIdentityMapper(),
            jgit,
            GitIdentities(gitCli),
            GitSigningConfigs(gitCli),
        )
    }

    private val formatDiff by lazy {
        val getCurrentBranch = GetCurrentBranchGitAction(GetBranchesGitAction(JGitBranchMapper(), jgit), jgit)
        val getRepositoryState = GetRepositoryStateGitAction(jgit, RepositoryStateMapper())

        FormatDiffGitAction(
            FormatHunksGitAction(),
            GetDiffContentGitAction(RawFileManager(TempFilesManager(AppFilesManager()))),
            CanGenerateTextDiffGitAction(),
            GetDiffEntryFromDiffTypeGitAction(GetDiffEntryFromStatusEntryGitAction(getRepositoryState, getCurrentBranch)),
            GetSubmodulesGitAction(jgit, JGitSubmoduleMapper()),
            TextDiffFromDiffLinesGitAction(),
            jgit,
            testPartialCloneFetch(jgit),
        )
    }

    @BeforeEach
    fun setUp() {
        reader = IsolatedSystemReader(File(tempDir, "config"), originalReader)
        SystemReader.setInstance(reader)
        globalConfig.writeText("[user]\n\tname = Leaf Test\n\temail = test@example.com\n[commit]\n\tgpgSign = false\n")
    }

    @AfterEach
    fun tearDown() {
        SystemReader.setInstance(originalReader)
    }

    @Test
    fun `lists every kind of file as git status does, with intent-to-add files new in the working tree`(): Unit =
        runBlocking {
            for (indexVersion in listOf(null, "4")) {
                val repository = repositoryWithEveryKind("every-${indexVersion ?: "default"}", indexVersion)
                val status = leafStatus(repository)

                assertEquals(gitStatus(repository), status.asPorcelain(), "index.version=$indexVersion")

                val intentToAdd = status.unstaged.filter { it.isIntentToAdd }.map { it.filePath to it.statusType }
                assertEquals(
                    INTENT_TO_ADD_KINDS.flatMap { (name, type) -> listOf("$name.txt" to type, "dir/$name.txt" to type) }
                        .sortedBy { it.first },
                    intentToAdd.sortedBy { it.first },
                    "index.version=$indexVersion",
                )
                assertTrue(status.staged.none { it.isIntentToAdd })
            }
        }

    @Test
    fun `an intent-to-add file isn't staged, and the ones that HEAD has are staged as deleted`(): Unit = runBlocking {
        val repository = newRepository("simple")
        commitFile(repository, "a.txt", "a\n")
        File(repository, "new.txt").writeText("new\n")
        git(repository, "add", "-N", "new.txt")

        val status = leafStatus(repository)

        assertEquals(emptyList<StatusEntry>(), status.staged)
        assertEquals(
            listOf(StatusEntry("new.txt", StatusType.ADDED, EntryType.UNSTAGED, isIntentToAdd = true)),
            status.unstaged,
        )

        git(repository, "rm", "-q", "--cached", "a.txt")
        git(repository, "add", "-N", "a.txt")

        val again = leafStatus(repository)
        assertEquals(listOf(StatusEntry("a.txt", StatusType.REMOVED, EntryType.STAGED)), again.staged)
        assertEquals(mapOf("a.txt" to "DA", "new.txt" to " A"), again.asPorcelain())
        assertEquals(gitStatus(repository), again.asPorcelain())
    }

    @Test
    fun `a status of some paths lists only their intent-to-add files`(): Unit = runBlocking {
        val repository = repositoryWithEveryKind("paths", indexVersion = null)

        val status = (getStatus(gitDir(repository), listOf("dir")) as Either.Ok).value

        assertTrue(status.unstaged.filter { it.isIntentToAdd }.all { it.filePath.startsWith("dir/") })
        assertTrue(status.unstaged.any { it.isIntentToAdd })
        assertTrue(status.staged.all { it.filePath.startsWith("dir/") })
    }

    @Test
    fun `commits what git commit commits, leaving intent-to-add files out and keeping them`(): Unit = runBlocking {
        for ((case, setUp) in COMMIT_CASES) {
            val (leafRepository, gitRepository) = twinRepositories("commit-$case")
            setUp(this@IntentToAddTest, leafRepository)
            setUp(this@IntentToAddTest, gitRepository)

            val gitCommitted = gitCommit(gitRepository, amend = false)
            val leafResult = commit(gitDir(leafRepository), "Commit\n", false, null)

            if (gitCommitted) {
                assertInstanceOf(Either.Ok::class.java, leafResult, case)
            } else {
                assertInstanceOf(Either.Err::class.java, leafResult, case)
            }

            assertSameRepository(leafRepository, gitRepository, case)
        }
    }

    @Test
    fun `amends as git commit --amend does, leaving intent-to-add files out and keeping them`(): Unit = runBlocking {
        for ((case, setUp) in COMMIT_CASES) {
            val (leafRepository, gitRepository) = twinRepositories("amend-$case")
            setUp(this@IntentToAddTest, leafRepository)
            setUp(this@IntentToAddTest, gitRepository)

            assertTrue(gitCommit(gitRepository, amend = true), case)
            assertInstanceOf(Either.Ok::class.java, commit(gitDir(leafRepository), "Commit\n", true, null), case)

            assertSameRepository(leafRepository, gitRepository, case)
        }
    }

    @Test
    fun `the commit's hooks see the intent-to-add files, as git's do`(): Unit = runBlocking {
        val (leafRepository, gitRepository) = twinRepositories("hooks")

        for (repository in listOf(leafRepository, gitRepository)) {
            File(repository, ".git/hooks/pre-commit").writeExecutable(
                "#!/bin/sh\ngit status --porcelain=v2 > \"\$(git rev-parse --git-dir)/../../${repository.name}-hook\"\n"
            )
            COMMIT_CASES.getValue("with a staged file").invoke(this@IntentToAddTest, repository)
        }

        assertTrue(gitCommit(gitRepository, amend = false))
        assertInstanceOf(Either.Ok::class.java, commit(gitDir(leafRepository), "Commit\n", false, null))

        val gitHook = File(gitRepository.parentFile, "git-hook").readText()
        assertTrue(gitHook.contains(".A N... 000000 000000 100644"), gitHook)
        assertEquals(gitHook, File(leafRepository.parentFile, "leaf-hook").readText())
        assertSameRepository(leafRepository, gitRepository, "hooks")
    }

    @Test
    fun `staging an intent-to-add file stages it as git add does`(): Unit = runBlocking {
        for ((name, type) in INTENT_TO_ADD_KINDS) {
            val (leafRepository, gitRepository) = twinRepositories("stage-$name")
            setUpIntentToAdd(leafRepository, name)
            setUpIntentToAdd(gitRepository, name)

            val entry = leafStatus(leafRepository).unstaged.single { it.filePath == "$name.txt" }
            assertEquals(type, entry.statusType)
            assertTrue(entry.isIntentToAdd)

            assertEquals(Either.Ok(Unit), StageEntryGitAction(jgit)(gitDir(leafRepository), entry))
            git(gitRepository, "add", "$name.txt")

            assertSameIndex(leafRepository, gitRepository, name)
        }
    }

    @Test
    fun `Stage all stages intent-to-add files as git add -A does`(): Unit = runBlocking {
        val leafRepository = repositoryWithEveryKind("stage-all/leaf", indexVersion = null)
        val gitRepository = repositoryWithEveryKind("stage-all/git", indexVersion = null)

        assertInstanceOf(
            Either.Ok::class.java,
            StageAllGitAction(getStatus, jgit)(gitDir(leafRepository), null),
        )
        git(gitRepository, "add", "-A")

        assertSameIndex(leafRepository, gitRepository, "stage all")
    }

    @Test
    fun `Stage all of some files stages those intent-to-add files only`(): Unit = runBlocking {
        val leafRepository = repositoryWithEveryKind("stage-some/leaf", indexVersion = null)
        val gitRepository = repositoryWithEveryKind("stage-some/git", indexVersion = null)
        val chosen = leafStatus(leafRepository).unstaged.filter { it.filePath.startsWith("dir/") }

        assertInstanceOf(
            Either.Ok::class.java,
            StageAllGitAction(getStatus, jgit)(gitDir(leafRepository), chosen),
        )
        git(gitRepository, "add", "-A", "--", *chosen.map { it.filePath }.toTypedArray())

        assertSameIndex(leafRepository, gitRepository, "stage some")
        assertTrue(leafStatus(leafRepository).unstaged.any { it.isIntentToAdd && !it.filePath.startsWith("dir/") })
    }

    @Test
    fun `staging a folder stages its intent-to-add files as git add does`(): Unit = runBlocking {
        val leafRepository = repositoryWithEveryKind("stage-folder/leaf", indexVersion = null)
        val gitRepository = repositoryWithEveryKind("stage-folder/git", indexVersion = null)

        assertEquals(
            Either.Ok(Unit),
            StageByDirectoryGitAction(jgit, testSparseCheckoutGit(jgit, gitCli))(gitDir(leafRepository), "dir"),
        )
        git(gitRepository, "add", "dir")

        assertSameIndex(leafRepository, gitRepository, "stage folder")
    }

    @Test
    fun `the diff of an intent-to-add file adds the whole file, as git diff shows it`(): Unit = runBlocking {
        val repository = newRepository("diff")
        commitFile(repository, "a.txt", "a\n")
        File(repository, "new.txt").writeText("one\ntwo\n")
        File(repository, "empty.txt").writeText("")
        git(repository, "add", "-N", "new.txt", "empty.txt")

        val entries = leafStatus(repository).unstaged.associateBy { it.filePath }
        val diff = unstagedDiff(repository, entries.getValue("new.txt"))

        assertEquals(DiffEntry.ChangeType.ADD, diff.diffEntry.changeType)
        val lines = (diff as DiffResult.Text).hunks.single().lines
        assertEquals(listOf("one", "two"), lines.map { it.text.trimEnd() })
        assertTrue(lines.all { it.lineType == LineType.ADDED })

        val emptyDiff = unstagedDiff(repository, entries.getValue("empty.txt"))
        assertEquals(DiffEntry.ChangeType.ADD, emptyDiff.diffEntry.changeType)
    }

    @Test
    fun `deleting an intent-to-add file drops its entry too, as git rm -f does`(): Unit = runBlocking {
        for ((name, _) in INTENT_TO_ADD_KINDS.filter { it.second == StatusType.ADDED }) {
            val (leafRepository, gitRepository) = twinRepositories("delete-$name")
            setUpIntentToAdd(leafRepository, name, "dir/")
            setUpIntentToAdd(gitRepository, name, "dir/")

            assertEquals(Either.Ok(Unit), DeleteFileGitAction(jgit)(gitDir(leafRepository), "dir/$name.txt"))
            git(gitRepository, "rm", "-q", "-f", "dir/$name.txt")

            assertSameIndex(leafRepository, gitRepository, name)
            assertFalse(File(leafRepository, "dir/$name.txt").exists(), name)
        }
    }

    @Test
    fun `deleting a tracked file still leaves its entry, so it shows as deleted`(): Unit = runBlocking {
        val repository = repositoryWithEveryKind("delete-tracked", indexVersion = null)

        assertEquals(Either.Ok(Unit), DeleteFileGitAction(jgit)(gitDir(repository), "modified.txt"))

        assertEquals(" D", gitStatus(repository)["modified.txt"])
        assertEquals(" A", gitStatus(repository)["new.txt"], "the intent-to-add files stay")
    }

    @Test
    fun `discarding leaves an intent-to-add file on the disk as it is, where git restore empties it`(): Unit =
        runBlocking {
            val repository = repositoryWithEveryKind("discard", indexVersion = null)
            val entries = leafStatus(repository).unstaged.filter { it.filePath in listOf("new.txt", "modified.txt") }

            // As a stale selection would pass it: the menus leave it out
            assertEquals(Either.Ok(Unit), DiscardEntriesGitAction(jgit)(gitDir(repository), entries, staged = false))

            assertEquals("new\n", File(repository, "new.txt").readText())
            assertEquals(" A", gitStatus(repository)["new.txt"])
            assertEquals(null, gitStatus(repository)["modified.txt"], "the other file is restored")
        }

    @Test
    fun `discarding a deleted intent-to-add file brings it back empty, as git restore does`(): Unit = runBlocking {
        val (leafRepository, gitRepository) = twinRepositories("discard-deleted")
        setUpIntentToAdd(leafRepository, "new-deleted")
        setUpIntentToAdd(gitRepository, "new-deleted")
        val entry = leafStatus(leafRepository).unstaged.single { it.filePath == "new-deleted.txt" }

        assertEquals(StatusType.REMOVED, entry.statusType)
        assertEquals(Either.Ok(Unit), DiscardEntriesGitAction(jgit)(gitDir(leafRepository), listOf(entry), staged = false))
        git(gitRepository, "restore", "new-deleted.txt")

        assertSameIndex(leafRepository, gitRepository, "deleted")
        assertEquals(File(gitRepository, "new-deleted.txt").readText(), File(leafRepository, "new-deleted.txt").readText())
    }

    @Test
    fun `Discard all keeps intent-to-add files, and deletes them and their entries when asked`(): Unit = runBlocking {
        for (deleteNewFiles in listOf(false, true)) {
            val repository = repositoryWithEveryKind("discard-all-$deleteNewFiles", indexVersion = null)
            val unstaged = leafStatus(repository).unstaged
            val plan = unstaged.allDiscard()
            val before = gitStatus(repository)

            val result = DiscardAllGitAction(getStatus, DiscardEntriesGitAction(jgit), jgit)(
                gitDir(repository),
                plan.restored,
                if (deleteNewFiles) plan.newFiles else emptyList(),
            )
            assertEquals(Either.Ok(Unit), result)

            val after = gitStatus(repository)
            val intentToAddFiles = unstaged.filter { it.isIntentToAddFile }.map { it.filePath }
            assertTrue(intentToAddFiles.isNotEmpty())

            for (path in intentToAddFiles) {
                if (deleteNewFiles) {
                    // As after git rm -f: gone, or a deletion staged where HEAD has the path
                    val inHead = git(repository, "ls-tree", "HEAD", "--", path).isNotBlank()
                    assertEquals(if (inHead) "D " else null, after[path], path)
                    assertFalse(File(repository, path).exists(), path)
                } else {
                    assertEquals(before[path], after[path], path)
                    assertTrue(File(repository, path).exists(), path)
                }
            }
            // A deleted one is restored, as git restore does: empty, and intent to add
            assertEquals(" A", after["new-deleted.txt"])
            assertEquals("", File(repository, "new-deleted.txt").readText())
        }
    }

    @Test
    fun `reads intent-to-add paths only from an index whose format can hold them`(): Unit = runBlocking {
        val repository = newRepository("version")
        commitFile(repository, "a.txt", "a\n")

        jgit.provide(gitDir(repository)) { git ->
            assertEquals(emptySet<String>(), git.repository.intentToAddPaths())
        }
        assertEquals(2, indexVersion(repository))

        File(repository, "new.txt").writeText("new\n")
        git(repository, "add", "-N", "new.txt")

        assertEquals(3, indexVersion(repository), "git writes intent-to-add entries in version 3")
        jgit.provide(gitDir(repository)) { git ->
            assertEquals(setOf("new.txt"), git.repository.intentToAddPaths())
        }
    }

    /**
     * A repository with each kind of file that a status lists, at the root and in `dir`, `index.version` set to
     * [indexVersion] when it's given: committed and unchanged, modified, deleted, staged, untracked, and every kind of
     * intent-to-add file ([INTENT_TO_ADD_KINDS]).
     */
    private fun repositoryWithEveryKind(name: String, indexVersion: String?): File {
        val repository = newRepository(name)
        indexVersion?.let { git(repository, "config", "index.version", it) }

        for (folder in listOf("", "dir/")) {
            for (kind in listOf("unchanged", "modified", "deleted", "staged", "staged-deleted", "over-head", "over-head-same")) {
                File(repository, "$folder$kind.txt").apply { parentFile.mkdirs() }.writeText("$kind\n")
            }
        }
        git(repository, "add", ".")
        git(repository, "commit", "-q", "-m", "Files")

        for (folder in listOf("", "dir/")) {
            File(repository, "${folder}modified.txt").appendText("more\n")
            File(repository, "${folder}deleted.txt").delete()
            File(repository, "${folder}staged.txt").appendText("staged\n")
            git(repository, "add", "${folder}staged.txt")
            git(repository, "rm", "-q", "${folder}staged-deleted.txt")
            File(repository, "${folder}untracked.txt").writeText("untracked\n")
            File(repository, "${folder}added.txt").writeText("added\n")
            git(repository, "add", "${folder}added.txt")

            for ((kind, _) in INTENT_TO_ADD_KINDS) {
                setUpIntentToAdd(repository, kind, folder)
            }
        }

        return repository
    }

    /** An intent-to-add file `<folder><kind>.txt` of the [kind] that [INTENT_TO_ADD_KINDS] names. */
    private fun setUpIntentToAdd(repository: File, kind: String, folder: String = "") {
        val path = "$folder$kind.txt"
        val file = File(repository, path).apply { parentFile.mkdirs() }

        when (kind) {
            "new" -> {
                file.writeText("new\n")
                git(repository, "add", "-N", path)
            }

            "new-empty" -> {
                file.writeText("")
                git(repository, "add", "-N", path)
            }

            "new-deleted" -> {
                file.writeText("gone\n")
                git(repository, "add", "-N", path)
                file.delete()
            }

            "over-head", "over-head-same" -> {
                if (!isTracked(repository, path)) {
                    file.writeText("$kind\n")
                    git(repository, "add", path)
                    git(repository, "commit", "-q", "-m", "Add $path")
                }
                git(repository, "rm", "-q", "--cached", path)
                git(repository, "add", "-N", path)
                if (kind == "over-head") {
                    file.appendText("changed\n")
                }
            }

            else -> error(kind)
        }
    }

    private fun isTracked(repository: File, path: String) =
        git(repository, "ls-files", "--", path).isNotBlank()

    /** Commits with git, without opening an editor. False when git refused, as with nothing to commit. */
    private fun gitCommit(repository: File, amend: Boolean): Boolean = runBlocking {
        val args = listOfNotNull("commit", "-q", "--amend".takeIf { amend }, "-m", "Commit")

        when (val result = gitCli.execute(repository, args)) {
            is Either.Ok -> result.value.exitCode == 0
            is Either.Err -> error("git commit failed: ${result.error}")
        }
    }

    /**
     * The two repositories have the same HEAD commit apart from its dates, the same index entries and the same status,
     * intent-to-add files included.
     */
    private fun assertSameRepository(leafRepository: File, gitRepository: File, case: String) {
        assertEquals(headCommit(gitRepository), headCommit(leafRepository), case)
        assertSameIndex(leafRepository, gitRepository, case)
    }

    /** The same index entries, with the same intent-to-add flags (`git status --porcelain=v2` shows them). */
    private fun assertSameIndex(leafRepository: File, gitRepository: File, case: String) {
        assertEquals(git(gitRepository, "ls-files", "-s"), git(leafRepository, "ls-files", "-s"), case)
        assertEquals(
            git(gitRepository, "status", "--porcelain=v2", "--untracked-files=all"),
            git(leafRepository, "status", "--porcelain=v2", "--untracked-files=all"),
            case,
        )
    }

    /** HEAD's commit, byte for byte, without the dates of its author and committer. */
    private fun headCommit(repository: File): String =
        git(repository, "cat-file", "commit", "HEAD").replace(Regex("> \\d+ [+-]\\d{4}\\n"), ">\n")

    /**
     * Two repositories with the same first commit, one for Leaf and one for git. It has `README.md` besides `a.txt`, so
     * that a commit that leaves `a.txt` out isn't empty: git refuses to amend into an empty commit, where Leaf's amend
     * always allowed it.
     */
    private fun twinRepositories(name: String): Pair<File, File> {
        val repositories = listOf("leaf", "git").map { side ->
            newRepository("$name/$side").also {
                File(it, "README.md").writeText("Twin\n")
                git(it, "add", "README.md")
                commitFile(it, "a.txt", "a\n")
            }
        }

        return repositories[0] to repositories[1]
    }

    private fun newRepository(name: String): File = File(root, name).apply {
        mkdirs()
        git(this, "init", "-q", "-b", "main")
    }

    /** Commits [path] with [content], with fixed dates, so that twin repositories make the same commit. */
    private fun commitFile(repository: File, path: String, content: String) {
        File(repository, path).apply { parentFile.mkdirs() }.writeText(content)
        git(repository, "add", path)
        gitWithDates(repository, "commit", "-q", "-m", "Add $path")
    }

    private fun gitWithDates(repository: File, vararg args: String): String = runBlocking {
        val dates = mapOf(
            "GIT_AUTHOR_DATE" to "2026-11-12T10:00:00+0000",
            "GIT_COMMITTER_DATE" to "2026-11-12T10:00:00+0000",
        )
        when (val result = gitCli.execute(repository, args.toList(), environment = dates)) {
            is Either.Ok -> result.value.stdout.also { check(result.value.exitCode == 0) { result.value.stderr } }
            is Either.Err -> error("git ${args.joinToString(" ")} failed: ${result.error}")
        }
    }

    private suspend fun leafStatus(repository: File): Status =
        (getStatus(gitDir(repository), emptyList()) as Either.Ok).value

    private suspend fun unstagedDiff(repository: File, entry: StatusEntry): DiffResult {
        val diffType = DiffType.UncommittedDiff(entry, EntryType.UNSTAGED)
        return (formatDiff(gitDir(repository), diffType, isDisplayFullFile = false) as Either.Ok).value
    }

    /** git's status: each path and its two letters, as `git status --short` writes them. */
    private fun gitStatus(repository: File): Map<String, String> =
        git(repository, "status", "--porcelain=v1", "-z", "--no-renames", "--untracked-files=all")
            .split('\u0000')
            .filter { it.isNotEmpty() }
            .associate { it.substring(3) to it.substring(0, 2) }

    /** Leaf's status in [gitStatus]'s letters: an untracked file is `??`, and an intent-to-add file ` A`. */
    private fun Status.asPorcelain(): Map<String, String> {
        val stagedLetters = staged.associate { it.filePath to it.statusType.letter() }
        val unstagedLetters = unstaged.associate { entry ->
            val isUntracked = entry.statusType == StatusType.ADDED && !entry.isIntentToAdd
            entry.filePath to if (isUntracked) "?" else entry.statusType.letter()
        }

        return (stagedLetters.keys + unstagedLetters.keys).associateWith { path ->
            val y = unstagedLetters[path]
            if (y == "?") "??" else (stagedLetters[path] ?: " ") + (y ?: " ")
        }
    }

    private fun StatusType.letter() = when (this) {
        StatusType.ADDED -> "A"
        StatusType.MODIFIED -> "M"
        StatusType.REMOVED -> "D"
        StatusType.CONFLICTING -> "U"
        StatusType.RENAMED -> "R"
    }

    private fun indexVersion(repository: File): Int =
        File(repository, ".git/index").readBytes().let { bytes -> (4 until 8).fold(0) { v, i -> (v shl 8) or (bytes[i].toInt() and 0xFF) } }

    private fun gitDir(repository: File) = File(repository, ".git").path

    private fun git(directory: File, vararg args: String): String = runBlocking {
        when (val result = gitCli.run(directory, args.toList())) {
            is Either.Ok -> result.value
            is Either.Err -> error("git ${args.joinToString(" ")} failed: ${result.error}")
        }
    }

    private companion object {
        /** The kinds of intent-to-add files, and the type that the Unstaged list gives each. */
        val INTENT_TO_ADD_KINDS = listOf(
            "new" to StatusType.ADDED,
            "new-empty" to StatusType.ADDED,
            "new-deleted" to StatusType.REMOVED,
            "over-head" to StatusType.ADDED,
            "over-head-same" to StatusType.ADDED,
        )

        /** What is in the index when Leaf and git commit, set up the same way in both repositories. */
        val COMMIT_CASES: Map<String, IntentToAddTest.(File) -> Unit> = mapOf(
            "with a staged file" to { repository ->
                setUpIntentToAdd(repository, "new")
                File(repository, "a.txt").appendText("staged\n")
                git(repository, "add", "a.txt")
            },
            "empty, in a folder, with a staged file in it" to { repository ->
                setUpIntentToAdd(repository, "new-empty", "dir/")
                File(repository, "dir/staged.txt").writeText("staged\n")
                git(repository, "add", "dir/staged.txt")
            },
            "deleted from the disk, with a staged file" to { repository ->
                setUpIntentToAdd(repository, "new-deleted")
                File(repository, "b.txt").writeText("b\n")
                git(repository, "add", "b.txt")
            },
            "over a file that HEAD has, which the commit deletes" to { repository ->
                git(repository, "rm", "-q", "--cached", "a.txt")
                git(repository, "add", "-N", "a.txt")
            },
            "alone, so nothing to commit" to { repository ->
                setUpIntentToAdd(repository, "new")
            },
        )
    }
}
