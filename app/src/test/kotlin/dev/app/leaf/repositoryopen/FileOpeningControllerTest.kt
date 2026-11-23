// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.repositoryopen

import dev.app.leaf.domain.editor.DEFAULT_APP_EDITOR
import dev.app.leaf.domain.editor.EditorChoice
import dev.app.leaf.domain.editor.EditorKind
import dev.app.leaf.domain.editor.EditorSettings
import dev.app.leaf.domain.editor.IExternalEditors
import dev.app.leaf.domain.editor.InstalledEditor
import dev.app.leaf.domain.models.AppConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/** Which editor "Open in …" names for a tab, and the picker of "Open in another editor…" (fork-only). */
class FileOpeningControllerTest {
    @TempDir
    lateinit var temp: File

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val vsCode = InstalledEditor("vscode", "Visual Studio Code", EditorKind.VS_CODE, tool = "/bin/code")
    private val intellij = InstalledEditor("intellij-idea", "IntelliJ IDEA", EditorKind.JETBRAINS, tool = "/bin/idea")

    private val settings = MutableStateFlow(EditorSettings())
    private val opened = mutableListOf<Triple<String, Int?, String?>>()
    private val saved = mutableListOf<AppConfig>()
    private var looks = 0

    private val editors = object : IExternalEditors {
        override suspend fun installed(refresh: Boolean): List<InstalledEditor> {
            looks++
            return listOf(vsCode, intellij)
        }
    }

    /** A repository's git dir, as the tab has it, and the real path that its editor is kept by. */
    private val gitDir by lazy { File(temp, "leaf/.git").apply { mkdirs() } }
    private val repository by lazy { gitDir.canonicalPath }

    private fun controller() = FileOpeningController(
        scope = scope,
        gitDir = MutableStateFlow(gitDir.path),
        editorSettings = settings,
        editors = editors,
        openInEditor = { path, line, id -> synchronized(opened) { opened += Triple(path, line, id) } },
        showInFileManager = {},
        saveSetting = { config ->
            synchronized(saved) { saved += config }
            settings.value = when (config) {
                is AppConfig.Editor -> settings.value.copy(editor = config.id)
                is AppConfig.RepositoryEditors -> settings.value.copy(repositoryEditors = config.editors)
                else -> settings.value
            }
        },
    )

    @AfterEach
    fun cancel() {
        scope.cancel()
    }

    private fun <T> waitFor(read: () -> T, until: (T) -> Boolean): T = runBlocking {
        withTimeout(5_000) {
            while (!until(read())) kotlinx.coroutines.delay(10)
            read()
        }
    }

    @Test
    fun `the tab's repository takes its own editor, else the default`(): Unit = runBlocking {
        settings.value = EditorSettings(editor = "vscode", repositoryEditors = mapOf(repository to "intellij-idea"))
        val controller = controller()

        assertEquals(EditorChoice.Installed(intellij), controller.choice.first { it is EditorChoice.Installed })

        settings.value = EditorSettings(editor = "vscode")
        assertEquals(EditorChoice.Installed(vsCode), controller.choice.first { it == EditorChoice.Installed(vsCode) })
    }

    @Test
    fun `with no editor chosen, opening asks which one, after looking for the editors again`(): Unit = runBlocking {
        val controller = controller()
        waitFor({ looks }) { it >= 1 }

        controller.open("a/b.kt", 12, isAtMenu = true)

        val picker = controller.picker.first { it != null }!!
        assertEquals(EditorPickerState("a/b.kt", 12, listOf(vsCode, intellij), null, null, "leaf", true), picker)
        assertEquals(2, looks)
        assertEquals(emptyList<Triple<String, Int?, String?>>(), opened)
    }

    @Test
    fun `with an editor chosen, opening opens it at once`(): Unit = runBlocking {
        settings.value = EditorSettings(editor = "vscode")
        val controller = controller()
        controller.choice.first { it is EditorChoice.Installed }

        controller.open("a/b.kt", 12, isAtMenu = true)

        assertEquals(listOf(Triple("a/b.kt", 12, null)), opened)
        assertNull(controller.picker.value)
    }

    @Test
    fun `the first editor picked to keep becomes the default`(): Unit = runBlocking {
        val controller = controller()
        controller.chooseEditor("a.kt", null, isAtMenu = false)
        controller.picker.first { it != null }

        controller.pick("intellij-idea", remember = true)

        waitFor({ synchronized(opened) { opened.toList() } }) { it.isNotEmpty() }
        assertEquals(listOf<AppConfig>(AppConfig.Editor("intellij-idea")), saved)
        assertEquals(listOf(Triple("a.kt", null, "intellij-idea")), opened)
        assertNull(controller.picker.value)
    }

    @Test
    fun `with a default, an editor kept for the repository is its own, and the default takes its own away`(): Unit =
        runBlocking {
            settings.value = EditorSettings(editor = "vscode")
            val controller = controller()
            controller.choice.first { it is EditorChoice.Installed }

            controller.chooseEditor("a.kt", 3, isAtMenu = true)
            controller.picker.first { it != null }
            controller.pick("intellij-idea", remember = true)
            waitFor({ synchronized(saved) { saved.toList() } }) { it.size == 1 }

            assertEquals(mapOf(repository to "intellij-idea"), settings.value.repositoryEditors)
            assertEquals(EditorChoice.Installed(intellij), controller.choice.first { it == EditorChoice.Installed(intellij) })

            controller.chooseEditor("a.kt", 3, isAtMenu = true)
            assertEquals("intellij-idea", controller.picker.first { it != null }!!.repositoryEditor)
            controller.pick("vscode", remember = true)
            waitFor({ synchronized(saved) { saved.toList() } }) { it.size == 2 }

            assertEquals(emptyMap<String, String>(), settings.value.repositoryEditors)
        }

    @Test
    fun `a pick that isn't kept opens once and changes no setting`(): Unit = runBlocking {
        settings.value = EditorSettings(editor = "vscode")
        val controller = controller()

        controller.chooseEditor("a.kt", 3, isAtMenu = true)
        controller.picker.first { it != null }
        controller.pick(DEFAULT_APP_EDITOR, remember = false)

        waitFor({ synchronized(opened) { opened.toList() } }) { it.isNotEmpty() }
        assertEquals(emptyList<AppConfig>(), saved)
        assertEquals(listOf(Triple("a.kt", 3, DEFAULT_APP_EDITOR)), opened)
    }

    @Test
    fun `closing the picker opens nothing, and a pick after it does nothing`(): Unit = runBlocking {
        val controller = controller()
        controller.chooseEditor("a.kt", null, isAtMenu = true)
        controller.picker.first { it != null }

        controller.closePicker()
        controller.pick("vscode", remember = true)

        kotlinx.coroutines.delay(100)
        assertEquals(emptyList<Triple<String, Int?, String?>>(), opened)
        assertEquals(emptyList<AppConfig>(), saved)
    }
}
