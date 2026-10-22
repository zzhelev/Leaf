// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import dev.app.leaf.domain.models.Commit
import dev.app.leaf.domain.models.DiffType
import dev.app.leaf.domain.models.EntryType
import dev.app.leaf.domain.models.Identity
import dev.app.leaf.domain.models.StatusEntry
import dev.app.leaf.domain.models.StatusType
import dev.app.leaf.domain.models.ui.AccentStyle
import dev.app.leaf.domain.models.ui.Theme
import dev.app.leaf.domain.refresh.WatchUsage
import dev.app.leaf.domain.styles.BuiltInStyles
import dev.app.leaf.extensions.iconColor
import dev.app.leaf.ui.RestoredVersionStrip
import dev.app.leaf.ui.components.FilesChangedHeader
import dev.app.leaf.ui.components.PrimaryButton
import dev.app.leaf.ui.components.SecondaryButton
import dev.app.leaf.ui.components.WatchLimitNotice
import dev.app.leaf.ui.diff.HunkHeader
import dev.app.leaf.ui.diff.LineAction
import dev.app.leaf.ui.diff.LineActionButtons
import dev.app.leaf.ui.dialogs.ConfirmableAction.AbortOperation.Operation
import dev.app.leaf.ui.dialogs.DialogNotice
import dev.app.leaf.ui.status.AbortButton
import dev.app.leaf.ui.status.ConfirmationButton
import dev.app.leaf.ui.status.FinishBlocker
import dev.app.leaf.ui.status.FinishHint
import dev.app.leaf.ui.status.OperationInProgress
import dev.app.leaf.ui.status.OperationInProgressBanner
import dev.app.leaf.ui.status.getActionInfo
import dev.app.leaf.viewmodels.RestoredVersion
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.swing.Swing
import org.jetbrains.skia.Bitmap
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * The buttons and strips as drawn in each style and theme (fork-only): their fills, the lines inside their edges, and
 * that Classic draws the colours Leaf drew before the styles.
 */
class StyleRenderTest {
    private data class Case(val style: AccentStyle, val theme: Theme) {
        val scheme = (if (theme == Theme.Light) lightTheme else darkTheme).styled(style)
        val roles = styleColorsFor(style, scheme)
        override fun toString() = "$style $theme"
    }

    private val cases = AccentStyle.entries.flatMap { style -> listOf(Theme.Light, Theme.Dark).map { Case(style, it) } }

    private class Drawn(private val scene: ImageComposeScene) : AutoCloseable {
        val bitmap: Bitmap = Bitmap.makeFromImage(scene.render(System.nanoTime()))

        fun pixel(x: Float, y: Float): Int = bitmap.getColor(x.roundToInt(), y.roundToInt())

        /** The bounds of the clickable node whose text is [text], or of the node tagged [text]. */
        fun bounds(text: String): Rect = nodes().first { node ->
            node.config.getOrNull(SemanticsProperties.TestTag) == text ||
                (node.config.getOrNull(SemanticsActions.OnClick) != null &&
                    node.config.getOrNull(SemanticsProperties.Text).orEmpty().any { it.text == text })
        }.boundsInRoot

        private fun nodes(): List<SemanticsNode> {
            val nodes = mutableListOf<SemanticsNode>()

            fun visit(node: SemanticsNode) {
                nodes += node
                node.children.forEach(::visit)
            }

            scene.semanticsOwners.forEach { visit(it.rootSemanticsNode) }

            return nodes
        }

        override fun close() = scene.close()
    }

    private fun draw(case: Case, width: Int = 900, height: Int = 200, content: @Composable () -> Unit): Drawn {
        val scene = ImageComposeScene(width = width, height = height, density = Density(1f)) {
            AppTheme(selectedTheme = case.theme, style = BuiltInStyles.ofLook(case.style)) {
                Box(Modifier.fillMaxSize().background(MaterialTheme.colors.background)) { content() }
            }
        }

        return Drawn(scene)
    }

    private fun assertClose(expected: Color, actual: Int, message: String, tolerance: Int = 2) {
        val argb = expected.toArgb()

        for (shift in listOf(16, 8, 0)) {
            val difference = abs(((argb shr shift) and 0xFF) - ((actual shr shift) and 0xFF))
            assertTrue(
                difference <= tolerance,
                "$message: expected #${Integer.toHexString(argb)}, was #${Integer.toHexString(actual)}",
            )
        }
    }

    /** The fill just inside the left edge, past the line, and the line itself, at half the height. */
    private fun Drawn.assertButton(text: String, role: RoleColors, under: Color, message: String) {
        val bounds = bounds(text)
        val fill = role.background.compositeOver(under)

        assertClose(fill, pixel(bounds.left + 3, bounds.center.y), "$message: $text's fill")
        assertClose(role.border.compositeOver(fill), pixel(bounds.left, bounds.center.y), "$message: $text's edge")
    }

    @Test
    fun `each role's button has the style's fill and line, on the background`(): Unit = runBlocking(Dispatchers.Swing) {
        for (case in cases) {
            draw(case) {
                Column(Modifier.padding(10.dp)) {
                    PrimaryButton(text = "Commit", onClick = {})
                    SecondaryButton(text = "Stage file", onClick = {}, modifier = Modifier.padding(top = 10.dp))
                    SecondaryButton(
                        text = "Unstage file",
                        colors = MaterialTheme.styleColors.neutral,
                        onClick = {},
                        modifier = Modifier.padding(top = 10.dp),
                    )
                    SecondaryButton(
                        text = "Discard hunk",
                        colors = MaterialTheme.styleColors.destructive,
                        onClick = {},
                        modifier = Modifier.padding(top = 10.dp),
                    )
                }
            }.use { drawn ->
                val background = case.scheme.background

                drawn.assertButton("Commit", case.roles.mainAction, background, "$case")
                drawn.assertButton("Stage file", case.roles.action, background, "$case")
                drawn.assertButton("Unstage file", case.roles.neutral, background, "$case")
                drawn.assertButton("Discard hunk", case.roles.destructive, background, "$case")
            }
        }
    }

    @Test
    fun `a button given a fill of its own gets no line of the role's`(): Unit = runBlocking(Dispatchers.Swing) {
        for (case in cases) {
            draw(case) {
                SecondaryButton(
                    text = "Use ours",
                    textColor = MaterialTheme.colors.onBackground,
                    backgroundButton = MaterialTheme.colors.secondarySurface,
                    onClick = {},
                    modifier = Modifier.padding(10.dp),
                )
            }.use { drawn ->
                val bounds = drawn.bounds("Use ours")

                assertClose(case.scheme.secondarySurface, drawn.pixel(bounds.left, bounds.center.y), "$case: edge")
            }
        }
    }

    @Test
    fun `the status pane's Abort and Continue take the destructive and the main action`(): Unit =
        runBlocking(Dispatchers.Swing) {
            for (case in cases) {
                draw(case) {
                    Column(Modifier.padding(10.dp).width(300.dp)) {
                        AbortButton(Modifier.width(140.dp).padding(bottom = 10.dp).testTag("abort"), onClick = {})
                        ConfirmationButton(
                            text = "Continue",
                            modifier = Modifier.width(140.dp).testTag("continue"),
                            onClick = {},
                        )
                    }
                }.use { drawn ->
                    val abort = drawn.bounds("abort")
                    val continueButton = drawn.bounds("continue")
                    val fill = case.roles.destructive.background.compositeOver(case.scheme.background)

                    assertClose(fill, drawn.pixel(abort.left + 3, abort.center.y), "$case: Abort's fill")
                    assertClose(
                        case.roles.destructive.border.compositeOver(fill),
                        drawn.pixel(abort.left, abort.center.y),
                        "$case: Abort's edge",
                    )
                    assertClose(
                        case.roles.mainAction.background,
                        drawn.pixel(continueButton.left + 3, continueButton.center.y),
                        "$case: Continue",
                    )
                }
            }
        }

    @Test
    fun `the hunk's Discard is destructive, Stage an action and Unstage neutral`(): Unit =
        runBlocking(Dispatchers.Swing) {
            val entry = StatusEntry("src/Scanner.kt", StatusType.MODIFIED)

            for (case in cases) {
                for (entryType in EntryType.entries) {
                    draw(case) {
                        HunkHeader(
                            header = "@@ -48,6 +48,7 @@",
                            diffType = DiffType.UncommittedDiff(entry.copy(entryType = entryType), entryType),
                            canUseHunkActions = true,
                            onUnstageHunk = {},
                            onStageHunk = {},
                            onResetHunk = {},
                        )
                    }.use { drawn ->
                        val header = case.scheme.secondarySurface

                        if (entryType == EntryType.UNSTAGED) {
                            drawn.assertButton("Discard hunk", case.roles.destructive, header, "$case")
                            drawn.assertButton("Stage hunk", case.roles.action, header, "$case")
                        } else {
                            drawn.assertButton("Unstage hunk", case.roles.neutral, header, "$case")
                        }
                    }
                }
            }
        }

    @Test
    fun `a line's buttons are opaque, so that the line's tint doesn't show through`(): Unit =
        runBlocking(Dispatchers.Swing) {
            val tint = Color(0xFFFF00FF)

            for (case in cases) {
                draw(case) {
                    Box(Modifier.background(tint).padding(10.dp)) {
                        LineActionButtons(listOf(LineAction.STAGE, LineAction.UNSTAGE, LineAction.DISCARD), onAction = {})
                    }
                }.use { drawn ->
                    // 16 dp each, 4 dp apart, from (10, 10). The icons' paths start past the third pixel
                    for ((index, role) in listOf(case.roles.action, case.roles.neutral, case.roles.destructive).withIndex()) {
                        val left = 10f + index * 20

                        assertClose(
                            role.background.compositeOver(case.scheme.background),
                            drawn.pixel(left + 1, 13f),
                            "$case: button $index",
                        )
                    }
                }
            }
        }

    @Test
    fun `the operation banner is the warning strip, with its line, its button and a destructive Abort`(): Unit =
        runBlocking(Dispatchers.Swing) {
            for (case in cases) {
                draw(case, width = 1200, height = 60) {
                    OperationInProgressBanner(
                        inProgress = OperationInProgress(Operation.MERGE, conflicts = 1),
                        onAbort = {},
                        modifier = Modifier.testTag("banner"),
                        onResolveConflicts = {},
                    )
                }.use { drawn ->
                    val banner = drawn.bounds("banner")
                    val strip = case.roles.warningStrip

                    assertClose(strip.background, drawn.pixel(banner.right - 2, banner.top + 2), "$case: strip")
                    assertClose(
                        strip.border.compositeOver(strip.background),
                        drawn.pixel(banner.right - 2, banner.bottom - 1),
                        "$case: line along the bottom",
                    )
                    drawn.assertButton("Resolve conflicts", strip.button, strip.background, "$case")
                    drawn.assertButton("Abort merge", case.roles.destructive, strip.background, "$case")
                }
            }
        }

    @Test
    fun `the restored strip is the success strip, and a notice the warning strip with its line`(): Unit =
        runBlocking(Dispatchers.Swing) {
            val commit = Commit(
                hash = "e4f5a6b".padEnd(40, '0'),
                message = "Split the lexer",
                committer = Identity("Zhelyazko Zhelev", "zzhelev@gmail.com"),
                author = Identity("Zhelyazko Zhelev", "zzhelev@gmail.com"),
                date = 1_760_000_000,
                parentsHashes = emptyList(),
            )

            for (case in cases) {
                draw(case, width = 900, height = 160) {
                    Column {
                        Box(Modifier.testTag("restored")) {
                            RestoredVersionStrip(RestoredVersion("Scanner.kt", commit), onShowChanges = {}, onDismiss = {})
                        }
                        Box(Modifier.padding(10.dp).testTag("notice")) {
                            DialogNotice("Changed less than a minute ago. An agent may still be working in it.")
                        }
                    }
                }.use { drawn ->
                    val restored = drawn.bounds("restored")
                    val success = case.roles.successStrip

                    assertClose(success.background, drawn.pixel(restored.left + 4, restored.top + 3), "$case: restored")
                    drawn.assertButton("Show changes", success.button, success.background, "$case")

                    val notice = drawn.bounds("notice")
                    val warning = case.roles.warningStrip

                    assertClose(warning.background, drawn.pixel(notice.left + 3, notice.center.y), "$case: notice")
                    assertClose(
                        warning.border.compositeOver(warning.background),
                        drawn.pixel(notice.left, notice.center.y),
                        "$case: notice's line",
                    )
                }
            }
        }

    @Test
    fun `in the lists' headers Stage all is an action and Unstage all neutral`(): Unit = runBlocking(Dispatchers.Swing) {
        for (case in cases) {
            draw(case, height = 120) {
                Column {
                    for (type in EntryType.entries) {
                        FilesChangedHeader(
                            title = type.name,
                            showSearch = false,
                            showActionForSelected = false,
                            actionInfo = getActionInfo(type),
                            onAllAction = {},
                            onSearchFilterToggled = {},
                            onSearchFocused = {},
                            searchFilter = TextFieldValue(""),
                            onSearchFilterChanged = {},
                        )
                    }
                }
            }.use { drawn ->
                val header = case.scheme.tertiarySurface

                drawn.assertButton("Stage all", case.roles.action, header, "$case")
                drawn.assertButton("Unstage all", case.roles.neutral, header, "$case")
            }
        }
    }

    @Test
    fun `the watch limit's chip is the warning strip, with its line`(): Unit = runBlocking(Dispatchers.Swing) {
        for (case in cases) {
            draw(case, height = 60) {
                Box(Modifier.padding(10.dp).testTag("chip")) { WatchLimitNotice(WatchUsage(210, 3)) }
            }.use { drawn ->
                val chip = drawn.bounds("chip")
                val strip = case.roles.warningStrip

                assertClose(strip.background, drawn.pixel(chip.left + 3, chip.center.y), "$case: chip")
                assertClose(strip.border.compositeOver(strip.background), drawn.pixel(chip.left, chip.center.y), "$case: line")
            }
        }
    }

    @Test
    fun `a hint's icon takes the warning icon, not the conflict amber`(): Unit = runBlocking(Dispatchers.Swing) {
        for (case in cases) {
            draw(case, width = 300, height = 40) {
                FinishHint(FinishBlocker.NothingStaged)
            }.use { drawn ->
                // The 16 dp icon, 8 dp below the top and 1 dp more: its filled circle has pixels of the icon's colour
                val pixels = (0 until 16).flatMap { x -> (9 until 25).map { y -> drawn.pixel(x.toFloat(), y.toFloat()) } }

                fun Int.isClose(color: Color) = listOf(16, 8, 0).all { shift ->
                    abs(((this shr shift) and 0xFF) - ((color.toArgb() shr shift) and 0xFF)) <= 2
                }

                assertTrue(pixels.any { it.isClose(case.roles.warningIcon) }, "$case: no pixel of the warning icon")

                if (case.style != AccentStyle.CLASSIC) {
                    assertTrue(pixels.none { it.isClose(case.scheme.conflictingFile) }, "$case: the conflict amber")
                }
            }
        }
    }

    @Test
    fun `a conflicted file's icon takes the warning icon, the other files keep their colours`(): Unit =
        runBlocking(Dispatchers.Swing) {
            for (case in cases) {
                val colors = mutableMapOf<StatusType, Color>()

                draw(case) {
                    for (type in StatusType.entries) {
                        colors[type] = type.iconColor
                    }
                }.close()

                assertEquals(case.roles.warningIcon, colors[StatusType.CONFLICTING], "$case")
                assertEquals(case.scheme.addFile, colors[StatusType.ADDED], "$case")
                assertEquals(case.scheme.modifiedFile, colors[StatusType.MODIFIED], "$case")
                assertEquals(case.scheme.error, colors[StatusType.REMOVED], "$case")
            }
        }

    @Test
    fun `Classic draws what Leaf drew before the styles`(): Unit = runBlocking(Dispatchers.Swing) {
        for ((theme, scheme) in listOf(Theme.Light to lightTheme, Theme.Dark to darkTheme)) {
            val case = Case(AccentStyle.CLASSIC, theme)
            val nearBlack = Color(0xFF1E1E1E)

            assertEquals(scheme, case.scheme, "The scheme as it is")
            assertEquals(RoleColors(scheme.primary, scheme.onPrimary), case.roles.mainAction)
            assertEquals(RoleColors(scheme.primary, scheme.onPrimary), case.roles.action)
            assertEquals(RoleColors(scheme.error, scheme.onError), case.roles.neutral)
            assertEquals(RoleColors(scheme.error, scheme.onError), case.roles.destructive)
            assertEquals(
                StripColors(scheme.conflictingFile, nearBlack, nearBlack, Color.Transparent, RoleColors(nearBlack, Color.White)),
                case.roles.warningStrip,
            )
            assertEquals(
                StripColors(scheme.addFile, nearBlack, nearBlack, Color.Transparent, RoleColors(nearBlack, Color.White)),
                case.roles.successStrip,
            )
            assertEquals(scheme.conflictingFile, case.roles.warningIcon)
        }
    }
}
