package dev.app.leaf.domain.models

import org.eclipse.jgit.diff.DiffEntry

sealed class DiffResult(
    val diffEntry: DiffEntry,
) {
    /**
     * @param onlyWhitespaceChanged the diff ignores whitespace and found no other change in a file whose versions
     * differ, so it has no hunks (fork-only).
     */
    sealed class TextDiff(diffEntry: DiffEntry, val onlyWhitespaceChanged: Boolean = false) : DiffResult(diffEntry)

    class Text(
        diffEntry: DiffEntry,
        val hunks: List<Hunk>,
        onlyWhitespaceChanged: Boolean = false,
    ) : TextDiff(diffEntry, onlyWhitespaceChanged)

    class TextSplit(
        diffEntry: DiffEntry,
        val hunks: List<SplitHunk>,
        onlyWhitespaceChanged: Boolean = false,
    ) : TextDiff(diffEntry, onlyWhitespaceChanged)

    class NonText(
        diffEntry: DiffEntry,
        val oldBinaryContent: EntryContent,
        val newBinaryContent: EntryContent,
    ) : DiffResult(diffEntry)

    class Submodule(
        diffEntry: DiffEntry,
        val submodule: dev.app.leaf.domain.models.Submodule?,
    ) : DiffResult(diffEntry)
}