// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.domain.lfs

/**
 * Why git outside Leaf has no working LFS filter in a repository that uses LFS (fork-only), which the strip under the
 * toolbar says. The git commands that Leaf runs get Leaf's own filter, but a terminal's or an agent's git doesn't.
 * [key] is how a dismissed notice is stored.
 */
enum class GitLfsNotice(val key: String) {
    /** git-lfs doesn't work, and git's config doesn't name it: git checks out pointers, and stages files whole. */
    NOT_INSTALLED("not-installed"),

    /** git's config names git-lfs, which doesn't work: git fails on the LFS files. */
    MISSING("missing"),

    /** git-lfs works, but git's config doesn't name it, as `git lfs install` never ran. */
    NOT_SET_UP("not-set-up");

    /** Whether installing git-lfs is the fix, rather than setting it up. */
    val needsInstall: Boolean get() = this != NOT_SET_UP

    companion object {
        fun ofKey(key: String?): GitLfsNotice? = entries.firstOrNull { it.key == key }
    }
}

/** Where git-lfs is installed from. */
const val GIT_LFS_URL = "https://git-lfs.com"
