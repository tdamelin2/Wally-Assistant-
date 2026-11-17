import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import dev.tim.assistant.db.AssistantDb
import dev.tim.assistant.model.PushRequest
import dev.tim.assistant.model.SyncRow
import dev.tim.assistant.model.toSyncRow
import dev.tim.assistant.model.upsertFromSyncRow
import dev.tim.assistant.time.localDateOf
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Instant

class Phase1AcceptanceTest {
    private val root: File = File(System.getProperty("user.dir")).absoluteFile.parentFile.parentFile
    private fun text(path: String) = File(root, path).readText()

    private fun catalogValue(key: String): String {
        val m = Regex("""(?m)^\s*${Regex.escape(key)}\s*=\s*"([^"]+)"""").find(text("gradle/libs.versions.toml"))
        return m?.groupValues?.get(1) ?: error("catalog key $key missing")
    }

    @Test fun test_AC2_catalog_pins() {
        val toml = text("gradle/libs.versions.toml")
        assertTrue(Regex("""(?m)^\s*kotlin\s*=\s*"2\.4\.21"""").containsMatchIn(toml), "kotlin")
        assertTrue(Regex("""(?m)^\s*agp\s*=\s*"9\.3\.1"""").containsMatchIn(toml), "agp")
        assertEquals("1.12.2", Regex("""(?m)^\s*compose-?[mM]ultiplatform\s*=\s*"([^"]+)"""").find(toml)?.groupValues?.get(1))
        assertEquals("3.6.1", catalogValue("ktor"))
        assertEquals("2.4.1", catalogValue("sqldelight"))
        assertEquals("1.12.0", catalogValue("kotlinx-serialization"))
        assertEquals("0.8.0", catalogValue("kotlinx-datetime"))
    }

    @Test fun test_AC2_wrapper_gradle_970() {
        val props = text("gradle/wrapper/gradle-wrapper.properties")
        assertTrue(Regex("""distributionUrl=.*gradle-9\.7\.0-bin\.zip""").containsMatchIn(props), props)
    }

    @Test fun test_AC3_settings_exact_modules() {
        val includes = Regex("""include\(\s*"([^"]+)"\s*\)""").findAll(text("settings.gradle.kts"))
            .map { it.groupValues[1] }.toList()
        assertEquals(setOf(":shared", ":androidApp", ":desktopApp", ":server", ":acceptance"), includes.toSet())
        assertEquals(5, includes.size)
    }

    @Test fun test_AC3_wizard_removed() {
        assertFalse(File(root, "_wizard").exists())
    }

    @Test fun test_AC6_gitattributes_lf() {
        val ga = text(".gitattributes")
        for (ext in listOf("sh", "service", "timer", "json")) {
            assertTrue(Regex("""(?m)^\*\.$ext\s+.*eol=lf""").containsMatchIn(ga), "gitattributes $ext")
        }
    }

    @Test fun test_AC6_editorconfig_lf() {
        val lines = text(".editorconfig").lines().map { it.trim() }
        for (ext in listOf("sh", "service", "timer", "json")) {
            val idx = lines.indexOfFirst { it.startsWith("[") && Regex("""[\{,*.]$ext[,\}\]]""").containsMatchIn(it) }
            assertTrue(idx >= 0, "section for $ext")
            val body = lines.drop(idx + 1).takeWhile { !it.startsWith("[") }
            assertTrue(body.any { it.replace(" ", "") == "end_of_line=lf" }, "end_of_line for $ext")
        }
    }

    private fun d(iso: String) = localDateOf(Instant.parse(iso))

    @Test fun test_AC4_days_dst_start() {
        // 2026-03-27 01:30 local (+02:00) = 2026-03-26T23:30Z
        assertEquals("2026-03-27", d("2026-03-26T23:30:00Z"))
    }

    @Test fun test_AC4_days_dst_end_both_0130() {
        assertEquals("2026-10-25", d("2026-10-24T22:30:00Z")) // 01:30 +03:00
        assertEquals("2026-10-25", d("2026-10-24T23:30:00Z")) // 01:30 +02:00
    }

    @Test fun test_AC4_days_midnight_boundary() {
        assertEquals("2026-01-15", d("2026-01-15T21:59:59Z")) // 23:59:59 +02:00
        assertEquals("2026-01-16", d("2026-01-15T22:00:00Z")) // 00:00:00
        assertEquals("2026-06-15", d("2026-06-15T20:59:59Z")) // 23:59:59 +03:00
        assertEquals("2026-06-16", d("2026-06-15T21:00:00Z")) // 00:00:00
    }

    @Test fun test_AC4_syncrow_hebrew_emoji_roundtrip() {
        val row = SyncRow("journal", "j1", 1L, false, JsonObject(mapOf("text" to JsonPrimitive("שלום 👋"))))
        val json = Json
        val back = json.decodeFromString(PushRequest.serializer(), json.encodeToString(PushRequest.serializer(), PushRequest(listOf(row))))
        assertEquals(listOf(row), back.rows)
        assertEquals(JsonPrimitive("שלום 👋"), back.rows[0].data["text"])
    }

    @Test fun test_AC4_rowmapping_task_nullable_roundtrip() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        AssistantDb.Schema.create(driver)
        val db = AssistantDb(driver)
        val data = JsonObject(mapOf(
            "title" to JsonPrimitive("קנה חלב 👋"),
            "dueDate" to JsonNull,
            "done" to JsonPrimitive(false),
            "doneAt" to JsonNull,
            "goalId" to JsonNull,
        ))
        val sr = SyncRow("task", "t1", Long.MAX_VALUE, false, data)
        upsertFromSyncRow(db, sr)
        val stored = db.taskQueries.selectById("t1").executeAsOne()
        assertEquals(0L, stored.serverSeq)
        assertEquals("SYNCED", stored.syncState)
        assertEquals(null, stored.dueDate)
        assertEquals(sr, toSyncRow(stored))
    }

    @Test fun test_AC4_rowmapping_unknown_table_rejected() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        AssistantDb.Schema.create(driver)
        val db = AssistantDb(driver)
        val bad = SyncRow("nope", "x", 1L, false, JsonObject(emptyMap()))
        val ex = runCatching { upsertFromSyncRow(db, bad) }.exceptionOrNull()
        assertTrue(ex is IllegalArgumentException, "got $ex")
    }
}
