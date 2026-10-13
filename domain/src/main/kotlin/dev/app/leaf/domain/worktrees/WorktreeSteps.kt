// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.domain.worktrees

import dev.app.leaf.domain.models.Worktree
import dev.app.leaf.domain.models.WorktreeRemoval
import java.nio.file.InvalidPathException
import java.nio.file.Path

/**
 * One step of a worktree action (fork-only). The action's dialog shows its steps, and the same steps run once the user
 * confirms, so what the dialog shows is what runs.
 */
sealed interface WorktreeStep {
    /**
     * `git <args>`, run in [folder].
     *
     * @param mayStopAtConflicts the command is a merge or a rebase, which can stop at conflicts for the user to
     * resolve. Then the steps go on, and the action ends as one that stopped at conflicts, not as a failure.
     */
    data class Git(
        val folder: String,
        val args: List<String>,
        val mayStopAtConflicts: Boolean = false,
    ) : WorktreeStep

    /**
     * Switches the tab to the worktree in [folder], before the steps that follow.
     *
     * @param onlyIfStoppedAtConflicts only when a command before it stopped at conflicts, which then show in the tab.
     */
    data class SwitchTab(val folder: String, val onlyIfStoppedAtConflicts: Boolean = false) : WorktreeStep

    /**
     * Copies [files], relative paths, from the worktree in [from] to the one in [to]: the gitignored files that a new
     * worktree needs, such as `.env`. A file that's already there, or a symbolic link, isn't copied.
     */
    data class CopyFiles(val from: String, val to: String, val files: List<String>) : WorktreeStep

    /**
     * Writes [message] to `SQUASH_MSG` in the git dir of the worktree in [folder], over the one that
     * `git merge --squash` wrote, so that the commit field and a commit from a terminal both start from it.
     */
    data class WriteSquashMessage(val folder: String, val message: String) : WorktreeStep
}

/**
 * What removing a worktree does besides `git worktree remove`.
 *
 * @param force deletes uncommitted changes and untracked files too (`--force`).
 * @param overrideLock removes a locked worktree, by unlocking it first.
 * @param deleteBranch then deletes [WorktreeRemoval.branch], merged or not (`git branch -D`).
 */
data class RemoveWorktreeOptions(
    val force: Boolean = false,
    val overrideLock: Boolean = false,
    val deleteBranch: Boolean = false,
)

/**
 * The steps that remove the worktree, run in the main worktree. The tab switches to the main worktree first when it
 * shows the one that goes.
 *
 * A locked worktree is unlocked first, rather than removed with `--force` twice, git's override: that also deletes
 * uncommitted changes, which an agent may have made since the dialog checked, so git would no longer refuse them.
 */
fun WorktreeRemoval.removeSteps(options: RemoveWorktreeOptions): List<WorktreeStep> = buildList {
    val folder = mainWorktree.path
    val path = pathArgument(worktree.path, folder)

    if (worktree.isCurrent) {
        add(WorktreeStep.SwitchTab(mainWorktree.path))
    }

    if (options.overrideLock && isLocked) {
        add(WorktreeStep.Git(folder, listOf("worktree", "unlock", path)))
    }

    val force = if (options.force) listOf("--force") else emptyList()

    add(WorktreeStep.Git(folder, listOf("worktree", "remove") + force + path))

    if (options.deleteBranch && branch != null) {
        add(WorktreeStep.Git(folder, listOf("branch", "-D", branch)))
    }
}

/** Locks [worktree], with [reason] when it isn't blank, from the main worktree [mainWorktree]. */
fun lockWorktreeSteps(worktree: Worktree, mainWorktree: Worktree, reason: String): List<WorktreeStep> {
    val folder = mainWorktree.path
    val reasonArgs = if (reason.isBlank()) emptyList() else listOf("--reason", reason.trim())

    return listOf(
        WorktreeStep.Git(folder, listOf("worktree", "lock") + reasonArgs + pathArgument(worktree.path, folder))
    )
}

/** Unlocks [worktree], from the main worktree [mainWorktree]. */
fun unlockWorktreeSteps(worktree: Worktree, mainWorktree: Worktree): List<WorktreeStep> {
    val folder = mainWorktree.path

    return listOf(WorktreeStep.Git(folder, listOf("worktree", "unlock", pathArgument(worktree.path, folder))))
}

/** Prunes the worktrees whose folders are missing, from the main worktree [mainWorktree]. */
fun pruneWorktreesSteps(mainWorktree: Worktree): List<WorktreeStep> {
    return listOf(WorktreeStep.Git(mainWorktree.path, listOf("worktree", "prune")))
}

/**
 * How a command run in [folder] names [path]: relative when it's inside [folder] or next to it (`../leaf-agent1`,
 * `.claude/worktrees/agent1`), which reads better in the dialog, and absolute otherwise. A relative path that starts
 * with `-` gets `./`, so that git can't take it for an option.
 */
fun pathArgument(path: String, folder: String): String {
    val relative = try {
        Path.of(folder).normalize().relativize(Path.of(path).normalize())
    } catch (e: IllegalArgumentException) {
        // Paths on different drives (Windows), or one relative and one absolute
        null
    } catch (e: InvalidPathException) {
        null
    } ?: return path

    val text = relative.toString()
    val levelsUp = relative.count { it.toString() == ".." }

    return when {
        text.isEmpty() || levelsUp > 1 -> path
        text.startsWith("-") -> "./$text"
        else -> text
    }
}

/** Characters that a POSIX shell reads as they are, outside quotes. */
private val SHELL_SAFE = Regex("""[A-Za-z0-9_@%+=:,./-]+""")

/** [argument] as a POSIX shell reads it back: as it is when it's safe, otherwise in single quotes. */
fun shellQuote(argument: String): String = when {
    argument.matches(SHELL_SAFE) -> argument
    else -> "'" + argument.replace("'", """'\''""") + "'"
}

/** The command line of this step, as it would be typed in a terminal: `git worktree remove ../leaf-agent1`. */
fun WorktreeStep.Git.commandLine(): String = (listOf("git") + args.map(::shellQuote)).joinToString(" ")

/** `cd <folder>`, with the home folder [home] written as `~`. */
fun cdLine(folder: String, home: String?): String {
    val inHome = home?.trimEnd('/')?.takeIf { it.isNotEmpty() && folder.startsWith("$it/") }
    val shownFolder = if (inHome != null) {
        "~/" + shellQuote(folder.removePrefix("$inHome/"))
    } else {
        shellQuote(folder)
    }

    return "cd $shownFolder"
}

/**
 * The lines that the dialog of an action shows for its [steps], which can be pasted into a terminal: a `cd` line each
 * time the folder changes, then the git commands. A step that isn't a command becomes a comment, from [comment].
 */
fun shellLines(
    steps: List<WorktreeStep>,
    home: String?,
    comment: (WorktreeStep) -> String,
): List<String> {
    var currentFolder: String? = null

    return buildList {
        for (step in steps) {
            when (step) {
                is WorktreeStep.SwitchTab, is WorktreeStep.CopyFiles, is WorktreeStep.WriteSquashMessage ->
                    add("# " + comment(step))
                is WorktreeStep.Git -> {
                    if (step.folder != currentFolder) {
                        add(cdLine(step.folder, home))
                        currentFolder = step.folder
                    }

                    add(step.commandLine())
                }
            }
        }
    }
}
