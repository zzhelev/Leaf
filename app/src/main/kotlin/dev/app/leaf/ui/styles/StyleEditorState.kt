// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.ui.styles

import androidx.compose.runtime.Immutable
import dev.app.leaf.domain.models.ui.AccentStyle
import dev.app.leaf.domain.styles.StyleDefinition
import dev.app.leaf.domain.styles.StyleSeed
import dev.app.leaf.domain.styles.StyleSide
import dev.app.leaf.domain.styles.StyleSlot
import dev.app.leaf.theme.styles.ResolvedStyleSide
import dev.app.leaf.theme.styles.resolveStyleSide

/** What a colour picker is open for (fork-only). */
sealed interface ColourTarget {
    /** A seed: the background has a colour per side, the others one for both, which sets both sides. */
    data class Seed(val seed: StyleSeed, val isLight: Boolean? = null) : ColourTarget

    /** A colour of the Advanced table, on one side. */
    data class Slot(val slot: StyleSlot, val isLight: Boolean) : ColourTarget
}

/**
 * The style editor's state (fork-only, `docs/fork/custom-styles.md` 5.2): the style as saved, the draft being edited,
 * and what the dialog shows. Every change is a function that gives the next state, so the editor is tested without
 * Compose. [isNew] is a style that has no file yet: a new one, or a copy of another.
 */
@Immutable
data class StyleEditorState(
    val saved: StyleDefinition,
    val draft: StyleDefinition,
    val isNew: Boolean,
    /** The name of the style this one started from, for a new one. */
    val startedFrom: String? = null,
    val isAdvancedOpen: Boolean = false,
    val picker: ColourTarget? = null,
) {
    val hasChanges: Boolean get() = isNew || draft != saved

    fun renamed(name: String) = copy(draft = draft.copy(name = name))

    fun withLook(look: AccentStyle) = copy(draft = draft.copy(look = look))

    /** Sets a seed: [isLight] null sets both sides, as Accent, Danger, Warning and Success do. */
    fun withSeed(seed: StyleSeed, argb: Int, isLight: Boolean? = null): StyleEditorState {
        val opaque = argb or OPAQUE
        fun StyleSide.with() = copy(seeds = seeds.with(seed, opaque))

        return copy(
            draft = draft.copy(
                light = if (isLight != false) draft.light.with() else draft.light,
                dark = if (isLight != true) draft.dark.with() else draft.dark,
            ),
        )
    }

    /** Sets [slot] by hand on one side: it's used as given, whatever the seeds. */
    fun withColourSetByHand(slot: StyleSlot, isLight: Boolean, argb: Int): StyleEditorState {
        val side = draft.side(isLight)
        return copy(draft = draft.withSide(isLight, side.copy(setByHand = side.setByHand + (slot to argb))))
    }

    /** Lets Leaf work [slot] out again on one side. */
    fun withWorkedOutColour(slot: StyleSlot, isLight: Boolean): StyleEditorState {
        val side = draft.side(isLight)
        return copy(draft = draft.withSide(isLight, side.copy(setByHand = side.setByHand - slot)))
    }

    fun reverted() = copy(draft = saved, picker = null)

    fun withAdvancedOpen(isOpen: Boolean) = copy(isAdvancedOpen = isOpen)

    fun withPicker(target: ColourTarget?) = copy(picker = target)

    fun isSetByHand(slot: StyleSlot, isLight: Boolean): Boolean = slot in draft.side(isLight).setByHand

    companion object {
        private const val OPAQUE = 0xFF000000.toInt()

        /** Editing a style of the user's, as its file has it. */
        fun editing(style: StyleDefinition) = StyleEditorState(saved = style, draft = style, isNew = false)

        /** A new style, or a copy, starting from [from]'s colours, with the [id] and [name] it will be saved under. */
        fun startingFrom(from: StyleDefinition, id: String, name: String, fromName: String): StyleEditorState {
            val start = from.copy(id = id, name = name)
            return StyleEditorState(saved = start, draft = start, isNew = true, startedFrom = fromName)
        }
    }
}

/** Both sides of the draft, worked out: what the previews and the Advanced table show. */
@Immutable
class ResolvedDraft(val draft: StyleDefinition) {
    val light: ResolvedStyleSide = resolveStyleSide(draft, isLight = true)
    val dark: ResolvedStyleSide = resolveStyleSide(draft, isLight = false)

    fun side(isLight: Boolean) = if (isLight) light else dark
}
