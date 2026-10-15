// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.data.git.config

import dev.app.leaf.common.printError
import dev.app.leaf.data.git.cli.GitCli
import dev.app.leaf.data.git.credentials.ConfigEntry
import dev.app.leaf.data.shell.LoginShellEnvironment
import dev.app.leaf.domain.errors.Either
import org.eclipse.jgit.lib.Constants
import org.eclipse.jgit.lib.Repository
import java.io.File
import javax.inject.Inject
import kotlin.time.Duration.Companion.seconds

private const val TAG = "IncludedConfig"
private val CONFIG_TIMEOUT = 10.seconds

/** The scopes of the user's and the system's config, which JGit's repository config sits on. */
private val BASE_SCOPES = setOf("system", "global")

/**
 * What git reads in a repository's config and JGit doesn't: the [entries] of the files that `includeIf` names (JGit's
 * parser expands only `[include]`), and the [files] that git read, or would read if they changed, to find them.
 */
class IncludedConfig internal constructor(internal val entries: List<ConfigEntry>, val files: List<File>)

/** Reads what git reads through `includeIf` in a repository ([IncludedConfig]). */
interface IncludedConfigSource {
    /** What git reads through `includeIf` in [repository]: no entries when git can't tell, as JGit then reads alone. */
    suspend fun read(repository: Repository): IncludedConfig
}

/** Asks git, with `git config --list -z --show-origin --show-scope`. */
class GitIncludedConfig @Inject constructor(
    private val gitCli: GitCli,
    private val loginShellEnvironment: LoginShellEnvironment,
) : IncludedConfigSource {
    override suspend fun read(repository: Repository): IncludedConfig {
        // git expands ~ with the HOME it runs with, which GitCli takes from the login shell
        val environment = loginShellEnvironment.variables()
        val home = environment["HOME"] ?: System.getenv("HOME")
        val xdgConfigHome = environment["XDG_CONFIG_HOME"] ?: System.getenv("XDG_CONFIG_HOME")
        val filesToWatch = filesThatGitMayRead(repository, home, xdgConfigHome)

        // With --git-dir, git takes the folder it runs in as the working tree's root
        val runDirectory = if (repository.isBare) repository.directory else repository.workTree
        val args = listOf(
            "--git-dir=${repository.directory.absolutePath}",
            "config",
            "--list",
            "-z",
            "--show-origin",
            "--show-scope",
        )

        val output = when (val result = gitCli.run(runDirectory, args, CONFIG_TIMEOUT)) {
            is Either.Ok -> result.value
            is Either.Err -> {
                printError(TAG, "Reading the config as JGit does, as git couldn't list it: ${result.error}")
                return IncludedConfig(emptyList(), filesToWatch)
            }
        }

        val included = includedConfig(parseOriginList(output), runDirectory, home)

        return IncludedConfig(included.entries, (included.files + filesToWatch).distinct())
    }
}

/**
 * The files that may change what git reads, whether they exist or not: the repository's HEAD, for `onbranch:`, its
 * config files, and the user's.
 */
private fun filesThatGitMayRead(repository: Repository, home: String?, xdgConfigHome: String?): List<File> {
    val userFiles = listOfNotNull(
        home?.let { File(it, ".gitconfig") },
        (xdgConfigHome ?: home?.let { File(it, ".config").path })?.let { File(it, "git/config") },
    )

    return listOf(
        File(repository.directory, Constants.HEAD),
        File(repository.commonDirectory, Constants.CONFIG),
        File(repository.directory, "config.worktree"),
    ) + userFiles
}

/**
 * One entry of `git config --list -z --show-origin --show-scope`: the [scope] of the file that git read it in
 * (`system`, `global`, `local`, `worktree` or `command`; an included file has its includer's), where it read it
 * ([origin], `file:<path>` for a file), and its key and value. A key without `=` has no value.
 */
internal data class OriginEntry(val scope: String, val origin: String, val key: String, val value: String?)

/** The entries that `git config --list -z --show-origin --show-scope` printed: three fields each, ended by NULs. */
internal fun parseOriginList(output: String): List<OriginEntry> = output
    .split('\u0000')
    .chunked(3)
    .filter { it.size == 3 }
    .map { (scope, origin, keyAndValue) ->
        val newline = keyAndValue.indexOf('\n')

        if (newline < 0) {
            OriginEntry(scope, origin, keyAndValue, null)
        } else {
            OriginEntry(scope, origin, keyAndValue.substring(0, newline), keyAndValue.substring(newline + 1))
        }
    }

/**
 * What git read only through `includeIf`, from [entries] in git's order: the entries of a file that an
 * `includeIf.<condition>.path` names, or that a file read that way includes. JGit reads the other files itself,
 * `[include]` too. git lists an included file's entries right after the entry that includes it, so an include whose
 * condition matched is the next entry coming from the file that its path names.
 *
 * A key that the user's or the system's config sets again after its last value from such a file also gets those later
 * values, as git takes the last value and JGit puts these entries above those files. A key that the repository's own
 * config sets stays the repository's in JGit, even where git reads an `includeIf` file after it.
 *
 * Paths printed relative are in [runDirectory], where git ran. [home] is git's `~`.
 */
internal fun includedConfig(entries: List<OriginEntry>, runDirectory: File, home: String?): IncludedConfig {
    class Frame(val file: File?, val conditional: Boolean)
    class Include(val target: File, val conditional: Boolean)

    val stack = ArrayDeque<Frame>()
    var pending: Include? = null
    val isConditional = BooleanArray(entries.size)
    val files = linkedSetOf<File>()

    entries.forEachIndexed { index, entry ->
        val file = configFileOf(entry.origin, runDirectory)?.also { files += it }
        val include = pending
        pending = null

        val frame = if (include != null && file != null && file == include.target) {
            Frame(file, include.conditional).also { stack.addLast(it) }
        } else {
            while (stack.isNotEmpty() && stack.last().file != file) {
                stack.removeLast()
            }

            stack.lastOrNull() ?: Frame(file, conditional = false).also { stack.addLast(it) }
        }

        if (entry.isInclude) {
            val target = if (file != null && entry.value != null) includeTarget(entry.value, file, home) else null

            if (target != null) {
                files += target
                pending = Include(target, frame.conditional || entry.isConditionalInclude)
            }
        } else {
            isConditional[index] = frame.conditional
        }
    }

    val lastConditional = mutableMapOf<String, Int>()
    entries.forEachIndexed { index, entry -> if (isConditional[index]) lastConditional[entry.key] = index }

    val laterBaseValues = entries.filterIndexed { index, entry ->
        val last = lastConditional[entry.key]

        last != null && index > last && !isConditional[index] && entry.scope in BASE_SCOPES
    }

    val included = entries.filterIndexed { index, _ -> isConditional[index] } + laterBaseValues

    return IncludedConfig(included.map { ConfigEntry(it.key, it.value) }, files.toList())
}

/** The file that git read an entry in, as git printed it: a path relative to the folder git ran in, or absolute. */
private fun configFileOf(origin: String, runDirectory: File): File? {
    if (!origin.startsWith("file:")) {
        return null
    }

    val path = File(origin.removePrefix("file:"))

    return if (path.isAbsolute) path else File(runDirectory, path.path)
}

private val OriginEntry.isInclude: Boolean
    get() = key.equals("include.path", ignoreCase = true) || isConditionalInclude

private val OriginEntry.isConditionalInclude: Boolean
    get() = key.startsWith("includeif.", ignoreCase = true) && key.endsWith(".path", ignoreCase = true)

/**
 * The file that an include's [path] names, as git opens and prints it: `~/` in [home], and a relative path next to the
 * file that includes it, as git printed that file, without resolving links. Null for what git would expand otherwise
 * (`~user/`, `%(prefix)/`), whose entries then stay JGit's to miss.
 */
private fun includeTarget(path: String, includingFile: File, home: String?): File? = when {
    path.startsWith("~/") -> File(home ?: return null, path.removePrefix("~/"))
    path.startsWith("~") || path.startsWith("%(") -> null
    File(path).isAbsolute -> File(path)
    else -> File(includingFile.parentFile ?: return null, path)
}
