// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.data.git.lfs

import org.eclipse.jgit.lib.Config

private const val SKIP_SMUDGE_VARIABLE = "GIT_LFS_SKIP_SMUDGE"
private const val SKIP_DOWNLOAD_ERRORS_VARIABLE = "GIT_LFS_SKIP_DOWNLOAD_ERRORS"

/**
 * What git-lfs's settings ask of a smudge filter (fork-only), which Leaf's built-in filters follow too: write the
 * pointer instead of the object ([skipsSmudge]), or when the object can't be downloaded ([skipsDownloadErrors]).
 * [environment] is the variables that git-lfs would get: Leaf's own, with the login shell's on top.
 */
class LfsSmudgeSettings(private val config: Config, private val environment: Map<String, String>) {
    /** `GIT_LFS_SKIP_SMUDGE`, as git-lfs reads it. */
    val skipsSmudge: Boolean get() = isTrue(environment[SKIP_SMUDGE_VARIABLE])

    /** `lfs.skipdownloaderrors`, or `GIT_LFS_SKIP_DOWNLOAD_ERRORS`, as git-lfs reads them. */
    val skipsDownloadErrors: Boolean
        get() = isTrue(environment[SKIP_DOWNLOAD_ERRORS_VARIABLE]) ||
            config.getBoolean("lfs", null, "skipdownloaderrors", false)
}

/** A variable that git-lfs 3.8 reads as true (probed): `1`, `t`, `true`, `on` or `yes`, in any case, with no spaces. */
private fun isTrue(value: String?): Boolean {
    return value?.lowercase() in setOf("1", "t", "true", "on", "yes")
}
