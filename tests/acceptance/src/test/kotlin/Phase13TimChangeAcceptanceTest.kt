import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import dev.tim.assistant.ai.tools.ApprovalAction
import dev.tim.assistant.ai.tools.ApprovalExecutor
import dev.tim.assistant.ai.tools.ReadTools
import dev.tim.assistant.ai.tools.ToolBox
import dev.tim.assistant.ai.tools.ToolResult
import dev.tim.assistant.ai.tools.WriteHandler
import dev.tim.assistant.ai.ToolCall
import dev.tim.assistant.ai.tools.Tools
import dev.tim.assistant.ai.tools.researchQueuedText
import dev.tim.assistant.ai.tools.statesEventLength
import dev.tim.assistant.data.LocalStore
import dev.tim.assistant.data.Repository
import dev.tim.assistant.db.AssistantDb
import dev.tim.assistant.data.SyncClient
import dev.tim.assistant.ui.NavTab
import io.ktor.client.engine.mock.MockEngine
import java.io.File
import java.nio.file.Files
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

private val TC_T0: Long = Instant.parse("2026-11-10T10:00:00Z").toEpochMilli()

private fun tcText(rel: String): String {
    var d: File? = File("").absoluteFile
    while (d != null && !File(d, "settings.gradle.kts").exists()) d = d.parentFile
    return String(Files.readAllBytes(File(assertNotNull(d), rel).toPath()), Charsets.UTF_8)
}

private fun tj(vararg p: Pair<String, Any?>): JsonObject = JsonObject(
    p.associate { (k, v) ->
        k to when (v) {
            null -> JsonNull
            is JsonElement -> v
            is Boolean -> JsonPrimitive(v)
            is Number -> JsonPrimitive(v)
            else -> JsonPrimitive(v.toString())
        }
    },
)

private class TcEnv {
    var clock = TC_T0
    val db = AssistantDb(JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).also { AssistantDb.Schema.create(it) })
    val repo = Repository(LocalStore(db), now = { clock }, today = { "2026-11-10" })
    val box = ToolBox(ReadTools(repo) { clock }, WriteHandler { _, _, _ -> ToolResult(text = "stub") })
    fun tasks() = db.taskQueries.selectAll().executeAsList()
}

class Phase13TimChangeAcceptanceTest {

    @Test
    fun test_TC2_no_delete_tool_and_no_tool_touches_dream_rows() {
        val all = Tools.read + Tools.write + Tools.research
        assertTrue(all.none { it.name.contains("delete", true) || it.name.contains("remove", true) }, all.map { it.name }.toString())
        // Phase 17: the only dream tool is add_dream (server-only, AUTO); nothing reads dream rows back
        assertEquals(listOf("add_dream"), all.filter { it.name.contains("dream", true) }.map { it.name })
        assertEquals(38, all.size)
    }

    // ---------------------------------------------------------------- TC-3
    @Test
    fun test_TC3_missing_required_argument_asks_tim_and_creates_no_approval() = runBlocking<Unit> {
        val e = TcEnv()
        val r = e.box.call(ToolCall("add_meal", tj("type" to "MEAL")))
        assertContains(r.text, "Missing")
        assertContains(r.text, "ask Tim")
        val r2 = e.box.call(ToolCall("add_journal", tj()))
        assertContains(r2.text, "ask Tim")
        assertEquals(0, e.repo.approvals().size)
    }

    @Test
    fun test_TC3_statesEventLength_english_and_hebrew_positives() {
        for (t in listOf("dentist tomorrow for 30 minutes", "for 2 hours", "for an hour", "half an hour", "until 16:00", "to 4pm", "15:00-16:00", "meet at 15 for 45 min", "1 hr")) {
            assertTrue(statesEventLength(t), t)
        }
        for (t in listOf("פגישה לשעה", "שעתיים", "20 דקות", "חצי שעה", "עד 16:00")) {
            assertTrue(statesEventLength(t), t)
        }
    }

    @Test
    fun test_TC3_statesEventLength_start_only_is_negative() {
        assertFalse(statesEventLength("add a dentist event at 15:00 tomorrow"))
        assertFalse(statesEventLength("dentist tomorrow"))
        assertFalse(statesEventLength("קבע לי רופא שיניים מחר ב 15:00"))
    }

    // ---------------------------------------------------------------- TC-4
    @Test
    fun test_TC4_research_queued_text_differs_for_laptop_and_phone() {
        val laptop = researchQueuedText(true)
        val phone = researchQueuedText(false)
        assertEquals("Looking this up with Codex - the answer will appear here in about 20 seconds", laptop)
        assertContains(phone, "open on the laptop", ignoreCase = true)
    }

    // TC-5 retired in Phase 17: the client chat history (ChatEntry JSON) is gone; chats live on the HP (see Phase17AcceptanceTest).

    // ---------------------------------------------------------------- TC-6
    @Test
    fun test_TC6_nav_has_12_tabs_without_approvals_and_files_has_approvals_section() {
        assertEquals(17, NavTab.entries.size) // Phase 14: 17 NavTabs in 5 hubs
        assertTrue(NavTab.entries.none { it.label == "Approvals" })
        assertContains(tcText("shared/src/commonMain/kotlin/dev/tim/assistant/ui/files/FilesScreen.kt"), "Approvals")
    }

    @Test
    fun test_TC6_registry_is_13_read_21_write_2_research() {
        assertEquals(14, Tools.read.size) // Phase 17 adds find
        assertEquals(22, Tools.write.size) // Phase 17 adds add_dream
        assertEquals(2, Tools.research.size)
    }

    // ---------------------------------------------------------------- TC-8
    @Test
    fun test_TC8_w12_checklist_has_the_new_lines() {
        val t = tcText("ops/checklists/w12.md").lowercase()
        for (k in listOf("approvals", "clear", "meal", "spending", "check-in", "journal", "task", "study guide", "length", "calorie", "laptop")) {
            assertContains(t, k, message = "w12.md lacks '$k'")
        }
    }

    @Test
    fun test_TC8_w13_calendar_create_event_line_mentions_asking_for_the_length() {
        val t = tcText("ops/checklists/w13-calendar.md").lowercase()
        assertTrue(t.contains("length") || t.contains("how long"), "w13-calendar.md")
    }
}
