package dev.tim.assistant.desktop

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

class LaptopTrackerTest {
    private val base = Instant.parse("2026-11-05T08:00:00Z").toEpochMilliseconds()
    private fun at(s: Long) = base + s * 1000
    private fun sample(s: Long, exe: String = "code.exe", title: String = "main", idleS: Long = 0) =
        Sample(exe, title, idleS * 1000, at(s))

    @Test
    fun the_same_program_and_title_extend_one_session() {
        val t = LaptopTracker()
        var closed = emptyList<LaptopSession>()
        for (s in 0..60 step 5) closed = closed + t.onSample(sample(s.toLong()))
        assertTrue(closed.isEmpty())
        val open = assertNotNull(t.open())
        assertEquals(at(0), open.startAt)
        assertEquals(at(60), open.endAt)
        assertEquals("use:laptop:${at(0)}", open.id)
        assertEquals("2026-11-05", open.localDate)
    }

    @Test
    fun a_title_change_splits_the_session() {
        val t = LaptopTracker()
        t.onSample(sample(0)); t.onSample(sample(5)); t.onSample(sample(10))
        val closed = t.onSample(sample(15, title = "other"))
        assertEquals(1, closed.size)
        assertEquals(at(0), closed[0].startAt)
        assertEquals(at(15), closed[0].endAt)
        assertEquals("other", t.open()?.title)
    }

    @Test
    fun a_program_change_splits_the_session_and_the_name_is_lowercase() {
        val t = LaptopTracker()
        t.onSample(sample(0, exe = "Chrome.EXE")); t.onSample(sample(10, exe = "Chrome.EXE"))
        val closed = t.onSample(sample(20, exe = "slack.exe"))
        assertEquals("chrome.exe", closed.single().exe)
    }

    @Test
    fun idle_closes_the_session_at_the_last_input() {
        val t = LaptopTracker()
        for (s in 0..300 step 5) t.onSample(sample(s.toLong()))
        // idle began at second 300; the 2-minute mark is seen at second 420
        for (s in 305..415 step 5) assertTrue(t.onSample(sample(s.toLong(), idleS = s - 300L)).isEmpty())
        val closed = t.onSample(sample(420, idleS = 120))
        assertEquals(1, closed.size)
        assertEquals(at(300), closed[0].endAt)
        assertNull(t.open())
    }

    @Test
    fun nothing_is_opened_while_idle_and_a_new_session_starts_after_input() {
        val t = LaptopTracker()
        t.onSample(sample(0)); t.onSample(sample(10))
        t.onSample(sample(200, idleS = 190))
        assertNull(t.open())
        assertTrue(t.onSample(sample(205, idleS = 195)).isEmpty())
        t.onSample(sample(210, idleS = 0))
        assertEquals(at(210), t.open()?.startAt)
    }

    @Test
    fun a_gap_over_thirty_seconds_closes_at_the_previous_sample() {
        val t = LaptopTracker()
        t.onSample(sample(0)); t.onSample(sample(20))
        val closed = t.onSample(sample(100))
        assertEquals(1, closed.size)
        assertEquals(at(20), closed[0].endAt)
        assertEquals(at(100), t.open()?.startAt)
    }

    @Test
    fun a_gap_of_exactly_thirty_seconds_does_not_split() {
        val t = LaptopTracker()
        t.onSample(sample(0)); t.onSample(sample(30))
        assertEquals(at(0), t.open()?.startAt)
        assertEquals(at(30), t.open()?.endAt)
    }

    @Test
    fun sessions_under_five_seconds_are_dropped() {
        val t = LaptopTracker()
        t.onSample(sample(0, title = "a"))
        assertTrue(t.onSample(sample(4, title = "b")).isEmpty())
        val closed = t.onSample(sample(20, title = "c"))
        assertEquals(listOf("b"), closed.map { it.title })
        assertEquals(emptyList(), LaptopTracker().also { it.onSample(sample(0)); it.onSample(sample(3)) }.flush())
    }

    @Test
    fun flush_closes_the_open_session_at_its_last_reading() {
        val t = LaptopTracker()
        t.onSample(sample(0)); t.onSample(sample(25))
        val closed = t.flush()
        assertEquals(1, closed.size)
        assertEquals(at(25), closed[0].endAt)
        assertNull(t.open())
        assertTrue(t.flush().isEmpty())
    }

    @Test
    fun an_unavailable_reading_closes_at_the_last_valid_sample() {
        val t = LaptopTracker()
        t.onSample(sample(0)); t.onSample(sample(10))
        val closed = t.onSample(Sample("unknown", "", 0, at(15)))
        assertEquals(at(10), closed.single().endAt)
        assertNull(t.open())
    }

    @Test
    fun a_long_title_is_compared_in_full_and_stored_at_200_characters() {
        val base200 = "x".repeat(200)
        val t = LaptopTracker()
        t.onSample(sample(0, title = base200 + "A"))
        // same first 200 characters but a different tail: still a different title
        val closed = t.onSample(sample(10, title = base200 + "B"))
        assertEquals(1, closed.size)
        assertEquals(200, closed[0].title?.length)
        assertEquals(200, t.open()?.title?.length)
        // identical long titles stay one session
        t.onSample(sample(20, title = base200 + "B"))
        assertEquals(at(10), t.open()?.startAt)
    }

    @Test
    fun a_blank_title_is_stored_as_null() {
        val t = LaptopTracker()
        t.onSample(sample(0, title = "  "))
        assertNull(t.open()?.title)
    }
}
