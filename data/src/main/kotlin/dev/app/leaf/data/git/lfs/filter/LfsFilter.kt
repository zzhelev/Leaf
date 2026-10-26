// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.data.git.lfs.filter

import dev.app.leaf.data.git.JGit
import dev.app.leaf.data.git.cli.askpass.AskpassFilter
import dev.app.leaf.data.git.cli.remote.quotedForShell
import dev.app.leaf.data.git.lfs.FetchLfsObjectsGitAction
import dev.app.leaf.data.git.lfs.GitLfsSetup
import dev.app.leaf.data.git.lfs.LfsSmudgeSettings
import dev.app.leaf.data.git.lfs.lfsStore
import dev.app.leaf.data.shell.LoginShellEnvironment
import dev.app.leaf.domain.errors.LfsError
import dev.app.leaf.domain.errors.LfsFileFailure
import dev.app.leaf.domain.errors.okOrNull
import dev.app.leaf.domain.interfaces.IGetLfsUrlGitAction
import dev.app.leaf.domain.lfs.LfsObjectBatch
import org.eclipse.jgit.lfs.LfsPointer
import org.eclipse.jgit.lib.Repository
import org.eclipse.jgit.storage.file.FileRepositoryBuilder
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.Collections
import javax.inject.Inject

/**
 * Leaf's built-in LFS client as git's LFS filter, for the git commands that Leaf runs with its askpass helper
 * (fork-only, `docs/fork/lfs-filter.md`): where git-lfs isn't set up, git checks LFS files out and stages them through
 * Leaf, instead of writing pointer files, storing the files in git, or failing to run git-lfs.
 */
class LfsFilter @Inject constructor(
    private val gitLfsSetup: GitLfsSetup,
    private val jgit: JGit,
    private val getLfsUrlGitAction: IGetLfsUrlGitAction,
    private val fetchLfsObjectsGitAction: FetchLfsObjectsGitAction,
    private val loginShellEnvironment: LoginShellEnvironment,
) {
    /**
     * Leaf's filter for a git command that runs in [workingDirectory] with the askpass [helper], or null when git-lfs
     * is set up there and does the work ([GitLfsSetup.gitRunsGitLfs]). git starts the filter only for a file whose
     * attributes name it, so a repository without LFS never does. [onProgress] gets the downloads' progress.
     */
    suspend fun forCommand(
        workingDirectory: File,
        helper: File,
        onProgress: (stage: String, percent: Int?) -> Unit,
    ): LfsFilterRun? {
        if (gitLfsSetup.gitRunsGitLfs(workingDirectory)) {
            return null
        }

        return LfsFilterRun(workingDirectory, helper) { directory -> openSession(directory, onProgress) }
    }

    private suspend fun openSession(directory: File, onProgress: (String, Int?) -> Unit): LfsFilterSession {
        val gitDir = try {
            FileRepositoryBuilder().setWorkTree(directory).setup().gitDir
        } catch (e: IllegalArgumentException) {
            throw IOException("No repository at $directory", e)
        }

        val repository = jgit.provide(gitDir.absolutePath) { git -> git.repository }.okOrNull()
            ?: throw IOException("Couldn't open the repository at $directory")
        val environment = System.getenv() + loginShellEnvironment.variables()

        return LfsFilterSession(
            store = repository.lfsStore(),
            settings = LfsSmudgeSettings(repository.config, environment),
        ) { pointers -> download(repository, pointers, onProgress) }
    }

    private suspend fun download(
        repository: Repository,
        pointers: List<LfsPointer>,
        onProgress: (String, Int?) -> Unit,
    ): Map<String, LfsError> {
        val server = getLfsUrlGitAction(repository, remoteName = null)
            ?: return pointers.associate { it.oid.name() to LfsError.NoServer }
        val total = pointers.sumOf { it.size }

        return fetchLfsObjectsGitAction(
            repository,
            server,
            pointers.map { LfsObjectBatch(it.oid.name(), it.size) },
        ) { done, receivedBytes ->
            val percent = if (total > 0) (receivedBytes * 100 / total).toInt().coerceIn(0, 100) else null
            onProgress("Downloading LFS objects (${minOf(done + 1, pointers.size)} of ${pointers.size})", percent)
        }
    }
}

/**
 * Leaf's LFS filter for one git command: the [arguments] that make git run it, the sessions that git starts (one per
 * repository it checks out, a submodule's too), and the files whose objects they couldn't download.
 */
class LfsFilterRun internal constructor(
    private val workingDirectory: File,
    helper: File,
    private val openSession: suspend (directory: File) -> LfsFilterSession,
) : AskpassFilter {
    /**
     * git's options for the command: `-c` applies after every config file, and reaches the gits that the command
     * starts (`GIT_CONFIG_PARAMETERS`), in submodules too. `process` wins over git-lfs's `smudge` and `clean`, and
     * `required` makes a failed download fail the command, as with git-lfs, instead of writing the pointer.
     */
    val arguments: List<String> = listOf(
        "-c", "filter.lfs.process=${quotedForShell(helper)} lfs-filter",
        "-c", "filter.lfs.required=true",
    )

    private val failures = Collections.synchronizedList(mutableListOf<LfsFileFailure>())

    /** The files whose objects couldn't be downloaded, with their paths from [workingDirectory]. */
    val failedFiles: List<LfsFileFailure> get() = synchronized(failures) { failures.toList() }

    override suspend fun serve(directory: File, input: InputStream, output: OutputStream) {
        val session = openSession(directory)

        try {
            session.run(input, output)
        } finally {
            for ((path, error) in session.failures) {
                failures += LfsFileFailure(pathFromWorkingDirectory(directory, path), error)
            }
        }
    }

    /** A submodule's file, as the path from the command's working tree. */
    private fun pathFromWorkingDirectory(directory: File, path: String): String {
        val file = File(directory, path).canonicalFile
        val relative = file.toRelativeStringOrNull(workingDirectory.canonicalFile)

        return relative ?: path
    }
}

private fun File.toRelativeStringOrNull(base: File): String? {
    val relative = runCatching { relativeTo(base) }.getOrNull() ?: return null

    return relative.invariantSeparatorsPath.takeUnless { it.startsWith("..") }
}
