// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.domain.errors

/**
 * [remote] pushes to several URLs (`remote.<name>.pushurl`, or several `url`s), so asking one server what it has after
 * a push can't tell what the others have (fork-only).
 */
data class SeveralPushUrlsError(val remote: String, val urls: List<String>) : GitError
