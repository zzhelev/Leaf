// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.domain.errors

/** Errors of adding ignore rules (fork-only). Nothing changed. */
sealed interface IgnoreError : GitError {
    /**
     * Leaf won't write to the ignore file at [path], for [reason]. It never writes through a symbolic link, as that
     * could change a file outside the repository, and git ignores a symbolic link as `.gitignore` anyway.
     */
    data class FileNotWritable(val path: String, val reason: Reason) : IgnoreError

    /**
     * The repository's own config (or the system's) sets `core.excludesFile` to [path], so git doesn't read the
     * user's global ignore file there, and rules written to it wouldn't apply. Leaf takes the setting from the user's
     * global config only: a repository must not be able to point it at any file.
     */
    data class GlobalListOverridden(val path: String) : IgnoreError

    enum class Reason {
        SYMBOLIC_LINK,
        NOT_A_FILE,

        /** The global ignore file's path isn't absolute, so Leaf can't tell what it is relative to. */
        NOT_ABSOLUTE,
    }
}
