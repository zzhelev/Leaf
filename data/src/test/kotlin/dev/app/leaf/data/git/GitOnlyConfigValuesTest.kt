// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.data.git

import dev.app.leaf.data.git.MergeContents.Clean
import dev.app.leaf.data.git.MergeContents.Companion.FILE
import dev.app.leaf.data.git.MergeContents.Companion.ZDIFF3
import dev.app.leaf.data.git.MergeContents.Companion.conflictIn
import dev.app.leaf.data.git.MergeContents.Conflict
import dev.app.leaf.data.git.MergeContents.MyersOnlyMerge
import dev.app.leaf.data.git.branches.GetBranchesGitAction
import dev.app.leaf.data.git.branches.GetCurrentBranchGitAction
import dev.app.leaf.data.git.branches.MergeBranchGitAction
import dev.app.leaf.data.git.diff.CanGenerateTextDiffGitAction
import dev.app.leaf.data.git.diff.FormatDiffGitAction
import dev.app.leaf.data.git.diff.FormatHunksGitAction
import dev.app.leaf.data.git.diff.GetCommitDiffEntriesGitAction
import dev.app.leaf.data.git.diff.GetDiffContentGitAction
import dev.app.leaf.data.git.diff.GetDiffEntryFromDiffTypeGitAction
import dev.app.leaf.data.git.diff.GetDiffEntryFromStatusEntryGitAction
import dev.app.leaf.data.git.diff.TextDiffFromDiffLinesGitAction
import dev.app.leaf.data.git.log.CherryPickCommitGitAction
import dev.app.leaf.data.git.log.RevertCommitGitAction
import dev.app.leaf.data.git.rebase.ContinueRebaseGitAction
import dev.app.leaf.data.git.rebase.RebaseBranchGitAction
import dev.app.leaf.data.git.rebase.ResumeRebaseInteractiveGitAction
import dev.app.leaf.data.git.rebase.StartRebaseInteractiveGitAction
import dev.app.leaf.data.git.repository.GetRepositoryStateGitAction
import dev.app.leaf.data.git.stash.ApplyStashGitAction
import dev.app.leaf.data.git.submodules.GetSubmodulesGitAction
import dev.app.leaf.data.mappers.JGitBranchMapper
import dev.app.leaf.data.mappers.JGitCommitMapper
import dev.app.leaf.data.mappers.JGitIdentityMapper
import dev.app.leaf.data.mappers.JGitSubmoduleMapper
import dev.app.leaf.data.mappers.RepositoryStateMapper
import dev.app.leaf.domain.AppFilesManager
import dev.app.leaf.domain.TempFilesManager
import dev.app.leaf.domain.errors.Either
import dev.app.leaf.domain.errors.GenericError
import dev.app.leaf.domain.errors.GitError
import dev.app.leaf.domain.models.Branch
import dev.app.leaf.domain.models.Commit
import dev.app.leaf.domain.models.DiffResult
import dev.app.leaf.domain.models.DiffType
import dev.app.leaf.domain.models.EntryType
import dev.app.leaf.domain.models.LineType
import dev.app.leaf.domain.models.StatusEntry
import dev.app.leaf.domain.models.StatusType
import kotlinx.coroutines.runBlocking
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.api.RebaseCommand
import org.eclipse.jgit.lib.Config
import org.eclipse.jgit.lib.RebaseTodoLine
import org.eclipse.jgit.lib.RepositoryState
import org.eclipse.jgit.revwalk.RevWalk
import org.eclipse.jgit.util.SystemReader
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.io.File

/**
 * Leaf's merges, rebases, cherry-picks, reverts, stash applies and diffs in a repository that sets
 * `merge.conflictStyle` or `diff.algorithm` to a value that git accepts and JGit's enums lack. They failed with
 * "Invalid value" before [GitCompatibleConfigGetter]. The repository's config is never changed.
 */
class GitOnlyConfigValuesTest {
    @TempDir
    lateinit var tempDir: File

    private val originalReader: SystemReader = SystemReader.getInstance()
    private val jgit = testJGit()
    private lateinit var isolatedReader: IsolatedSystemReader
    private lateinit var git: TestGitCli
    private lateinit var work: File

    private val gitDir get() = File(work, ".git").absolutePath

    @BeforeEach
    fun setUp() {
        isolatedReader = IsolatedSystemReader(File(tempDir, "jgit-config"), originalReader)
        SystemReader.setInstance(isolatedReader)
        readGitOnlyConfigValues()
        git = TestGitCli(File(tempDir, "empty.gitconfig"))
        work = git.initRepository(File(tempDir, "work"))
        git.run(work, "config", "user.name", "Leaf Test")
        git.run(work, "config", "user.email", "test@example.invalid")
    }

    @AfterEach
    fun restore() {
        Config.setTypedConfigGetter(null)
        SystemReader.setInstance(originalReader)
    }

    @Test
    fun `a merge stops at conflicts with zdiff3's layout`(): Unit = runBlocking {
        branches(Conflict)

        assertEquals(Either.Ok(true), keepingConfig(ZDIFF3) { merge("feature") })

        assertEquals(Conflict.result, conflictIn(work))
    }

    @Test
    fun `the conflict is the one that git's zdiff3 merge writes, but for the base's lines`(): Unit = runBlocking {
        branches(Conflict)
        val copy = File(tempDir, "copy")
        git.run(tempDir, "clone", "--branch", "main", work.absolutePath, copy.absolutePath)
        git.runFailing(copy, "-c", ZDIFF3, "merge", "origin/feature")

        keepingConfig(ZDIFF3) { merge("feature") }

        assertEquals(conflictIn(copy), conflictIn(work))
    }

    @ParameterizedTest
    @ValueSource(strings = ["patience", "default", "minimal"])
    fun `a merge merges`(algorithm: String): Unit = runBlocking {
        branches(Clean)

        assertEquals(Either.Ok(false), keepingConfig("diff.algorithm=$algorithm") { merge("feature") })

        assertEquals(Clean.result, File(work, FILE).readText())
    }

    @ParameterizedTest
    @ValueSource(strings = ["default", "minimal"])
    fun `default and minimal merge as myers does, as with git`(algorithm: String): Unit = runBlocking {
        branches(MyersOnlyMerge)
        val gitMerged = mergedByGit("diff.algorithm=$algorithm")

        assertEquals(Either.Ok(false), keepingConfig("diff.algorithm=$algorithm") { merge("feature") })

        assertEquals(MyersOnlyMerge.result, File(work, FILE).readText())
        assertEquals(gitMerged, MyersOnlyMerge.result)
    }

    @Test
    fun `patience merges as histogram does, which stops at conflicts there, as git's patience does`(): Unit =
        runBlocking {
            branches(MyersOnlyMerge)
            val copy = File(tempDir, "copy")
            git.run(tempDir, "clone", "--branch", "main", work.absolutePath, copy.absolutePath)
            git.runFailing(copy, "-c", "diff.algorithm=patience", "merge", "origin/feature")

            assertEquals(Either.Ok(true), keepingConfig("diff.algorithm=patience") { merge("feature") })

            assertEquals(conflictIn(copy), conflictIn(work))
        }

    @Test
    fun `both set in the user's config, a merge stops at conflicts with zdiff3's layout`(): Unit = runBlocking {
        branches(Conflict)
        val userConfig = "[merge]\n\tconflictStyle = zdiff3\n[diff]\n\talgorithm = patience\n"
        isolatedReader.userConfigFile.apply { parentFile.mkdirs() }.writeText(userConfig)

        assertEquals(Either.Ok(true), keepingConfig(setting = null) { merge("feature") })

        assertEquals(Conflict.result, conflictIn(work))
        assertEquals(userConfig, isolatedReader.userConfigFile.readText())
    }

    @Test
    fun `a rebase stops at conflicts with zdiff3's layout`(): Unit = runBlocking {
        branches(Conflict)
        git.run(work, "switch", "feature")

        assertEquals(Either.Ok(true), keepingConfig(ZDIFF3) { RebaseBranchGitAction(jgit)(gitDir, branch("main")) })

        assertEquals(Conflict.result, conflictIn(work))
    }

    @ParameterizedTest
    @ValueSource(strings = ["patience", "default", "minimal"])
    fun `a rebase rebases`(algorithm: String): Unit = runBlocking {
        branches(Clean)
        git.run(work, "switch", "feature")

        val result = keepingConfig("diff.algorithm=$algorithm") { RebaseBranchGitAction(jgit)(gitDir, branch("main")) }

        assertEquals(Either.Ok(false), result)
        assertEquals(Clean.result, File(work, FILE).readText())
        assertEquals(git.run(work, "rev-parse", "main").trim(), git.run(work, "rev-parse", "HEAD~1").trim())
    }

    @Test
    fun `a rebase continues to the next conflict, with zdiff3's layout`(): Unit = runBlocking {
        rebaseStoppedBeforeLastCommit(Conflict, ZDIFF3)

        // true: it stopped at the next commit's conflicts
        assertEquals(Either.Ok(true), keepingConfig(ZDIFF3) { ContinueRebaseGitAction(jgit)(gitDir) })

        assertEquals(RepositoryState.REBASING_MERGE, repositoryState())
        assertEquals(Conflict.result, conflictIn(work))
    }

    @ParameterizedTest
    @ValueSource(strings = ["patience", "default", "minimal"])
    fun `a rebase continues to its end`(algorithm: String): Unit = runBlocking {
        rebaseStoppedBeforeLastCommit(Clean, "diff.algorithm=$algorithm")

        val result = keepingConfig("diff.algorithm=$algorithm") { ContinueRebaseGitAction(jgit)(gitDir) }

        assertEquals(Either.Ok(false), result)

        assertEquals(RepositoryState.SAFE, repositoryState())
        assertEquals(Clean.result, File(work, FILE).readText())
    }

    @Test
    fun `an interactive rebase stops at conflicts with zdiff3's layout`(): Unit = runBlocking {
        branches(Conflict)
        git.run(work, "switch", "feature")

        assertEquals(Either.Ok(Unit), keepingConfig(ZDIFF3) { rebaseInteractively(onto = "main") })

        assertEquals(RepositoryState.REBASING_INTERACTIVE, repositoryState())
        assertEquals(Conflict.result, conflictIn(work))
    }

    @ParameterizedTest
    @ValueSource(strings = ["patience", "default", "minimal"])
    fun `an interactive rebase rebases`(algorithm: String): Unit = runBlocking {
        branches(Clean)
        git.run(work, "switch", "feature")

        assertEquals(Either.Ok(Unit), keepingConfig("diff.algorithm=$algorithm") { rebaseInteractively(onto = "main") })

        assertEquals(RepositoryState.SAFE, repositoryState())
        assertEquals(Clean.result, File(work, FILE).readText())
    }

    @Test
    fun `a cherry-pick stops at conflicts with zdiff3's layout`(): Unit = runBlocking {
        branches(Conflict)

        val result = keepingConfig(ZDIFF3) { CherryPickCommitGitAction(jgit)(gitDir, commit("feature")) }

        assertEquals(
            "Cherry-pick stopped with conflicts. Fix the conflicts and commit the desired changes.",
            message(result),
        )
        assertEquals(RepositoryState.CHERRY_PICKING, repositoryState())
        assertEquals(Conflict.result, conflictIn(work))
    }

    @ParameterizedTest
    @ValueSource(strings = ["patience", "default", "minimal"])
    fun `a cherry-pick picks`(algorithm: String): Unit = runBlocking {
        branches(Clean)

        val result = keepingConfig("diff.algorithm=$algorithm") {
            CherryPickCommitGitAction(jgit)(gitDir, commit("feature"))
        }

        assertEquals(Either.Ok(Unit), result)
        assertEquals(Clean.result, File(work, FILE).readText())
    }

    @Test
    fun `a revert stops at conflicts with zdiff3's layout`(): Unit = runBlocking {
        val reverted = revertableCommit(Conflict)

        val result = keepingConfig(ZDIFF3) { RevertCommitGitAction(jgit)(gitDir, commit(reverted)) }

        assertEquals("Revert failed. Fix the conflicts and commit the desired changes.", message(result))
        assertEquals(Conflict.result, conflictIn(work))
    }

    @ParameterizedTest
    @ValueSource(strings = ["patience", "default", "minimal"])
    fun `a revert reverts`(algorithm: String): Unit = runBlocking {
        val reverted = revertableCommit(Clean)

        val result = keepingConfig("diff.algorithm=$algorithm") {
            RevertCommitGitAction(jgit)(gitDir, commit(reverted))
        }

        assertEquals(Either.Ok(Unit), result)
        assertEquals(Clean.result, File(work, FILE).readText())
    }

    @Test
    fun `a stash applies with conflicts in zdiff3's layout`(): Unit = runBlocking {
        val stash = stashOnOtherChange(Conflict)

        val result = keepingConfig(ZDIFF3) { ApplyStashGitAction(jgit)(gitDir, stash) }

        assertEquals("Applying stashed changes resulted in a conflict", message(result))
        assertEquals(Conflict.result, conflictIn(work))
    }

    @ParameterizedTest
    @ValueSource(strings = ["patience", "default", "minimal"])
    fun `a stash applies`(algorithm: String): Unit = runBlocking {
        val stash = stashOnOtherChange(Clean)

        val result = keepingConfig("diff.algorithm=$algorithm") { ApplyStashGitAction(jgit)(gitDir, stash) }

        assertEquals(Either.Ok(Unit), result)

        assertEquals(Clean.result, File(work, FILE).readText())
    }

    @ParameterizedTest
    @ValueSource(strings = ["patience", "default", "minimal"])
    fun `the diff of a changed file shows`(algorithm: String): Unit = runBlocking {
        commitFile(Clean.base)
        File(work, FILE).writeText(Clean.ours)
        val entry = StatusEntry(FILE, StatusType.MODIFIED, EntryType.UNSTAGED)

        val result = keepingConfig("diff.algorithm=$algorithm") {
            formatDiff()(gitDir, DiffType.UncommittedDiff(entry, EntryType.UNSTAGED), isDisplayFullFile = false)
        }

        val diff = assertInstanceOf(DiffResult.Text::class.java, (result as? Either.Ok)?.value, "$result")
        val changes = diff.hunks.flatMap { it.lines }.filter { it.lineType != LineType.CONTEXT }

        assertEquals(
            listOf("-2", "+two"),
            changes.map { (if (it.lineType == LineType.ADDED) "+" else "-") + it.text.trim() },
        )
    }

    @ParameterizedTest
    @ValueSource(strings = ["patience", "default", "minimal"])
    fun `a commit's changed files show`(algorithm: String): Unit = runBlocking {
        commitFile(Clean.base)
        commitFile(Clean.ours)

        val result = keepingConfig("diff.algorithm=$algorithm") {
            GetCommitDiffEntriesGitAction(jgit)(gitDir, commit("HEAD"))
        }

        assertEquals(listOf(FILE), (result as? Either.Ok)?.value?.map { it.newPath }, "$result")
    }

    /**
     * Runs [block] with [setting] (`section.key=value`) in the repository's config, if any, and checks that the config
     * file is as it was.
     */
    private suspend fun <T> keepingConfig(setting: String?, block: suspend () -> T): T {
        if (setting != null) {
            val (key, value) = setting.split("=", limit = 2)
            git.run(work, "config", key, value)
        }

        val config = File(gitDir, "config")
        val before = config.readText()

        return block().also { assertEquals(before, config.readText(), "The repository's config changed") }
    }

    private suspend fun merge(branch: String) = MergeBranchGitAction(jgit)(gitDir, branch(branch), fastForward = true)

    /** Starts an interactive rebase onto [onto] and runs its steps as they are, as the rebase screen does. */
    private suspend fun rebaseInteractively(onto: String): Either<Unit, GitError> {
        val started = StartRebaseInteractiveGitAction(jgit)(gitDir, commit(onto))

        if (started is Either.Err) {
            return started
        }

        val keepSteps = object : RebaseCommand.InteractiveHandler {
            override fun prepareSteps(steps: MutableList<RebaseTodoLine>?) {}
            override fun modifyCommitMessage(message: String?) = message.orEmpty()
        }

        return ResumeRebaseInteractiveGitAction(jgit)(gitDir, keepSteps)
    }

    private fun formatDiff(): FormatDiffGitAction {
        val getCurrentBranch = GetCurrentBranchGitAction(GetBranchesGitAction(JGitBranchMapper(), jgit), jgit)
        val getRepositoryState = GetRepositoryStateGitAction(jgit, RepositoryStateMapper())

        return FormatDiffGitAction(
            FormatHunksGitAction(),
            GetDiffContentGitAction(RawFileManager(TempFilesManager(AppFilesManager()))),
            CanGenerateTextDiffGitAction(),
            GetDiffEntryFromDiffTypeGitAction(
                GetDiffEntryFromStatusEntryGitAction(getRepositoryState, getCurrentBranch)
            ),
            GetSubmodulesGitAction(jgit, JGitSubmoduleMapper()),
            TextDiffFromDiffLinesGitAction(),
            jgit,
        )
    }

    /** [FILE] as [contents]' base on main, its theirs on the branch feature, and its ours on main, where it ends. */
    private fun branches(contents: MergeContents) {
        commitFile(contents.base)
        git.run(work, "switch", "-c", "feature")
        commitFile(contents.theirs)
        git.run(work, "switch", "main")
        commitFile(contents.ours)
    }

    /**
     * A rebase of feature onto main with [setting], stopped at feature's first commit, which conflicts in another file,
     * and then resolved. Its last commit changes [FILE] from [contents]' base to theirs, where main has ours.
     */
    private suspend fun rebaseStoppedBeforeLastCommit(contents: MergeContents, setting: String) {
        commitFile(contents.base)
        git.run(work, "switch", "-c", "feature")
        commitFile("feature's line\n", file = "other.txt")
        commitFile(contents.theirs)
        git.run(work, "switch", "main")
        commitFile("main's line\n", file = "other.txt")
        commitFile(contents.ours)
        git.run(work, "switch", "feature")

        assertEquals(Either.Ok(true), keepingConfig(setting) { RebaseBranchGitAction(jgit)(gitDir, branch("main")) })

        File(work, "other.txt").writeText("resolved\n")
        git.run(work, "add", "other.txt")
    }

    /**
     * A commit to revert that changed [FILE] from [contents]' theirs to its base, after which main changed it to ours,
     * so that reverting it merges ours and theirs from the base. Returns the commit's hash.
     */
    private fun revertableCommit(contents: MergeContents): String {
        commitFile(contents.theirs)
        commitFile(contents.base)
        val reverted = git.run(work, "rev-parse", "HEAD").trim()
        commitFile(contents.ours)

        return reverted
    }

    /** Stashes [contents]' theirs over its base, then commits its ours on main. Returns the stash. */
    private fun stashOnOtherChange(contents: MergeContents): Commit {
        commitFile(contents.base)
        File(work, FILE).writeText(contents.theirs)
        git.run(work, "stash")
        commitFile(contents.ours)

        return commit("stash@{0}")
    }

    /** What `git merge` with [setting] makes of feature, in a copy of the repository. */
    private fun mergedByGit(setting: String): String {
        val copy = File(tempDir, "copy")
        git.run(tempDir, "clone", "--branch", "main", work.absolutePath, copy.absolutePath)
        git.run(copy, "-c", setting, "merge", "--no-edit", "origin/feature")

        return File(copy, FILE).readText()
    }

    private fun commitFile(content: String, file: String = FILE) {
        File(work, file).writeText(content)
        git.run(work, "add", file)
        git.run(work, "commit", "-m", "Change $file")
    }

    private fun branch(name: String) =
        Branch(git.run(work, "rev-parse", name).trim(), "refs/heads/$name", isLocal = true)

    private fun commit(rev: String): Commit = Git.open(work).use { jgit ->
        RevWalk(jgit.repository).use { walk ->
            JGitCommitMapper(JGitIdentityMapper()).toDomain(walk.parseCommit(jgit.repository.resolve(rev)))
        }
    }

    private fun repositoryState() = Git.open(work).use { it.repository.repositoryState }

    /** The message of the [GenericError] that [result] failed with. */
    private fun message(result: Either<*, GitError>) = ((result as? Either.Err)?.error as? GenericError)?.message
}
