// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.domain.usecases

import dev.app.leaf.domain.highlighting.HighlightedText
import dev.app.leaf.domain.highlighting.Languages
import dev.app.leaf.domain.interfaces.IHighlightTextAction
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
}
