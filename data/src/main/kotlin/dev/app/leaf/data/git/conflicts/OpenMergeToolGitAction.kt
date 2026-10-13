// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.data.git.conflicts

import dev.app.leaf.common.printError
import dev.app.leaf.data.git.JGit
import dev.app.leaf.data.git.cli.GitCli
import dev.app.leaf.domain.errors.ConflictError
import dev.app.leaf.domain.errors.Either
import dev.app.leaf.domain.errors.GitCliError
import dev.app.leaf.domain.errors.GitError
import dev.app.leaf.domain.errors.bind
import dev.app.leaf.domain.errors.either
import dev.app.leaf.domain.errors.raiseError
import dev.app.leaf.domain.interfaces.IOpenMergeToolGitAction
import dev.app.leaf.domain.models.MergeToolTurn
import dev.app.leaf.domain.models.TaskProgress
import dev.app.leaf.domain.models.mergeToolRefusal
import dev.app.leaf.domain.repositories.RepositoryStateRepository
import java.io.File
import javax.inject.Inject
import kotlin.time.Duration

private const val TAG = "OpenMergeToolGitAction"

/**
 * Resolves a conflicted file in the user's merge tool with `git mergetool`, so that the tool and its settings are the
 * ones git uses in a terminal: `merge.guitool` or `merge.tool`, `mergetool.<tool>.cmd`, `.path`, `.trustExitCode`, and
 * the built-in tools' own settings, such as `mergetool.meld.useAutoMerge`. It runs in the tab's working tree, which may
 * be a linked worktree's.
 *
 * When the tool's exit code is trusted and the staged file still has conflict markers, the conflict is put back
 * ([ConflictError.MarkersLeft]).
 *
 * git's questions can't be answered, as stdin is closed:
 * - "Was the merge successful?", which git asks when it doesn't trust the tool's exit code and the file wasn't saved,
 *   reads the end of input and counts as no: git puts the file back as it was, and Leaf reports [ConflictError.NotResolved];
 * - the ones git asks about deletions, symbolic links and submodules, and before guessing a tool when none is set,
 *   are never reached: Leaf refuses those conflicts and passes `--tool` itself. When git stops at such a question, it
 *   leaves its temporary files behind.
 */
class OpenMergeToolGitAction @Inject constructor(
    private val jgit: JGit,
    private val gitCli: GitCli,
    private val repositoryStateRepository: RepositoryStateRepository,
) : IOpenMergeToolGitAction {
    override suspend fun invoke(
        repositoryPath: String,
        filePath: String,
        turn: MergeToolTurn?,
    ): Either<Unit, GitError> = either {
        val (workTree, conflict) = jgit.provide(repositoryPath) { git ->
            git.repository.workTree to git.repository.readDirCache().fileConflict(filePath)
        }.bind()

        if (conflict == null) {
            raiseError(ConflictError.NotConflicted(listOf(filePath)))
        }

        mergeToolRefusal(filePath, conflict)?.let { reason ->
            raiseError(ConflictError.MergeToolRefused(filePath, reason))
        }

        val config = readMergeToolConfig(gitCli, workTree).bind()
        val tool = config.tool ?: raiseError(ConflictError.NoMergeTool)

        val args = config.overrides() + listOf(
            // git-mergetool reads the file names back from git, which then takes them as they are
            "--literal-pathspecs",
            "mergetool",
            // Named, so that git never guesses one: it would ask before starting it, even with --no-prompt
            "--tool=$tool",
            "--no-prompt",
            "--",
            filePath,
        )

        // The processing screen's Cancel stops waiting: it kills git and what it started. A tool that detaches from
        // its starter, as `open -W -a Meld` does on macOS, stays open
        val count = turn?.let { " (${it.number} of ${it.count})" }.orEmpty()
        repositoryStateRepository.updateTaskProgress(TaskProgress(stage = "$tool: $filePath$count", percent = null))

        val output = try {
            // No timeout: the tool stays open as long as the user needs it
            gitCli.execute(workTree, args, timeout = Duration.INFINITE).bind()
        } finally {
            repositoryStateRepository.updateTaskProgress(null)
        }

        if (output.exitCode != 0) {
            val printed = listOf(output.stdout, output.stderr).filter { it.isNotBlank() }.joinToString("\n")
            raiseError(ConflictError.NotResolved(tool, filePath, printed.trim()))
        }

        if (hasConflictMarkers(workTree, filePath)) {
            // git staged what the tool left, as it trusts its exit code: a tool started with `open`, for example, exits
            // with 0 whether the file was saved or not. The conflict comes back, and the file stays as the tool left it,
            // with whatever the user resolved
            gitCli.run(workTree, listOf("--literal-pathspecs", "update-index", "--unresolve", "--", filePath)).bind()
            raiseError(ConflictError.MarkersLeft(tool, filePath))
        }

        Either.Ok(Unit)
    }

    /**
     * Whether the staged file still has lines that `git diff --check` takes as conflict markers. git stages what a tool
     * whose exit code it trusts leaves, even when the user closed it without saving. A check that fails counts as none,
     * leaving the file as git staged it.
     */
    private suspend fun hasConflictMarkers(workTree: File, filePath: String): Boolean {
        val output = gitCli.execute(
            workTree,
            listOf("--literal-pathspecs", "diff", "--cached", "--check", "--", filePath),
        )

        return when (output) {
            is Either.Ok -> output.value.stdout.lineSequence().any { it.endsWith(LEFTOVER_CONFLICT_MARKER) }
            is Either.Err -> {
                printError(TAG, "Checking $filePath for conflict markers failed: ${output.error}")
                false
            }
        }
    }
}

/** What `git diff --check` prints (`f.txt:2: leftover conflict marker`) for each marker line it finds. */
private const val LEFTOVER_CONFLICT_MARKER = ": leftover conflict marker"

/**
 * The merge tool that `git mergetool --gui` would pick, [tool], and whether the user set `mergetool.keepBackup` and
 * `mergetool.writeToTemp` themselves.
 */
internal data class MergeToolConfig(
    val tool: String?,
    val keepBackupSet: Boolean,
    val writeToTempSet: Boolean,
) {
    /**
     * Settings that Leaf changes, unless the user set them:
     * - `mergetool.keepBackup` (git's default: true) would leave `<file>.orig` in the working tree, which then shows in
     *   Unstaged and goes in with "Stage all". While the merge is in progress, `git checkout -m <file>` brings the
     *   conflict back anyway;
     * - `mergetool.writeToTemp` (git's default: false) would put the `_BASE_`, `_LOCAL_`, `_REMOTE_` and `_BACKUP_`
     *   copies next to the file, where git leaves them when it stops early, such as when the tool isn't installed.
     */
    fun overrides(): List<String> = buildList {
        if (!keepBackupSet) {
            addAll(listOf("-c", "mergetool.keepBackup=false"))
        }

        if (!writeToTempSet) {
            addAll(listOf("-c", "mergetool.writeToTemp=true"))
        }
    }
}

/** The merge tool settings that [OpenMergeToolGitAction] reads, from the git config of [workTree]. */
internal suspend fun readMergeToolConfig(gitCli: GitCli, workTree: File): Either<MergeToolConfig, GitError> = either {
    val output = gitCli.execute(
        workTree,
        listOf("config", "-z", "--get-regexp", """^merge\.(gui)?tool$|^mergetool\.(keepbackup|writetotemp)$"""),
    ).bind()

    // 1 means that none of the keys is set
    if (output.exitCode != 0 && output.exitCode != 1) {
        raiseError(GitCliError.CommandFailed("git config", output.exitCode, output.stderr.trim()))
    }

    Either.Ok(parseMergeToolConfig(output.stdout))
}

/**
 * Reads `git config -z --get-regexp` output: `<key>\n<value>\0` per entry, or `<key>\0` for a key without a value. git
 * lowercases the keys, and the last value of a key wins. An empty tool is no tool, as for git.
 */
internal fun parseMergeToolConfig(output: String): MergeToolConfig {
    val values = output
        .split('\u0000')
        .filter { it.isNotEmpty() }
        .associate { entry ->
            val key = entry.substringBefore('\n')
            val value = if ('\n' in entry) entry.substringAfter('\n') else null

            key to value
        }

    return MergeToolConfig(
        tool = values["merge.guitool"]?.takeIf { it.isNotBlank() } ?: values["merge.tool"]?.takeIf { it.isNotBlank() },
        keepBackupSet = "mergetool.keepbackup" in values,
        writeToTempSet = "mergetool.writetotemp" in values,
    )
}
