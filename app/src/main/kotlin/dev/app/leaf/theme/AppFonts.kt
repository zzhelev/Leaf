// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.platform.SystemFont
import dev.app.leaf.common.OS
import dev.app.leaf.common.currentOs
import dev.app.leaf.common.printError
import dev.app.leaf.domain.models.ui.FontSettings
import dev.app.leaf.domain.models.ui.InterfaceFont
import org.jetbrains.skia.Font
import org.jetbrains.skia.FontHinting
import org.jetbrains.skia.FontMgr
import org.jetbrains.skia.Typeface

private const val TAG = "AppFonts"

/** The bundled code font's name, as the list and the Settings row show it. */
const val BUNDLED_CODE_FONT = "Noto Sans Mono"

/**
 * Ligatures off: the standard ones and the contextual alternates, through which code fonts such as Fira Code and
 * JetBrains Mono draw theirs. Compose's desktop text hands this to Skia's `FontFeature.parseW3`, which takes the tags
 * without quotes: CSS's `"liga" 0` parses to no feature at all (Skiko 0.150.1).
 */
internal const val NO_LIGATURES = "liga 0, calt 0"

/**
 * The font that code, commit hashes and commands are drawn in, and the font features its text takes (fork-only). The
 * features are null when ligatures are allowed, which leaves a font's own.
 */
@Immutable
class CodeFont(val family: FontFamily, val featureSettings: String?)

internal val LocalCodeFont = staticCompositionLocalOf<CodeFont?> { null }

/** The settings that an [AppTheme] inside another, as the style editor's previews, follows. */
internal val LocalFontSettings = staticCompositionLocalOf { FontSettings() }

/** The code font that [AppTheme] provides, or the bundled one outside it. */
@Composable
fun codeFont(): CodeFont = LocalCodeFont.current ?: CodeFont(bundledMonoTypography(), NO_LIGATURES)

/** This style in the code font, with its ligatures as Settings has them: for code, where `!=` must read as typed. */
@Composable
fun TextStyle.inCodeFont(): TextStyle {
    val code = codeFont()

    return copy(fontFamily = code.family, fontFeatureSettings = code.featureSettings)
}

/** An installed font's family, by its name, in the weights and styles that Leaf's text asks for. */
@OptIn(ExperimentalTextApi::class)
fun installedFontFamily(name: String): FontFamily = FontFamily(
    listOf(FontWeight.Normal, FontWeight.Medium, FontWeight.SemiBold, FontWeight.Bold).flatMap { weight ->
        listOf(SystemFont(name, weight, FontStyle.Normal), SystemFont(name, weight, FontStyle.Italic))
    }
)

/** The family that the interface font setting draws with, or null for Inter, the default. */
fun interfaceFontFamily(choice: InterfaceFont): FontFamily? = when (choice) {
    InterfaceFont.INTER -> null
    InterfaceFont.SYSTEM -> SystemFonts.interfaceFont?.let { installedFontFamily(it.family) }
}

/** The family that the code font setting draws with, or null for the bundled font, also when the font isn't installed. */
fun codeFontFamily(name: String?): FontFamily? =
    name?.takeIf(SystemFonts::isInstalled)?.let(::installedFontFamily)

/** The operating system's interface font: [family] as Skia finds it, and [displayName] as people know it. */
data class SystemInterfaceFont(val family: String, val displayName: String)

/**
 * The installed fonts, as Skia, which draws Compose's text, finds them (fork-only). Its list on macOS leaves out the
 * system's hidden fonts, whose names start with a dot, and SF Mono, which comes with Terminal rather than the system.
 */
object SystemFonts {
    private val manager: FontMgr get() = FontMgr.default

    /** Every installed family's name, read again on each call, as fonts can be installed while Leaf runs. */
    fun families(): List<String> = try {
        (0 until manager.familiesCount).map(manager::getFamilyName).filter { it.isNotBlank() && !it.startsWith(".") }
    } catch (e: Exception) {
        printError(TAG, "Couldn't list the installed fonts: ${e.message}", e)
        emptyList()
    }

    /** Whether a family of [name] is installed, ignoring case. */
    fun isInstalled(name: String): Boolean = families().any { it.equals(name, ignoreCase = true) }

    /** The installed families whose regular style is monospaced. Slow, as it opens every family: off the UI thread. */
    fun monospaced(): List<String> = families().filter { family ->
        try {
            manager.matchFamilyStyle(family, org.jetbrains.skia.FontStyle.NORMAL)?.let(::isMonospaced) == true
        } catch (e: Exception) {
            false
        }
    }

    /** SF Pro on macOS, Segoe UI on Windows, fontconfig's sans-serif on Linux, or null where none is found. */
    val interfaceFont: SystemInterfaceFont? by lazy {
        try {
            when (currentOs) {
                OS.MAC -> manager.matchFamilyStyle(MAC_SYSTEM_FONT, org.jetbrains.skia.FontStyle.NORMAL)
                    ?.let { SystemInterfaceFont(MAC_SYSTEM_FONT, "SF Pro") }

                OS.WINDOWS -> WINDOWS_SYSTEM_FONT.takeIf(::isInstalled)
                    ?.let { SystemInterfaceFont(it, it) }

                else -> manager.matchFamilyStyle("sans-serif", org.jetbrains.skia.FontStyle.NORMAL)
                    ?.familyName
                    ?.takeIf { it.isNotBlank() }
                    ?.let { SystemInterfaceFont(it, it) }
            }
        } catch (e: Exception) {
            printError(TAG, "Couldn't find the system's interface font: ${e.message}", e)
            null
        }
    }

    private const val MAC_SYSTEM_FONT = ".AppleSystemUIFont"
    private const val WINDOWS_SYSTEM_FONT = "Segoe UI"
}

/**
 * Whether [typeface] draws code: it has the letters and digits of code, and they're all as wide, which also catches a
 * monospaced font that doesn't say so. A font without those letters, such as a symbol font, isn't one.
 */
internal fun isMonospaced(typeface: Typeface): Boolean {
    val glyphs = typeface.getStringGlyphs(MONOSPACE_PROBE)

    if (glyphs.any { it.toInt() == 0 }) return false
    if (typeface.isFixedPitch) return true

    // The widths in the font itself. Hinted to pixels, as FreeType hints them on Linux, letters of one width can come
    // out a pixel apart, and a font whose fixed-pitch flag isn't set, as Noto Sans Mono's isn't, was left out
    val widths = Font(typeface, 13f).use { font ->
        font.hinting = FontHinting.NONE
        font.isLinearMetrics = true
        font.getWidths(glyphs)
    }

    return widths.all { it == widths.first() }
}

private const val MONOSPACE_PROBE = "iMW0."
