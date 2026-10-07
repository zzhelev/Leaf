// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.data.git

import java.io.File

/**
 * Git for Windows' `bin\bash.exe`, which [WindowsFs] runs hooks with. It is a launcher that puts Git's Unix tools on
 * PATH before it starts the real bash, so hooks find `sed`, `grep` and `git` as they do when the git CLI runs them.
 *
 * [quoteArgument] prepares each argument for the command line that Java builds on Windows. Tests on macOS and Linux,
 * where Java passes arguments as they are, use the identity.
 */
internal class GitBash(
    val executable: String,
    private val quoteArgument: (String) -> String = ::quoteForMsys,
) {
    /**
     * The command that runs the hook at [hookPath] with [args]. Bash reads the hook as a shell script, whatever its
     * `#!` line says. The path gets forward slashes, like the paths git gives hooks, so a hook that runs
     * `dirname "$0"` (husky) gets a folder bash understands.
     */
    fun hookCommand(hookPath: String, args: List<String>): List<String> {
        return listOf(executable, quoteArgument(hookPath.replace('\\', '/'))) + args.map(quoteArgument)
    }

    companion object {
        /** The first of [gitBashCandidates] that exists, or null when Git for Windows isn't installed. */
        fun find(
            pathVariable: String?,
            getenv: (String) -> String?,
            isFile: (String) -> Boolean = { File(it).isFile },
        ): GitBash? = gitBashCandidates(pathVariable, getenv).firstOrNull(isFile)?.let { GitBash(it) }
    }
}

/** The folders of a Git for Windows install that can be on PATH, relative to the install. Longest first. */
private val GIT_FOLDERS_ON_PATH = listOf(
    "\\mingw64\\bin",
    "\\mingw32\\bin",
    "\\clangarm64\\bin",
    "\\usr\\bin",
    "\\cmd",
    "\\bin",
)

/**
 * Where Git Bash may be, in search order: in each Git for Windows install on PATH (its installer adds `cmd`, and
 * optionally `mingw64\bin` and `usr\bin`), then in its default install folders, which `gitExecutableCandidates`
 * searches for git too.
 */
internal fun gitBashCandidates(pathVariable: String?, getenv: (String) -> String?): List<String> {
    val installsOnPath = pathVariable.orEmpty()
        .split(';')
        .map { it.trim().removeSurrounding("\"").trimEnd('\\', '/') }
        .mapNotNull { entry ->
            GIT_FOLDERS_ON_PATH
                .firstOrNull { entry.endsWith(it, ignoreCase = true) }
                ?.let { entry.dropLast(it.length) }
        }
        .filter { it.isNotEmpty() }

    val defaultInstalls = listOfNotNull(
        getenv("ProgramFiles")?.let { "$it\\Git" },
        getenv("ProgramFiles(x86)")?.let { "$it\\Git" },
        getenv("LOCALAPPDATA")?.let { "$it\\Programs\\Git" },
    )

    return (installsOnPath + defaultInstalls).map { "$it\\bin\\bash.exe" }.distinct()
}

/** Characters that the MSYS2 runtime expands or treats as quotes in a command line, besides whitespace. */
private const val MSYS_SPECIAL_CHARACTERS = "\\\"{'?*~"

/**
 * Quotes [argument] for a program built on the MSYS2 runtime, such as Git Bash, the way Git for Windows does
 * (`quote_arg_msys2` in `compat/mingw.c`). The runtime splits its own command line, treats single quotes as quotes
 * and expands `*`, `?`, `{…}` and `~` in arguments that aren't double quoted, so `C:/Users/O'Brien/repo` would be cut
 * short. Java passes an argument that is already in double quotes as it is.
 */
internal fun quoteForMsys(argument: String): String {
    if (argument.isNotEmpty() && argument.none { it.isWhitespace() || it in MSYS_SPECIAL_CHARACTERS }) {
        return argument
    }

    return buildString {
        append('"')

        for (char in argument) {
            if (char == '\\' || char == '"') {
                append('\\')
            }

            append(char)
        }

        append('"')
    }
}
