// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.viewmodels

import dev.app.leaf.domain.highlighting.HighlightedVersions
import dev.app.leaf.domain.highlighting.versions
import dev.app.leaf.domain.models.ViewDiffResult
import dev.app.leaf.domain.usecases.HighlightTextUseCase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.eclipse.jgit.diff.RawText

/**
 * Colours the versions of the diffs that a view shows (fork-only, `docs/fork/syntax-highlighting.md`, step 5), in
 * [scope]. [show] takes each diff before the view does: a version with the path and the bytes of a version already
 * coloured keeps those colours at once, so a diff loaded again, as each refresh does, doesn't flash plain; the others
 * are coloured in the background, the last diff's only. The diff shown before stays in [versions], as the view may draw
 * it until it has the next.
 */
class DiffHighlightsLoader(
    private val scope: CoroutineScope,
    private val highlightText: HighlightTextUseCase,
) {
    private val _versions = MutableStateFlow(HighlightedVersions())
    val versions: StateFlow<HighlightedVersions> = _versions

    /** The texts of the diff shown last. */
    private var shown: List<RawText> = emptyList()
    private var job: Job? = null

    @Synchronized
    fun show(diff: ViewDiffResult?) {
        job?.cancel()
        val known = _versions.value
        val next = (diff as? ViewDiffResult.Loaded)?.diffResult?.versions().orEmpty()
            .map { (text, path) -> known.versionOf(text) ?: known.sameAs(text, path) }
        val before = known.versions.filter { version ->
            shown.any { it === version.text } && next.none { it.text === version.text }
        }

        shown = next.map { it.text }
        _versions.value = HighlightedVersions(next + before)

        val uncoloured = next.filter { it.highlighted == null }
        if (uncoloured.isEmpty()) return

        job = scope.launch {
            for (version in uncoloured) {
                launch {
                    val highlighted = highlightText(version.path, version.text) ?: return@launch
                    _versions.update { it.withHighlighted(version.text, highlighted) }
                }
            }
        }
    }

    /**
     * [text], from [path], with the colours of a version of these whose path and bytes are the same, or none: a diff
     * loaded again reads its versions again.
     */
    private fun HighlightedVersions.sameAs(text: RawText, path: String): HighlightedVersions.Version {
        val same = versions.firstOrNull { version ->
            version.highlighted != null && version.path == path && version.text.rawContent.contentEquals(text.rawContent)
        }

        return HighlightedVersions.Version(text, path, same?.highlighted)
    }
}
