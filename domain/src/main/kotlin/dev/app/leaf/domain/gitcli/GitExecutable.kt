// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.domain.gitcli

/** A git CLI binary that was found and verified by running `git --version`. */
data class GitExecutable(
    val path: String,
    val version: GitVersion,
)

data class GitVersion(
    val major: Int,
    val minor: Int,
    val patch: Int,
) : Comparable<GitVersion> {
    override fun compareTo(other: GitVersion): Int = compareValuesBy(this, other, { it.major }, { it.minor }, { it.patch })

    override fun toString() = "$major.$minor.$patch"

    companion object {
        /** `git worktree list --porcelain -z` was added in git 2.36. */
        val MINIMUM_SUPPORTED = GitVersion(2, 36, 0)

        private val versionRegex = Regex("""^git version (\d+)\.(\d+)(?:\.(\d+))?""")

        /**
         * Parses the output of `git --version`, for example `git version 2.54.0 (Apple Git-157)` or
         * `git version 2.45.1.windows.1`.
         */
        fun parse(versionOutput: String): GitVersion? {
            val match = versionRegex.find(versionOutput.trim()) ?: return null
            val (major, minor, patch) = match.destructured

            return GitVersion(major.toInt(), minor.toInt(), patch.toIntOrNull() ?: 0)
        }
    }
}
