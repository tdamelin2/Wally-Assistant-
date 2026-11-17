package dev.tim.assistant.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.input.key.Key
import kotlin.concurrent.Volatile

/** The laptop keyboard commands: N = new, Esc = cancel, Enter = save, Ctrl+Enter = save long text, Ctrl+F = search, Ctrl+B = hide/show the chat list. */
enum class Shortcut { NewItem, Cancel, Save, SaveLong, FocusSearch, ToggleChatList }

/**
 * Maps one key-down to a command, or null. N counts only outside text fields; plain Enter saves only outside
 * multi-line fields (there it adds a line); Ctrl+Enter, Esc and Ctrl+F work anywhere. Key-up never reaches this.
 */
fun shortcutFor(key: Key, ctrl: Boolean, inTextField: Boolean, multiline: Boolean): Shortcut? {
    val enter = key == Key.Enter || key == Key.NumPadEnter
    return when {
        enter && ctrl -> Shortcut.SaveLong
        enter && !multiline -> Shortcut.Save
        key == Key.Escape -> Shortcut.Cancel
        key == Key.F && ctrl -> Shortcut.FocusSearch
        key == Key.B && ctrl -> Shortcut.ToggleChatList
        key == Key.N && !ctrl && !inTextField -> Shortcut.NewItem
        else -> null
    }
}

/** What the window currently offers; decides whether a command is consumed or left to the normal key handling. */
data class ShortcutContext(
    val chip: NavTab,
    val pickerOpen: Boolean,
    val formRegistered: Boolean,
    val writerRegistered: Boolean,
    val dialogOpen: Boolean,
    val searchMounted: Boolean,
)

/** True when [s] has something to do right now; false keys keep their normal behaviour (Enter in Chat, Esc in a dialog). */
fun shortcutHandled(s: Shortcut, ctx: ShortcutContext): Boolean = when (s) {
    Shortcut.NewItem -> addActionFor(ctx.chip) != null && !ctx.dialogOpen && !ctx.pickerOpen
    Shortcut.Cancel -> ctx.pickerOpen || ctx.formRegistered || ctx.writerRegistered
    Shortcut.Save -> ctx.formRegistered && !ctx.dialogOpen
    Shortcut.SaveLong -> ctx.writerRegistered && !ctx.dialogOpen
    Shortcut.FocusSearch -> ctx.chip == NavTab.Files && ctx.searchMounted && !ctx.dialogOpen
    Shortcut.ToggleChatList -> ctx.chip == NavTab.Chat && !ctx.dialogOpen && !ctx.pickerOpen
}

/**
 * Which text field has focus. Every field has its own owner token, so a blur or dispose of an old field can never
 * clear the state of a newer one that already took focus.
 */
class TextEntryState {
    @Volatile
    private var owner: Any? = null

    @Volatile
    var inTextField: Boolean = false
        private set

    @Volatile
    var multiline: Boolean = false
        private set

    fun focus(token: Any, multiline: Boolean) {
        owner = token
        inTextField = true
        this.multiline = multiline
    }

    fun blur(token: Any) {
        if (owner === token) {
            owner = null
            inTextField = false
            multiline = false
        }
    }
}

val LocalTextEntry = compositionLocalOf { TextEntryState() }

/**
 * The forms and writers that are open register their Save/Cancel here with an owner token; unregistering with an
 * old token does nothing once a newer form has registered.
 */
class ShortcutRegistry {
    private class Slot(val token: Any, val save: () -> Unit, val saveLong: () -> Unit, val cancel: () -> Unit)

    @Volatile
    private var form: Slot? = null

    @Volatile
    private var writer: Slot? = null

    @Volatile
    private var search: Any? = null

    val formRegistered: Boolean get() = form != null
    val writerRegistered: Boolean get() = writer != null
    val searchMounted: Boolean get() = search != null

    fun registerForm(token: Any, onSave: () -> Unit, onCancel: () -> Unit) {
        form = Slot(token, onSave, {}, onCancel)
    }

    fun unregisterForm(token: Any) {
        if (form?.token === token) form = null
    }

    fun registerWriter(token: Any, onSave: () -> Unit, onCancel: () -> Unit) {
        writer = Slot(token, onSave, onSave, onCancel)
    }

    fun unregisterWriter(token: Any) {
        if (writer?.token === token) writer = null
    }

    fun registerSearch(token: Any) {
        search = token
    }

    fun unregisterSearch(token: Any) {
        if (search === token) search = null
    }

    /** Esc: the open form or writer closes. */
    fun cancel() {
        (form ?: writer)?.cancel?.invoke()
    }

    fun save() {
        form?.save?.invoke()
    }

    fun saveLong() {
        writer?.saveLong?.invoke()
    }
}

val LocalShortcutRegistry = compositionLocalOf { ShortcutRegistry() }

/** App-side facts the window's key hook needs, plus the live registry. The window reads [context] on every key. */
class ShortcutState {
    val registry = ShortcutRegistry()

    @Volatile
    var chip: NavTab = NavTab.Today

    @Volatile
    var pickerOpen: Boolean = false

    @Volatile
    var dialogOpen: Boolean = false

    fun context(): ShortcutContext = ShortcutContext(
        chip = chip,
        pickerOpen = pickerOpen,
        formRegistered = registry.formRegistered,
        writerRegistered = registry.writerRegistered,
        dialogOpen = dialogOpen,
        searchMounted = registry.searchMounted,
    )
}

/**
 * A screen with unsaved work registers here (with an owner token) so the shell can ask "Discard your changes?" before a
 * rail, chip or New click replaces it. Unregistering with an old token does nothing once a newer screen has registered.
 */
class LeaveGuard {
    private class Slot(val token: Any, val dirty: () -> Boolean)

    @Volatile
    private var slot: Slot? = null

    val isDirty: Boolean get() = slot?.dirty?.invoke() == true

    fun register(token: Any, dirty: () -> Boolean) {
        slot = Slot(token, dirty)
    }

    fun unregister(token: Any) {
        if (slot?.token === token) slot = null
    }
}

val LocalLeaveGuard = compositionLocalOf { LeaveGuard() }

/** Registers the calling screen with the [LeaveGuard] while it is shown; [dirty] is read at the moment of a click. */
@Composable
fun LeaveGuardEffect(dirty: Boolean) {
    val guard = LocalLeaveGuard.current
    val current by rememberUpdatedState(dirty)
    val token = remember { Any() }
    DisposableEffect(guard, token) {
        guard.register(token) { current }
        onDispose { guard.unregister(token) }
    }
}

/** Lets one Save (button or key) start at a time; released shortly after, so a failed save can be tried again. */
class SaveGuard {
    private var running = false

    fun tryStart(): Boolean {
        if (running) return false
        running = true
        return true
    }

    fun release() {
        running = false
    }
}

/** How long one started save blocks a second one. */
const val SAVE_GUARD_MS = 1_000L
