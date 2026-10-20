// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.data.git.author

import dev.app.leaf.data.git.IsolatedSystemReader
import dev.app.leaf.data.git.cli.GitCli
import dev.app.leaf.data.git.cli.remote.testPartialCloneFetch
import dev.app.leaf.data.git.log.CherryPickCommitGitAction
import dev.app.leaf.data.git.repository.GetRepositoryStateGitAction
import dev.app.leaf.data.git.signers.GitSigningConfigs
import dev.app.leaf.data.git.stash.StashChangesGitAction
import dev.app.leaf.data.git.tags.CreateTagGitAction
import dev.app.leaf.data.git.testGitCli
import dev.app.leaf.data.git.testJGit
import dev.app.leaf.data.git.workspace.DoCommitGitAction
import dev.app.leaf.data.mappers.JGitCommitMapper
import dev.app.leaf.data.mappers.JGitIdentityMapper
import dev.app.leaf.data.mappers.RepositoryStateMapper
import dev.app.leaf.domain.errors.Either
import dev.app.leaf.domain.errors.okOrNull
import dev.app.leaf.domain.models.Commit
import dev.app.leaf.domain.models.Identity
import kotlinx.coroutines.runBlocking
import org.eclipse.jgit.lib.PersonIdent
import org.eclipse.jgit.util.SystemReader
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.DisabledOnOs
import org.junit.jupiter.api.condition.OS
import org.junit.jupiter.api.io.TempDir
import java.io.File

private const val PERSONAL = "Personal Name <personal@example.com>"
private const val WORK = "Work Name <work@example.com>"
private const val AGENT_DATE = "2026-01-02T03:04:05+01:00"

/**
 * Who Leaf commits, tags and stashes as, compared with git, when an `includeIf` file sets the identity (upstream issue
 * 186). git and JGit read the same user config, and JGit skips its `includeIf`.
 */
@DisabledOnOs(OS.WINDOWS)
class CommitIdentityTest {
    @TempDir
    lateinit var tempDir: File

    private val originalReader: SystemReader = SystemReader.getInstance()
    private lateinit var reader: IsolatedSystemReader

    // git matches includeIf's gitdir against the real path, which on macOS starts with /private
    private val root by lazy { tempDir.canonicalFile }

    /** The user's config: JGit's user config, and git's global config. */
    private val globalConfig by lazy { reader.userConfigFile.apply { parentFile.mkdirs() } }

    /** What the login shell adds: git reads [globalConfig] and no system config. */
    private val shellVariables by lazy {
        mutableMapOf(
            "GIT_CONFIG_GLOBAL" to globalConfig.path,
            "GIT_CONFIG_NOSYSTEM" to "1",
            "HOME" to File(root, "home").path,
            "XDG_CONFIG_HOME" to File(root, "xdg").path,
        )
    }

    private val gitCli by lazy { testGitCli(shellVariables) }
    private val jgit = testJGit()

    @BeforeEach
    fun setUp() {
        reader = IsolatedSystemReader(File(tempDir, "config"), originalReader)
        SystemReader.setInstance(reader)
    }

    @AfterEach
    fun tearDown() {
        SystemReader.setInstance(originalReader)
    }

    @Test
    fun `commits as the identity that an includeIf file sets, as git commit does`(): Unit = runBlocking {
        writeGlobalConfig(personalIdentity = true)
        val repository = workRepository()

        stage(repository, "a.txt")
        assertInstanceOf(Either.Ok::class.java, doCommit()(gitDir(repository), "By Leaf", false, null))
        stage(repository, "b.txt")
        git(repository, "commit", "-m", "By git")

        assertEquals("$WORK|$WORK", identities(repository, "HEAD~1"))
        assertEquals(identities(repository, "HEAD"), identities(repository, "HEAD~1"))
        // JGit alone commits as the global identity
        assertEquals("Personal Name", jgit.provide(gitDir(repository)) { PersonIdent(it.repository).name }.okOrNull())
    }

    @Test
    fun `an amended commit keeps its author, and gets git's committer`(): Unit = runBlocking {
        writeGlobalConfig(personalIdentity = true)
        val repository = workRepository()
        stage(repository, "a.txt")
        git(repository, "commit", "-m", "First", "--author=Other Author <other@example.com>")

        stage(repository, "b.txt")
        assertInstanceOf(Either.Ok::class.java, doCommit()(gitDir(repository), "Amended", true, null))

        assertEquals("Other Author <other@example.com>|$WORK", identities(repository))
        assertEquals("Amended", git(repository, "log", "-1", "--format=%s").trim())
    }

    @Test
    fun `a squash merge of an agent's branch is committed as git's identity`(): Unit = runBlocking {
        writeGlobalConfig(personalIdentity = true)
        val repository = workRepository()
        commitOnAgentBranch(repository)

        git(repository, "merge", "--squash", "agent")
        assertInstanceOf(Either.Ok::class.java, doCommit()(gitDir(repository), "Squashed", false, null))

        assertEquals("$WORK|$WORK", identities(repository))
    }

    @Test
    fun `a merge from the worktree dialog is committed as git's identity`(): Unit = runBlocking {
        writeGlobalConfig(personalIdentity = true)
        val repository = workRepository()
        commitOnAgentBranch(repository)

        git(repository, "merge", "--no-ff", "--no-commit", "agent")
        assertInstanceOf(Either.Ok::class.java, doCommit()(gitDir(repository), "Merged", false, null))

        assertEquals("$WORK|$WORK", identities(repository))
        assertEquals(2, git(repository, "log", "-1", "--format=%p").trim().split(" ").size)
    }

    @Test
    fun `a resolved cherry-pick keeps the picked commit's author, as git commit does`(): Unit = runBlocking {
        writeGlobalConfig(personalIdentity = true)
        val repository = workRepository()
        stage(repository, "a.txt")
        git(repository, "commit", "-m", "First")
        git(repository, "switch", "-q", "-c", "agent")
        File(repository, "a.txt").writeText("Agent's change\n")
        git(repository, "commit", "-a", "-m", "Agent's change", "--author=Agent <agent@example.com>")
        git(repository, "commit", "--amend", "--no-edit", "--date=$AGENT_DATE")
        val picked = git(repository, "rev-parse", "HEAD").trim()
        git(repository, "switch", "-q", "main")
        File(repository, "a.txt").writeText("Main's change\n")
        git(repository, "commit", "-a", "-m", "Main's change")
        val start = git(repository, "rev-parse", "HEAD").trim()
        val agent = Identity("Agent", "agent@example.com")

        // Leaf's cherry-pick stops at the conflict, and Leaf commits the resolution with the message it left
        val pick = CherryPickCommitGitAction(jgit, testPartialCloneFetch(jgit))(
            gitDir(repository),
            Commit(picked, "Agent's change", agent, agent, 0, emptyList()),
        )
        assertInstanceOf(Either.Err::class.java, pick)
        resolveConflict(repository)
        val message = File(repository, ".git/MERGE_MSG").readText()
        assertInstanceOf(Either.Ok::class.java, doCommit()(gitDir(repository), message, false, null))
        val byLeaf = identitiesAndAuthorDate(repository)

        git(repository, "reset", "-q", "--hard", start)
        assertEquals(1, gitCli.execute(repository, listOf("cherry-pick", picked)).okOrNull()?.exitCode)
        resolveConflict(repository)
        git(repository, "commit", "--no-edit")

        assertEquals("Agent <agent@example.com> 2026-01-02 03:04:05 +0100|$WORK", byLeaf)
        assertEquals(identitiesAndAuthorDate(repository), byLeaf)
    }

    @Test
    fun `asks for an identity only when git has none`(): Unit = runBlocking {
        writeGlobalConfig(personalIdentity = false)
        val repository = workRepository()
        val elsewhere = initRepository(File(root, "elsewhere/repo"))

        val inWork = LoadAuthorGitAction(jgit, GitIdentities(gitCli))(gitDir(repository)).okOrNull()!!
        val outsideWork = LoadAuthorGitAction(jgit, GitIdentities(gitCli))(gitDir(elsewhere)).okOrNull()!!

        assertEquals(Identity(null, null), inWork.globalIdentity)
        assertEquals(Identity("Work Name", "work@example.com"), inWork.gitIdentity)
        assertEquals(Identity("Work Name", "work@example.com"), inWork.identityToUse())
        assertFalse(inWork.isIdentityMissing())
        assertNull(outsideWork.gitIdentity)
        assertTrue(outsideWork.isIdentityMissing())
    }

    @Test
    fun `the identity given for a commit is its author and its committer`(): Unit = runBlocking {
        writeGlobalConfig(personalIdentity = false)
        val repository = initRepository(File(root, "elsewhere/repo"))
        stage(repository, "a.txt")

        val given = Identity("Given Name", "given@example.com")
        assertInstanceOf(Either.Ok::class.java, doCommit()(gitDir(repository), "Given", false, given))

        assertEquals("Given Name <given@example.com>|Given Name <given@example.com>", identities(repository))
    }

    @Test
    fun `each worktree commits as the identity that its branch gives, as git does`(): Unit = runBlocking {
        writeGlobalConfig(personalIdentity = true)
        File(root, "release.gitconfig").writeText("[user]\n\temail = release@example.com\n")
        globalConfig.appendText("[includeIf \"onbranch:release/**\"]\n\tpath = ${File(root, "release.gitconfig")}\n")
        val main = initRepository(File(root, "main"))
        git(main, "commit", "--allow-empty", "-m", "Initial commit")
        val release = File(root, "release")
        git(main, "worktree", "add", "-b", "release/1", release.path)
        val releaseGitDir = File(git(release, "rev-parse", "--absolute-git-dir").trim())

        stage(release, "a.txt")
        assertInstanceOf(Either.Ok::class.java, doCommit()(releaseGitDir.path, "On release", false, null))
        stage(main, "b.txt")
        assertInstanceOf(Either.Ok::class.java, doCommit()(gitDir(main), "On main", false, null))

        val onRelease = "Personal Name <release@example.com>"
        assertEquals("$onRelease|$onRelease", identities(release))
        assertEquals("$PERSONAL|$PERSONAL", identities(main))
    }

    @Test
    fun `tags and stashes name git's committer`(): Unit = runBlocking {
        writeGlobalConfig(personalIdentity = true)
        val repository = workRepository()
        stage(repository, "a.txt")
        git(repository, "commit", "-m", "First")
        val head = git(repository, "rev-parse", "HEAD").trim()
        val identity = Identity("Work Name", "work@example.com")

        val tag = CreateTagGitAction(jgit, GitIdentities(gitCli), GitSigningConfigs(gitCli))(
            gitDir(repository),
            "v1",
            Commit(head, "First", identity, identity, 0, emptyList()),
        )
        File(repository, "a.txt").writeText("changed\n")
        val stash = StashChangesGitAction(jgit, GitIdentities(gitCli))(gitDir(repository), "Saved")

        assertInstanceOf(Either.Ok::class.java, tag)
        assertInstanceOf(Either.Ok::class.java, stash)
        val tagger = git(repository, "for-each-ref", "--format=%(taggername) %(taggeremail)", "refs/tags/v1")
        assertEquals(WORK, tagger.trim())
        assertEquals("$WORK|$WORK", identities(repository, "refs/stash"))
    }

    @Test
    fun `takes the author from the login shell and the config's author section, as git does`(): Unit = runBlocking {
        writeGlobalConfig(personalIdentity = true)
        File(root, "work.gitconfig").appendText("[author]\n\temail = author@example.com\n")
        shellVariables["GIT_AUTHOR_NAME"] = "Shell Author"
        val repository = workRepository()

        stage(repository, "a.txt")
        assertInstanceOf(Either.Ok::class.java, doCommit()(gitDir(repository), "By Leaf", false, null))
        stage(repository, "b.txt")
        git(repository, "commit", "-m", "By git")

        assertEquals("Shell Author <author@example.com>|$WORK", identities(repository, "HEAD~1"))
        assertEquals(identities(repository, "HEAD"), identities(repository, "HEAD~1"))
    }

    @Test
    fun `without a usable git, commits as JGit's identity, as before`(): Unit = runBlocking {
        writeGlobalConfig(personalIdentity = true)
        val repository = workRepository()
        val withoutGit = testGitCli(shellVariables, configuredPath = File(root, "missing/git").path)
        stage(repository, "a.txt")

        val result = doCommit(withoutGit)(gitDir(repository), "Without git", false, null)
        val authorInfo = LoadAuthorGitAction(jgit, GitIdentities(withoutGit))(gitDir(repository)).okOrNull()!!

        assertInstanceOf(Either.Ok::class.java, result)
        assertEquals("$PERSONAL|$PERSONAL", identities(repository))
        assertNull(authorInfo.gitIdentity)
        assertEquals(Identity("Personal Name", "personal@example.com"), authorInfo.identityToUse())
    }

    /** The user's config: [PERSONAL] when [personalIdentity], and [WORK] for the repositories under `work/`. */
    private fun writeGlobalConfig(personalIdentity: Boolean) {
        val workConfig = File(root, "work.gitconfig")
        workConfig.writeText("[user]\n\tname = Work Name\n\temail = work@example.com\n")

        val personal = if (personalIdentity) "[user]\n\tname = Personal Name\n\temail = personal@example.com\n" else ""
        val includeIf = "[includeIf \"gitdir:${File(root, "work").path}/\"]\n\tpath = ${workConfig.path}\n"
        globalConfig.writeText(personal + includeIf)
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
        git(repository, "commit", "-m", "First")
        git(repository, "switch", "-q", "-c", "agent")
        stage(repository, "agent.txt")
        git(repository, "commit", "-m", "Agent's work", "--author=Agent <agent@example.com>")
        git(repository, "switch", "-q", "main")
        stage(repository, "main.txt")
        git(repository, "commit", "-m", "On main")
    }

    private fun stage(repository: File, name: String) {
        File(repository, name).writeText("$name\n")
        git(repository, "add", name)
    }

    private fun identities(repository: File, revision: String = "HEAD") =
        git(repository, "log", "-1", "--format=%an <%ae>|%cn <%ce>", revision).trim()

    private fun identitiesAndAuthorDate(repository: File) =
        git(repository, "log", "-1", "--format=%an <%ae> %ai|%cn <%ce>").trim()

    private fun resolveConflict(repository: File) {
        File(repository, "a.txt").writeText("Both changes\n")
        git(repository, "add", "a.txt")
    }

    private fun gitDir(repository: File) = File(repository, ".git").path

    private fun doCommit(gitCli: GitCli = this.gitCli) = DoCommitGitAction(
        GetRepositoryStateGitAction(jgit, RepositoryStateMapper()),
        JGitCommitMapper(JGitIdentityMapper()),
        JGitIdentityMapper(),
        jgit,
        GitIdentities(gitCli),
        GitSigningConfigs(gitCli),
    )

    /** Runs git as Leaf runs it, with [shellVariables]. */
    private fun git(directory: File, vararg args: String): String = runBlocking {
        when (val result = gitCli.run(directory, args.toList())) {
            is Either.Ok -> result.value
            is Either.Err -> error("git ${args.joinToString(" ")} failed: ${result.error}")
        }
    }
}
