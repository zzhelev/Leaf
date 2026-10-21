// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.domain.commitmessage

/**
 * The commit field's helpers that Settings → Commits turns on (fork-only).
 *
 * @property subjectLength the subject's count past [SUBJECT_SOFT_LIMIT] characters, and its tint past [SUBJECT_LIMIT]
 * @property rulerColumn the column of the ruler, which also sets the field in a monospace font, or 0 for none
 */
data class CommitMessageHints(
    val subjectLength: Boolean = true,
    val rulerColumn: Int = CommitMessageRuler.DEFAULT_COLUMN,
) {
    /** The ruler's column, or null when there's none. */
    val ruler: Int? get() = rulerColumn.takeIf { it > 0 }
}

/** The commit field's ruler ([dev.app.leaf.domain.models.AppConfig.CommitMessageRuler]). */
object CommitMessageRuler {
    /** No ruler. */
    const val DEFAULT_COLUMN = 0

    /** What the settings offer. 0 is no ruler. */
    val CHOICES = listOf(0, 50, 72, 80, 100)
}
