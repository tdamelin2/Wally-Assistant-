package dev.tim.assistant.usage

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import dev.tim.assistant.data.LocalStore
import dev.tim.assistant.data.Repository
import dev.tim.assistant.db.App_rule
import dev.tim.assistant.db.AssistantDb
import dev.tim.assistant.db.Usage_session
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

class LabelsTest {
    private fun session(
        id: String, app: String, ms: Long, device: String = "LAPTOP", title: String? = null,
        date: String = "2026-11-05", start: Long = 0, deleted: Long = 0,
    ) = Usage_session(id, 1, deleted, 0, "SYNCED", device, app, title, start, start + ms, date)

    private fun rule(type: String, pattern: String, label: String, deleted: Long = 0) =
        App_rule(ruleId(type, pattern), 1, deleted, 0, "SYNCED", type, pattern, label)

    @Test
    fun an_unmatched_session_is_unsorted() {
        assertEquals(Label.UNSORTED, label(session("a", "code.exe", 1000), emptyList()))
    }

    @Test
    fun an_exe_rule_labels_laptop_sessions_and_a_package_rule_labels_phone_sessions() {
        val rules = listOf(rule("EXE", "code.exe", "PRODUCTIVE"), rule("PACKAGE", "com.app", "UNPRODUCTIVE"))
        assertEquals(Label.PRODUCTIVE, label(session("a", "code.exe", 1), rules))
        assertEquals(Label.UNPRODUCTIVE, label(session("b", "com.app", 1, device = "PHONE"), rules))
        // the phone does not use EXE rules and the laptop does not use PACKAGE rules
        assertEquals(Label.UNSORTED, label(session("c", "code.exe", 1, device = "PHONE"), rules))
        assertEquals(Label.UNSORTED, label(session("d", "com.app", 1), rules))
    }

    @Test
    fun a_title_rule_beats_an_app_rule() {
        val rules = listOf(rule("EXE", "chrome.exe", "NEUTRAL"), rule("TITLE_CONTAINS", "youtube", "UNPRODUCTIVE"))
        assertEquals(Label.UNPRODUCTIVE, label(session("a", "chrome.exe", 1, title = "Cats - YouTube"), rules))
        assertEquals(Label.NEUTRAL, label(session("b", "chrome.exe", 1, title = "Docs"), rules))
    }

    @Test
    fun the_longest_title_pattern_wins_and_a_tie_goes_to_the_lowest_rule_id() {
        val rules = listOf(
            rule("TITLE_CONTAINS", "you", "PRODUCTIVE"),
            rule("TITLE_CONTAINS", "youtube", "UNPRODUCTIVE"),
        )
        assertEquals(Label.UNPRODUCTIVE, label(session("a", "x.exe", 1, title = "YouTube"), rules))
        val tie = listOf(rule("TITLE_CONTAINS", "bbb", "NEUTRAL"), rule("TITLE_CONTAINS", "aaa", "PRODUCTIVE"))
        assertEquals(Label.PRODUCTIVE, label(session("b", "x.exe", 1, title = "aaa bbb"), tie))
    }

    @Test
    fun a_null_device_sums_every_device_and_floors_the_total_once() {
        val rules = listOf(rule("EXE", "x.exe", "PRODUCTIVE"), rule("PACKAGE", "x.exe", "PRODUCTIVE"))
        val sessions = listOf(
            session("a", "x.exe", 30_000, device = "LAPTOP"),
            session("b", "x.exe", 30_000, device = "PHONE"),
        )
        val dates = listOf("2026-11-05")
        val all = dayTotals(sessions, rules, null, dates).getValue("2026-11-05")
        val laptop = dayTotals(sessions, rules, "LAPTOP", dates).getValue("2026-11-05")
        val phone = dayTotals(sessions, rules, "PHONE", dates).getValue("2026-11-05")
        assertEquals(1L, all.getValue(Label.PRODUCTIVE))
        assertEquals(0L, laptop.getValue(Label.PRODUCTIVE))
        assertEquals(0L, phone.getValue(Label.PRODUCTIVE))
        assertEquals(60_000L, topApps(sessions, null, dates).single().ms)
        assertEquals(emptyList(), unsortedApps(sessions, rules, null, dates))
    }

    @Test
    fun a_null_device_unsorted_list_covers_both_devices() {
        val sessions = listOf(session("a", "a.exe", 10_000, device = "LAPTOP"), session("b", "b.app", 20_000, device = "PHONE"))
        val dates = listOf("2026-11-05")
        assertEquals(listOf("b.app", "a.exe"), unsortedApps(sessions, emptyList(), null, dates).map { it.app })
        assertEquals(listOf("a.exe"), unsortedApps(sessions, emptyList(), "LAPTOP", dates).map { it.app })
    }

    @Test
    fun a_deleted_rule_is_ignored() {
        val rules = listOf(rule("EXE", "code.exe", "PRODUCTIVE", deleted = 1))
        assertEquals(Label.UNSORTED, label(session("a", "code.exe", 1), rules))
    }

    @Test
    fun totals_are_the_floor_of_the_summed_milliseconds_not_of_each_session() {
        val rules = listOf(rule("EXE", "code.exe", "PRODUCTIVE"))
        val sessions = listOf(session("a", "code.exe", 40_000), session("b", "code.exe", 40_000), session("c", "other.exe", 59_999))
        val totals = dayTotals(sessions, rules, "LAPTOP", listOf("2026-11-05"))
        assertEquals(1L, totals.getValue("2026-11-05").getValue(Label.PRODUCTIVE))
        assertEquals(0L, totals.getValue("2026-11-05").getValue(Label.UNSORTED))
    }

    @Test
    fun a_session_across_midnight_counts_whole_on_its_start_date() {
        val rules = listOf(rule("EXE", "code.exe", "PRODUCTIVE"))
        val s = session("a", "code.exe", 2 * 60 * 60_000L, date = "2026-11-05")
        val totals = dayTotals(listOf(s), rules, "LAPTOP", listOf("2026-11-05", "2026-11-06"))
        assertEquals(120L, totals.getValue("2026-11-05").getValue(Label.PRODUCTIVE))
        assertEquals(0L, totals.getValue("2026-11-06").getValue(Label.PRODUCTIVE))
    }

    @Test
    fun totals_skip_other_devices_other_dates_and_deleted_sessions() {
        val sessions = listOf(
            session("a", "x", 60_000), session("b", "x", 60_000, device = "PHONE"),
            session("c", "x", 60_000, date = "2026-10-01"), session("d", "x", 60_000, deleted = 1),
        )
        val totals = dayTotals(sessions, emptyList(), "LAPTOP", listOf("2026-11-05"))
        assertEquals(1L, totals.getValue("2026-11-05").getValue(Label.UNSORTED))
    }

    @Test
    fun top_apps_are_ordered_by_time_then_name_and_limited_to_ten() {
        val sessions = (1..12).map { session("s$it", "app%02d".format(it), it * 1000L) } +
            session("tie", "zzz", 12_000L)
        val top = topApps(sessions, "LAPTOP", listOf("2026-11-05"))
        assertEquals(10, top.size)
        assertEquals(listOf("app12", "zzz", "app11"), top.take(3).map { it.app })
        assertEquals(12_000L, top[0].ms)
    }

    @Test
    fun unsorted_apps_list_only_apps_with_unlabelled_time() {
        val rules = listOf(rule("EXE", "code.exe", "PRODUCTIVE"))
        val sessions = listOf(session("a", "code.exe", 5000), session("b", "new.exe", 7000), session("c", "new.exe", 1000))
        val unsorted = unsortedApps(sessions, rules, "LAPTOP", listOf("2026-11-05"))
        assertEquals(listOf(AppTotal("new.exe", 8000)), unsorted)
    }

    @Test
    fun last7_dates_are_today_and_the_six_days_before() {
        val dates = last7Dates("2026-03-02")
        assertEquals(7, dates.size)
        assertEquals("2026-03-02", dates.first())
        assertEquals("2026-02-24", dates.last())
    }

    // ---- Repository rules ----------------------------------------------------------------------

    private val db = AssistantDb(JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).also { AssistantDb.Schema.create(it) })
    private var clock = 1_000L
    private val repo = Repository(LocalStore(db), now = { clock })

    @Test
    fun saving_a_rule_normalizes_the_pattern_and_a_change_replaces_the_label() = runBlocking<Unit> {
        val id = repo.saveRule("EXE", "  Code.EXE ", Label.PRODUCTIVE)
        assertEquals("rule:EXE:code.exe", id)
        clock += 10
        repo.saveRule("EXE", "code.exe", Label.NEUTRAL)
        val rules = repo.appRules()
        assertEquals(1, rules.size)
        assertEquals("NEUTRAL", rules[0].label)
        assertEquals("PENDING", rules[0].syncState)
        assertNull(repo.saveRule("EXE", "   ", Label.NEUTRAL))
        assertNull(repo.saveRule("EXE", "x", Label.UNSORTED))
        assertNull(repo.saveRule("BOGUS", "x", Label.NEUTRAL))
    }

    @Test
    fun deleting_a_rule_hides_it_and_accept_is_a_no_op_when_a_rule_exists() = runBlocking<Unit> {
        assertTrue(repo.acceptSuggestion("LAPTOP", "Slack.exe", Label.PRODUCTIVE))
        assertFalse(repo.acceptSuggestion("LAPTOP", "slack.exe", Label.UNPRODUCTIVE))
        assertEquals("PRODUCTIVE", repo.appRules().single().label)
        assertEquals("EXE", repo.appRules().single().matchType)
        repo.deleteRule("rule:EXE:slack.exe")
        assertTrue(repo.appRules().isEmpty())
        assertTrue(repo.acceptSuggestion("PHONE", "com.app", Label.NEUTRAL))
        assertEquals("PACKAGE", repo.appRules().single().matchType)
    }
}
