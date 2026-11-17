package dev.tim.assistant.google

import dev.tim.assistant.db.Cal_event
import dev.tim.assistant.time.JERUSALEM
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.toInstant

class GoogleTodayTest {
    private fun at(y: Int, mo: Int, d: Int, h: Int = 0, mi: Int = 0): Long =
        LocalDateTime(y, mo, d, h, mi).toInstant(JERUSALEM).toEpochMilliseconds()

    private val now = at(2026, 11, 8, 12, 0)

    private fun ev(
        id: String,
        start: Long,
        end: Long = start + 3_600_000,
        allDay: Boolean = false,
        deleted: Boolean = false,
        title: String = id,
    ) = Cal_event(id, 1, if (deleted) 1 else 0, 1, "SYNCED", "c", id, title, start, end, if (allDay) 1 else 0, null)

    @Test
    fun labels_and_entities() {
        assertEquals("Sun 8 Nov", dayHeader("2026-11-08"))
        assertEquals("09:05–10:00", eventTimeLabel(ev("t", at(2026, 11, 9, 9, 5), at(2026, 11, 9, 10, 0))))
        assertEquals("All day", eventTimeLabel(ev("d", at(2026, 11, 9), at(2026, 11, 10), allDay = true)))
        assertEquals("a & b < c > \"d\" 'e'", decodeSnippet("a &amp; b &lt; c &gt; &quot;d&quot; &#39;e&#39;"))
        assertEquals("&lt;", decodeSnippet("&amp;lt;"))
    }
}
