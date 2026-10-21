// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.domain.errors

/**
 * A hunk or line action was asked to [action] lines of the file at [path] from a diff that ignored whitespace. Such a
 * diff has context lines whose two versions differ, so the patch of its lines can't apply to both the index and the
 * working tree. The UI has no buttons for them, so this only stops a stale one. Nothing changed.
 */
data class WhitespaceIgnoredHunkError(val action: HunkAction, val path: String) : GitError
