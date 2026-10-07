// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.domain.lfs

/**
 * The LFS server at [url], and the URL of the git remote it serves, when Leaf knows the remote. Like git-lfs, Leaf asks
 * the credential helper about the remote's URL when the server is on the remote's host, so that LFS gets the
 * credentials that git uses.
 */
data class LfsServer(
    val url: String,
    val remoteUrl: String?,
)
