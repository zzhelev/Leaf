// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.ui

import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.Density
import dev.app.leaf.data.highlighting.TextMateHighlighter
import dev.app.leaf.domain.highlighting.HighlightedText
import dev.app.leaf.domain.models.ui.Theme
import dev.app.leaf.domain.styles.BuiltInStyles
import dev.app.leaf.domain.usecases.HighlightTextUseCase
import dev.app.leaf.theme.AppTheme
import dev.app.leaf.theme.styles.resolveStyleSide
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.swing.Swing
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.blame.BlameResult
import org.eclipse.jgit.lib.PersonIdent
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/** Blame's lines in the file's colours (fork-only, step 6 of `docs/fork/syntax-highlighting.md`), rendered. */
class BlameSyntaxTest {
    @TempDir
    lateinit var dir: File

    private var scene: ImageComposeScene? = null

    @AfterEach
    fun close() {
        scene?.close()
    }

    private val colours = resolveStyleSide(BuiltInStyles.DEFAULT, isLight = true).palette.toSyntaxColors()

    /** [content] committed as [path] in a new repository, blamed. */
    private fun blame(path: String, content: String): BlameResult {
        Git.init().setDirectory(dir).call().use { git ->
            File(dir, path).apply { parentFile.mkdirs() }.writeText(content)
            git.add().addFilepattern(path).call()
            val ident = PersonIdent("Maya Chen", "maya.chen@example.com")
            git.commit().setMessage("Add $path").setSign(false).setAuthor(ident).setCommitter(ident).call()
            return git.blame().setFilePath(path).call()!!
        }
    }

    private fun show(path: String, blame: BlameResult, highlighted: HighlightedText?) {
        scene?.close()
        scene = ImageComposeScene(width = 1200, height = 600, density = Density(1f)) {
            AppTheme(selectedTheme = Theme.Light) {
                Blame(path, blame, onSelectCommit = {}, onClose = {}, highlighted = highlighted)
            }
        }.also { it.render() }
    }

    private fun nodes(): List<SemanticsNode> {
        val nodes = mutableListOf<SemanticsNode>()
        fun visit(node: SemanticsNode) {
            nodes += node
            node.children.forEach(::visit)
        }
        scene!!.semanticsOwners.forEach { visit(it.rootSemanticsNode) }
        return nodes
    }

    private fun drawn(text: String): AnnotatedString =
        nodes().flatMap { node -> node.config.getOrNull(SemanticsProperties.Text).orEmpty() }.single { it.text == text }

    private fun colourOf(drawn: AnnotatedString, piece: String): Color? {
        val at = drawn.text.indexOf(piece)
        check(at >= 0) { "No \"$piece\" in \"${drawn.text}\"" }
        return drawn.spanStyles.lastOrNull { it.start <= at && at < it.end && it.item.color != Color.Unspecified }?.item?.color
    }

    @Test
    fun `blame's lines take the file's colours, coloured whole, with tabs as spaces`(): Unit = runBlocking(Dispatchers.Swing) {
        val blame = blame("src/Demo.kt", "/* Loads\n   the config */\nfun load(): Int {\n\treturn 42\n}\n")
        val highlighted = HighlightTextUseCase(TextMateHighlighter())("src/Demo.kt", blame.resultContents)

        show("src/Demo.kt", blame, highlighted)

        assertEquals(colours.comment, colourOf(drawn("   the config */"), "config"))
        assertEquals(colours.keyword, colourOf(drawn("fun load(): Int {"), "fun"))
        assertEquals(colours.function, colourOf(drawn("fun load(): Int {"), "load"))
        val tabbed = drawn("    return 42")
        assertEquals(colours.keyword, colourOf(tabbed, "return"))
        assertEquals(colours.number, colourOf(tabbed, "42"))
    }

    @Test
    fun `before its colours, or with none, blame is plain, and a last line without a line ending is drawn as it is`(): Unit =
        runBlocking(Dispatchers.Swing) {
            val blame = blame("NOTES", "first\nlast")

            show("NOTES", blame, highlighted = null)

            for (line in listOf("first", "last")) {
                assertTrue(drawn(line).spanStyles.none { it.item.color != Color.Unspecified }, line)
            }
        }

    @Test
    fun `a file without a line break is its line, with nothing after it`(): Unit = runBlocking(Dispatchers.Swing) {
        // Upstream drew each line with the file's line delimiter after it, which JGit gives as null for such a file
        val blame = blame("VERSION", "1.4.0")
        assertEquals(null, blame.resultContents.lineDelimiter)

        show("VERSION", blame, highlighted = null)

        assertEquals("1.4.0", drawn("1.4.0").text)
    }
}
