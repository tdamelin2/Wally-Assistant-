package dev.tim.assistant.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.rememberUpdatedState

/**
 * The screens that want the system back button, innermost last. The phone activity listens with
 * `androidx.activity.compose.BackHandler(enabled = SystemBackStack.active) { SystemBackStack.back() }`;
 * the desktop has no back button, so nothing calls [back] there.
 */
object SystemBackStack {
    private val handlers = mutableStateListOf<() -> Unit>()

    /** True while at least one screen has asked for back. */
    val active: Boolean get() = handlers.isNotEmpty()

    /** Runs the innermost handler (the most recently shown screen). */
    fun back() {
        handlers.lastOrNull()?.invoke()
    }

    internal fun push(handler: () -> Unit) { handlers.add(handler) }
    internal fun remove(handler: () -> Unit) { handlers.remove(handler) }
}

/** While this is shown and [enabled], system back (phone back button or gesture) runs [onBack]. */
@Composable
fun SystemBack(enabled: Boolean = true, onBack: () -> Unit) {
    val current = rememberUpdatedState(onBack)
    DisposableEffect(enabled) {
        val handler: () -> Unit = { current.value() }
        if (enabled) SystemBackStack.push(handler)
        onDispose { SystemBackStack.remove(handler) }
    }
}
