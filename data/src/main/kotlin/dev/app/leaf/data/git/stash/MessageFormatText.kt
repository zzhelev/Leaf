// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.data.git.stash

/** A `{` with the `{` and `'` that follow it, which go in one quote. */
private val QUOTED_RUN = Regex("""\{[{']*|'""")

/**
 * A [java.text.MessageFormat] pattern that formats to [text] as it is. JGit's `StashCreateCommand` takes its messages
 * as patterns, where `'` quotes and `{` starts an argument, so "don't" would lose its `'`, and a `{b}` would fail the
 * stash.
 *
 * Two `'` are one `'`, in a quote or out of one, and a `{` goes in a quote. A quote can't end where the next one starts,
 * nor before a `''`: those `'` would pair up as a `'` of the text. So a quote holds the `{` and every `{` and `'` that
 * follow it, and ends before other text.
 */
internal fun messageFormatText(text: String) = QUOTED_RUN.replace(text) { match ->
    if (match.value == "'") "''" else "'" + match.value.replace("'", "''") + "'"
}
