// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.ui

import androidx.compose.ui.input.key.KeyEvent
import dev.app.leaf.domain.models.AppConfig
import dev.app.leaf.domain.models.ui.TextSize
import dev.app.leaf.domain.services.AppSettingsService
import dev.app.leaf.keybindings.KeybindingOption
import dev.app.leaf.keybindings.matchesBinding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/** A step of the text size, which the shortcuts and the macOS View menu take. */
enum class TextSizeStep {
    BIGGER,
    SMALLER,
    DEFAULT;

    fun from(size: Int): Int = when (this) {
        BIGGER -> TextSize.bigger(size)
        SMALLER -> TextSize.smaller(size)
        DEFAULT -> TextSize.DEFAULT
    }
}

/** Where a step came from: a key press in the window, or the macOS menu bar, which can answer the same press too. */
enum class TextSizeStepSource { KEY, MENU }

/** The step that a key press asks for, or null for any other key. */
fun KeyEvent.textSizeStep(): TextSizeStep? = when {
    matchesBinding(KeybindingOption.TEXT_BIGGER) -> TextSizeStep.BIGGER
    matchesBinding(KeybindingOption.TEXT_SMALLER) -> TextSizeStep.SMALLER
    matchesBinding(KeybindingOption.TEXT_DEFAULT_SIZE) -> TextSizeStep.DEFAULT
    else -> null
}

/**
 * The size of Leaf's text (fork-only): the setting, which Settings writes, and the steps that the shortcuts and the
 * macOS View menu take, shown at once and written to the settings one after another.
 */
@Singleton
class TextSizeController internal constructor(
    private val appSettings: AppSettingsService,
    private val scope: CoroutineScope,
    private val nanoTime: () -> Long,
) {
    @Inject
    constructor(appSettings: AppSettingsService) :
            this(appSettings, CoroutineScope(SupervisorJob() + Dispatchers.IO), System::nanoTime)

    val size: StateFlow<Int>
        field = MutableStateFlow(TextSize.DEFAULT)

    /** The size a step asked for that the settings don't have yet, so that an earlier write doesn't take it back. */
    private var writing: Int? = null
    private val writes = Channel<Int>(Channel.CONFLATED)
    private var lastStep: Step? = null

    private class Step(val step: TextSizeStep, val source: TextSizeStepSource, val time: Long)

    /** Reads the setting, then follows it. Leaf calls it before its window shows, so that it opens at its size. */
    suspend fun start() {
        size.value = appSettings.textSize.first()

        scope.launch {
            appSettings.textSize.collect { stored ->
                synchronized(this@TextSizeController) {
                    if (writing == null) {
                        size.value = stored
                    }
                }
            }
        }

        scope.launch {
            for (value in writes) {
                try {
                    appSettings.setConfiguration(AppConfig.TextSize(value))
                } finally {
                    synchronized(this@TextSizeController) {
                        if (writing == value) {
                            writing = null
                        }
                    }
                }
            }
        }
    }

    fun step(step: TextSizeStep, source: TextSizeStepSource) {
        val time = nanoTime()

        synchronized(this) {
            val last = lastStep

            // On macOS, a press of a menu item's shortcut can reach the menu and the window both
            if (last != null && last.step == step && last.source != source && time - last.time < SAME_PRESS_NANOS) {
                return
            }

            lastStep = Step(step, source, time)

            val newSize = step.from(size.value)

            if (newSize != size.value) {
                size.value = newSize
                writing = newSize
                writes.trySend(newSize)
            }
        }
    }

    private companion object {
        /** Longer than the menu and the window take to answer one press, shorter than two presses by hand. */
        const val SAME_PRESS_NANOS = 250_000_000L
    }
}
