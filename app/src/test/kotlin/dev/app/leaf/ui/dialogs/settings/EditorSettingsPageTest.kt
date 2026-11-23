// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.ui.dialogs.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getAllSemanticsNodes
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import dev.app.leaf.domain.editor.CUSTOM_EDITOR
import dev.app.leaf.domain.editor.DEFAULT_APP_EDITOR
import dev.app.leaf.domain.editor.EditorKind
import dev.app.leaf.domain.editor.EditorSettings
import dev.app.leaf.domain.editor.InstalledEditor
import dev.app.leaf.domain.models.AppConfig
import dev.app.leaf.domain.models.ui.Theme
import dev.app.leaf.theme.AppTheme
import dev.app.leaf.viewmodels.SettingsAction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.swing.Swing
import org.jetbrains.skia.EncodedImageFormat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/** Settings › Editor (fork-only). Set `LEAF_RENDER_DIR` and pass `--rerun` for PNGs of it in both themes. */
class EditorSettingsPageTest {
    private var scene: ImageComposeScene? = null
    private val actions = mutableListOf<SettingsAction>()

    private val installed = listOf(
        InstalledEditor("intellij-idea", "IntelliJ IDEA", EditorKind.JETBRAINS, tool = null),
        InstalledEditor("vscode", "Visual Studio Code", EditorKind.VS_CODE, tool = null),
    )

    @AfterEach
    fun close() {
        scene?.close()
    }

    private fun show(state: EditorSettingsState, theme: Theme = Theme.Light) {
        scene?.close()
        scene = ImageComposeScene(width = 760, height = 520, density = Density(1f)) {
            AppTheme(selectedTheme = theme) {
                Box(Modifier.background(MaterialTheme.colors.background).padding(16.dp)) {
                    Column(Modifier.width(700.dp)) { EditorSettingsPage(state) { actions += it } }
                }
            }
        }.also { it.render() }
    }

    @Test
    fun `the editor's choices are none, the editors found, the default app and a command`() {
        val labels = composed { editorOptions(EditorSettingsState(EditorSettings(), installed)).map { it.optionName } }

        assertEquals(
            listOf("Not chosen", "IntelliJ IDEA", "Visual Studio Code", "Default app for each file type", "Custom command"),
            labels,
        )
    }

    @Test
    fun `a chosen editor that isn't found stays a choice, which says so`() {
        val options = composed { editorOptions(EditorSettingsState(EditorSettings(editor = "zed"), installed)) }

        assertEquals("Zed (not found)", options.single { it.value == "zed" }.optionName)
        assertEquals(
            "an-editor-of-a-later-leaf (not found)",
            composed {
                editorOptions(EditorSettingsState(EditorSettings(editor = "an-editor-of-a-later-leaf"), installed))
            }.single { it.value == "an-editor-of-a-later-leaf" }.optionName,
        )
    }

    @Test
    fun `the command row shows only for a command of one's own`(): Unit = runBlocking(Dispatchers.Swing) {
        show(EditorSettingsState(EditorSettings(editor = "vscode"), installed))
        assertFalse("Command" in texts(), texts().toString())

        show(EditorSettingsState(EditorSettings(editor = CUSTOM_EDITOR, customCommand = "ed {file}"), installed))
        assertTrue("Command" in texts(), texts().toString())
        assertTrue("ed {file}" in texts(), texts().toString())
    }

    @Test
    fun `repositories with their own editor are listed by name, and one can be taken away`(): Unit =
        runBlocking(Dispatchers.Swing) {
            val editors = mapOf("/Users/me/website/.git" to "vscode", "/Users/me/app/.git" to DEFAULT_APP_EDITOR)
            show(EditorSettingsState(EditorSettings(editor = "intellij-idea", repositoryEditors = editors), installed))

            val texts = texts()
            assertTrue(texts.indexOf("app") < texts.indexOf("website"), texts.toString())
            assertTrue("Visual Studio Code" in texts && "Default app for each file type" in texts, texts.toString())

            val remove = nodes().first { "Remove website" in it.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty() }
            checkNotNull(remove.config[SemanticsActions.OnClick].action).invoke()

            assertEquals(
                listOf<SettingsAction>(SettingsAction.SetConfig(AppConfig.RepositoryEditors(mapOf("/Users/me/app/.git" to DEFAULT_APP_EDITOR)))),
                actions,
            )
        }

    @Test
    fun `with none of their own, the list says so`(): Unit = runBlocking(Dispatchers.Swing) {
        show(EditorSettingsState(EditorSettings(), installed))

        assertTrue("None" in texts(), texts().toString())
        assertTrue("Not chosen" in texts(), texts().toString())
    }

    @Test
    fun `renders in both themes`(): Unit = runBlocking(Dispatchers.Swing) {
        val dir = System.getenv("LEAF_RENDER_DIR")?.let(::File) ?: return@runBlocking
        val editors = mapOf("/Users/me/website/.git" to "vscode")

        for (theme in listOf(Theme.Light, Theme.Dark)) {
            show(EditorSettingsState(EditorSettings(editor = "intellij-idea", repositoryEditors = editors), installed), theme)
            File(dir, "settings-editor-${theme.name.lowercase()}.png")
                .writeBytes(scene!!.render().encodeToData(EncodedImageFormat.PNG)!!.bytes)
        }
    }

    private fun nodes(): List<SemanticsNode> = scene!!.semanticsOwners.flatMap { it.getAllSemanticsNodes(false) }

    private fun texts(): List<String> = nodes().flatMap {
        it.config.getOrNull(SemanticsProperties.Text).orEmpty().map { text -> text.text } +
                listOfNotNull(it.config.getOrNull(SemanticsProperties.EditableText)?.text)
    }

    private fun <T> composed(read: @Composable () -> T): T {
        var value: T? = null
        val scene = ImageComposeScene(width = 10, height = 10) { value = read() }

        try {
            scene.render()
        } finally {
            scene.close()
        }

        @Suppress("UNCHECKED_CAST")
        return value as T
    }
}
