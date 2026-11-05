// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.ui

import dev.app.leaf.domain.models.AppConfig
import dev.app.leaf.domain.models.ui.TextSize
import dev.app.leaf.domain.repositories.AppSettingsRepository
import dev.app.leaf.domain.services.AppSettingsService
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.util.Collections

/** The text size's steps from the shortcuts and the macOS View menu, and the setting they write (fork-only). */
class TextSizeControllerTest {
    private val stored = MutableStateFlow<Int?>(null)
    private val writes: MutableList<Int> = Collections.synchronizedList(mutableListOf())
    private val startedWrites: MutableList<Int> = Collections.synchronizedList(mutableListOf())

    /** Each write waits for the next of these, while there are any, until a test lets it finish. */
    private val writeGates = ArrayDeque<CompletableDeferred<Unit>>()

    private val repository = mockk<AppSettingsRepository>(relaxed = true).also { repository ->
        every { repository.textSize } returns stored
        coEvery { repository.setConfiguration(any()) } coAnswers {
            val config = firstArg<AppConfig>() as AppConfig.TextSize
            startedWrites += config.value
            synchronized(writeGates) { writeGates.removeFirstOrNull() }?.await()
            writes += config.value
            stored.value = config.value
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var now = 0L

    @AfterEach
    fun cancel() {
        scope.cancel()
    }

    private fun controller(): TextSizeController =
        TextSizeController(AppSettingsService(repository), scope) { now }.also { runBlocking { it.start() } }

    private fun ms(millis: Long) = millis * 1_000_000L

    private suspend fun awaitWritten(size: Int) = withTimeout(5_000) {
        while (stored.value != size) delay(5)
    }

    @Test
    fun `it starts at the stored size, or at the default without one`() {
        assertEquals(TextSize.DEFAULT, controller().size.value)

        stored.value = 16
        assertEquals(16, controller().size.value)
    }

    @Test
    fun `a step shows at once and is written`(): Unit = runBlocking {
        val controller = controller()

        controller.step(TextSizeStep.BIGGER, TextSizeStepSource.KEY)

        assertEquals(14, controller.size.value)
        awaitWritten(14)
        assertEquals(listOf(14), writes)
    }

    @Test
    fun `repeated presses each take a step, and the default goes back to 13`(): Unit = runBlocking {
        val controller = controller()

        repeat(4) {
            now += ms(30)
            controller.step(TextSizeStep.BIGGER, TextSizeStepSource.KEY)
        }
        assertEquals(17, controller.size.value)

        now += ms(30)
        controller.step(TextSizeStep.SMALLER, TextSizeStepSource.KEY)
        assertEquals(16, controller.size.value)

        now += ms(30)
        controller.step(TextSizeStep.DEFAULT, TextSizeStepSource.MENU)
        assertEquals(13, controller.size.value)
        awaitWritten(13)
    }

    @Test
    fun `one press that reaches the menu and the window takes one step, in either order`() {
        val controller = controller()

        controller.step(TextSizeStep.BIGGER, TextSizeStepSource.KEY)
        now += ms(5)
        controller.step(TextSizeStep.BIGGER, TextSizeStepSource.MENU)
        assertEquals(14, controller.size.value)

        now += ms(1_000)
        controller.step(TextSizeStep.SMALLER, TextSizeStepSource.MENU)
        now += ms(5)
        controller.step(TextSizeStep.SMALLER, TextSizeStepSource.KEY)
        assertEquals(13, controller.size.value)
    }

    @Test
    fun `holding the key keeps stepping, though the menu answers each press too`() {
        val controller = controller()

        repeat(3) {
            now += ms(40)
            controller.step(TextSizeStep.BIGGER, TextSizeStepSource.KEY)
            now += ms(2)
            controller.step(TextSizeStep.BIGGER, TextSizeStepSource.MENU)
        }

        assertEquals(16, controller.size.value)
    }

    @Test
    fun `a click in the menu a while after a press, or another step, isn't taken for the same press`() {
        val controller = controller()

        controller.step(TextSizeStep.BIGGER, TextSizeStepSource.KEY)
        now += ms(300)
        controller.step(TextSizeStep.BIGGER, TextSizeStepSource.MENU)
        assertEquals(15, controller.size.value)

        now += ms(5)
        controller.step(TextSizeStep.SMALLER, TextSizeStepSource.KEY)
        assertEquals(14, controller.size.value)
    }

    @Test
    fun `stepping past either end writes nothing`(): Unit = runBlocking {
        stored.value = 20
        val controller = controller()

        controller.step(TextSizeStep.BIGGER, TextSizeStepSource.KEY)
        stored.value = 11
        awaitSize(controller, 11)
        now += ms(1_000)
        controller.step(TextSizeStep.SMALLER, TextSizeStepSource.KEY)
        delay(100)

        assertEquals(11, controller.size.value)
        assertEquals(emptyList<Int>(), writes)
    }

    @Test
    fun `a size set in Settings is followed`(): Unit = runBlocking {
        val controller = controller()

        stored.value = 18

        awaitSize(controller, 18)
    }

    @Test
    fun `a write that is still running doesn't take a later step back`(): Unit = runBlocking {
        val controller = controller()
        val first = CompletableDeferred<Unit>()
        val second = CompletableDeferred<Unit>()
        synchronized(writeGates) {
            writeGates += first
            writeGates += second
        }

        controller.step(TextSizeStep.BIGGER, TextSizeStepSource.KEY)
        // The writer has taken 14 and waits; later steps until it's done come after it as one
        withTimeout(5_000) { while (14 !in startedWrites) delay(5) }
        now += ms(40)
        controller.step(TextSizeStep.BIGGER, TextSizeStepSource.KEY)
        now += ms(40)
        controller.step(TextSizeStep.BIGGER, TextSizeStepSource.KEY)
        assertEquals(16, controller.size.value)

        // The first write comes back from the settings while the last one waits
        first.complete(Unit)
        awaitWritten(14)
        delay(100)
        assertEquals(16, controller.size.value)

        second.complete(Unit)
        awaitWritten(16)
        delay(100)
        assertEquals(16, controller.size.value)
        assertEquals(listOf(14, 16), writes)
    }

    private suspend fun awaitSize(controller: TextSizeController, size: Int) = withTimeout(5_000) {
        while (controller.size.value != size) delay(5)
    }
}
