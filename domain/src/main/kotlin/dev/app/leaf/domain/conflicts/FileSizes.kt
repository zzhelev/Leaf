// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.domain.conflicts

import java.util.Locale

private const val KB = 1024.0
private const val MB = KB * 1024
private const val GB = MB * 1024

/**
 * A file's size as the choice cards show it, in units of 1,024 as the resolver's limits are: bytes and kilobytes whole
 * (`820 B`, `48 KB`), megabytes and gigabytes to one decimal, without a `.0` (`12.4 MB`, `10 MB`). Kilobytes round up,
 * so that a file of a few bytes over a kilobyte doesn't read as one.
 */
fun formatFileSize(bytes: Long): String = when {
    bytes < KB -> "$bytes B"
    bytes < MB -> "${Math.ceil(bytes / KB).toLong()} KB"
    bytes < GB -> "${oneDecimal(bytes / MB)} MB"
    else -> "${oneDecimal(bytes / GB)} GB"
}

private fun oneDecimal(value: Double) = String.format(Locale.ROOT, "%.1f", value).removeSuffix(".0")
