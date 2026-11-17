package dev.tim.assistant.ui

import dev.tim.assistant.charts.chartsLabels
import dev.tim.assistant.health.healthLabels
import dev.tim.assistant.health.laptopHealthLabels
import dev.tim.assistant.ui.dream.dreamLabels
import dev.tim.assistant.ui.dream.laptopDreamLabels
import dev.tim.assistant.ui.files.filesLabels
import dev.tim.assistant.ui.files.laptopFilesLabels
import dev.tim.assistant.ui.study.laptopExamsLabels
import dev.tim.assistant.ui.study.laptopStudyLabels
import dev.tim.assistant.ui.goals.goalsLabels
import dev.tim.assistant.ui.goals.laptopGoalDetailLabels
import dev.tim.assistant.ui.goals.laptopGoalsLabels
import dev.tim.assistant.ui.hardware.hardwareLabels
import dev.tim.assistant.ui.lists.checkinsLabels
import dev.tim.assistant.ui.lists.journalLabels
import dev.tim.assistant.ui.lists.laptopTasksLabels
import dev.tim.assistant.ui.lists.mealsLabels
import dev.tim.assistant.ui.lists.spendingLabels
import dev.tim.assistant.ui.lists.tasksLabels
import dev.tim.assistant.ui.mail.laptopMailLabels
import dev.tim.assistant.ui.mail.mailLabels
import dev.tim.assistant.ui.screentime.labelAppsLabels
import dev.tim.assistant.ui.screentime.screentimeLabels
import dev.tim.assistant.ui.study.studyLabels

/** Headings and screen-level buttons of the screen behind [chip] (user data and row actions excluded). */
internal fun screenLabels(chip: NavTab): List<String> = when (chip) {
    NavTab.Today -> todayLabels
    NavTab.Checkins -> checkinsLabels
    NavTab.Spending -> spendingLabels
    NavTab.Journal -> journalLabels
    NavTab.Meals -> mealsLabels
    NavTab.Tasks -> tasksLabels
    NavTab.Dreams -> dreamLabels
    NavTab.Charts -> chartsLabels
    NavTab.Health -> healthLabels
    NavTab.Screentime -> screentimeLabels
    NavTab.Goals -> goalsLabels
    NavTab.Chat -> chatLabels
    NavTab.Files -> filesLabels
    NavTab.Study -> studyLabels
    NavTab.Mail -> mailLabels
    NavTab.Hardware -> hardwareLabels
    NavTab.Settings -> settingsLabels
}

/**
 * Every label that can be on screen at once for [hub] showing [chip]: the five hub labels, the chip labels
 * when the chip row shows, and that screen's own labels. No two of them may be the same word.
 */
fun labelsFor(hub: Hub, chip: NavTab): List<String> =
    shellLabels(hub) + screenLabels(chip)

/** The five hub labels plus the chip labels when [hub] shows a chip row. */
private fun shellLabels(hub: Hub): List<String> =
    Hub.entries.map { it.label } +
        (if (showsChipRow(hub)) hubChips.getValue(hub).map { it.label } else emptyList())

/** One screen state (history, detail, label editor, sheet, writer) and every label on screen in it. */
class LabelState(val name: String, val labels: List<String>)

private val checkinMeters = listOf("Mood", "Energy", "Sleep", "Stress")

/** Texts a month history screen adds for the Log chip [chip] (heading, empty text and its row captions). */
private fun historyLabels(chip: NavTab): List<String> {
    val base = listOf("${chip.label} history", "Nothing in November")
    return base + when (chip) {
        NavTab.Checkins -> checkinMeters
        NavTab.Spending -> listOf("Spent in the month")
        NavTab.Meals -> listOf("No estimate")
        NavTab.Tasks -> listOf("Done in November")
        else -> emptyList()
    }
}

/**
 * Every screen state added in Phase 15 beyond the hub + chip screens. History screens, the dream detail, the
 * label editor and All exams sit inside the shell, so they include the hub and chip labels. Sheets and the
 * full-screen writers cover the shell, so they list only their own texts.
 */
fun labelStates(): List<LabelState> {
    val states = mutableListOf<LabelState>()
    val logChips = hubChips.getValue(Hub.Log)
    for (chip in logChips) {
        states += LabelState("history ${chip.label}", shellLabels(Hub.Log) + historyLabels(chip))
    }
    states += LabelState(
        "dream detail",
        shellLabels(Hub.Log) + listOf("Edit", "Lucid", "Interpretation", "Interpret again"),
    )
    states += LabelState("dream detail without interpretation", shellLabels(Hub.Log) + listOf("Edit", "Lucid", "Interpretation", "Interpret"))
    states += LabelState("label apps", shellLabels(Hub.Insights) + labelAppsLabels)
    states += LabelState("all exams", shellLabels(Hub.Browse) + listOf("All exams", "Add exam", "No exams yet", "Upcoming", "Past"))
    val buttons = listOf("Cancel", "Save")
    states += LabelState("log picker", listOf("What do you want to log?", "Task", "Check-in", "Spending", "Journal", "Meal", "Dream", "Cancel"))
    states += LabelState("study add sheet", listOf("Upload file", "Add exam", "Cancel"))
    states += LabelState("exam form", listOf("New exam", "Title", "Date", "None") + buttons)
    states += LabelState("check-in form", listOf("New check-in", "Date") + checkinMeters.map { if (it == "Sleep") "Sleep quality" else it } + listOf("Note") + buttons)
    states += LabelState("spending form", listOf("New spending", "Amount", "Category", "Note", "Date") + buttons)
    states += LabelState("meal form", listOf("New meal", "Meal", "Snack", "What did you eat?", "Date", "Time", "Look up") + buttons)
    states += LabelState("task form", listOf("New task", "Title", "Due", "None", "Today", "Tomorrow", "Pick date") + buttons)
    states += LabelState("journal writer", listOf("New journal entry", "Clear", "Draft kept if you leave") + buttons)
    states += LabelState(
        "dream writer",
        listOf("New dream", "Last night", "What did you dream?", "Mood on waking", "Lucid dream", "Clear", "Draft kept if you leave") + buttons,
    )
    // Laptop panes (Phase 16): the rail's hub labels, the list and the side form are separate panes, so a contextual
    // word may repeat across panes (the hub "Today" and the task group "Today"); inside one pane it may not.
    val keys = listOf("Esc", "Enter")
    states += LabelState("laptop today", todayLabels + "New")
    states += LabelState("laptop tasks list", logChips.map { it.label } + laptopTasksLabels + "New")
    states += LabelState(
        "laptop check-in side form",
        listOf("New check-in", "Date") + checkinMeters.map { if (it == "Sleep") "Sleep quality" else it } + listOf("Note", "Cancel", "Save check-in") + keys,
    )
    states += LabelState("laptop meal side form", listOf("New meal", "Meal", "Snack", "What did you eat?", "Date", "Time", "Look up", "Cancel", "Save meal") + keys)
    states += LabelState("laptop task side form", listOf("New task", "Title", "Due", "None", "Today", "Tomorrow", "Pick date", "Cancel", "Save task") + keys)
    states += LabelState("laptop dreams list", logChips.map { it.label } + laptopDreamLabels + "New")
    states += LabelState("laptop journal writer", listOf("New entry", "Draft kept if you leave", "Cancel", "Save", "Ctrl+Enter", "Esc"))
    states += LabelState(
        "laptop dream writer",
        listOf("New dream", "Last night", "What did you dream?", "Mood on waking", "Lucid dream", "Draft kept if you leave", "Clear", "Save & interpret", "Save", "Ctrl+Enter"),
    )
    states += LabelState(
        "laptop dream read pane",
        listOf("Edit", "Lucid", "Interpretation", "Interpret", "Interpret again"),
    )
    // Laptop Insights (Phase 16): Screentime and Goals have a list pane and a side panel; a word may repeat across panes.
    val insightsShell = shellLabels(Hub.Insights)
    states += LabelState("laptop charts", insightsShell + chartsLabels)
    states += LabelState("laptop health", insightsShell + laptopHealthLabels)
    states += LabelState("laptop screentime list", insightsShell + screentimeLabels.filter { it != "Label apps" })
    states += LabelState("laptop screentime label panel", labelAppsLabels)
    states += LabelState("laptop goals list", insightsShell + laptopGoalsLabels)
    states += LabelState("laptop goal detail", laptopGoalDetailLabels)
    // Laptop Browse (Phase 16): Files and Study each have the chip row plus their own columns.
    val browseShell = shellLabels(Hub.Browse)
    states += LabelState("laptop files", browseShell + laptopFilesLabels)
    states += LabelState("laptop study", browseShell + laptopStudyLabels)
    states += LabelState("laptop all exams pane", laptopExamsLabels)
    states += LabelState("laptop mail", browseShell + laptopMailLabels)
    // Chat (Phase 17): the phone list drawer and the Memory screen cover the shell; the laptop has list, conversation and Memory panes.
    states += LabelState("chat list drawer", chatListLabels)
    states += LabelState("chat memory", chatMemoryLabels)
    // Phone Settings (Phase 19): the Phone brain card is on the phone's Settings screen only.
    states += LabelState("phone settings", shellLabels(Hub.Browse) + settingsLabels + phoneBrainLabels)
    states += LabelState("laptop chat", laptopChatLabels)
    states += LabelState("laptop chat list hidden", laptopChatHiddenLabels)
    states += LabelState("laptop chat memory pane", chatMemoryLabels)
    return states
}
