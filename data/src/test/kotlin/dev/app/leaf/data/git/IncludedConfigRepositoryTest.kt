// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.data.git

import dev.app.leaf.data.git.branches.MergeBranchGitAction
import dev.app.leaf.data.git.cli.ProcessRunner
import dev.app.leaf.data.git.cli.askpass.builtAskpassHelper
import dev.app.leaf.data.git.cli.remote.GitCliPullBranchGitAction
import dev.app.leaf.data.git.cli.remote.TestRemoteCommand
import dev.app.leaf.data.git.cli.remote.testPartialCloneFetch
import dev.app.leaf.data.git.config.GitIncludedConfig
import dev.app.leaf.data.git.log.CherryPickCommitGitAction
import dev.app.leaf.data.git.log.RevertCommitGitAction
import dev.app.leaf.data.git.rebase.RebaseBranchGitAction
import dev.app.leaf.data.git.rebase.ResumeRebaseInteractiveGitAction
import dev.app.leaf.data.git.rebase.StartRebaseInteractiveGitAction
import dev.app.leaf.data.git.signers.GpgProgramSigner
import dev.app.leaf.data.git.stash.CreateSnapshotStashGitAction
import dev.app.leaf.data.git.stash.DeleteStashGitAction
import dev.app.leaf.data.git.workspace.CheckHasUncommittedChangesGitAction
import dev.app.leaf.data.mappers.JGitCommitMapper
import dev.app.leaf.data.mappers.JGitIdentityMapper
import dev.app.leaf.data.shell.LoginShellEnvironment
import dev.app.leaf.domain.errors.Either
import dev.app.leaf.domain.errors.GitError
import dev.app.leaf.domain.errors.okOrNull
import dev.app.leaf.domain.models.Branch
import dev.app.leaf.domain.models.Commit
import dev.app.leaf.domain.models.Identity
import dev.app.leaf.domain.models.PullType
import kotlinx.coroutines.runBlocking
import org.eclipse.jgit.api.RebaseCommand
import org.eclipse.jgit.lib.GpgConfig
import org.eclipse.jgit.lib.PersonIdent
import org.eclipse.jgit.lib.RebaseTodoLine
import org.eclipse.jgit.lib.Signers
import org.eclipse.jgit.util.SystemReader
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.DisabledOnOs
import org.junit.jupiter.api.condition.OS
import org.junit.jupiter.api.io.TempDir
import java.io.File

private const val PERSONAL = "Personal Name <personal@example.com>"
private const val WORK = "Work Name <work@example.com>"
private const val AGENT = "Agent <agent@example.com>"
private const val PGP_SIGNATURE = "-----BEGIN PGP SIGNATURE-----\n\niHUEABYKAB0WIQRK\n-----END PGP SIGNATURE-----\n"

/**
 * The commits that JGit makes itself, their signatures, reflog entries and hooks, in a repository whose identity a
 * file that `includeIf` names sets ([IncludedConfigRepository]), compared with git (Gitnuro#186). git and JGit read
 * the same user config, and JGit skips its `includeIf`.
 */
@DisabledOnOs(OS.WINDOWS)
class IncludedConfigRepositoryTest {
    @TempDir
    lateinit var tempDir: File

    private val originalReader: SystemReader = SystemReader.getInstance()
    private lateinit var reader: IsolatedSystemReader

    // git matches includeIf's gitdir against the real path, which on macOS starts with /private
    private val root by lazy { tempDir.canonicalFile }

    /** The user's config: JGit's user config, and git's global config. */
    private val globalConfig by lazy { reader.userConfigFile.apply { parentFile.mkdirs() } }

    /** The file that the user's config includes for repositories under `work/`. */
    private val workConfig by lazy { File(root, "work.gitconfig") }

    /** What the login shell adds: git reads [globalConfig] and no system config. */
    private val shellVariables by lazy {
        mapOf(
            "GIT_CONFIG_GLOBAL" to globalConfig.path,
            "GIT_CONFIG_NOSYSTEM" to "1",
            "HOME" to File(root, "home").path,
            "XDG_CONFIG_HOME" to File(root, "xdg").path,
        )
    }

    private val gitCli by lazy { testGitCli(shellVariables) }
    private val includedConfig by lazy { GitIncludedConfig(gitCli, LoginShellEnvironment { shellVariables }) }
    private val jgit by lazy { testJGit(includedConfig = includedConfig) }

    @BeforeEach
    fun setUp() {
        reader = IsolatedSystemReader(File(tempDir, "config"), originalReader)
        SystemReader.setInstance(reader)
        workConfig.writeText("[user]\n\tname = Work Name\n\temail = work@example.com\n")
        globalConfig.writeText(
            "[user]\n\tname = Personal Name\n\temail = personal@example.com\n" +
                "[includeIf \"gitdir:${File(root, "work").path}/\"]\n\tpath = ${workConfig.path}\n"
        )
    }

    @AfterEach
    fun tearDown() {
        Signers.set(GpgConfig.GpgFormat.OPENPGP, null)
        SystemReader.setInstance(originalReader)
    }

    @Test
    fun `a merge from the branch menu is committed and logged as git's identity`(): Unit = runBlocking {
        val repository = workRepository()
        commitOnAgentBranch(repository)

        val result = mergeAgent(repository)

        assertEquals(false, result.okOrNull())
        assertEquals(WORK, gitIdentity(repository))
        assertEquals("$WORK|$WORK", identities(repository))
        assertEquals(WORK, git(repository, "reflog", "-1", "--format=%gn <%ge>").trim())
    }

    @Test
    fun `JGit alone commits as the identity that its own config gives, as before`(): Unit = runBlocking {
        val repository = workRepository()
        commitOnAgentBranch(repository)

        mergeAgent(repository, testJGit())

        assertEquals("$PERSONAL|$PERSONAL", identities(repository))
    }

    @Test
    fun `a revert is authored and committed as git's identity`(): Unit = runBlocking {
        val repository = workRepository()
        commitOnAgentBranch(repository)

        val result = RevertCommitGitAction(jgit, partialClone(jgit))(gitDir(repository), commit(repository, "HEAD"))

        assertInstanceOf(Either.Ok::class.java, result)

        assertEquals("$WORK|$WORK", identities(repository))
    }

    @Test
    fun `a cherry-pick keeps its author and is committed as git's identity`(): Unit = runBlocking {
        val repository = workRepository()
        commitOnAgentBranch(repository)

        val cherryPick = CherryPickCommitGitAction(jgit, partialClone(jgit))
        val result = cherryPick(gitDir(repository), commit(repository, "agent"))

        assertInstanceOf(Either.Ok::class.java, result)

        assertEquals("$AGENT|$WORK", identities(repository))
    }

    @Test
    fun `a rebase is committed as git's identity`(): Unit = runBlocking {
        val repository = workRepository()
        commitOnAgentBranch(repository)
        git(repository, "switch", "-q", "agent")

        val result = RebaseBranchGitAction(jgit, partialClone(jgit))(gitDir(repository), branch(repository, "main"))

        assertEquals(false, result.okOrNull())
        assertEquals("$AGENT|$WORK", identities(repository))
    }

    @Test
    fun `an interactive rebase is committed as git's identity`(): Unit = runBlocking {
        val repository = workRepository()
        commitOnAgentBranch(repository)
        stage(repository, "second.txt")
        git(repository, "commit", "-m", "Second on main", "--author=$AGENT")

        StartRebaseInteractiveGitAction(jgit, partialClone(jgit))(gitDir(repository), commit(repository, "HEAD~2"))
        val reword = object : RebaseCommand.InteractiveHandler {
            override fun prepareSteps(steps: MutableList<RebaseTodoLine>) {
                steps.first().action = RebaseTodoLine.Action.REWORD
            }

            override fun modifyCommitMessage(message: String): String = "Reworded"
        }
        val result = ResumeRebaseInteractiveGitAction(jgit)(gitDir(repository), reword)

        assertInstanceOf(Either.Ok::class.java, result)
        assertEquals("Reworded", git(repository, "log", "-1", "--format=%s", "HEAD~1").trim())
        assertEquals("$WORK|$WORK", identities(repository, "HEAD~1"))
        assertEquals("$AGENT|$WORK", identities(repository))
    }

    @Test
    fun `a pull's merge and rebase are committed as git's identity`(): Unit = runBlocking {
        val helper = builtAskpassHelper()
        assumeTrue(helper?.exists() == true) { "leaf-askpass isn't built, run ./gradlew :app:rustTasks" }
        val origin = File(root, "origin.git")
        git(root, "init", "-q", "--bare", "-b", "main", origin.path)
        val other = File(root, "other")
        git(root, "clone", "-q", origin.path, other.path)
        stage(other, "a.txt")
        git(other, "commit", "-m", "First")
        git(other, "push", "-q", "origin", "main")
        val repository = File(root, "work/repo")
        git(root, "clone", "-q", origin.path, repository.path)
        stage(other, "theirs.txt")
        git(other, "commit", "-m", "Theirs", "--author=$AGENT")
        git(other, "push", "-q", "origin", "main")
        stage(repository, "ours.txt")
        git(repository, "commit", "-m", "Ours")
        val remote = TestRemoteCommand(helper!!, globalConfig, shellVariables = shellVariables, jgit = jgit)
        val pull = GitCliPullBranchGitAction(
            jgit = jgit,
            remoteCommand = remote.command,
            gitLfsFetch = remote.gitLfsFetch,
            partialClone = remote.partialClone,
            checkHasUncommittedChangesGitAction = CheckHasUncommittedChangesGitAction(jgit),
            deleteStashGitAction = DeleteStashGitAction(jgit),
            commitMapper = JGitCommitMapper(JGitIdentityMapper()),
        )

        assertEquals(false, pull(gitDir(repository), PullType.MERGE, false, null, "automatic stash").okOrNull())
        assertEquals("$WORK|$WORK", identities(repository))

        git(repository, "reset", "-q", "--hard", "HEAD~1")
        assertEquals(false, pull(gitDir(repository), PullType.REBASE, false, null, "automatic stash").okOrNull())
        assertEquals("$WORK|$WORK", identities(repository))
        assertEquals("Theirs", git(repository, "log", "-1", "--format=%s", "HEAD~1").trim())
    }

    @Test
    fun `a snapshot stash names git's identity`(): Unit = runBlocking {
        val repository = workRepository()
        commitOnAgentBranch(repository)
        File(repository, "main.txt").writeText("changed\n")

        val createStash = CreateSnapshotStashGitAction(JGitCommitMapper(JGitIdentityMapper()), jgit)
        val stash = createStash(gitDir(repository), "Saved", true)

        assertInstanceOf(Either.Ok::class.java, stash)
        assertEquals("$WORK|$WORK", identities(repository, "refs/stash"))
    }

    @Test
    fun `a merge is signed as a file that includeIf names says`(): Unit = runBlocking {
        val gpg = File(root, "bin/gpg").writeExecutable(
            "#!/bin/sh\ncat > /dev/null\n" +
                "echo '[GNUPG:] SIG_CREATED D 22 10 00 1791381795 4AE4445EFE49B9DFFBC8E1A8B4CE4B3E4E90D11A' >&2\n" +
                "printf '%s' '$PGP_SIGNATURE'\n"
        )
        workConfig.appendText("[commit]\n\tgpgSign = true\n[gpg]\n\tprogram = ${gpg.path}\n")
        val signer = GpgProgramSigner(ProcessRunner(), LoginShellEnvironment { emptyMap() })
        Signers.set(GpgConfig.GpgFormat.OPENPGP, signer)
        val repository = workRepository()
        commitOnAgentBranch(repository)

        mergeAgent(repository)

        assertTrue(git(repository, "cat-file", "commit", "HEAD").contains("gpgsig -----BEGIN PGP SIGNATURE-----"))
    }

    @Test
    fun `JGit's commits run the hooks of a folder that a file that includeIf names sets`(): Unit = runBlocking {
        val hooks = File(root, "work-hooks")
        val ran = File(root, "ran")
        File(hooks, "pre-commit").writeExecutable("#!/bin/sh\ntouch '$ran'\n")
        workConfig.appendText("[core]\n\thooksPath = ${hooks.path}\n")
        val repository = workRepository()
        commitOnAgentBranch(repository)

        mergeAgent(repository)

        assertTrue(ran.exists())
    }

    @Test
    fun `saving the repository's config writes only its own entries`(): Unit = runBlocking {
        val repository = workRepository()
        val before = git(repository, "config", "--local", "--list").lines().filter { it.isNotEmpty() }

        val name = jgit.provide(gitDir(repository)) { git ->
            git.repository.config.apply { setString("leaf", null, "test", "saved") }.save()
            git.repository.config.getString("user", null, "name")
        }

        assertEquals("Work Name", name.okOrNull())
        val after = git(repository, "config", "--local", "--list").lines().filter { it.isNotEmpty() }
        assertEquals(before + "leaf.test=saved", after)
    }

    @Test
    fun `reads the included file again once it changes, and the branch's file once the branch changes`(): Unit =
        runBlocking {
            File(root, "release.gitconfig").writeText("[user]\n\temail = release@example.com\n")
            val release = File(root, "release.gitconfig")
            globalConfig.appendText("[includeIf \"onbranch:release/**\"]\n\tpath = ${release.path}\n")
            val repository = workRepository()
            git(repository, "commit", "-q", "--allow-empty", "-m", "First")

            assertEquals(WORK, jgitIdentity(repository))

            workConfig.writeText("[user]\n\tname = Renamed Work\n\temail = work@example.com\n")
            assertEquals("Renamed Work <work@example.com>", jgitIdentity(repository))

            git(repository, "switch", "-q", "-c", "release/1")
            assertEquals("Renamed Work <release@example.com>", jgitIdentity(repository))
            assertEquals(gitIdentity(repository), jgitIdentity(repository))
        }

    @Test
    fun `a value that the user's config sets again after the include stays the last one, as in git`(): Unit =
        runBlocking {
            globalConfig.appendText("[user]\n\temail = later@example.com\n")
            val repository = workRepository()

            assertEquals("Work Name <later@example.com>", gitIdentity(repository))
            assertEquals(gitIdentity(repository), jgitIdentity(repository))
        }

    @Test
    fun `a repository that no includeIf applies to reads its config as before`(): Unit = runBlocking {
        globalConfig.appendText("[test]\n\tmulti = one\n\tmulti = two\n")
        val repository = initRepository(File(root, "personal/repo"))

        val multi = jgit.provide(gitDir(repository)) { it.repository.config.getStringList("test", null, "multi") }

        assertEquals(listOf("one", "two"), multi.okOrNull()?.toList())
        assertEquals(PERSONAL, jgitIdentity(repository))
    }

    @Test
    fun `without a usable git, reads the config as JGit does, as before`(): Unit = runBlocking {
        val repository = workRepository()
        val withoutGit = testGitCli(shellVariables, configuredPath = File(root, "missing/git").path)
        val jgit = testJGit(includedConfig = GitIncludedConfig(withoutGit, LoginShellEnvironment { shellVariables }))

        assertEquals(PERSONAL, jgitIdentity(repository, jgit))
    }

    private fun workRepository() = initRepository(File(root, "work/repo"))

    private fun initRepository(directory: File): File {
        directory.mkdirs()
        git(directory, "init", "-q", "-b", "main")

        return directory
    }

    /** A branch `agent` with a commit that someone else authored, and `main` checked out with a commit of its own. */
    private fun commitOnAgentBranch(repository: File) {
        stage(repository, "a.txt")
        git(repository, "commit", "--no-gpg-sign", "-m", "First")
        git(repository, "switch", "-q", "-c", "agent")
        stage(repository, "agent.txt")
        git(repository, "commit", "--no-gpg-sign", "-m", "Agent's work", "--author=$AGENT")
        git(repository, "switch", "-q", "main")
        stage(repository, "main.txt")
        git(repository, "commit", "--no-gpg-sign", "-m", "On main")
    }

    private fun stage(repository: File, name: String) {
        File(repository, name).writeText("$name\n")
        git(repository, "add", name)
    }

    private fun branch(repository: File, name: String) =
        Branch(git(repository, "rev-parse", name).trim(), "refs/heads/$name", isLocal = true)

    private fun commit(repository: File, revision: String): Commit {
        val identity = Identity("Someone", "someone@example.com")

        return Commit(git(repository, "rev-parse", revision).trim(), "", identity, identity, 0, emptyList())
    }

    private fun identities(repository: File, revision: String = "HEAD") =
        git(repository, "log", "-1", "--format=%an <%ae>|%cn <%ce>", revision).trim()

    /** Who git commits as in [repository]. */
    private fun gitIdentity(repository: File) =
        git(repository, "var", "GIT_COMMITTER_IDENT").trim().substringBefore("> ") + ">"

    /** Who JGit commits as in [repository], through [jgit]'s cache. */
    private suspend fun jgitIdentity(repository: File, jgit: JGit = this.jgit): String? {
        return jgit.provide(gitDir(repository)) { PersonIdent(it.repository).let { "${it.name} <${it.emailAddress}>" } }
            .okOrNull()
    }

    private fun gitDir(repository: File) = File(repository, ".git").path

    private fun partialClone(jgit: JGit) = testPartialCloneFetch(jgit, globalConfig)

    /** Merges `agent` into `main` with the branch menu's action, without fast-forwarding. */
    private suspend fun mergeAgent(repository: File, jgit: JGit = this.jgit): Either<Boolean, GitError> {
        val merge = MergeBranchGitAction(jgit, partialClone(jgit))

        return merge(gitDir(repository), branch(repository, "agent"), fastForward = false)
    }

    /** Runs git as Leaf runs it, with [shellVariables]. */
    private fun git(directory: File, vararg args: String): String = runBlocking {
        when (val result = gitCli.run(directory, args.toList())) {
            is Either.Ok -> result.value
            is Either.Err -> error("git ${args.joinToString(" ")} failed: ${result.error}")
        }
    }
}
