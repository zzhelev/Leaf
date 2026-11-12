// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.ui.dialogs.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.MaterialTheme
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getAllSemanticsNodes
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import dev.app.leaf.domain.models.AppConfig
import dev.app.leaf.domain.models.ui.Theme
import dev.app.leaf.theme.AppTheme
import dev.app.leaf.viewmodels.SettingsAction
import dev.app.leaf.viewmodels.emptySettingsState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.swing.Swing
import org.jetbrains.skia.EncodedImageFormat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

private const val TITLE = "Cache credentials in memory"

/**
 * Settings → Authentication's switch (fork-only text), which covers SSH key passphrases as well as HTTPS credentials.
 * Set `LEAF_RENDER_DIR` for PNGs of the row in both themes.
 */
class CredentialsCacheRowTest {
    private var scene: ImageComposeScene? = null
    private val actions = mutableListOf<SettingsAction>()

    @AfterEach
    fun close() {
        scene?.close()
    }

    private fun show(isOn: Boolean, theme: Theme = Theme.Light) {
        scene?.close()
        scene = ImageComposeScene(width = 1600, height = 180, density = Density(2f), coroutineContext = Dispatchers.Swing) {
            AppTheme(selectedTheme = theme) {
                Box(Modifier.background(MaterialTheme.colors.background).padding(16.dp)) {
                    // As wide as the Settings dialog's content: 1000 dp less its 200 dp of sections and the padding
                    Box(Modifier.width(768.dp)) {
                        Column {
                            Authentication(emptySettingsState().copy(cacheCredentialsInMemory = isOn)) { actions += it }
                        }
                    }
                }
            }
        }.also { it.render() }
    }

    @Test
    fun `the row says that it covers passphrases too`(): Unit = runBlocking(Dispatchers.Swing) {
        show(isOn = true)

        assertTrue(TITLE in texts(), "the title, in ${texts()}")
        assertTrue("HTTPS credentials and SSH key passphrases are remembered until Leaf is closed" in texts())
        assertTrue(texts().none { "HTTP Credentials" in it || "HTTP credentials" in it }, "the old wording")
    }

    @Test
    fun `switching it sets the other value`(): Unit = runBlocking(Dispatchers.Swing) {
        for (isOn in listOf(true, false)) {
            show(isOn)

            actions.clear()
            checkNotNull(switch().config[SemanticsActions.OnClick].action).invoke()

            assertEquals(listOf<SettingsAction>(SettingsAction.SetConfig(AppConfig.CacheCredentialsInMemory(!isOn))), actions)
        }
    }

    @Test
    fun `renders the row`(): Unit = runBlocking(Dispatchers.Swing) {
        val dir = System.getenv("LEAF_RENDER_DIR")?.let(::File) ?: return@runBlocking
        dir.mkdirs()

        for (theme in listOf(Theme.Light, Theme.Dark)) {
            show(isOn = true, theme)
            repeat(12) {
                delay(16)
                scene!!.render(System.nanoTime())
            }
            val file = File(dir, "credentials-cache-row-${theme.name.lowercase()}.png")
            file.writeBytes(scene!!.render(System.nanoTime()).encodeToData(EncodedImageFormat.PNG)!!.bytes)
        }
    }

    private fun nodes() = scene!!.semanticsOwners.flatMap { it.getAllSemanticsNodes(mergingEnabled = false) }

    private fun texts() = nodes().flatMap { node ->
        node.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text }
    }

    /** The row's only control. */
    private fun switch(): SemanticsNode = nodes().single { SemanticsActions.OnClick in it.config }
}
