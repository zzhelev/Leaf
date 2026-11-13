// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.ui.find

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.KeyEvent
import dev.app.leaf.domain.log.SearchStep
import dev.app.leaf.keybindings.KeybindingOption
import dev.app.leaf.keybindings.matchesBinding

/**
 * The views of a tab that find can search (fork-only, upstream issues 275 and 312): the diff pane, file history and
 * blame, whichever shows, as each takes the place of the others. The tab's keys reach them through it: they see every
 * key first (`RepositoryOpenPage`), so Ctrl+F (⌘F) works wherever the focus is, a list of files included.
 */
class FindTargets {
    private val targets = ArrayList<Target>()

    /** The log, or its search field, has the focus: Ctrl+F (⌘F) is the log search's then. */
    var isLogFocused = false

    private class Target(val state: FindState) {
        var hasFocus = false
    }

    /** The view that find searches: the one that showed last. */
    val current: FindState? get() = targets.lastOrNull()?.state

    fun add(state: FindState) {
        if (targets.none { it.state === state }) targets.add(Target(state))
    }

    fun remove(state: FindState) {
        targets.removeAll { it.state === state }
    }

    fun focusChanged(state: FindState, hasFocus: Boolean) {
        targets.find { it.state === state }?.hasFocus = hasFocus
    }

    /**
     * Handles the find keys of the tab's [event]: find, go to line, and the next and the previous match. Ctrl+F (⌘F)
     * goes to [onFindInLog] while the log has the focus or nothing else can find. False for any other key.
     */
    fun onKeyEvent(event: KeyEvent, onFindInLog: () -> Unit): Boolean {
        val target = current

        return when {
            event.matchesBinding(KeybindingOption.FIND) -> {
                if (target == null || isLogFocused) onFindInLog() else target.open(FindField.FIND)
                true
            }

            event.matchesBinding(KeybindingOption.GO_TO_LINE) -> {
                target?.open(FindField.LINE)
                target != null
            }

            event.matchesBinding(KeybindingOption.FIND_NEXT) -> step(target, SearchStep.NEXT)
            event.matchesBinding(KeybindingOption.FIND_PREVIOUS) -> step(target, SearchStep.PREVIOUS)
            else -> false
        }
    }

    /** Esc closes the strip of the view that has the focus. False when there is none, and Esc goes on. */
    fun closeFocusedFind(): Boolean {
        val target = targets.lastOrNull { it.hasFocus && it.state.isOpen } ?: return false

        target.state.close()
        return true
    }

    private fun step(target: FindState?, step: SearchStep): Boolean {
        if (target == null) return false

        if (!target.isOpen || target.mode != FindField.FIND || target.query.text.isEmpty()) {
            target.open(FindField.FIND)
        } else {
            target.step(step)
        }

        return true
    }
}

val LocalFindTargets = staticCompositionLocalOf<FindTargets?> { null }

/** Makes [state] the view that find searches while this is composed and [isAvailable]. */
@Composable
fun FindTarget(state: FindState, isAvailable: Boolean) {
    val targets = LocalFindTargets.current ?: return

    DisposableEffect(targets, state, isAvailable) {
        if (isAvailable) targets.add(state)

        onDispose { targets.remove(state) }
    }
}

/**
 * The root of the view of [state]: tells the tab whether it has the focus, which Esc closes its strip in, and takes
 * Select all and Copy for its lines wherever the focus is in it.
 */
@Composable
fun Modifier.findableView(state: FindState): Modifier {
    val targets = LocalFindTargets.current
    val focus = if (targets == null) this else onFocusChanged { targets.focusChanged(state, it.hasFocus) }

    return focus.selectAllKeys(state)
}

/** Tells the tab whether the log has the focus, where Ctrl+F (⌘F) goes to the log's search instead. */
@Composable
fun Modifier.findsInLogWhileFocused(): Modifier {
    val targets = LocalFindTargets.current ?: return this

    // The log goes when blame takes the centre, with the focus in it
    DisposableEffect(targets) {
        onDispose { targets.isLogFocused = false }
    }

    return onFocusChanged { targets.isLogFocused = it.hasFocus }
}
