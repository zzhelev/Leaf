// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.domain.usecases

import dev.app.leaf.domain.highlighting.HighlightedText
import dev.app.leaf.domain.highlighting.Languages
import dev.app.leaf.domain.highlighting.decodedText
import dev.app.leaf.domain.highlighting.lineCountOf
import dev.app.leaf.domain.highlighting.lines
import dev.app.leaf.domain.interfaces.IHighlightTextAction
import org.eclipse.jgit.diff.RawText
import org.eclipse.jgit.util.RawParseUtils
import javax.inject.Inject

/** How much of a text's start is read for its `#!` line, so that a one-line minified file isn't copied whole. */
private const val MAX_FIRST_LINE = 256

/**
 * The colours of a version of the file at [path] whose content is [text], in the language that the path or the first
 * line's `#!` names, or null when neither names one (fork-only, `docs/fork/syntax-highlighting.md`). Cancelling the
 * caller stops the work.
 */
class HighlightTextUseCase @Inject constructor(
    private val highlightTextAction: IHighlightTextAction,
) {
    suspend operator fun invoke(path: String, text: String): HighlightedText? {
        val language = Languages.of(path, text.take(MAX_FIRST_LINE).substringBefore('\n')) ?: return null
        return highlightTextAction(language.scopeName, text)
    }

    /**
     * The colours of [text], a version that a diff holds, whose lines are decoded one at a time as the diff shows them
     * ([decodedText]). It's decoded only when a language is found, and an empty text has none.
     */
    suspend operator fun invoke(path: String, text: RawText): HighlightedText? {
        if (text.size() == 0) return null
        val content = text.rawContent
        val start = RawParseUtils.decode(content, 0, minOf(content.size, MAX_FIRST_LINE))
        val language = Languages.of(path, start.substringBefore('\n')) ?: return null
        return highlightTextAction(language.scopeName, text.decodedText())
    }

    /**
     * The colours of [text], lines that follow [before] in a version of the file at [path]: they're coloured from the
     * state that the lines before them leave, so that lines typed in a comment are the comment's (step 6).
     */
    suspend fun inContext(path: String, before: String, text: String): HighlightedText? {
        val start = if (before.isEmpty() || before.endsWith('\n')) before else before + "\n"
        val highlighted = invoke(path, start + text) ?: return null
        return highlighted.lines(start.count { it == '\n' }, lineCountOf(text))
    }
}
