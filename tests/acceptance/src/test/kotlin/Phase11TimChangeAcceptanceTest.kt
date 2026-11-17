import dev.tim.assistant.ui.calendarStatusLine
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Phase 11 step 3 (Tim change): calendar off Today, Settings status line, docs. */
class Phase11TimChangeAcceptanceTest {
    private fun root(): File = generateSequence(File("").absoluteFile) { it.parentFile }
        .first { File(it, "settings.gradle.kts").exists() }

    private fun doc(p: String) = File(root(), p).readText()

    @Test
    fun test_AC_P11A08_calendar_status_line_texts() {
        assertEquals("Wally Calendar: connected", calendarStatusLine(true))
        assertEquals("Wally Calendar: not reachable - Wally keeps the saved events", calendarStatusLine(false))
        assertEquals("Wally Calendar: not set up", calendarStatusLine(null))
    }

    @Test
    fun test_AC_P11A07_today_reads_and_shows_no_events() {
        val t = doc("shared/src/commonMain/kotlin/dev/tim/assistant/ui/TodayScreen.kt")
        assertFalse(t.contains("calendarEvents"))
        assertFalse(t.contains("calendarOk"))
        assertFalse(t.contains("Next 7 days"))
        assertFalse(t.contains("Add as task"))
    }

    @Test
    fun test_AC_P11A09_unreachable_constant_removed() {
        assertFalse(doc("shared/src/commonMain/kotlin/dev/tim/assistant/data/DreamWeek.kt").contains("CALENDAR_UNREACHABLE_LINE"))
    }

    @Test
    fun test_AC_P11B08_w13_checklist() {
        val c = doc("ops/checklists/w13-calendar.md")
        assertTrue(c.contains("journalctl"))
        assertTrue(c.contains("Chat"))
        assertTrue(c.contains("Wally Calendar: connected") || c.contains("Wally Calendar: "))
        assertFalse(c.contains("Next 7 days"))
        assertFalse(Regex("(?i)today[^\\n]*(shows|lists)[^\\n]*events").containsMatchIn(c.replace("does not list events", "")))
    }

    @Test
    fun test_AC_P11B08_docs_have_no_stale_today_events() {
        for (p in listOf("ops/wallycal.md", "ops/checklists/phase3.md", "ops/checklists/phase4.md", "ops/checklists/phase5.md")) {
            val c = doc(p)
            assertFalse(c.contains("Next 7 days"), "$p still mentions Next 7 days")
        }
    }
}