import dev.tim.assistant.db.Cal_event
import dev.tim.assistant.db.Mail_item
import dev.tim.assistant.server.Config
import dev.tim.assistant.server.GoogleConfig
import dev.tim.assistant.server.ServerDb
import dev.tim.assistant.server.SyncStore
import dev.tim.assistant.server.google.GoogleApi
import dev.tim.assistant.server.google.GoogleAuth
import dev.tim.assistant.server.google.GooglePoller
import dev.tim.assistant.server.google.GoogleStatus
import dev.tim.assistant.server.module
import dev.tim.assistant.server.openDb
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.HttpRequestData
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.server.testing.testApplication
import java.io.File
import java.net.URI
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

private const val DAY_MS = 24L * 60 * 60 * 1000
private val NOW: Long = Instant.parse("2026-11-08T10:00:00Z").toEpochMilli()
private val JLM = ZoneId.of("Asia/Jerusalem")

private fun midnight(date: String) = LocalDate.parse(date).atStartOfDay(JLM).toInstant().toEpochMilli()
private fun enc(s: String) = java.net.URLEncoder.encode(s, "UTF-8").replace("+", "%20")
private fun page(key: String, items: List<String>, next: String? = null) =
    """{"$key":[${items.joinToString(",")}]${if (next != null) ""","nextPageToken":"$next"""" else ""}}"""

private fun timed(id: String, start: String = "2026-11-09T09:00:00+02:00", end: String = "2026-11-09T10:00:00+02:00") =
    """{"id":"$id","status":"confirmed","summary":"Meeting $id","start":{"dateTime":"$start"},"end":{"dateTime":"$end"}}"""

private fun ids(list: List<String>) = list.map { """{"id":"$it","threadId":"$it"}""" }

private fun msg(id: String, at: Long) =
    """{"id":"$id","snippet":"snip $id","internalDate":"$at","payload":{"headers":[{"name":"From","value":"Ann <a@x.com>"},{"name":"Subject","value":"Subj $id"}]}}"""

/** Scripted Google over a Ktor MockEngine. */
private class Google {
    val requests = ArrayList<HttpRequestData>()
    var unread: List<Pair<Int, String>> = listOf(200 to page("messages", emptyList()))
    var messageAt: Long = NOW - DAY_MS

    fun engine() = MockEngine { r ->
        requests += r
        val path = r.url.encodedPath
        val idx = r.url.parameters["pageToken"]?.removePrefix("p")?.toIntOrNull() ?: 0
        val (code, body) = when {
            r.url.host == "oauth2.googleapis.com" -> 200 to """{"access_token":"at","expires_in":3600,"token_type":"Bearer"}"""
            path == "/gmail/v1/users/me/messages" -> unread.getOrNull(idx) ?: (404 to "{}")
            path.startsWith("/gmail/v1/users/me/messages/") ->
                200 to msg(path.removePrefix("/gmail/v1/users/me/messages/"), messageAt)
            else -> 404 to "{}"
        }
        respond(body, HttpStatusCode.fromValue(code), headersOf(HttpHeaders.ContentType, "application/json"))
    }
}

private class Rig(val g: Google = Google()) {
    var now = NOW
    val sdb: ServerDb = openDb(Files.createTempDirectory("p4acc").resolve("t.db").toString())
    val store = SyncStore(sdb)
    val status = GoogleStatus()
    private val tokenFile = Files.createTempDirectory("p4tok").resolve("google-token.json").toFile().also {
        it.writeText("""{"refresh_token":"r1","obtained_at":$NOW}""")
    }
    private val client = HttpClient(g.engine())
    private val auth = GoogleAuth(GoogleConfig("cid", "secret"), tokenFile, client, clock = { now })
    private val poller = GooglePoller(sdb, store, GoogleApi(client, auth), status) { now }

    fun unread(vararg pages: Pair<Int, String>) { g.unread = pages.toList() }
    fun unreadIds(vararg list: String) = unread(200 to page("messages", ids(list.toList())))

    fun poll(at: Long = now) = runBlocking { now = at; poller.run() }
    fun events(): List<Cal_event> = sdb.db.cal_eventQueries.selectAll().executeAsList().sortedBy { it.id }
    fun mail(): List<Mail_item> = sdb.db.mail_itemQueries.selectAll().executeAsList().sortedBy { it.id }
    fun snapshot() = events().map { Triple(it.id, it.serverSeq, it.updatedAt) } + mail().map { Triple(it.id, it.serverSeq, it.updatedAt) }
    fun cursor() = runBlocking { store.pull(0).cursor }
}

class Phase4AcceptanceTest {
    // ---- AC-1: PLAN Task 3.3 Verify cases ----

    @Test
    fun test_AC1_new_events_and_mail_inserted() {
        val r = Rig()
        r.unreadIds("m1", "m2")
        r.poll()
        assertEquals(listOf("mail:m1", "mail:m2"), r.mail().map { it.id })
        assertTrue(r.events().isEmpty(), "Gmail poll writes no calendar rows (Phase 11)")
        assertEquals(true, r.status.snapshot().first)
        assertEquals(NOW, r.status.snapshot().second)
    }

    @Test
    fun test_AC1_read_mail_soft_deleted() {
        val r = Rig()
        r.unreadIds("m1", "m2")
        r.poll()
        r.unreadIds("m2")
        r.poll(NOW + 15 * 60_000)
        val byId = r.mail().associateBy { it.id }
        assertEquals(1L, byId.getValue("mail:m1").deleted)
        assertEquals(0L, byId.getValue("mail:m2").deleted)
    }

    @Test
    fun test_AC1_read_then_unread_mail_undeleted() {
        val r = Rig()
        r.unreadIds("m1")
        r.poll()
        r.unreadIds()
        r.poll(NOW + 15 * 60_000)
        assertEquals(1L, r.mail().single().deleted)
        r.unreadIds("m1")
        r.poll(NOW + 30 * 60_000)
        assertEquals(0L, r.mail().single().deleted)
    }

    @Test
    fun test_AC1_unread_mail_older_than_30_days_stays_deleted() {
        val r = Rig()
        r.g.messageAt = NOW - 29 * DAY_MS
        r.unreadIds("old")
        r.poll()
        r.unreadIds()
        r.poll(NOW + 15 * 60_000)
        assertEquals(1L, r.mail().single().deleted)
        // three days later the message is more than 30 days old; Google still lists it as unread
        r.unreadIds("old")
        r.poll(NOW + 3 * DAY_MS)
        r.poll(NOW + 3 * DAY_MS + 15 * 60_000)
        assertEquals(1L, r.mail().single().deleted)
    }

    @Test
    fun test_AC1_second_identical_poll_changes_no_serverSeq() {
        val r = Rig()
        r.unreadIds("m1")
        r.poll()
        val before = r.snapshot()
        val cursorBefore = r.cursor()
        assertTrue(before.isNotEmpty())
        r.poll(NOW + 15 * 60_000)
        assertEquals(before, r.snapshot())
        assertEquals(cursorBefore, r.cursor())
    }

    @Test
    fun test_AC1_google_401_sets_ok_false_and_changes_no_rows() {
        val r = Rig()
        r.unreadIds("m1")
        r.poll()
        val before = r.snapshot()
        r.unread(401 to """{"error":{"code":401}}""")
        r.poll(NOW + 15 * 60_000)
        assertEquals(false, r.status.snapshot().first)
        assertEquals(before, r.snapshot())
    }

    // ---- AC-1 extras from the Plan ----

    @Test
    fun test_AC1_only_GET_to_google_apis() {
        val r = Rig()
        r.unreadIds("m1")
        r.poll()
        r.g.requests.forEach {
            if (it.url.host == "oauth2.googleapis.com") assertEquals(HttpMethod.Post, it.method)
            else assertEquals(HttpMethod.Get, it.method, it.url.toString())
        }
        assertTrue(r.g.requests.any { it.url.host != "oauth2.googleapis.com" })
    }

    @Test
    fun test_AC1_200_id_cap_removes_no_mail_and_stays_ok() {
        val r = Rig()
        r.unreadIds("keep")
        r.poll()
        assertEquals(0L, r.mail().single().deleted)
        val pages = (0 until 5).map { p ->
            200 to page("messages", ids((0 until 100).map { "c$p-$it" }), next = "p${p + 1}")
        }
        r.unread(*pages.toTypedArray())
        r.poll(NOW + 15 * 60_000)
        assertEquals(0L, r.mail().first { it.id == "mail:keep" }.deleted)
        assertEquals(true, r.status.snapshot().first)
        assertTrue(r.mail().size <= 201)
    }

    // ---- App side: googleToday projection tests retired. The function was removed in Phase 14 (P14-A-06): Today no longer shows mail/events. ----

    // ---- Health ----

    @Test
    fun test_AC1_health_returns_google_status_snapshot() = testApplication {
        val sdb = openDb(Files.createTempDirectory("p4h").resolve("h.db").toString())
        val status = GoogleStatus()
        val cfg = Config("127.0.0.1", 0, "unused", mapOf("laptop" to "tok-test"))
        application { module(cfg, SyncStore(sdb), { NOW }, status) }
        suspend fun health() = client.get("/v1/health") { header(HttpHeaders.Authorization, "Bearer tok-test") }.bodyAsText()
        val j0 = Json.parseToJsonElement(health()).jsonObject
        assertEquals("null", j0.getValue("googleOk").toString())
        assertEquals("null", j0.getValue("googleLastFetchAt").toString())
        status.update(true, 12345L)
        val j1 = Json.parseToJsonElement(health()).jsonObject
        assertEquals("true", j1.getValue("googleOk").jsonPrimitive.content)
        assertEquals("12345", j1.getValue("googleLastFetchAt").jsonPrimitive.content)
    }

    // ---- AC-2: env-gated live check (counts only, never token or content) ----

    @Test
    fun test_AC2_live_pull_has_calendar_and_mail_rows() {
        val url = System.getenv("P4_SERVER_URL")?.trimEnd('/')
        val token = System.getenv("P4_TOKEN")
        if (url.isNullOrBlank() || token.isNullOrBlank()) return
        val http = java.net.http.HttpClient.newHttpClient()
        val counts = HashMap<String, Int>()
        var since = 0L
        while (true) {
            val req = HttpRequest.newBuilder(URI("$url/v1/sync/pull?since=$since"))
                .header("Authorization", "Bearer $token").GET().build()
            val resp = http.send(req, HttpResponse.BodyHandlers.ofString())
            assertEquals(200, resp.statusCode())
            val obj = Json.parseToJsonElement(resp.body()).jsonObject
            val rows = obj.getValue("rows").jsonArray
            rows.forEach { counts.merge(it.jsonObject.getValue("table").jsonPrimitive.content, 1, Int::plus) }
            since = obj.getValue("cursor").jsonPrimitive.content.toLong()
            if (rows.size < 1000) break
        }
        println("AC2 counts: cal_event=${counts["cal_event"] ?: 0} mail_item=${counts["mail_item"] ?: 0}")
        assertTrue((counts["cal_event"] ?: 0) > 0, "no cal_event rows")
        assertTrue((counts["mail_item"] ?: 0) > 0, "no mail_item rows")
    }

    // ---- File-level checks for the manual ACs ----

    private fun root(): File {
        var d: File? = File("").absoluteFile
        while (d != null && !File(d, "settings.gradle.kts").exists()) d = d.parentFile
        return assertNotNull(d)
    }

    @Test
    fun test_AC3_checklist_covers_task_3_4_lines() {
        val c = File(root(), "ops/checklists/phase3.md").readText()
        listOf("permissions", "Sync", "Unread mail", "banner", "google-auth", "last google-auth", "backup").forEach {
            assertTrue(c.contains(it, ignoreCase = true), "phase3.md lacks '$it'")
        }
    }

    @Test
    fun test_AC4_AC5_dated_pending_lines() {
        val c = File(root(), "ops/checklists/phase3.md").readText()
        assertTrue(c.contains("AC-4"))
        assertTrue(c.contains("AC-5") && c.contains("2026-11-19") && c.contains("14 files") && c.contains("gcrypt:"))
    }

    @Test
    fun test_AC3_setup_guide_has_readonly_scopes_and_no_secret() {
        val g = File(root(), "ops/google-setup.md").readText()
        assertTrue(g.contains("gmail.readonly") && !g.contains("google-calendars"))
        assertTrue(g.contains("google-auth") && g.contains("8765"))
        assertFalse(Regex("GOCSPX-[A-Za-z0-9_-]{10,}").containsMatchIn(g))
        val cfg = Json.parseToJsonElement(File(root(), "ops/config.example.json").readText()).jsonObject
        assertTrue(cfg.containsKey("google"))
    }

    @Test
    fun test_server_scopes_are_read_only_in_source() {
        val src = File(root(), "server/src/main/kotlin").walkTopDown().filter { it.extension == "kt" }.joinToString("\n") { it.readText() }
        // Phase 12 deliberately adds gmail.send and gmail.modify; calendar and full-mail scopes stay forbidden.
        assertFalse(src.contains("auth/calendar\"") || src.contains("mail.google.com/\""))
    }
}
