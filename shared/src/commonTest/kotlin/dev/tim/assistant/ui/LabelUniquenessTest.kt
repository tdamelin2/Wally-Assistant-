package dev.tim.assistant.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LabelUniquenessTest {
    @Test
    fun no_label_repeats_on_any_hub_and_chip_screen() {
        var screens = 0
        for ((hub, chips) in hubChips) {
            for (chip in chips) {
                screens++
                val labels = labelsFor(hub, chip).map { it.trim().lowercase() }
                val dupes = labels.groupBy { it }.filterValues { it.size > 1 }.keys
                assertTrue(dupes.isEmpty(), "$hub/$chip repeats: $dupes")
            }
        }
        assertEquals(NavTab.entries.size, screens)
    }

    @Test
    fun no_label_repeats_in_any_history_detail_sheet_or_writer_state() {
        val states = labelStates()
        assertTrue(states.size >= 19)
        for (state in states) {
            val labels = state.labels.map { it.trim().lowercase() }
            val dupes = labels.groupBy { it }.filterValues { it.size > 1 }.keys
            assertTrue(dupes.isEmpty(), "${state.name} repeats: $dupes")
        }
    }

    @Test
    fun hub_labels_are_always_included_and_chip_labels_only_with_a_chip_row() {
        val log = labelsFor(Hub.Log, NavTab.Journal)
        assertTrue(Hub.entries.all { it.label in log })
        assertTrue("Check-ins" in log)
        val chat = labelsFor(Hub.Chat, NavTab.Chat)
        assertTrue(chat.count { it == "Chat" } == 1)
    }

    @Test
    fun phone_chat_screen_registers_its_header_and_composer_labels() {
        val chat = labelsFor(Hub.Chat, NavTab.Chat)
        assertTrue("Open chat list" in chat)
        assertTrue("New chat" in chat)
        assertTrue("Message Wally…" in chat)
        assertTrue("Chats" !in chat)
    }

    @Test
    fun phone_settings_state_registers_the_phone_brain_card_but_the_laptop_screen_does_not() {
        val phone = labelStates().first { it.name == "phone settings" }.labels
        assertTrue(listOf("Phone brain", "Test phone brain", "Answer on this phone when the laptop is off").all { it in phone })
        assertTrue("Phone brain" !in labelsFor(Hub.Browse, NavTab.Settings))
    }

    @Test
    fun the_ask_buttons_are_registered_for_the_phone_and_both_laptop_states() {
        assertTrue(listOf("Ask model", "Ask Codex").all { it in labelsFor(Hub.Chat, NavTab.Chat) })
        for (name in listOf("laptop chat", "laptop chat list hidden")) {
            val labels = labelStates().first { it.name == name }.labels
            assertTrue(listOf("Ask model", "Ask Codex").all { it in labels }, name)
        }
    }

    @Test
    fun phone_chat_drawer_is_its_own_state_without_hub_labels() {
        val drawer = labelStates().first { it.name == "chat list drawer" }.labels
        assertTrue(listOf("Chats", "Pinned", "New chat", "Memory").all { it in drawer })
        assertTrue("Hide chats" !in drawer)
        assertTrue(Hub.entries.none { it.label in drawer })
    }
}
