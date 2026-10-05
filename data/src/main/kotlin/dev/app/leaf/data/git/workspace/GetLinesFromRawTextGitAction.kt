package dev.app.leaf.data.git.workspace

import dev.app.leaf.domain.interfaces.IGetLinesFromRawTextGitAction
import org.eclipse.jgit.diff.RawText
import javax.inject.Inject

class GetLinesFromRawTextGitAction @Inject constructor(
    private val getLinesFromTextGitAction: GetLinesFromTextGitAction,
) : IGetLinesFromRawTextGitAction {
    override operator fun invoke(rawFile: RawText): List<String> {
        val content = rawFile.rawContent.toString(Charsets.UTF_8)

        return getLinesFromTextGitAction(content) // TODO Test this
    }
}