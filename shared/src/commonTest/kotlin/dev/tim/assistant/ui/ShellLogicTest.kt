package dev.tim.assistant.ui

import dev.tim.assistant.data.SyncStatus
import dev.tim.assistant.google.hhmm
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ShellLogicTest {
    @Test
    fun everyNavTabIsInExactlyOneHub() {
        NavTab.entries.forEach { tab -> assertEquals(1, hubChips.values.count { tab in it }, tab.name) }
        assertEquals(17, NavTab.entries.size)
        assertEquals(NavTab.entries.toSet(), hubChips.values.flatten().toSet())
        assertEquals(Hub.entries.toSet(), hubChips.keys)
    }

    @Test
    fun hubOfAgreesWithHubChips() {
        hubChips.forEach { (hub, chips) -> chips.forEach { assertEquals(hub, hubOf(it)) } }
        assertEquals(Hub.Browse, hubOf(NavTab.Settings))
        assertEquals(Hub.Log, hubOf(NavTab.Dreams))
        assertEquals(Hub.Insights, hubOf(NavTab.Goals))
        assertEquals(listOf("Today", "Log", "Chat", "Insights", "Browse"), Hub.entries.map { it.label })
        assertEquals("Check-ins", NavTab.Checkins.label)
    }

    @Test
    fun hubOrderIsTodayLogChatInsightsBrowse() {
        assertEquals(listOf(Hub.Today, Hub.Log, Hub.Chat, Hub.Insights, Hub.Browse), hubOrder)
        assertEquals(Hub.entries, hubOrder)
    }

    @Test
    fun addActionMapping() {
        assertEquals(AddAction.Picker, addActionFor(NavTab.Today))
        assertEquals(AddAction.Form(FormTarget.Task(null)), addActionFor(NavTab.Tasks))
        assertEquals(AddAction.Form(FormTarget.Checkin(null)), addActionFor(NavTab.Checkins))
        assertEquals(AddAction.Form(FormTarget.Spending(null)), addActionFor(NavTab.Spending))
        assertEquals(AddAction.Form(FormTarget.Journal(null)), addActionFor(NavTab.Journal))
        assertEquals(AddAction.Form(FormTarget.Meal(null)), addActionFor(NavTab.Meals))
        assertEquals(AddAction.Form(FormTarget.Dream(null)), addActionFor(NavTab.Dreams))
        assertEquals(AddAction.NewGoal, addActionFor(NavTab.Goals))
        assertEquals(AddAction.NewDoc, addActionFor(NavTab.Files))
        assertEquals(AddAction.StudyAdd, addActionFor(NavTab.Study))
        assertEquals(AddAction.NewEmail, addActionFor(NavTab.Mail))
        listOf(NavTab.Charts, NavTab.Health, NavTab.Screentime, NavTab.Chat, NavTab.Hardware, NavTab.Settings)
            .forEach { assertNull(addActionFor(it), it.name) }
        // every tab is covered: 11 with a +, 6 without
        assertEquals(11, NavTab.entries.count { addActionFor(it) != null })
        assertEquals(17, NavTab.entries.size)
    }

    @Test
    fun pendingRequestOnlyWhenTheCounterIsAhead() {
        assertFalse(pendingRequest(0, 0))
        assertTrue(pendingRequest(1, 0))
        assertFalse(pendingRequest(1, 1))
        assertFalse(pendingRequest(2, 3))
        // entering a screen after two earlier requests starts handled at 2, so nothing replays
        assertFalse(pendingRequest(2, 2))
        assertTrue(pendingRequest(3, 2))
    }

    @Test
    fun logPickerHasTheSixLogTargets() {
        assertEquals(
            listOf("Task", "Check-in", "Spending", "Journal", "Meal", "Dream"),
            logPickerTiles.map { it.first },
        )
        assertEquals(hubChips.getValue(Hub.Log).mapNotNull { (addActionFor(it) as? AddAction.Form)?.target }.toSet(), logPickerTiles.map { it.second }.toSet())
    }
    @Test
    fun selectChipRemembersTheChipPerHub() {
        var memory = initialChips()
        assertEquals(NavTab.Charts, memory.getValue(Hub.Insights))
        memory = selectChip(memory, NavTab.Health)
        memory = selectChip(memory, NavTab.Meals)
        memory = selectChip(memory, NavTab.Today)
        assertEquals(NavTab.Health, memory.getValue(Hub.Insights))
        assertEquals(NavTab.Meals, memory.getValue(Hub.Log))
        assertEquals(NavTab.Files, memory.getValue(Hub.Browse))
        assertEquals(Hub.entries.toSet(), memory.keys)
    }

    @Test
    fun chipRowShowsOnlyForHubsWithSeveralScreens() {
        assertFalse(showsChipRow(Hub.Today))
        assertFalse(showsChipRow(Hub.Chat))
        assertTrue(showsChipRow(Hub.Log))
        assertTrue(showsChipRow(Hub.Insights))
        assertTrue(showsChipRow(Hub.Browse))
    }

    @Test
    fun layoutFollowsTheDevice() {
        assertEquals(LayoutMode.Wide, layoutModeForDevice(true))
        assertEquals(LayoutMode.Narrow, layoutModeForDevice(false))
    }

    @Test
    fun todayDrawsItsOwnHeader() {
        assertFalse(showsShellHeader(NavTab.Today))
        assertFalse(showsShellHeader(NavTab.Chat))
        assertTrue(NavTab.entries.filter { it != NavTab.Today && it != NavTab.Chat }.all { showsShellHeader(it) })
    }

    @Test
    fun phoneChatHasNoSyncBar() {
        assertFalse(showsPhoneSyncBar(NavTab.Chat, SyncStatus.WrongToken))
        assertTrue(showsPhoneSyncBar(NavTab.Files, SyncStatus.WrongToken))
        assertTrue(showsPhoneSyncBar(NavTab.Today, SyncStatus.NotConfigured))
        assertFalse(showsPhoneSyncBar(NavTab.Files, SyncStatus.Syncing))
    }

    @Test
    fun sidePanelStacksBelow900() {
        assertTrue(stacksSidePanel(899f))
        assertFalse(stacksSidePanel(900f))
        assertFalse(stacksSidePanel(901f))
    }

    @Test
    fun dockedFormFollowsTheChip() {
        // no open form: the six Log chips show their new-row form, every other chip has none
        assertEquals(FormTarget.Checkin(null), dockedFormFor(NavTab.Checkins, null))
        assertEquals(FormTarget.Dream(null), dockedFormFor(NavTab.Dreams, null))
        assertNull(dockedFormFor(NavTab.Today, null))
        assertNull(dockedFormFor(NavTab.Goals, null))
        // an open form of this chip's type replaces the new-row form (a dream read view stays on Dreams)
        val read = FormTarget.DreamRead("d1")
        assertEquals(read, dockedFormFor(NavTab.Dreams, read))
        assertEquals(FormTarget.Dream("d1"), dockedFormFor(NavTab.Dreams, FormTarget.Dream("d1")))
        // an open form of another type is not docked here
        assertEquals(FormTarget.Meal(null), dockedFormFor(NavTab.Meals, FormTarget.Task(null)))
        assertNull(dockedFormFor(NavTab.Today, FormTarget.Task(null)))
    }

    @Test
    fun pickerChoiceJumpsToItsLogChip() {
        assertEquals(NavTab.Tasks, pickerChipFor(FormTarget.Task(null)))
        assertEquals(NavTab.Checkins, pickerChipFor(FormTarget.Checkin(null)))
        assertEquals(NavTab.Dreams, pickerChipFor(FormTarget.DreamRead("x")))
        logPickerTiles.forEach { (_, target) -> assertEquals(target, dockedFormFor(pickerChipFor(target), null)) }
    }

    @Test
    fun syncIconAndErrorBar() {
        val synced = SyncStatus.Synced(1_700_000_000_000L)
        assertEquals(SyncIcon.Synced, syncIconFor(synced))
        assertEquals(SyncIcon.Syncing, syncIconFor(SyncStatus.Syncing))
        assertEquals(SyncIcon.NotSynced, syncIconFor(SyncStatus.Offline(2)))
        assertEquals(SyncIcon.NotSynced, syncIconFor(SyncStatus.WrongToken))
        assertEquals(SyncIcon.NotSynced, syncIconFor(SyncStatus.NotConfigured))
        assertTrue(showsErrorBar(SyncStatus.Offline(0)))
        assertTrue(showsErrorBar(SyncStatus.WrongToken))
        assertTrue(showsErrorBar(SyncStatus.NotConfigured))
        assertFalse(showsErrorBar(synced))
        assertFalse(showsErrorBar(SyncStatus.Syncing))
    }

    @Test
    fun sidebarSyncTextWording() {
        val at = 1_700_000_000_000L
        assertEquals("Synced ${hhmm(at)}", sidebarSyncText(SyncStatus.Synced(at)))
        assertEquals("Syncing…", sidebarSyncText(SyncStatus.Syncing))
        assertEquals("Offline — 3 waiting", sidebarSyncText(SyncStatus.Offline(3)))
        assertEquals("Wrong token — check Settings", sidebarSyncText(SyncStatus.WrongToken))
        assertEquals("Not set up — open Settings", sidebarSyncText(SyncStatus.NotConfigured))
    }

    @Test
    fun rowActionLabels() {
        assertEquals(listOf("Delete"), RowAction.entries.map { it.label })
    }
}
