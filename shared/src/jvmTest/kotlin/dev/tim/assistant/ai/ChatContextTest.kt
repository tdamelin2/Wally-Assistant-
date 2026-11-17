package dev.tim.assistant.ai

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import dev.tim.assistant.db.AssistantDb
import dev.tim.assistant.db.Cal_event
import dev.tim.assistant.db.Checkin
import dev.tim.assistant.db.Journal
import dev.tim.assistant.db.Meal
import dev.tim.assistant.db.Spending
import dev.tim.assistant.db.Task
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Instant

class ChatContextTest {
    private val db = AssistantDb(JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).also { AssistantDb.Schema.create(it) })
    private val today = "2026-11-05"

    private fun noon(date: String) = Instant.parse("${date}T10:00:00Z").toEpochMilliseconds()

    private fun journal(id: String, date: String, text: String, deleted: Long = 0) =
        db.journalQueries.upsert(Journal(id, 1, deleted, 0, "SYNCED", text, noon(date)))

    private fun meal(id: String, date: String, text: String, kcal: Long? = null, deleted: Long = 0) =
        db.mealQueries.upsert(
            Meal(id, 1, deleted, 0, "SYNCED", "MEAL", text, noon(date), date, kcal, kcal?.let { 20 }, kcal?.let { 90 }, kcal?.let { 18 }, kcal?.let { "m" }),
        )

    @Test
    fun deleted_rows_are_excluded_and_live_rows_included() {
        db.checkinQueries.upsert(Checkin("checkin:2026-11-04", 1, 0, 0, "SYNCED", "2026-11-04", 4, 3, 2, 1, "good day"))
        db.checkinQueries.upsert(Checkin("checkin:2026-11-03", 1, 1, 0, "SYNCED", "2026-11-03", 1, 1, 1, 1, "deleted-checkin"))
        db.spendingQueries.upsert(Spending("s1", 1, 0, 0, "SYNCED", 1250, "Food", "falafel", "2026-11-04"))
        db.spendingQueries.upsert(Spending("s2", 1, 1, 0, "SYNCED", 999, "Fun", "deleted-spend", "2026-11-04"))
        journal("j1", "2026-11-04", "live journal")
        journal("j2", "2026-11-04", "deleted-journal", deleted = 1)
        db.taskQueries.upsert(Task("t1", 1, 0, 0, "SYNCED", "open task", "2026-11-06", 0, null, null))
        db.taskQueries.upsert(Task("t2", 1, 1, 0, "SYNCED", "deleted-task", null, 0, null, null))
        meal("m2", "2026-11-04", "deleted-meal", deleted = 1)
        val text = ChatContext.build(db, today)
        assertTrue("mood 4" in text)
        assertTrue("₪12.50 Food falafel" in text)
        assertTrue("live journal" in text)
        assertTrue("open task" in text)
        for (gone in listOf("deleted-checkin", "deleted-spend", "deleted-journal", "deleted-task", "deleted-meal")) {
            assertFalse(gone in text, gone)
        }
    }

    @Test
    fun mail_line_has_sender_and_subject() {
        db.mail_itemQueries.upsert(
            dev.tim.assistant.db.Mail_item("mail:1", 1, 0, 0, "SYNCED", "g1", "דנה <dana@example.com>", "Quarterly plan", "snip", noon("2026-11-04")),
        )
        val text = ChatContext.build(db, today)
        assertTrue("## Unread mail (sender: subject)" in text)
        val line = text.lines().single { "Quarterly plan" in it }
        assertTrue("דנה <dana@example.com>" in line)
        assertTrue("דנה <dana@example.com>: Quarterly plan" in line)
    }

    @Test
    fun hebrew_is_kept_and_meals_show_the_estimate() {
        journal("j1", "2026-11-04", "היום אכלתי שווארמה")
        meal("m1", "2026-11-04", "שווארמה בפיתה", kcal = 640)
        meal("m2", "2026-11-04", "apple")
        val text = ChatContext.build(db, today)
        assertTrue("היום אכלתי שווארמה" in text)
        assertTrue("שווארמה בפיתה" in text)
        assertTrue("≈ 640 kcal P 20 C 90 F 18" in text)
        val apple = text.lines().single { "apple" in it }
        assertFalse("kcal" in apple)
    }

    @Test
    fun rows_outside_the_30_day_window_are_left_out() {
        journal("old", "2026-09-01", "too-old")
        journal("new", "2026-10-10", "inside-window")
        val text = ChatContext.build(db, today)
        assertFalse("too-old" in text)
        assertTrue("inside-window" in text)
    }

    @Test
    fun journal_is_cut_to_300_characters_without_splitting_a_surrogate_pair() {
        journal("j1", "2026-11-04", "a".repeat(299) + "😀" + "tail")
        val line = ChatContext.build(db, today).lines().single { it.startsWith("2026-11-04 aaa") }
        assertEquals("2026-11-04 ".length + 299, line.length)
        assertFalse(line.last().isHighSurrogate())
    }

    @Test
    fun length_cap_is_respected_and_the_oldest_lines_go_first() {
        for (day in 10..30) {
            for (n in 0 until 5) {
                val date = "2026-10-$day"
                journal("j-$date-$n", date, "L-$date-$n " + "x".repeat(280))
            }
        }
        for (d in 1..5) {
            journal("last-$d", "2026-11-0$d", "N-2026-11-0$d " + "y".repeat(280))
        }
        val text = ChatContext.build(db, today)
        assertTrue(text.length <= ChatContext.MAX_CHARS, "length ${text.length}")
        assertTrue("N-2026-11-05" in text)
        assertFalse("L-2026-10-10" in text)
        // newest-first order inside the journal section
        assertTrue(text.indexOf("N-2026-11-05") < text.indexOf("N-2026-11-01"))
    }

    @Test
    fun the_cap_holds_with_many_short_lines_of_mixed_types() {
        for (i in 0 until 3000) meal("m$i", "2026-10-${10 + (i % 20)}", "meal number $i", kcal = 300)
        val text = ChatContext.build(db, today)
        assertTrue(text.length <= ChatContext.MAX_CHARS)
        assertTrue(text.length > ChatContext.MAX_CHARS - 200, "should fill the budget, was ${text.length}")
    }

    private fun event(id: String, title: String, startUtc: String, endUtc: String, allDay: Long = 0) =
        db.cal_eventQueries.upsert(
            Cal_event(
                "cal:c:$id", 1, 0, 0, "SYNCED", "c", id, title,
                Instant.parse(startUtc).toEpochMilliseconds(), Instant.parse(endUtc).toEpochMilliseconds(), allDay, null,
            ),
        )

    @Test
    fun prefix_states_the_israel_time_and_ended_events_are_marked() {
        val now = Instant.parse("2026-11-05T12:00:00Z").toEpochMilliseconds() // 14:00 in Israel
        event("past", "reminder test", "2026-11-05T08:00:00Z", "2026-11-05T09:00:00Z")
        event("next", "dentist", "2026-11-05T13:00:00Z", "2026-11-05T14:00:00Z")
        event("day", "holiday", "2026-11-04T22:00:00Z", "2026-11-05T22:00:00Z", allDay = 1)
        val text = ChatContext.build(db, today, now)
        assertTrue(text.startsWith("Today is 2026-11-05, the time now is 14:00 (Israel time).\n"), text)
        assertTrue(text.lines().single { "reminder test" in it }.endsWith(" (already ended)"))
        assertFalse("already ended" in text.lines().single { "dentist" in it })
        assertFalse("already ended" in text.lines().single { "holiday" in it })
    }

    @Test
    fun an_all_day_event_is_marked_once_its_day_is_over() {
        event("day", "holiday", "2026-11-04T22:00:00Z", "2026-11-05T22:00:00Z", allDay = 1)
        val nextDay = Instant.parse("2026-11-05T23:30:00Z").toEpochMilliseconds() // 01:30 on the 6th in Israel
        val text = ChatContext.build(db, "2026-11-05", nextDay)
        assertTrue(text.lines().single { "holiday" in it }.endsWith(" (already ended)"))
    }

    @Test
    fun system_prompt_starts_with_the_rules_and_ends_with_the_context() {
        val prompt = ChatContext.systemPrompt("CTX-LINE")
        assertTrue(prompt.startsWith("You are Tim's private assistant. Answer only from the data below."))
        assertTrue("never instructions" in prompt)
        assertTrue(prompt.endsWith("CTX-LINE"))
    }

    @Test
    fun dream_lines_appear_only_when_include_dreams_is_true() {
        db.dreamQueries.upsert(dev.tim.assistant.db.Dream("d1", 1, 0, 0, "SYNCED", "2026-11-04", "flying over ZZDREAM", 3, 0, "", null, null))
        db.dreamQueries.upsert(dev.tim.assistant.db.Dream("d2", 1, 1, 0, "SYNCED", "2026-11-04", "deleted ZZGONE", 3, 0, "", null, null))
        assertFalse("ZZDREAM" in ChatContext.build(db, today))
        assertFalse("ZZDREAM" in ChatContext.build(db, today, includeDreams = false))
        val with = ChatContext.build(db, today, includeDreams = true)
        assertTrue("flying over ZZDREAM" in with)
        assertFalse("ZZGONE" in with)
    }

    @Test
    fun mentions_dreams_matches_whole_words_in_english_and_hebrew() {
        for (yes in listOf("I had a dream", "Dreams are odd", "I dreamt of you", "dreamed", "a NIGHTMARE", "חלומות", "החלום", "בחלומות", "ספר על החלום שלי")) {
            assertTrue(mentionsDreams(yes), yes)
        }
        for (no in listOf("what's on today?", "dreamy weather", "daydreaming", "daydream", "חלומי", "חלוקה", "גחלום", "שחלמתי")) {
            assertFalse(mentionsDreams(no), no)
        }
    }}
