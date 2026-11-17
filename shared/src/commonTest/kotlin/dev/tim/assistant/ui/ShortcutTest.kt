package dev.tim.assistant.ui

import androidx.compose.ui.input.key.Key
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ShortcutTest {
    private fun ctx(
        chip: NavTab = NavTab.Today,
        picker: Boolean = false,
        form: Boolean = false,
        writer: Boolean = false,
        dialog: Boolean = false,
        search: Boolean = false,
    ) = ShortcutContext(chip, picker, form, writer, dialog, search)

    @Test
    fun nOpensNewOnlyOutsideTextFields() {
        assertEquals(Shortcut.NewItem, shortcutFor(Key.N, ctrl = false, inTextField = false, multiline = false))
        assertNull(shortcutFor(Key.N, ctrl = false, inTextField = true, multiline = false))
        assertNull(shortcutFor(Key.N, ctrl = false, inTextField = true, multiline = true))
        assertNull(shortcutFor(Key.N, ctrl = true, inTextField = false, multiline = false))
    }

    @Test
    fun enterSavesOnlyOutsideMultilineFields() {
        assertEquals(Shortcut.Save, shortcutFor(Key.Enter, false, inTextField = true, multiline = false))
        assertEquals(Shortcut.Save, shortcutFor(Key.NumPadEnter, false, inTextField = false, multiline = false))
        assertNull(shortcutFor(Key.Enter, false, inTextField = true, multiline = true))
    }

    @Test
    fun ctrlEnterSavesLongTextEvenInMultilineFields() {
        assertEquals(Shortcut.SaveLong, shortcutFor(Key.Enter, true, inTextField = true, multiline = true))
        assertEquals(Shortcut.SaveLong, shortcutFor(Key.Enter, true, inTextField = false, multiline = false))
    }

    @Test
    fun escapeCancelsAnywhereAndCtrlFSearches() {
        assertEquals(Shortcut.Cancel, shortcutFor(Key.Escape, false, inTextField = true, multiline = true))
        assertEquals(Shortcut.Cancel, shortcutFor(Key.Escape, false, inTextField = false, multiline = false))
        assertEquals(Shortcut.FocusSearch, shortcutFor(Key.F, true, inTextField = true, multiline = false))
        assertNull(shortcutFor(Key.F, false, inTextField = false, multiline = false))
    }

    @Test
    fun ctrlBTogglesTheChatListOnlyInChatAndNeverPlainB() {
        assertEquals(Shortcut.ToggleChatList, shortcutFor(Key.B, true, inTextField = true, multiline = true))
        assertEquals(Shortcut.ToggleChatList, shortcutFor(Key.B, true, inTextField = false, multiline = false))
        assertNull(shortcutFor(Key.B, false, inTextField = false, multiline = false))
        assertTrue(shortcutHandled(Shortcut.ToggleChatList, ctx(chip = NavTab.Chat)))
        assertFalse(shortcutHandled(Shortcut.ToggleChatList, ctx(chip = NavTab.Today)))
        assertFalse(shortcutHandled(Shortcut.ToggleChatList, ctx(chip = NavTab.Chat, dialog = true)))
        assertFalse(shortcutHandled(Shortcut.ToggleChatList, ctx(chip = NavTab.Chat, picker = true)))
    }

    @Test
    fun otherKeysDoNothing() {
        assertNull(shortcutFor(Key.A, false, inTextField = false, multiline = false))
        assertNull(shortcutFor(Key.Tab, false, inTextField = false, multiline = false))
        assertNull(shortcutFor(Key.Spacebar, true, inTextField = true, multiline = true))
    }

    @Test
    fun newItemNeedsAnAddActionAndNoDialogOrPicker() {
        assertTrue(shortcutHandled(Shortcut.NewItem, ctx(chip = NavTab.Today)))
        assertTrue(shortcutHandled(Shortcut.NewItem, ctx(chip = NavTab.Tasks)))
        assertFalse(shortcutHandled(Shortcut.NewItem, ctx(chip = NavTab.Charts)))
        assertFalse(shortcutHandled(Shortcut.NewItem, ctx(chip = NavTab.Chat)))
        assertFalse(shortcutHandled(Shortcut.NewItem, ctx(chip = NavTab.Today, picker = true)))
        assertFalse(shortcutHandled(Shortcut.NewItem, ctx(chip = NavTab.Today, dialog = true)))
    }

    @Test
    fun cancelNeedsAnOpenPickerFormOrWriter() {
        assertFalse(shortcutHandled(Shortcut.Cancel, ctx()))
        assertTrue(shortcutHandled(Shortcut.Cancel, ctx(picker = true)))
        assertTrue(shortcutHandled(Shortcut.Cancel, ctx(form = true)))
        assertTrue(shortcutHandled(Shortcut.Cancel, ctx(writer = true)))
    }

    @Test
    fun saveNeedsARegisteredFormAndNoDialog() {
        assertFalse(shortcutHandled(Shortcut.Save, ctx(chip = NavTab.Chat)))
        assertTrue(shortcutHandled(Shortcut.Save, ctx(form = true)))
        assertFalse(shortcutHandled(Shortcut.Save, ctx(form = true, dialog = true)))
        assertFalse(shortcutHandled(Shortcut.Save, ctx(writer = true)))
    }

    @Test
    fun saveLongNeedsARegisteredWriterAndNoDialog() {
        assertTrue(shortcutHandled(Shortcut.SaveLong, ctx(writer = true)))
        assertFalse(shortcutHandled(Shortcut.SaveLong, ctx(writer = true, dialog = true)))
        assertFalse(shortcutHandled(Shortcut.SaveLong, ctx(form = true)))
    }

    @Test
    fun focusSearchOnlyOnFilesWithItsSearchField() {
        assertTrue(shortcutHandled(Shortcut.FocusSearch, ctx(chip = NavTab.Files, search = true)))
        assertFalse(shortcutHandled(Shortcut.FocusSearch, ctx(chip = NavTab.Files, search = false)))
        assertFalse(shortcutHandled(Shortcut.FocusSearch, ctx(chip = NavTab.Mail, search = true)))
        assertFalse(shortcutHandled(Shortcut.FocusSearch, ctx(chip = NavTab.Files, search = true, dialog = true)))
    }

    @Test
    fun anOldFieldCannotClearTheNewerOne() {
        val entry = TextEntryState()
        val first = Any()
        val second = Any()
        entry.focus(first, multiline = false)
        entry.focus(second, multiline = true)
        entry.blur(first)
        assertTrue(entry.inTextField)
        assertTrue(entry.multiline)
        entry.blur(second)
        assertFalse(entry.inTextField)
        assertFalse(entry.multiline)
    }

    @Test
    fun anOldFormCannotUnregisterANewerOne() {
        val registry = ShortcutRegistry()
        var saved = 0
        val first = Any()
        val second = Any()
        registry.registerForm(first, { saved += 1 }, {})
        registry.registerForm(second, { saved += 10 }, {})
        registry.unregisterForm(first)
        assertTrue(registry.formRegistered)
        registry.save()
        assertEquals(10, saved)
        registry.unregisterForm(second)
        assertFalse(registry.formRegistered)
    }

    @Test
    fun theSaveGuardAllowsOneStartUntilReleased() {
        val guard = SaveGuard()
        assertTrue(guard.tryStart())
        assertFalse(guard.tryStart())
        guard.release()
        assertTrue(guard.tryStart())
    }
}
