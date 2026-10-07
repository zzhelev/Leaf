package dev.app.leaf.data.git

import org.eclipse.jgit.api.errors.JGitInternalException
import org.eclipse.jgit.lib.Constants
import org.eclipse.jgit.lib.Repository
import org.eclipse.jgit.util.FS
import org.eclipse.jgit.util.FS_Win32
import org.eclipse.jgit.util.ProcessResult
import java.io.File
import java.io.IOException
import java.io.OutputStream
import javax.inject.Inject

/**
 * Version of the Windows FS with support for hooks by using Git Bash if present.
 *
 * The hook is found like JGit finds it on other systems: in `core.hooksPath`, or in the `hooks` folder of the common
 * git dir, which linked worktrees share. It runs in the same folder and with the same `GIT_*` variables as JGit's own
 * hook runner, gets its arguments and standard input, and both of its output streams are read while it runs.
 */
class WindowsFs : FS_Win32 {
    private val findGitBash: () -> GitBash?

    @Inject
    constructor() : this({ GitBash.find(System.getenv("PATH"), System::getenv) })

    internal constructor(findGitBash: () -> GitBash?) : super() {
        this.findGitBash = findGitBash
    }

    private constructor(source: WindowsFs) : super(source) {
        findGitBash = source.findGitBash
    }

    override fun newInstance(): FS = WindowsFs(this)

    override fun runHookIfPresent(
        repository: Repository,
        hookName: String,
        args: Array<out String?>,
        outRedirect: OutputStream?,
        errRedirect: OutputStream?,
        stdinArgs: String?
    ): ProcessResult {
        val hook = findHook(repository, hookName) ?: return ProcessResult(ProcessResult.Status.NOT_PRESENT)

        val gitBash = findGitBash() ?: throw JGitInternalException(
            "Git Bash was not found. It is needed to run the hook '$hookName' on Windows"
        )

        // In a linked worktree or a submodule, commit-msg gets the message file's absolute path, with forward slashes
        val hookArgs = hookArguments(repository, hookName, args) { it.absolutePath.replace('\\', '/') }

        val processBuilder = ProcessBuilder(gitBash.hookCommand(hook.absolutePath, hookArgs.map { it.orEmpty() }))
            .directory(hookRunDirectory(repository))

        processBuilder.environment().apply {
            put(Constants.GIT_DIR_KEY, repository.directory.absolutePath)

            if (!repository.isBare) {
                put(Constants.GIT_COMMON_DIR_KEY, repository.commonDirectory.absolutePath)
                put(Constants.GIT_WORK_TREE_KEY, repository.workTree.absolutePath)
            }
        }

        return try {
            // runProcess reads both output streams while the hook runs and closes its input after stdinArgs
            val exitCode = runProcess(processBuilder, outRedirect, errRedirect, stdinArgs)

            // ProcessResult(Status.OK) alone means exit code -1, which JGit takes as a failed hook
            ProcessResult(exitCode, ProcessResult.Status.OK)
        } catch (ex: IOException) {
            throw JGitInternalException("Could not run the hook '$hookName' with ${gitBash.executable}", ex)
        } catch (ex: InterruptedException) {
            throw JGitInternalException("The hook '$hookName' was interrupted", ex)
        }
    }

    /** Where JGit runs the client-side hooks it supports: the working tree, or the git dir of a bare repository. */
    private fun hookRunDirectory(repository: Repository): File {
        val directory = if (repository.isBare) repository.directory else repository.workTree

        return directory.absoluteFile
    }
}
