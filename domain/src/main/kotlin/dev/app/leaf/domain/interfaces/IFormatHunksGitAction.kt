package dev.app.leaf.domain.interfaces

import dev.app.leaf.domain.models.DiffWhitespaceMode
import dev.app.leaf.domain.models.Hunk
import org.eclipse.jgit.diff.RawText
import org.eclipse.jgit.patch.FileHeader

interface IFormatHunksGitAction {
    operator fun invoke(
        fileHeader: FileHeader,
        rawOld: RawText,
        rawNew: RawText,
        isDisplayFullFile: Boolean,
        whitespace: DiffWhitespaceMode = DiffWhitespaceMode.DEFAULT,
    ): List<Hunk>
}