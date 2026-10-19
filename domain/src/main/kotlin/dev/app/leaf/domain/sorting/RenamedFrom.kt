// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.domain.sorting

/**
 * Where a renamed or moved file came from, for the rows of a commit's or a review's file list.
 *
 * @param similarity How much of the content is alike, 0 to 100. 100 means it didn't change.
 */
data class RenamedFrom(
    val path: String,
    val similarity: Int,
) {
    val isContentUnchanged: Boolean get() = similarity >= 100

    /**
     * What a row shows after the file's name, `Lexer.kt` or `…/old/`: only the part of the old path that differs from
     * the new one, as git writes a rename (`{old => new}`), because the pane is rarely wide enough for a whole path.
     * - The old name, when the file stayed in its folder.
     * - The old folders that differ, when it kept its name: `…/legacy/` for `app/ui/legacy/` to `app/ui/components/`.
     * - Those and the old name, when both changed. `…/` stands for the folders that both paths start with.
     *
     * The whole old path when nothing would be left of its folder: a file that moved into a folder below its own, or
     * came from the top folder.
     */
    fun label(newPath: String): String {
        val oldName = path.substringAfterLast('/')
        val oldFolders = path.substringBeforeLast('/', missingDelimiterValue = "").splitFolders()
        val newFolders = newPath.substringBeforeLast('/', missingDelimiterValue = "").splitFolders()

        if (oldFolders == newFolders) {
            return oldName
        }

        val sharedStart = oldFolders.zip(newFolders).takeWhile { (old, new) -> old == new }.size
        val sharedEnd = oldFolders.asReversed().zip(newFolders.asReversed())
            .take(minOf(oldFolders.size, newFolders.size) - sharedStart)
            .takeWhile { (old, new) -> old == new }
            .size
        val differing = oldFolders.subList(sharedStart, oldFolders.size - sharedEnd)

        if (differing.isEmpty()) {
            return path
        }

        val elided = if (sharedStart > 0) "\u2026/" else ""
        val folders = differing.joinToString("/") + "/"

        return if (oldName == newPath.substringAfterLast('/')) "$elided$folders" else "$elided$folders$oldName"
    }

    private fun String.splitFolders(): List<String> = if (isEmpty()) emptyList() else split('/')
}
