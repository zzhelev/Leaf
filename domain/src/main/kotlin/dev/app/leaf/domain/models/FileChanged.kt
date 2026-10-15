// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.domain.models

/** A file or folder that the file watcher saw created, changed or deleted. */
data class FileChanged(val path: String, val fileType: FileType)

enum class FileType {
    FILE,

    /** A folder that was created, or a watched folder that was deleted. */
    DIRECTORY,
}
