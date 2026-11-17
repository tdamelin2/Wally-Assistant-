import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import dev.tim.assistant.ai.ChatMessage
import dev.tim.assistant.ai.Completion
import dev.tim.assistant.ai.jobs.GoalJobs
import dev.tim.assistant.ai.jobs.JobRunner
import dev.tim.assistant.ai.jobs.ModelClient
import dev.tim.assistant.data.DreamBlock
import dev.tim.assistant.data.DreamWeek
import dev.tim.assistant.data.DreamWeekResult
import dev.tim.assistant.data.LocalStore
import dev.tim.assistant.data.Repository
import dev.tim.assistant.data.ServerConfig
import dev.tim.assistant.data.SyncClient
import dev.tim.assistant.data.dreamWeekLine
import dev.tim.assistant.db.AssistantDb
import dev.tim.assistant.db.Cal_event
import dev.tim.assistant.model.HealthResponse
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
import dev.tim.assistant.server.parseConfig
import dev.tim.assistant.server.wallycal.WallyCalClient
import dev.tim.assistant.server.wallycal.WallyCalPoller
import dev.tim.assistant.server.wallycal.WallyCalService
import dev.tim.assistant.server.wallycal.WallyCalStatus
import dev.tim.assistant.server.wallycal.removeNonWallyCalEvents
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import io.ktor.server.testing.testApplication
import java.io.File
import java.nio.file.Files
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

private val P11_NOW: Long = Instant.parse("2026-11-10T10:00:00Z").toEpochMilli()
private const val MIN15 = 15 * 60_000L

private fun p11Root(): File {
    var d: File? = File("").absoluteFile
    while (d != null && !File(d, "settings.gradle.kts").exists()) d = d.parentFile
    return assertNotNull(d)
}

private fun p11Text(rel: String) = File(p11Root(), rel).readText()

private fun occ(id: String, date: String, title: String = "T $id", start: Long = P11_NOW + 3_600_000, end: Long = P11_NOW + 7_200_000, allDay: Boolean = false, location: String? = null) =
    """{"id":"$id","occurrenceDate":"$date","source":"OWN","title":"$title","startAt":$start,"endAt":$end,"allDay":$allDay,"location":${if (location == null) "null" else "\"$location\""},"note":null,"color":"BLUE","repeatRule":"NONE","repeatUntil":null,"reminderMinutes":null}"""

private fun envelope(vararg items: String) = """{"events":[${items.joinToString(",")}]}"""

/** Scripted Wally Calendar over a MockEngine. */
private class FakeWallyCal {
    val requests = ArrayList<HttpRequestData>()
    val postBodies = ArrayList<String>()
    var events: () -> Pair<Int, String> = { 200 to envelope() }
    var dream: () -> Pair<Int, String> = { 404 to """{"error":"not-sent"}""" }
    var create: () -> Pair<Int, String> = { 201 to """{"id":"new-1"}""" }
    var network = false
    var posts = 0

    fun engine() = MockEngine { r ->
        requests += r
        if (network) throw java.io.IOException("down")
        val (code, body) = when {
            r.method == HttpMethod.Post -> {
                posts++
                postBodies += (r.body as TextContent).text
                create()
            }
            r.url.encodedPath == "/v1/events" -> events()
            r.url.encodedPath == "/v1/dream-week" -> dream()
            else -> 404 to "{}"
        }
        respond(body, HttpStatusCode.fromValue(code), headersOf(HttpHeaders.ContentType, "application/json"))
    }
}

private class WRig(val w: FakeWallyCal = FakeWallyCal()) {
    var now = P11_NOW
    val sdb: ServerDb = openDb(Files.createTempDirectory("p11").resolve("t.db").toString())
    val store = SyncStore(sdb)
    val status = WallyCalStatus()
    val client = WallyCalClient(HttpClient(w.engine()), dev.tim.assistant.server.WallyCalConfig("http://wc.test:8081/", "wally-tok"))
    val poller = WallyCalPoller(sdb, store, client, status) { now }
    val service = WallyCalService(sdb, client, poller)

    fun poll(at: Long = now) = runBlocking { now = at; poller.run() }
    fun rows(): List<Cal_event> = sdb.db.cal_eventQueries.selectAll().executeAsList().sortedBy { it.id }
    fun live() = rows().filter { it.deleted == 0L }
    fun snapshot() = rows().map { Triple(it.id, it.serverSeq, it.updatedAt) }
    fun cursor() = runBlocking { store.pull(0).cursor }
    fun seed(id: String, deleted: Long = 0, updatedAt: Long = 5) = sdb.db.cal_eventQueries.upsert(
        Cal_event(id, updatedAt, deleted, 0, "SYNCED", "x", "x", "seed", 1, 2, 0, null),
    )
}

private val cfgTokens = mapOf("laptop" to "tok-test", "calendar" to "tok-cal")

class Phase11AcceptanceTest {
    // ================================================================ AC-1 poller

    @Test
    fun test_AC1_occurrences_mapped_with_stable_ids_across_polls() {
        val r = WRig()
        r.w.events = { 200 to envelope(occ("e1", "2026-11-12", "Dentist", location = "Clinic", allDay = false), occ("e1", "2026-11-19", "Dentist"), occ("a1", "2026-11-13", "Holiday", start = 1000, end = 2000, allDay = true)) }
        r.poll()
        assertEquals(listOf("wcal:a1:2026-11-13", "wcal:e1:2026-11-12", "wcal:e1:2026-11-19"), r.live().map { it.id })
        val e = r.live().first { it.id == "wcal:e1:2026-11-12" }
        assertEquals("wallycal", e.calendarId)
        assertEquals("e1", e.eventId)
        assertEquals("Dentist", e.title)
        assertEquals("Clinic", e.location)
        assertEquals(P11_NOW + 3_600_000, e.startAt)
        assertEquals(P11_NOW + 7_200_000, e.endAt)
        assertEquals(0L, e.allDay)
        assertEquals(1L, r.live().first { it.id == "wcal:a1:2026-11-13" }.allDay)
        assertEquals(true, r.status.get())
        val ids1 = r.live().map { it.id }
        r.poll(P11_NOW + MIN15)
        assertEquals(ids1, r.live().map { it.id })
    }

    @Test
    fun test_AC1_request_shape_bearer_and_window() {
        val r = WRig()
        r.poll()
        val req = r.w.requests.single()
        assertEquals(HttpMethod.Get, req.method)
        assertEquals("Bearer wally-tok", req.headers[HttpHeaders.Authorization])
        assertEquals("/v1/events", req.url.encodedPath)
        assertEquals("2026-10-11", req.url.parameters["from"])
        assertEquals("2027-11-10", req.url.parameters["to"])
    }

    @Test
    fun test_AC1_unchanged_second_poll_changes_no_serverSeq() {
        val r = WRig()
        r.w.events = { 200 to envelope(occ("e1", "2026-11-12"), occ("e2", "2026-11-13")) }
        r.poll()
        val before = r.snapshot()
        val cursor = r.cursor()
        assertEquals(2, before.size)
        r.poll(P11_NOW + MIN15)
        assertEquals(before, r.snapshot())
        assertEquals(cursor, r.cursor())
    }

    @Test
    fun test_AC1_edit_updates_row_and_missing_occurrence_is_soft_deleted() {
        val r = WRig()
        r.w.events = { 200 to envelope(occ("e1", "2026-11-12"), occ("e1", "2026-11-19")) }
        r.poll()
        r.w.events = { 200 to envelope(occ("e1", "2026-11-12", "Renamed")) }
        r.poll(P11_NOW + MIN15)
        val byId = r.rows().associateBy { it.id }
        assertEquals("Renamed", byId.getValue("wcal:e1:2026-11-12").title)
        assertEquals(0L, byId.getValue("wcal:e1:2026-11-12").deleted)
        assertEquals(1L, byId.getValue("wcal:e1:2026-11-19").deleted)
        assertTrue(byId.getValue("wcal:e1:2026-11-19").updatedAt > P11_NOW)
    }

    @Test
    fun test_AC1_deleted_occurrence_that_returns_is_revived() {
        val r = WRig()
        r.w.events = { 200 to envelope(occ("e1", "2026-11-12")) }
        r.poll()
        r.w.events = { 200 to envelope() }
        r.poll(P11_NOW + MIN15)
        assertEquals(1L, r.rows().single().deleted)
        r.w.events = { 200 to envelope(occ("e1", "2026-11-12")) }
        r.poll(P11_NOW + 2 * MIN15)
        assertEquals(0L, r.rows().single().deleted)
    }

    @Test
    fun test_AC1_empty_200_deletes_all_wcal_rows_and_status_true() {
        val r = WRig()
        r.w.events = { 200 to envelope(occ("e1", "2026-11-12"), occ("e2", "2026-11-13")) }
        r.poll()
        r.w.events = { 200 to envelope() }
        r.poll(P11_NOW + MIN15)
        assertTrue(r.rows().all { it.deleted == 1L })
        assertEquals(true, r.status.get())
    }

    @Test
    fun test_AC1_clock_skew_still_stamps_a_newer_updatedAt() {
        val r = WRig()
        r.w.events = { 200 to envelope(occ("e1", "2026-11-12", "A")) }
        r.poll(P11_NOW + 10 * MIN15) // stored with a "future" stamp
        val stored = r.rows().single().updatedAt
        r.w.events = { 200 to envelope(occ("e1", "2026-11-12", "B")) }
        r.poll(P11_NOW) // clock went backwards
        val now = r.rows().single()
        assertEquals("B", now.title)
        assertTrue(now.updatedAt > stored, "edit must stay newer than the stored row")
    }

    @Test
    fun test_AC1_unreachable_keeps_rows_and_sets_false() {
        val r = WRig()
        r.w.events = { 200 to envelope(occ("e1", "2026-11-12")) }
        r.poll()
        val before = r.snapshot()
        val failures: List<() -> Unit> = listOf(
            { r.w.events = { 500 to "{}" } },
            { r.w.events = { 401 to """{"error":"unauthorized"}""" } },
            { r.w.events = { 200 to "not json" } },
            { r.w.network = true },
        )
        failures.forEachIndexed { i, f ->
            r.w.network = false
            f()
            r.poll(P11_NOW + (i + 1) * MIN15)
            assertEquals(false, r.status.get(), "failure $i")
            assertEquals(before, r.snapshot(), "failure $i changed rows")
        }
    }

    @Test
    fun test_AC1_malformed_item_in_envelope_changes_nothing() {
        val r = WRig()
        r.w.events = { 200 to envelope(occ("e1", "2026-11-12"), occ("e2", "2026-11-13")) }
        r.poll()
        val before = r.snapshot()
        val bad = listOf(
            """{"id":"","occurrenceDate":"2026-11-12","title":"x","startAt":1,"endAt":2,"allDay":false,"location":null}""",
            """{"id":"z","occurrenceDate":"2026-13-45","title":"x","startAt":1,"endAt":2,"allDay":false,"location":null}""",
            """{"id":"z","occurrenceDate":"2026-11-12","title":"x","startAt":5,"endAt":2,"allDay":false,"location":null}""",
            """{"id":"z","occurrenceDate":"2026-11-12","title":5,"startAt":1,"endAt":2,"allDay":false,"location":null}""",
            """{"id":"z","occurrenceDate":"2026-11-12","title":"x","startAt":1,"endAt":2,"allDay":"no","location":null}""",
        )
        bad.forEachIndexed { i, b ->
            // one good new item plus one bad one: nothing may be written
            r.w.events = { 200 to envelope(occ("e1", "2026-11-12"), occ("new", "2026-11-14"), b) }
            r.poll(P11_NOW + (i + 1) * MIN15)
            assertEquals(false, r.status.get(), "bad item $i")
            assertEquals(before, r.snapshot(), "bad item $i changed rows")
        }
    }

    @Test
    fun test_AC1_no_wallycal_block_means_status_null_and_no_http() = testApplication {
        val sdb = openDb(Files.createTempDirectory("p11n").resolve("t.db").toString())
        val cfg = Config("127.0.0.1", 0, "unused", cfgTokens)
        application { module(cfg, SyncStore(sdb)) }
        val j = Json.parseToJsonElement(client.get("/v1/health") { header(HttpHeaders.Authorization, "Bearer tok-test") }.bodyAsText()).jsonObject
        assertEquals("null", j.getValue("calendarOk").toString())
    }

    @Test
    fun test_AC1_health_reports_calendarOk_from_status() = testApplication {
        val sdb = openDb(Files.createTempDirectory("p11h").resolve("t.db").toString())
        val st = WallyCalStatus()
        val cfg = cfgWith()
        application { module(cfg, SyncStore(sdb), wallyCalStatus = st) }
        suspend fun ok() = Json.parseToJsonElement(client.get("/v1/health") { header(HttpHeaders.Authorization, "Bearer tok-test") }.bodyAsText()).jsonObject.getValue("calendarOk").toString()
        assertEquals("null", ok())
        st.set(true)
        assertEquals("true", ok())
        st.set(false)
        assertEquals("false", ok())
    }

    // ================================================================ start-up cleanup / Gmail

    @Test
    fun test_AC1_startup_cleanup_soft_deletes_non_wcal_rows_only_and_is_idempotent() {
        val r = WRig()
        r.w.events = { 200 to envelope(occ("e1", "2026-11-12")) }
        r.poll()
        r.seed("cal:primary:old1")
        r.seed("cal:primary:old2")
        runBlocking { removeNonWallyCalEvents(r.sdb, r.store, P11_NOW + 1000) }
        val byId = r.rows().associateBy { it.id }
        assertEquals(1L, byId.getValue("cal:primary:old1").deleted)
        assertEquals(1L, byId.getValue("cal:primary:old2").deleted)
        assertEquals(0L, byId.getValue("wcal:e1:2026-11-12").deleted)
        val after = r.snapshot()
        val cursor = r.cursor()
        runBlocking { removeNonWallyCalEvents(r.sdb, r.store, P11_NOW + 2000) }
        assertEquals(after, r.snapshot())
        assertEquals(cursor, r.cursor(), "second run writes nothing")
        // the tombstones reach a puller
        val pulled = runBlocking { r.store.pull(0) }
        assertTrue(pulled.rows.any { it.id == "cal:primary:old1" && it.deleted })
    }

    @Test
    fun test_AC1_gmail_poll_leaves_wcal_rows_unchanged() {
        val r = WRig()
        r.w.events = { 200 to envelope(occ("e1", "2026-11-12")) }
        r.poll()
        val before = r.snapshot()
        val engine = MockEngine { req ->
            val path = req.url.encodedPath
            val body = when {
                req.url.host == "oauth2.googleapis.com" -> """{"access_token":"at","expires_in":3600,"token_type":"Bearer"}"""
                path == "/gmail/v1/users/me/messages" -> """{"messages":[{"id":"m1","threadId":"m1"}]}"""
                path.startsWith("/gmail/v1/users/me/messages/") -> """{"id":"m1","snippet":"s","internalDate":"${P11_NOW - 1000}","payload":{"headers":[{"name":"From","value":"A <a@x.com>"},{"name":"Subject","value":"S"}]}}"""
                else -> "{}"
            }
            respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        }
        val http = HttpClient(engine)
        val tokenFile = Files.createTempDirectory("p11g").resolve("google-token.json").toFile().also {
            it.writeText("""{"refresh_token":"r1","obtained_at":$P11_NOW}""")
        }
        val auth = GoogleAuth(GoogleConfig("cid", "secret"), tokenFile, http, clock = { P11_NOW })
        val gp = GooglePoller(r.sdb, r.store, GoogleApi(http, auth), GoogleStatus()) { P11_NOW }
        runBlocking { gp.run() }
        assertEquals(1, r.sdb.db.mail_itemQueries.selectAll().executeAsList().size)
        assertEquals(before, r.snapshot())
    }

    // ================================================================ routes

    private fun cfgWith() = Config("127.0.0.1", 0, "unused", cfgTokens, wallycal = dev.tim.assistant.server.WallyCalConfig("http://wc.test:8081", "wally-tok"))

    private fun routeApp(r: WRig, block: suspend io.ktor.server.testing.ApplicationTestBuilder.() -> Unit) = testApplication {
        application { module(cfgWith(), r.store, wallyCalStatus = r.status, wallyCal = r.service) }
        block()
    }

    private suspend fun io.ktor.server.testing.ApplicationTestBuilder.postEvent(body: String, token: String = "tok-test") =
        client.post("/v1/calendar/events") {
            header(HttpHeaders.Authorization, "Bearer $token")
            setBody(TextContent(body, ContentType.Application.Json))
        }

    @Test
    fun test_AC1_same_clientKey_twice_forwards_one_post() {
        val r = WRig()
        routeApp(r) {
            val a = postEvent("""{"clientKey":"k1","title":"Lunch","startAt":1000,"endAt":2000}""")
            assertEquals(HttpStatusCode.Created, a.status)
            assertEquals("new-1", Json.parseToJsonElement(a.bodyAsText()).jsonObject.getValue("id").jsonPrimitive.content)
            r.w.create = { 201 to """{"id":"new-2"}""" }
            val b = postEvent("""{"clientKey":"k1","title":"Lunch","startAt":1000,"endAt":2000}""")
            assertEquals(HttpStatusCode.OK, b.status)
            assertEquals("new-1", Json.parseToJsonElement(b.bodyAsText()).jsonObject.getValue("id").jsonPrimitive.content)
            assertEquals(1, r.w.posts)
        }
    }

    @Test
    fun test_AC1_concurrent_same_clientKey_forwards_one_post() {
        val r = WRig()
        routeApp(r) {
            val codes = kotlinx.coroutines.coroutineScope { (1..4).map { async { postEvent("""{"clientKey":"kc","title":"X","startAt":1,"endAt":2}""").status.value } }.awaitAll() }
            assertTrue(codes.all { it == 200 || it == 201 }, codes.toString())
            assertEquals(1, r.w.posts)
        }
    }

    @Test
    fun test_AC1_replay_after_new_service_instance_does_not_forward() {
        val r = WRig()
        routeApp(r) { assertEquals(HttpStatusCode.Created, postEvent("""{"clientKey":"kr","title":"X","startAt":1,"endAt":2}""").status) }
        val r2 = WRig(r.w)
        // same database file is required for replay, so reuse the first rig's store with a second service
        val service2 = WallyCalService(r.sdb, r.client, WallyCalPoller(r.sdb, r.store, r.client, r.status) { r.now })
        testApplication {
            application { module(cfgWith(), r.store, wallyCalStatus = r.status, wallyCal = service2) }
            val resp = postEvent("""{"clientKey":"kr","title":"X","startAt":1,"endAt":2}""")
            assertEquals(HttpStatusCode.OK, resp.status)
            assertEquals(1, r.w.posts)
        }
        assertNotNull(r2)
    }

    @Test
    fun test_AC1_forwarded_body_has_all_ten_fields_with_defaults_and_no_clientKey() {
        val r = WRig()
        routeApp(r) {
            postEvent("""{"clientKey":"kb","title":"Lunch","startAt":1000,"endAt":2000,"extra":"no"}""")
            val sent = Json.parseToJsonElement(r.w.postBodies.single()).jsonObject
            assertEquals(
                setOf("title", "startAt", "endAt", "allDay", "location", "note", "color", "repeatRule", "repeatUntil", "reminderMinutes"),
                sent.keys,
            )
            assertEquals("BLUE", sent.getValue("color").jsonPrimitive.content)
            assertEquals("false", sent.getValue("allDay").jsonPrimitive.content)
            assertEquals("NONE", sent.getValue("repeatRule").jsonPrimitive.content)
            assertEquals(JsonNull, sent.getValue("location"))
            assertEquals(JsonNull, sent.getValue("note"))
            assertEquals(JsonNull, sent.getValue("repeatUntil"))
            assertEquals(JsonNull, sent.getValue("reminderMinutes"))
            assertEquals("Lunch", sent.getValue("title").jsonPrimitive.content)
            assertEquals("Bearer wally-tok", r.w.requests.first { it.method == HttpMethod.Post }.headers[HttpHeaders.Authorization])
        }
    }

    @Test
    fun test_AC1_explicit_invalid_color_is_forwarded_and_400_passed_through_without_dedupe_record() {
        val r = WRig()
        r.w.create = { 400 to """{"error":"invalid: color"}""" }
        routeApp(r) {
            val resp = postEvent("""{"clientKey":"kv","title":"X","startAt":1,"endAt":2,"color":"PURPLE"}""")
            assertEquals(HttpStatusCode.BadRequest, resp.status)
            assertEquals("invalid: color", Json.parseToJsonElement(resp.bodyAsText()).jsonObject.getValue("error").jsonPrimitive.content)
            assertEquals("PURPLE", Json.parseToJsonElement(r.w.postBodies.single()).jsonObject.getValue("color").jsonPrimitive.content)
            // not remembered: a retry with the same key forwards again
            r.w.create = { 201 to """{"id":"ok-1"}""" }
            assertEquals(HttpStatusCode.Created, postEvent("""{"clientKey":"kv","title":"X","startAt":1,"endAt":2}""").status)
            assertEquals(2, r.w.posts)
        }
    }

    @Test
    fun test_AC1_create_validation_and_upstream_failures() {
        val r = WRig()
        routeApp(r) {
            val noKey = postEvent("""{"title":"x"}""")
            assertEquals(HttpStatusCode.BadRequest, noKey.status)
            assertEquals("invalid: clientKey", Json.parseToJsonElement(noKey.bodyAsText()).jsonObject.getValue("error").jsonPrimitive.content)
            assertEquals(HttpStatusCode.BadRequest, postEvent("""{"clientKey":"${"k".repeat(201)}"}""").status)
            assertEquals(HttpStatusCode.BadRequest, postEvent("""{"clientKey":""}""").status)
            val notObj = postEvent("""[1,2]""")
            assertEquals(HttpStatusCode.BadRequest, notObj.status)
            assertEquals("invalid: body", Json.parseToJsonElement(notObj.bodyAsText()).jsonObject.getValue("error").jsonPrimitive.content)
            assertEquals(0, r.w.posts)
            for (bad in listOf(500 to "{}", 401 to "{}", 201 to """{"nope":1}""")) {
                r.w.create = { bad }
                val resp = postEvent("""{"clientKey":"kf${bad.first}","title":"x","startAt":1,"endAt":2}""")
                assertEquals(HttpStatusCode.BadGateway, resp.status, bad.toString())
                assertEquals("calendar-unreachable", Json.parseToJsonElement(resp.bodyAsText()).jsonObject.getValue("error").jsonPrimitive.content)
            }
            // Phase 13 attempt fence: an unknown outcome (500/401/garbage) leaves the attempt, so the same key is
            // answered 502 without a second POST; a fresh key still works.
            r.w.create = { 201 to """{"id":"fine"}""" }
            val postsBefore = r.w.posts
            assertEquals(HttpStatusCode.BadGateway, postEvent("""{"clientKey":"kf500","title":"x","startAt":1,"endAt":2}""").status)
            assertEquals(postsBefore, r.w.posts, "no second POST for a fenced key")
            assertEquals(HttpStatusCode.Created, postEvent("""{"clientKey":"kfresh","title":"x","startAt":1,"endAt":2}""").status)
        }
    }

    @Test
    fun test_AC1_post_create_poll_failure_still_answers_201() {
        val r = WRig()
        r.w.events = { 500 to "{}" }
        routeApp(r) {
            val resp = postEvent("""{"clientKey":"kp","title":"x","startAt":1,"endAt":2}""")
            assertEquals(HttpStatusCode.Created, resp.status)
            assertEquals("new-1", Json.parseToJsonElement(resp.bodyAsText()).jsonObject.getValue("id").jsonPrimitive.content)
            assertEquals(false, r.status.get())
        }
    }

    @Test
    fun test_AC1_dream_week_200_passthrough_404_not_sent_and_502() {
        val r = WRig()
        routeApp(r) {
            suspend fun get() = client.get("/v1/calendar/dream-week") { header(HttpHeaders.Authorization, "Bearer tok-test") }
            val body = """{"sentAt":5,"blocks":[{"id":"b","title":"Gym","dayOfWeek":1,"startMinute":60,"endMinute":120}]}"""
            r.w.dream = { 200 to body }
            val ok = get()
            assertEquals(HttpStatusCode.OK, ok.status)
            assertEquals(Json.parseToJsonElement(body), Json.parseToJsonElement(ok.bodyAsText()))
            r.w.dream = { 404 to """{"error":"not-sent"}""" }
            val ns = get()
            assertEquals(HttpStatusCode.NotFound, ns.status)
            assertEquals("not-sent", Json.parseToJsonElement(ns.bodyAsText()).jsonObject.getValue("error").jsonPrimitive.content)
            r.w.dream = { 404 to """{"error":"not-found"}""" }
            assertEquals(HttpStatusCode.BadGateway, get().status)
            r.w.dream = { 500 to "{}" }
            val bad = get()
            assertEquals(HttpStatusCode.BadGateway, bad.status)
            assertEquals("calendar-unreachable", Json.parseToJsonElement(bad.bodyAsText()).jsonObject.getValue("error").jsonPrimitive.content)
            r.w.network = true
            assertEquals(HttpStatusCode.BadGateway, get().status)
        }
    }

    @Test
    fun test_AC1_calendar_token_gets_403_on_calendar_routes() {
        val r = WRig()
        routeApp(r) {
            val g = client.get("/v1/calendar/dream-week") { header(HttpHeaders.Authorization, "Bearer tok-cal") }
            assertEquals(HttpStatusCode.Forbidden, g.status)
            assertEquals(HttpStatusCode.Forbidden, postEvent("""{"clientKey":"k","title":"x"}""", token = "tok-cal").status)
            assertEquals(0, r.w.requests.size)
            val none = client.get("/v1/calendar/dream-week")
            assertEquals(HttpStatusCode.Unauthorized, none.status)
        }
    }

    @Test
    fun test_AC1_routes_503_calendar_not_configured_without_block() = testApplication {
        val sdb = openDb(Files.createTempDirectory("p11r").resolve("t.db").toString())
        val cfg = Config("127.0.0.1", 0, "unused", cfgTokens)
        application { module(cfg, SyncStore(sdb)) }
        val g = client.get("/v1/calendar/dream-week") { header(HttpHeaders.Authorization, "Bearer tok-test") }
        assertEquals(HttpStatusCode.ServiceUnavailable, g.status)
        assertEquals("calendar-not-configured", Json.parseToJsonElement(g.bodyAsText()).jsonObject.getValue("error").jsonPrimitive.content)
        val p = client.post("/v1/calendar/events") {
            header(HttpHeaders.Authorization, "Bearer tok-test")
            setBody(TextContent("""{"clientKey":"k"}""", ContentType.Application.Json))
        }
        assertEquals(HttpStatusCode.ServiceUnavailable, p.status)
    }

    // ================================================================ config / source (AC-2)

    @Test
    fun test_AC2_old_config_with_calendarIds_loads_and_wallycal_url_is_normalized() {
        val c = parseConfig(
            """{"bindAddress":"1.2.3.4","port":8080,"dbPath":"/x.db","tokens":{"laptop":"a","calendar":"b"},
               "google":{"clientId":"c","clientSecret":"s","calendarIds":["primary"]},
               "wallycal":{"url":"  http://hp:8081/ ","token":"wt"}}""",
        )
        assertEquals("http://hp:8081", c.wallycal!!.root)
        assertEquals("wt", c.wallycal!!.token)
        assertEquals("b", c.tokens["calendar"])
        assertNull(parseConfig("""{"bindAddress":"1.2.3.4","port":8080,"dbPath":"/x.db","tokens":{"laptop":"a"}}""").wallycal)
    }

    @Test
    fun test_AC2_no_google_calendar_code_left_in_server_main() {
        val src = File(p11Root(), "server/src/main").walkTopDown().filter { it.isFile && it.extension == "kt" }
        val bad = Regex("calendar/v3|calendar\\.readonly|calendarIds|google-calendars")
        val hits = src.filter { bad.containsMatchIn(it.readText()) }.map { it.name }.toList()
        assertTrue(hits.isEmpty(), "Google Calendar leftovers in $hits")
    }

    @Test
    fun test_AC2_main_usage_and_commands_drop_google_calendars() {
        val main = p11Text("server/src/main/kotlin/dev/tim/assistant/server/Main.kt")
        assertTrue(main.contains("server [google-auth | spotify-auth]"))
    }

    @Test
    fun test_AC2_w13_checklist_has_the_required_lines() {
        val c = p11Text("ops/checklists/w13-calendar.md")
        for (k in listOf("15 minutes", "Sync", "twice", "Import", "Make Wally task", "tokens.calendar", "Dream week", "Coach me on my Dream week", "Last sent")) {
            assertTrue(c.contains(k, ignoreCase = true), "w13-calendar.md lacks '$k'")
        }
        assertTrue(c.contains("1.12.0"))
    }

    @Test
    fun test_AC2_ops_guides_are_gmail_only_and_example_config_has_wallycal() {
        val cfg = Json.parseToJsonElement(p11Text("ops/config.example.json")).jsonObject
        assertTrue(cfg.containsKey("wallycal"))
        assertTrue(cfg.getValue("tokens").jsonObject.containsKey("calendar"))
        assertFalse(p11Text("ops/config.example.json").contains("calendarIds"))
        assertTrue(File(p11Root(), "ops/wallycal.md").exists())
        for (f in listOf("ops/google-setup.md", "ops/moodle.md", "ops/spotify.md")) {
            val t = p11Text(f)
            assertFalse(t.contains("calendarIds"), "$f still shows calendarIds")
            assertFalse(t.contains("server google-calendars"), "$f still tells to run google-calendars")
        }
    }

    // ================================================================ AC-3 shared

    private fun memDb() = AssistantDb(JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).also { AssistantDb.Schema.create(it) })

    private class Clk(var t: Long = 1_700_000_000_000L) { fun now() = t }

    private class Fake(val reply: suspend (List<ChatMessage>) -> Completion) : ModelClient {
        val prompts = mutableListOf<List<ChatMessage>>()
        override suspend fun complete(messages: List<ChatMessage>, format: JsonObject?): Completion {
            prompts += messages
            return reply(messages)
        }
    }

    private fun joined(f: Fake) = f.prompts.joinToString("\n---\n") { p -> p.joinToString("\n") { it.content } }

    private val week = DreamWeek(
        1_700_000_000_000L,
        listOf(
            DreamBlock("b2", "Deep work", 2, 9 * 60, 11 * 60),
            DreamBlock("b1", "Morning run", 1, 7 * 60, 8 * 60, "GREEN", "SECRETNOTE"),
            DreamBlock("b3", "Sleep", 7, 22 * 60, 1440),
        ),
    )

    private suspend fun runIdeal(
        input: String,
        source: DreamWeekResult,
        reply: String = "## Differences\n- gap\n\n- step one\n- step two\n- step three",
        offline: Boolean = false,
    ): Triple<Fake, Repository, String> {
        val clock = Clk(P11_NOW)
        val repo = Repository(LocalStore(memDb()), now = clock::now, today = { "2026-11-10" })
        repo.saveDream(null, "2026-11-08", "dream ZZ_DREAM_MARK", 3, false, "")
        val model = Fake { if (offline) Completion.Offline else Completion.Ok(reply) }
        val jobs = GoalJobs(repo, model, clock::now) { source }
        val id = repo.queueJob("IDEAL_WEEK", input)
        JobRunner(repo, sync = {}, handlers = jobs.handlers(), clock = clock::now).tick()
        return Triple(model, repo, id)
    }

    @Test
    fun test_AC3_successful_coaching_calls_model_with_blocks_and_no_dream_marker() = runBlocking {
        val (model, repo, id) = runIdeal("{}", DreamWeekResult.Sent(week))
        assertEquals(1, model.prompts.size, "the model was really called")
        val p = joined(model)
        assertTrue(p.contains("Morning run") && p.contains("Deep work") && p.contains("Sleep"))
        assertTrue(p.contains("Mon 07:00-08:00"), p)
        assertTrue(p.contains("Tue 09:00-11:00"), p)
        assertTrue(p.contains("Sun 22:00-24:00"), p)
        assertTrue(p.indexOf("Morning run") < p.indexOf("Deep work"), "sorted by day")
        assertFalse(p.contains("SECRETNOTE"), "notes excluded")
        assertFalse(p.contains("ZZ_DREAM_MARK"), "dream journal never read")
        val job = repo.jobById(id)!!
        assertEquals("DONE", job.status)
        val doc = repo.docs().single { it.id == "doc:idealweek:$id" }
        assertEquals("Dream week coaching 2026-11-10", doc.title)
    }

    @Test
    fun test_AC3_not_sent_fails_now_with_message_and_no_model_call_and_no_doc() = runBlocking {
        val (model, repo, id) = runIdeal("{}", DreamWeekResult.NotSent)
        assertTrue(model.prompts.isEmpty())
        val job = repo.jobById(id)!!
        assertEquals("FAILED", job.status)
        assertEquals("Send your Dream week from Wally Calendar first", job.error)
        assertTrue(repo.docs().none { it.id.startsWith("doc:idealweek:") })
    }

    @Test
    fun test_AC3_empty_blocks_still_coach_with_none_line() = runBlocking {
        val (model, _, _) = runIdeal("{}", DreamWeekResult.Sent(DreamWeek(1_700_000_000_000L, emptyList())))
        assertEquals(1, model.prompts.size)
        assertTrue(joined(model).contains("- none"))
    }

    @Test
    fun test_AC3_legacy_description_input_still_runs_and_is_ignored() = runBlocking {
        val (model, repo, id) = runIdeal("""{"localDate":"2026-11-09","description":"LEGACYDESC mornings"}""", DreamWeekResult.Sent(week))
        assertEquals(1, model.prompts.size)
        assertFalse(joined(model).contains("LEGACYDESC"))
        assertEquals("DONE", repo.jobById(id)!!.status)
    }

    @Test
    fun test_AC3_unavailable_is_a_retry_not_a_final_failure_and_no_model_call() = runBlocking {
        val (model, repo, id) = runIdeal("{}", DreamWeekResult.Unavailable("down"))
        assertTrue(model.prompts.isEmpty())
        val j = repo.jobById(id)!!
        assertEquals("QUEUED", j.status, "first failed attempt stays queued for retry")
        assertEquals(1L, j.attempts)
        val (_, repo2, id2) = runIdeal("{}", DreamWeekResult.Unauthorized)
        assertEquals("QUEUED", repo2.jobById(id2)!!.status)
    }

    @Test
    fun test_AC3_oversized_blocks_keep_the_prompt_bounded() = runBlocking {
        val many = DreamWeek(1_700_000_000_000L, (1..400).map { DreamBlock("id$it", "Block number $it " + "x".repeat(150), (it % 7) + 1, 60, 120) })
        val (model, _, _) = runIdeal("{}", DreamWeekResult.Sent(many))
        assertEquals(1, model.prompts.size)
        val user = model.prompts.single().filter { it.role != "system" }.joinToString("\n") { it.content }
        assertTrue(user.length < 14_000, "data stays near the 12 000 budget, was ${user.length}")
        assertTrue(user.contains("more)"), "cut sections end with '- (N more)'")
    }

    @Test
    fun test_AC3_dreamWeekLine_texts() {
        assertEquals("", dreamWeekLine(null))
        assertEquals("Dream week not sent from Wally Calendar yet", dreamWeekLine(DreamWeekResult.NotSent))
        assertEquals("Wally Calendar not reachable", dreamWeekLine(DreamWeekResult.Unauthorized))
        assertEquals("Wally Calendar not reachable", dreamWeekLine(DreamWeekResult.Unavailable("x")))
        val l = dreamWeekLine(DreamWeekResult.Sent(week))
        assertTrue(Regex("Last sent from Wally Calendar: \\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}").matches(l), l)
    }

    @Test
    fun test_AC3_syncClient_dreamWeek_maps_statuses() = runBlocking<Unit> {
        fun client(code: Int, body: String, fail: Boolean = false) = SyncClient(
            MockEngine {
                if (fail) throw java.io.IOException("x")
                respond(body, HttpStatusCode.fromValue(code), headersOf(HttpHeaders.ContentType, "application/json"))
            },
        )
        val cfg = ServerConfig("http://server:8080/", "tok")
        val ok = client(200, """{"sentAt":5,"blocks":[],"unknown":1}""").dreamWeek(cfg)
        assertIs<DreamWeekResult.Sent>(ok)
        assertEquals(5L, ok.week.sentAt)
        assertEquals(DreamWeekResult.NotSent, client(404, """{"error":"not-sent"}""").dreamWeek(cfg))
        assertIs<DreamWeekResult.Unavailable>(client(404, """{"error":"other"}""").dreamWeek(cfg))
        assertEquals(DreamWeekResult.Unauthorized, client(401, "{}").dreamWeek(cfg))
        assertIs<DreamWeekResult.Unavailable>(client(502, """{"error":"calendar-unreachable"}""").dreamWeek(cfg))
        assertIs<DreamWeekResult.Unavailable>(client(200, "garbage").dreamWeek(cfg))
        assertIs<DreamWeekResult.Unavailable>(client(200, "{}", fail = true).dreamWeek(cfg))
    }

    @Test
    fun test_AC3_healthResponse_calendarOk_defaults_null_and_is_last() {
        val h = HealthResponse::class.java.declaredFields.map { it.name }.filter { !it.startsWith("$") && it != "Companion" }
        assertEquals("calendarOk", h.last())
    }

    // ================================================================ AC-4 / AC-5 release pins

    @Test
    fun test_AC5_release_versions_1_9_0_and_versionCode_5() {
        val g = p11Text("desktopApp/build.gradle.kts")
        assertTrue(g.contains("packageVersion = \"1.18.0\""))
        assertTrue(g.contains("upgradeUuid"))
        val a = p11Text("androidApp/build.gradle.kts")
        assertTrue(a.contains("versionName = \"1.18.0\""))
        assertTrue(Regex("versionCode\\s*=\\s*14\\b").containsMatchIn(a))
    }

    @Test
    fun test_AC5_checklists_name_1_9_0_and_historical_ones_stay() {
        for (f in listOf("phase1-e2e", "phase4", "phase6", "nutrition")) {
            val t = p11Text("ops/checklists/$f.md")
            assertTrue(t.contains("1.18.0"), "$f.md names 1.18.0")
            assertFalse(t.contains("Wally-1.8.0"), "$f.md still names the old installer")
        }
        assertTrue(p11Text("ops/checklists/phase1-e2e.md").contains("Wally-1.18.0.msi"))
        assertTrue(p11Text("ops/checklists/w9-study.md").contains("1.7.0"))
    }

    @Test
    fun test_AC5_phase4_checklist_marks_google_calendar_lines_as_replaced() {
        val t = p11Text("ops/checklists/phase4.md")
        assertTrue(t.contains("w13-calendar"), "phase4.md points to w13-calendar.md")
    }
}
