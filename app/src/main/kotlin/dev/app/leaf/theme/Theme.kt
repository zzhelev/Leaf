@file:Suppress("unused")

package dev.app.leaf.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material.Colors
import androidx.compose.material.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.app.leaf.domain.models.ui.LinesHeightType
import dev.app.leaf.domain.models.ui.Theme
import dev.app.leaf.domain.models.ui.resolved
import dev.app.leaf.domain.styles.BuiltInStyles
import dev.app.leaf.domain.styles.StyleDefinition
import dev.app.leaf.theme.styles.CLASSIC_LANES
import dev.app.leaf.theme.styles.resolveStyleSide
import dev.app.leaf.ui.dropdowns.DropDownOption

private val defaultAppTheme: ColorsScheme = darkTheme

// Fork-only: provided by AppTheme rather than kept in a global flow, so that an AppTheme inside another one (the style
// editor's previews) colours only what it holds
internal val LocalColorsScheme = staticCompositionLocalOf { defaultAppTheme }

/** The log graph's lanes, which the style sets (fork-only). */
internal val LocalGraphLanes = staticCompositionLocalOf { CLASSIC_LANES }
internal val LocalLinesHeight = compositionLocalOf { spacedLineHeight }

class LinesHeight internal constructor(
    val fileHeight: Dp,
    val logCommitHeight: Dp,
    val sidePanelItemHeight: Dp,
    /** The padding around the icon of a branch or tag chip in the log, which sets the chip's height. */
    val refChipIconPadding: Dp = 6.dp,
)

val spacedLineHeight = LinesHeight(
    fileHeight = 38.dp,
    logCommitHeight = 38.dp,
    sidePanelItemHeight = 36.dp
)

// Fork-only: 32 dp, halfway between Spaced and Dense (34 dp until 2026-10-22, close to Spaced's 38 and 36)
val compactLineHeight = LinesHeight(
    fileHeight = 32.dp,
    logCommitHeight = 32.dp,
    sidePanelItemHeight = 32.dp
)

// The hovered file's Stage/Unstage button is 24 dp tall, so rows can't go much lower.
val denseLineHeight = LinesHeight(
    fileHeight = 26.dp,
    logCommitHeight = 26.dp,
    sidePanelItemHeight = 26.dp,
    refChipIconPadding = 4.dp,
)

@Composable
fun AppTheme(
    selectedTheme: Theme = Theme.Dark,
    linesHeightType: LinesHeightType = LinesHeightType.COMPACT,
    customTheme: ColorsScheme? = null,
    // Fork-only: every colour of the light and the dark theme (docs/fork/custom-styles.md). A custom theme keeps its own
    // colours, laid out as Classic
    style: StyleDefinition = BuiltInStyles.DEFAULT,
    // Fork-only: read only when the System theme is selected. Compose polls the operating system's setting, so a
    // change recomposes this, and the colours with it
    systemIsDark: Boolean = selectedTheme == Theme.System && isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val resolvedTheme = selectedTheme.resolved(systemIsDark)
    val isCustom = resolvedTheme == Theme.Custom
    val styleSide = remember(style, resolvedTheme) {
        // resolved() never returns System
        if (isCustom) null else resolveStyleSide(style, isLight = resolvedTheme == Theme.Light)
    }
    val theme = styleSide?.palette?.toColorsScheme() ?: customTheme ?: defaultAppTheme
    val styleColors = styleSide?.palette?.toStyleColors() ?: classicStyleColors(theme)
    val lanes = styleSide?.palette?.lanes ?: CLASSIC_LANES

    val lineHeight = when (linesHeightType) {
        LinesHeightType.SPACED -> spacedLineHeight
        LinesHeightType.COMPACT -> compactLineHeight
        LinesHeightType.DENSE -> denseLineHeight
    }

    val composeColors = theme.toComposeColors()
    val compositionValues = arrayOf(
        LocalLinesHeight provides lineHeight,
        LocalStyleColors provides styleColors,
        LocalColorsScheme provides theme,
        LocalGraphLanes provides lanes,
    )

    CompositionLocalProvider(values = compositionValues) {
        MaterialTheme(
            colors = composeColors,
            content = content,
            typography = typography(composeColors),
        )
    }

}

val MaterialTheme.linesHeight: LinesHeight
    @Composable
    @ReadOnlyComposable
    get() = LocalLinesHeight.current


private val theme: ColorsScheme
    @Composable
    @ReadOnlyComposable
    get() = LocalColorsScheme.current

/** The log graph's six lanes, in the order the log uses them (fork-only). */
val MaterialTheme.graphLanes: List<Color>
    @Composable
    @ReadOnlyComposable
    get() = LocalGraphLanes.current

val Colors.backgroundSelected: Color
    @Composable
    get() = theme.backgroundSelected

val Colors.onBackgroundSecondary: Color
    @Composable
    get() = theme.onBackgroundSecondary

val Colors.secondarySurface: Color
    @Composable
    get() = theme.secondarySurface

val Colors.tertiarySurface: Color
    @Composable
    get() = theme.tertiarySurface

val Colors.addFile: Color
    @Composable
    get() = theme.addFile

val Colors.deleteFile: Color
    @Composable
    get() = theme.deletedFile

val Colors.modifyFile: Color
    @Composable
    get() = theme.modifiedFile

val Colors.conflictFile: Color
    @Composable
    get() = theme.conflictingFile

val Colors.abortButton: Color
    @Composable
    get() = theme.error

val Colors.scrollbarNormal: Color
    @Composable
    get() = theme.normalScrollbar

val Colors.scrollbarHover: Color
    @Composable
    get() = theme.hoverScrollbar

val Colors.dialogOverlay: Color
    @Composable
    get() = theme.dialogOverlay

val Colors.diffLineAdded: Color
    @Composable
    get() = theme.diffLineAdded


val Colors.diffContentAdded: Color
    @Composable
    get() = theme.diffContentAdded

val Colors.diffLineRemoved: Color
    @Composable
    get() = theme.diffLineRemoved

val Colors.diffContentRemoved: Color
    @Composable
    get() = theme.diffContentRemoved

val Colors.diffKeyword: Color
    @Composable
    get() = theme.diffKeyword

val Colors.diffAnnotation: Color
    @Composable
    get() = theme.diffAnnotation

val Colors.diffComment: Color
    @Composable
    get() = theme.diffComment

val Colors.isDark: Boolean
    get() = !this.isLight


// TODO Do not hardcode theme here and use proper string resource
val themeLists = listOf(
    DropDownOption(Theme.Light, "Light"),
    DropDownOption(Theme.Dark, "Dark"),
    DropDownOption(Theme.System, "System"),
    DropDownOption(Theme.Custom, "Custom"),
)