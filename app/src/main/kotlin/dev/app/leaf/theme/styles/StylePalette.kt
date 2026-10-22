// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.theme.styles

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import dev.app.leaf.domain.models.ui.AccentStyle
import dev.app.leaf.domain.styles.StyleSlot
import dev.app.leaf.theme.ColorsScheme
import dev.app.leaf.theme.RoleColors
import dev.app.leaf.theme.StripColors
import dev.app.leaf.theme.StyleColors
import dev.app.leaf.theme.darkTheme
import dev.app.leaf.theme.lightTheme
import dev.app.leaf.theme.styleColorsFor
import dev.app.leaf.theme.styled

/**
 * The log graph's lanes as Leaf drew them until styles set them: Material's 400 shades. The grey one is `0xFFe78909c`,
 * nine digits, which Compose reads as its low 32 bits, `#78909C` at alpha 0xFE, so it stays that.
 */
val CLASSIC_LANES: List<Color> = listOf(
    Color(0xFF42a5f5),
    Color(0xFFef5350),
    Color(0xFFe78909c),
    Color(0xFFff7043),
    Color(0xFF66bb6a),
    Color(0xFFec407a),
)

/** Every colour of one side of a style, by slot (fork-only). */
@Immutable
class StylePalette(val isLight: Boolean, private val colors: Map<StyleSlot, Color>) {
    init {
        require(colors.keys.containsAll(StyleSlot.entries)) {
            "missing ${StyleSlot.entries.filter { it !in colors }}"
        }
    }

    operator fun get(slot: StyleSlot): Color = colors.getValue(slot)

    fun with(slot: StyleSlot, color: Color): StylePalette = StylePalette(isLight, colors + (slot to color))

    val lanes: List<Color> get() = StyleSlot.LANES.map { colors.getValue(it) }

    fun toColorsScheme() = ColorsScheme(
        primary = this[StyleSlot.MAIN_ACTION_FILL],
        primaryVariant = this[StyleSlot.LINKS],
        onPrimary = this[StyleSlot.MAIN_ACTION_TEXT],
        secondary = this[StyleSlot.HIGHLIGHT],
        onSecondary = this[StyleSlot.ON_HIGHLIGHT],
        onBackground = this[StyleSlot.TEXT],
        onBackgroundSecondary = this[StyleSlot.SECONDARY_TEXT],
        error = this[StyleSlot.ERROR],
        onError = this[StyleSlot.ON_ERROR],
        background = this[StyleSlot.BACKGROUND],
        backgroundSelected = this[StyleSlot.SELECTED_ROW],
        surface = this[StyleSlot.PANELS],
        secondarySurface = this[StyleSlot.BOXES],
        tertiarySurface = this[StyleSlot.HEADERS],
        addFile = this[StyleSlot.ADDED_FILE],
        deletedFile = this[StyleSlot.DELETED_FILE],
        modifiedFile = this[StyleSlot.MODIFIED_FILE],
        conflictingFile = this[StyleSlot.CONFLICTED_FILE],
        dialogOverlay = this[StyleSlot.DIALOG_OVERLAY],
        normalScrollbar = this[StyleSlot.SCROLLBAR],
        hoverScrollbar = this[StyleSlot.SCROLLBAR_HOVER],
        diffLineAdded = this[StyleSlot.ADDED_LINE],
        diffContentAdded = this[StyleSlot.ADDED_WORDS],
        diffLineRemoved = this[StyleSlot.REMOVED_LINE],
        diffContentRemoved = this[StyleSlot.REMOVED_WORDS],
        diffKeyword = this[StyleSlot.KEYWORD],
        diffAnnotation = this[StyleSlot.ANNOTATION],
        diffComment = this[StyleSlot.COMMENT],
        isLight = isLight,
    )

    fun toStyleColors() = StyleColors(
        mainAction = RoleColors(this[StyleSlot.MAIN_ACTION_FILL], this[StyleSlot.MAIN_ACTION_TEXT]),
        action = role(StyleSlot.ACTION_FILL, StyleSlot.ACTION_TEXT, StyleSlot.ACTION_EDGE),
        neutral = role(StyleSlot.NEUTRAL_FILL, StyleSlot.NEUTRAL_TEXT, StyleSlot.NEUTRAL_EDGE),
        destructive = role(StyleSlot.DESTRUCTIVE_FILL, StyleSlot.DESTRUCTIVE_TEXT, StyleSlot.DESTRUCTIVE_EDGE),
        warningStrip = StripColors(
            background = this[StyleSlot.WARNING_STRIP_FILL],
            content = this[StyleSlot.WARNING_STRIP_TEXT],
            icon = this[StyleSlot.WARNING_STRIP_ICON],
            border = this[StyleSlot.WARNING_STRIP_EDGE],
            button = role(
                StyleSlot.WARNING_STRIP_BUTTON_FILL,
                StyleSlot.WARNING_STRIP_BUTTON_TEXT,
                StyleSlot.WARNING_STRIP_BUTTON_EDGE,
            ),
        ),
        successStrip = StripColors(
            background = this[StyleSlot.SUCCESS_STRIP_FILL],
            content = this[StyleSlot.SUCCESS_STRIP_TEXT],
            icon = this[StyleSlot.SUCCESS_STRIP_ICON],
            border = this[StyleSlot.SUCCESS_STRIP_EDGE],
            button = role(
                StyleSlot.SUCCESS_STRIP_BUTTON_FILL,
                StyleSlot.SUCCESS_STRIP_BUTTON_TEXT,
                StyleSlot.SUCCESS_STRIP_BUTTON_EDGE,
            ),
        ),
        warningIcon = this[StyleSlot.WARNING_ICON],
    )

    private fun role(fill: StyleSlot, text: StyleSlot, edge: StyleSlot) = RoleColors(this[fill], this[text], this[edge])

    override fun equals(other: Any?): Boolean =
        other is StylePalette && other.isLight == isLight && other.colors == colors

    override fun hashCode(): Int = 31 * isLight.hashCode() + colors.hashCode()

    companion object {
        /** The palette of [scheme], [roles] and [lanes], which is how the built-in looks are kept. */
        fun of(scheme: ColorsScheme, roles: StyleColors, lanes: List<Color>): StylePalette {
            require(lanes.size == StyleSlot.LANES.size)

            val colors = mapOf(
                StyleSlot.BACKGROUND to scheme.background,
                StyleSlot.PANELS to scheme.surface,
                StyleSlot.BOXES to scheme.secondarySurface,
                StyleSlot.HEADERS to scheme.tertiarySurface,
                StyleSlot.SELECTED_ROW to scheme.backgroundSelected,
                StyleSlot.DIALOG_OVERLAY to scheme.dialogOverlay,
                StyleSlot.SCROLLBAR to scheme.normalScrollbar,
                StyleSlot.SCROLLBAR_HOVER to scheme.hoverScrollbar,
                StyleSlot.TEXT to scheme.onBackground,
                StyleSlot.SECONDARY_TEXT to scheme.onBackgroundSecondary,
                StyleSlot.LINKS to scheme.primaryVariant,
                StyleSlot.HIGHLIGHT to scheme.secondary,
                StyleSlot.ON_HIGHLIGHT to scheme.onSecondary,
                StyleSlot.ERROR to scheme.error,
                StyleSlot.ON_ERROR to scheme.onError,
                StyleSlot.ADDED_FILE to scheme.addFile,
                StyleSlot.MODIFIED_FILE to scheme.modifiedFile,
                StyleSlot.DELETED_FILE to scheme.deletedFile,
                StyleSlot.CONFLICTED_FILE to scheme.conflictingFile,
                StyleSlot.ADDED_LINE to scheme.diffLineAdded,
                StyleSlot.ADDED_WORDS to scheme.diffContentAdded,
                StyleSlot.REMOVED_LINE to scheme.diffLineRemoved,
                StyleSlot.REMOVED_WORDS to scheme.diffContentRemoved,
                StyleSlot.KEYWORD to scheme.diffKeyword,
                StyleSlot.ANNOTATION to scheme.diffAnnotation,
                StyleSlot.COMMENT to scheme.diffComment,
                // The scheme's primary and onPrimary are the main action's, in every built-in look
                StyleSlot.MAIN_ACTION_FILL to roles.mainAction.background,
                StyleSlot.MAIN_ACTION_TEXT to roles.mainAction.content,
                StyleSlot.ACTION_FILL to roles.action.background,
                StyleSlot.ACTION_TEXT to roles.action.content,
                StyleSlot.ACTION_EDGE to roles.action.border,
                StyleSlot.NEUTRAL_FILL to roles.neutral.background,
                StyleSlot.NEUTRAL_TEXT to roles.neutral.content,
                StyleSlot.NEUTRAL_EDGE to roles.neutral.border,
                StyleSlot.DESTRUCTIVE_FILL to roles.destructive.background,
                StyleSlot.DESTRUCTIVE_TEXT to roles.destructive.content,
                StyleSlot.DESTRUCTIVE_EDGE to roles.destructive.border,
                StyleSlot.WARNING_STRIP_FILL to roles.warningStrip.background,
                StyleSlot.WARNING_STRIP_TEXT to roles.warningStrip.content,
                StyleSlot.WARNING_STRIP_ICON to roles.warningStrip.icon,
                StyleSlot.WARNING_STRIP_EDGE to roles.warningStrip.border,
                StyleSlot.WARNING_STRIP_BUTTON_FILL to roles.warningStrip.button.background,
                StyleSlot.WARNING_STRIP_BUTTON_TEXT to roles.warningStrip.button.content,
                StyleSlot.WARNING_STRIP_BUTTON_EDGE to roles.warningStrip.button.border,
                StyleSlot.SUCCESS_STRIP_FILL to roles.successStrip.background,
                StyleSlot.SUCCESS_STRIP_TEXT to roles.successStrip.content,
                StyleSlot.SUCCESS_STRIP_ICON to roles.successStrip.icon,
                StyleSlot.SUCCESS_STRIP_EDGE to roles.successStrip.border,
                StyleSlot.SUCCESS_STRIP_BUTTON_FILL to roles.successStrip.button.background,
                StyleSlot.SUCCESS_STRIP_BUTTON_TEXT to roles.successStrip.button.content,
                StyleSlot.SUCCESS_STRIP_BUTTON_EDGE to roles.successStrip.button.border,
                StyleSlot.WARNING_ICON to roles.warningIcon,
            ) + StyleSlot.LANES.zip(lanes)

            return StylePalette(scheme.isLight, colors)
        }

        /** [look]'s colours for one side: what a style in that look is worked out from. */
        fun template(look: AccentStyle, isLight: Boolean): StylePalette {
            val scheme = (if (isLight) lightTheme else darkTheme).styled(look)

            return of(scheme, styleColorsFor(look, scheme), CLASSIC_LANES)
        }
    }
}
