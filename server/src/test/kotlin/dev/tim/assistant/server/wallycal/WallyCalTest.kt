package dev.tim.assistant.server.wallycal

import dev.tim.assistant.db.Cal_event
import dev.tim.assistant.model.toSyncRow
import dev.tim.assistant.server.Config
import dev.tim.assistant.server.ServerDb
import dev.tim.assistant.server.SyncStore
import dev.tim.assistant.server.TOKEN
import dev.tim.assistant.server.WallyCalConfig
import dev.tim.assistant.server.module
import dev.tim.assistant.server.openDb
import dev.tim.assistant.server.parseConfig
import dev.tim.assistant.server.tempDbPath
import dev.tim.assistant.server.testConfig
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.headersOf
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import java.io.IOException
import java.time.Instant
import java.time.LocalDate
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

private val T0: Long = Instant.parse("2026-11-08T10:00:00Z").toEpochMilli()
private const val CAL_TOKEN = "inbound-calendar-token"
private const val WALLY_TOKEN = "wally-outbound-secret"
private const val HOUR = 3_600_000L
private val json = Json { ignoreUnknownKeys = true }

private fun occ(
    id: String,
    date: String,
    title: String = "Event $id",
    start: Long = T0 + HOUR,
    end: Long = T0 + 2 * HOUR,
    allDay: Boolean = false,
    location: String? = null,
) = """{"id":"$id","occurrenceDate":"$date","source":"OWN","title":"$title","startAt":$start,"endAt":$end,"allDay":$allDay,""" +
    """"location":${if (location == null) "null" else "\"$location\""},"note":null,"color":"BLUE","repeatRule":"NONE","repeatUntil":null,"reminderMinutes":null}"""

private fun envelope(vararg items: String) = """{"events":[${items.joinToString(",")}]}"""

private class FakeWallyCal {
    class Req(val method: String, val path: String, val from: String?, val to: String?, val auth: String?, val body: String)

    val requests = CopyOnWriteArrayList<Req>()

    @Volatile var eventsReply: Pair<Int, String> = 200 to envelope()

    @Volatile var dreamReply: Pair<Int, String> = 404 to """{"error":"not-sent"}"""

    @Volatile var createReply: (Int) -> Pair<Int, String> = { 201 to """{"id":"ev-$it"}""" }

    @Volatile var failPaths: Set<String> = emptySet()

    @Volatile var failure: Exception? = null

    @Volatile var createGate: CompletableDeferred<Unit>? = null

    val posts get() = requests.filter { it.method == "POST" }

    fun engine() = MockEngine { request ->
        val body = String(request.body.toByteArray(), Charsets.UTF_8)
        requests += Req(
            request.method.value, request.url.encodedPath, request.url.parameters["from"], request.url.parameters["to"],
            request.headers[HttpHeaders.Authorization], body,
        )
        val path = request.url.encodedPath
        if (path in failPaths) throw (failure ?: IOException("network down"))
        val (status, text) = when {
            request.method.value == "GET" && path == "/prefix/v1/events" -> eventsReply
            request.method.value == "GET" && path == "/prefix/v1/dream-week" -> dreamReply
            request.method.value == "POST" && path == "/prefix/v1/events" -> {
                createGate?.await()
                createReply(posts.size)
            }
            else -> 404 to "{}"
        }
        respond(text, HttpStatusCode.fromValue(status), headersOf(HttpHeaders.ContentType, "application/json"))
    }
}

private class Rig(withWallyCal: Boolean = true, val fake: FakeWallyCal = FakeWallyCal()) {
    var now = T0
    val sdb: ServerDb = openDb(tempDbPath())
    val store = SyncStore(sdb)
    val status = WallyCalStatus()
    val wallyCfg = WallyCalConfig(" http://hp.example:8081/prefix/ ", WALLY_TOKEN).let { WallyCalConfig(it.root, it.token) }
    val config: Config = testConfig("unused").copy(
        tokens = testConfig("unused").tokens + ("calendar" to CAL_TOKEN),
        wallycal = if (withWallyCal) wallyCfg else null,
    )
    val client = WallyCalClient(HttpClient(fake.engine()), wallyCfg)
    val poller = WallyCalPoller(sdb, store, client, status) { now }
    var service = WallyCalService(sdb, client, poller)

    fun newService() = WallyCalService(sdb, client, poller).also { service = it }

    suspend fun poll(at: Long = now) {
        now = at
        poller.run()
    }

    fun events(): List<Cal_event> = sdb.db.cal_eventQueries.selectAll().executeAsList().sortedBy { it.id }
    fun seq(): String? = sdb.db.kvQueries.get("seqCounter").executeAsOneOrNull()
    fun kv(key: String): String? = sdb.db.kvQueries.get(key).executeAsOneOrNull()

    suspend fun seedEvent(id: String, calendarId: String, eventId: String, updatedAt: Long = T0 - 1000, deleted: Long = 0) {
        val row = toSyncRow(Cal_event(id, updatedAt, deleted, 0, "SYNCED", calendarId, eventId, "Seed", T0, T0 + HOUR, 0, null))
        sdb.writeLock.withLock { sdb.db.transaction { store.writeIfNewer(row) } }
    }
}

private fun ApplicationTestBuilder.start(rig: Rig) {
    application { module(rig.config, rig.store, wallyCalStatus = rig.status, wallyCal = rig.service) }
}

private suspend fun HttpClient.createEvent(body: String, token: String? = TOKEN): HttpResponse = post("/v1/calendar/events") {
    if (token != null) header(HttpHeaders.Authorization, "Bearer $token")
    contentType(ContentType.Application.Json)
    setBody(body)
}

private fun minimal(key: String = "k1") = """{"clientKey":"$key","title":"Dentist","startAt":${T0 + HOUR},"endAt":${T0 + 2 * HOUR}}"""

class WallyCalTest {

    // ---- poller ----

    @Test
    fun occurrencesAreMappedWithStableIdsAndRequestShapeIsRight() = runBlocking<Unit> {
        val rig = Rig()
        rig.fake.eventsReply = 200 to envelope(
            occ("ab", "2026-11-09", location = "Clinic"),
            occ("ab", "2026-11-16"),
            occ("day", "2026-11-10", allDay = true),
        )
        rig.poll()
        val events = rig.events()
        assertEquals(listOf("wcal:ab:2026-11-09", "wcal:ab:2026-11-16", "wcal:day:2026-11-10"), events.map { it.id })
        val first = events[0]
        assertEquals("wallycal", first.calendarId)
        assertEquals("ab", first.eventId)
        assertEquals("Event ab", first.title)
        assertEquals(T0 + HOUR, first.startAt)
        assertEquals(T0 + 2 * HOUR, first.endAt)
        assertEquals(0L, first.allDay)
        assertEquals("Clinic", first.location)
        assertEquals(1L, events[2].allDay)
        assertTrue(events.all { it.deleted == 0L && it.syncState == "SYNCED" && it.serverSeq > 0 })
        assertEquals(true, rig.status.get())

        val req = rig.fake.requests.single()
        assertEquals("GET", req.method)
        assertEquals("/prefix/v1/events", req.path)
        assertEquals("Bearer $WALLY_TOKEN", req.auth)
        val today = LocalDate.parse("2026-11-08")
        assertEquals(today.minusDays(30).toString(), req.from)
        assertEquals(today.plusDays(365).toString(), req.to)

        // an identical second poll changes nothing (ids stable, no serverSeq bump)
        val seq = rig.seq()
        val seqs = rig.events().map { it.serverSeq }
        rig.poll(T0 + 15 * 60_000)
        assertEquals(seq, rig.seq())
        assertEquals(seqs, rig.events().map { it.serverSeq })
    }

    @Test
    fun editedOccurrenceIsRewrittenAndMissingOneIsSoftDeleted() = runBlocking<Unit> {
        val rig = Rig()
        rig.fake.eventsReply = 200 to envelope(occ("a", "2026-11-09"), occ("a", "2026-11-16"))
        rig.poll()
        val before = rig.events().associateBy { it.id }
        rig.fake.eventsReply = 200 to envelope(occ("a", "2026-11-09", title = "Renamed", start = T0 + 5 * HOUR, end = T0 + 6 * HOUR))
        rig.poll(T0 + 60_000)
        val after = rig.events().associateBy { it.id }
        assertEquals("Renamed", after.getValue("wcal:a:2026-11-09").title)
        assertEquals(T0 + 5 * HOUR, after.getValue("wcal:a:2026-11-09").startAt)
        assertTrue(after.getValue("wcal:a:2026-11-09").updatedAt > before.getValue("wcal:a:2026-11-09").updatedAt)
        val gone = after.getValue("wcal:a:2026-11-16")
        assertEquals(1L, gone.deleted)
        assertEquals("Event a", gone.title)
        assertTrue(gone.updatedAt > before.getValue("wcal:a:2026-11-16").updatedAt)
        assertTrue(gone.serverSeq > before.getValue("wcal:a:2026-11-16").serverSeq)
    }

    @Test
    fun aReappearingOccurrenceIsRevived() = runBlocking<Unit> {
        val rig = Rig()
        rig.fake.eventsReply = 200 to envelope(occ("a", "2026-11-09"))
        rig.poll()
        rig.fake.eventsReply = 200 to envelope()
        rig.poll(T0 + 60_000)
        assertEquals(1L, rig.events().single().deleted)
        rig.fake.eventsReply = 200 to envelope(occ("a", "2026-11-09"))
        rig.poll(T0 + 120_000)
        assertEquals(0L, rig.events().single().deleted)
    }

    @Test
    fun updatedAtNeverGoesBackwardsWhenTheStoredRowIsAheadOfTheClock() = runBlocking<Unit> {
        val rig = Rig()
        rig.seedEvent("wcal:a:2026-11-09", "wallycal", "a", updatedAt = T0 + 10 * HOUR)
        rig.fake.eventsReply = 200 to envelope(occ("a", "2026-11-09", title = "Changed"))
        rig.poll()
        val row = rig.events().single()
        assertEquals("Changed", row.title)
        assertEquals(T0 + 10 * HOUR + 1, row.updatedAt)
    }

    @Test
    fun emptyValidAnswerDeletesAllWallyCalRowsButOnlyThose() = runBlocking<Unit> {
        val rig = Rig()
        rig.seedEvent("cal:primary:g1", "primary", "g1")
        rig.fake.eventsReply = 200 to envelope(occ("a", "2026-11-09"), occ("b", "2026-11-10"))
        rig.poll()
        rig.fake.eventsReply = 200 to envelope()
        rig.poll(T0 + 60_000)
        val byId = rig.events().associateBy { it.id }
        assertEquals(1L, byId.getValue("wcal:a:2026-11-09").deleted)
        assertEquals(1L, byId.getValue("wcal:b:2026-11-10").deleted)
        assertEquals(0L, byId.getValue("cal:primary:g1").deleted)
        assertEquals(true, rig.status.get())
    }

    @Test
    fun brokenAnswersChangeNoRowsAndSetStatusFalse() = runBlocking<Unit> {
        val rig = Rig()
        rig.fake.eventsReply = 200 to envelope(occ("a", "2026-11-09"), occ("b", "2026-11-10"))
        rig.poll()
        val seq = rig.seq()
        val rows = rig.events()
        val bad = listOf(
            200 to envelope(occ("a", "2026-11-09"), """{"id":"x","occurrenceDate":"2026-11-10","title":"no times","allDay":false,"location":null}"""),
            200 to envelope(occ("", "2026-11-09")),
            200 to envelope(occ("c", "2026-02-30")),
            200 to envelope(occ("c", "2026-11-9")),
            200 to envelope(occ("c", "2026-11-09", start = T0 + 2 * HOUR, end = T0 + HOUR)),
            200 to envelope(occ("c", "2026-11-09").replace("\"allDay\":false", "\"allDay\":\"false\"")),
            200 to envelope(occ("c", "2026-11-09").replace("\"title\":\"Event c\"", "\"title\":5")),
            200 to envelope(occ("c", "2026-11-09").replace("\"location\":null", "\"location\":7")),
            200 to envelope(occ("c", "2026-11-09").replace("\"startAt\":${T0 + HOUR}", "\"startAt\":1.5")),
            200 to "not json at all",
            200 to """{"events":"nope"}""",
            200 to """["x"]""",
            401 to "",
            403 to "{}",
            500 to "{}",
        )
        for ((i, reply) in bad.withIndex()) {
            rig.fake.eventsReply = reply
            rig.poll(T0 + 60_000L * (i + 1))
            assertEquals(false, rig.status.get(), "case $i")
            assertEquals(seq, rig.seq(), "case $i")
            assertEquals(rows, rig.events(), "case $i")
        }
        rig.fake.eventsReply = 200 to envelope(occ("a", "2026-11-09"), occ("b", "2026-11-10"))
        rig.fake.failPaths = setOf("/prefix/v1/events")
        rig.poll(T0 + 3_600_000)
        assertEquals(false, rig.status.get())
        assertEquals(rows, rig.events())
        rig.fake.failPaths = emptySet()
        rig.poll(T0 + 3_700_000)
        assertEquals(true, rig.status.get())
    }

    @Test
    fun statusIsNullBeforeTheFirstPollAndNoConfigMeansNoPollerAndNoHttp() = testApplication {
        val rig = Rig(withWallyCal = false)
        assertNull(rig.status.get())
        application { module(rig.config, rig.store, wallyCalStatus = rig.status) }
        val health = client.get("/v1/health") { header(HttpHeaders.Authorization, "Bearer $TOKEN") }.bodyAsText()
        assertTrue(health.contains("\"calendarOk\":null"), health)
        val dream = client.get("/v1/calendar/dream-week") { header(HttpHeaders.Authorization, "Bearer $TOKEN") }
        assertEquals(HttpStatusCode.ServiceUnavailable, dream.status)
        assertEquals("""{"error":"calendar-not-configured"}""", dream.bodyAsText())
        val create = client.createEvent(minimal())
        assertEquals(HttpStatusCode.ServiceUnavailable, create.status)
        assertEquals("""{"error":"calendar-not-configured"}""", create.bodyAsText())
        assertTrue(rig.fake.requests.isEmpty())
    }

    @Test
    fun healthReportsCalendarOkFromTheStatus() = testApplication {
        val rig = Rig()
        start(rig)
        suspend fun health() = client.get("/v1/health") { header(HttpHeaders.Authorization, "Bearer $TOKEN") }.bodyAsText()
        assertTrue(health().contains("\"calendarOk\":null"))
        rig.status.set(false)
        assertTrue(health().contains("\"calendarOk\":false"))
        rig.status.set(true)
        assertTrue(health().contains("\"calendarOk\":true"))
        assertTrue(health().contains("\"googleOk\":null"))
    }

    // ---- start-up cleanup ----

    @Test
    fun cleanupTombstonesOldGoogleRowsOnceAndLeavesWallyCalRows() = runBlocking<Unit> {
        val rig = Rig()
        rig.seedEvent("cal:primary:g1", "primary", "g1")
        rig.seedEvent("cal:primary:g2", "primary", "g2")
        rig.seedEvent("wcal:a:2026-11-09", "wallycal", "a")
        val before = rig.store.pull(0).rows.associateBy { it.id }
        assertEquals(2, removeNonWallyCalEvents(rig.sdb, rig.store, T0))
        val byId = rig.events().associateBy { it.id }
        assertEquals(1L, byId.getValue("cal:primary:g1").deleted)
        assertEquals(1L, byId.getValue("cal:primary:g2").deleted)
        assertEquals(0L, byId.getValue("wcal:a:2026-11-09").deleted)
        assertEquals(before.getValue("wcal:a:2026-11-09").updatedAt, byId.getValue("wcal:a:2026-11-09").updatedAt)
        // tombstones reach clients through a normal pull
        val pulled = rig.store.pull(0).rows.filter { it.table == "cal_event" }.associateBy { it.id }
        assertTrue(pulled.getValue("cal:primary:g1").deleted)
        assertFalse(pulled.getValue("wcal:a:2026-11-09").deleted)
        // idempotent
        val seq = rig.seq()
        assertEquals(0, removeNonWallyCalEvents(rig.sdb, rig.store, T0 + 1000))
        assertEquals(seq, rig.seq())
    }

    // ---- dream week route ----

    @Test
    fun dreamWeekIsPassedThroughAndErrorsAreMapped() = testApplication {
        val rig = Rig()
        start(rig)
        suspend fun get(token: String = TOKEN) = client.get("/v1/calendar/dream-week") { header(HttpHeaders.Authorization, "Bearer $token") }
        val body = """{"sentAt":5,"blocks":[{"id":"b1","title":"Deep","dayOfWeek":1,"startMinute":540,"endMinute":660,"color":null,"note":null}]}"""
        rig.fake.dreamReply = 200 to body
        val ok = get()
        assertEquals(HttpStatusCode.OK, ok.status)
        assertEquals(body, ok.bodyAsText())
        assertTrue(ok.headers[HttpHeaders.ContentType]!!.startsWith("application/json"))
        assertEquals("Bearer $WALLY_TOKEN", rig.fake.requests.last().auth)

        rig.fake.dreamReply = 404 to """{"error":"not-sent"}"""
        val notSent = get()
        assertEquals(HttpStatusCode.NotFound, notSent.status)
        assertEquals("""{"error":"not-sent"}""", notSent.bodyAsText())

        for (reply in listOf(404 to """{"error":"not-found"}""", 401 to "", 500 to "{}")) {
            rig.fake.dreamReply = reply
            val r = get()
            assertEquals(HttpStatusCode.BadGateway, r.status)
            assertEquals("""{"error":"calendar-unreachable"}""", r.bodyAsText())
        }
        rig.fake.failPaths = setOf("/prefix/v1/dream-week")
        assertEquals(HttpStatusCode.BadGateway, get().status)
    }

    // ---- create route ----

    @Test
    fun sameClientKeyTwiceForwardsOnce() = testApplication {
        val rig = Rig()
        start(rig)
        val first = client.createEvent(minimal("same"))
        assertEquals(HttpStatusCode.Created, first.status)
        assertEquals("""{"id":"ev-1"}""", first.bodyAsText())
        val second = client.createEvent(minimal("same"))
        assertEquals(HttpStatusCode.OK, second.status)
        assertEquals("""{"id":"ev-1"}""", second.bodyAsText())
        assertEquals(1, rig.fake.posts.size)
        assertEquals("ev-1", rig.kv("calcreate:same"))
        // a different key is a different event
        assertEquals("""{"id":"ev-2"}""", client.createEvent(minimal("other")).bodyAsText())
        assertEquals(2, rig.fake.posts.size)
    }

    @Test
    fun concurrentRequestsWithTheSameKeyForwardOnce() = testApplication {
        val rig = Rig()
        start(rig)
        val gate = CompletableDeferred<Unit>()
        rig.fake.createGate = gate
        val responses = coroutineScope {
            val a = async { client.createEvent(minimal("race")) }
            val b = async { client.createEvent(minimal("race")) }
            delay(300)
            gate.complete(Unit)
            listOf(a, b).awaitAll()
        }
        assertEquals(1, rig.fake.posts.size)
        assertEquals(listOf(200, 201), responses.map { it.status.value }.sorted())
        assertEquals(setOf("""{"id":"ev-1"}"""), responses.map { it.bodyAsText() }.toSet())
    }

    @Test
    fun replayAfterANewServiceInstanceDoesNotForwardAgain() = testApplication {
        val rig = Rig()
        start(rig)
        assertEquals(HttpStatusCode.Created, client.createEvent(minimal("persist")).status)
        assertEquals(1, rig.fake.posts.size)
        // a new service over the same database, as after a server restart
        val service = rig.newService()
        val outcome = service.createEvent("persist", JsonObject(emptyMap()))
        assertEquals(CreateOutcome.Created("ev-1", fresh = false), outcome)
        assertEquals(1, rig.fake.posts.size)
    }

    @Test
    fun aFailingPollAfterCreateStillAnswers201AndTheReplayIs200() = testApplication {
        val rig = Rig()
        start(rig)
        rig.fake.eventsReply = 500 to "{}"
        val first = client.createEvent(minimal("pf"))
        assertEquals(HttpStatusCode.Created, first.status)
        assertEquals("""{"id":"ev-1"}""", first.bodyAsText())
        assertEquals(false, rig.status.get())
        val second = client.createEvent(minimal("pf"))
        assertEquals(HttpStatusCode.OK, second.status)
        assertEquals(1, rig.fake.posts.size)
    }

    @Test
    fun aSuccessfulCreatePollsAtOnceAndMirrorsTheEvent() = testApplication {
        val rig = Rig()
        start(rig)
        rig.fake.eventsReply = 200 to envelope(occ("ev-1", "2026-11-09", title = "Dentist"))
        assertEquals(HttpStatusCode.Created, client.createEvent(minimal("mirror")).status)
        assertEquals(listOf("wcal:ev-1:2026-11-09"), rig.events().map { it.id })
        assertEquals(true, rig.status.get())
    }

    @Test
    fun forwardedBodyHasExactlyTheTenKeysWithDefaults() = testApplication {
        val rig = Rig()
        start(rig)
        assertEquals(HttpStatusCode.Created, client.createEvent(minimal("full")).status)
        val sent = json.parseToJsonElement(rig.fake.posts.single().body).jsonObject
        assertEquals(
            setOf("title", "startAt", "endAt", "allDay", "location", "note", "color", "repeatRule", "repeatUntil", "reminderMinutes"),
            sent.keys,
        )
        assertEquals(JsonPrimitive("Dentist"), sent["title"])
        assertEquals(JsonPrimitive(T0 + HOUR), sent["startAt"])
        assertEquals(JsonPrimitive(T0 + 2 * HOUR), sent["endAt"])
        assertEquals(JsonPrimitive(false), sent["allDay"])
        assertEquals(JsonPrimitive("BLUE"), sent["color"])
        assertEquals(JsonPrimitive("NONE"), sent["repeatRule"])
        for (k in listOf("location", "note", "repeatUntil", "reminderMinutes")) assertEquals(JsonNull, sent[k], k)
        assertEquals("Bearer $WALLY_TOKEN", rig.fake.posts.single().auth)
        assertEquals("/prefix/v1/events", rig.fake.posts.single().path)
    }

    @Test
    fun explicitValuesPassThroughEvenWhenInvalidAndExtraKeysAreDropped() = testApplication {
        val rig = Rig()
        start(rig)
        val body = """{"clientKey":"x1","title":"T","startAt":1,"endAt":2,"allDay":true,"color":"PINK","repeatRule":"WEEKLY",""" +
            """"repeatUntil":"2027-01-01","reminderMinutes":30,"location":null,"note":"n","id":"evil","source":"GOOGLE","foo":1}"""
        client.createEvent(body)
        val sent = json.parseToJsonElement(rig.fake.posts.single().body).jsonObject
        assertEquals(10, sent.size)
        assertEquals(JsonPrimitive("PINK"), sent["color"])
        assertEquals(JsonPrimitive(true), sent["allDay"])
        assertEquals(JsonPrimitive("WEEKLY"), sent["repeatRule"])
        assertEquals(JsonPrimitive(30), sent["reminderMinutes"])
        assertEquals(JsonPrimitive("n"), sent["note"])
        assertFalse("clientKey" in sent || "id" in sent || "source" in sent || "foo" in sent)
    }

    @Test
    fun explicitNullOnAKeyWithADefaultStaysNull() = testApplication {
        val rig = Rig()
        start(rig)
        client.createEvent("""{"clientKey":"n1","title":"T","startAt":1,"endAt":2,"color":null,"allDay":null,"repeatRule":null}""")
        val sent = json.parseToJsonElement(rig.fake.posts.single().body).jsonObject
        assertEquals(JsonNull, sent["color"])
        assertEquals(JsonNull, sent["allDay"])
        assertEquals(JsonNull, sent["repeatRule"])
    }

    @Test
    fun wallyCalendar400IsPassedThroughAndNothingIsStored() = testApplication {
        val rig = Rig()
        start(rig)
        rig.fake.createReply = { 400 to """{"error":"invalid: color"}""" }
        val r = client.createEvent("""{"clientKey":"bad","title":"T","startAt":1,"endAt":2,"color":"PINK"}""")
        assertEquals(HttpStatusCode.BadRequest, r.status)
        assertEquals("""{"error":"invalid: color"}""", r.bodyAsText())
        assertNull(rig.kv("calcreate:bad"))
        client.createEvent("""{"clientKey":"bad","title":"T","startAt":1,"endAt":2,"color":"PINK"}""")
        assertEquals(2, rig.fake.posts.size)
    }

    @Test
    fun aLostReplyThenARetryDoesNotPostAgain() = testApplication {
        val rig = Rig()
        start(rig)
        rig.fake.failPaths = setOf("/prefix/v1/events") // plain IOException: the request may have been sent
        assertEquals(HttpStatusCode.BadGateway, client.createEvent(minimal("lost")).status)
        assertEquals(1, rig.fake.posts.size)
        assertEquals("1", rig.kv("calattempt:lost"))
        rig.fake.failPaths = emptySet()
        val retry = client.createEvent(minimal("lost"))
        assertEquals(HttpStatusCode.BadGateway, retry.status)
        assertEquals("""{"error":"calendar-unreachable"}""", retry.bodyAsText())
        assertEquals(1, rig.fake.posts.size)
        assertNull(rig.kv("calcreate:lost"))
        // another key is not blocked
        assertEquals(HttpStatusCode.Created, client.createEvent(minimal("fresh")).status)
    }

    @Test
    fun anUnreadableOrErrorReplyKeepsTheAttempt() = testApplication {
        val rig = Rig()
        start(rig)
        for ((i, reply) in listOf(500 to "{}", 201 to "garbage", 201 to """{"id":""}""").withIndex()) {
            rig.fake.createReply = { reply }
            assertEquals(HttpStatusCode.BadGateway, client.createEvent(minimal("k$i")).status, "case $i")
            assertEquals("1", rig.kv("calattempt:k$i"), "case $i")
            assertEquals(HttpStatusCode.BadGateway, client.createEvent(minimal("k$i")).status, "retry $i")
        }
        assertEquals(3, rig.fake.posts.size)
    }

    @Test
    fun theFenceSurvivesAServerRestart() = testApplication {
        val rig = Rig()
        start(rig)
        rig.fake.failPaths = setOf("/prefix/v1/events")
        client.createEvent(minimal("restart"))
        rig.fake.failPaths = emptySet()
        val service = rig.newService()
        assertEquals(CreateOutcome.Unreachable, service.createEvent("restart", JsonObject(emptyMap())))
        assertEquals(1, rig.fake.posts.size)
    }

    @Test
    fun aSuccessStoresTheIdAndRemovesTheAttempt() = testApplication {
        val rig = Rig()
        start(rig)
        assertEquals(HttpStatusCode.Created, client.createEvent(minimal("ok")).status)
        assertEquals("ev-1", rig.kv("calcreate:ok"))
        assertNull(rig.kv("calattempt:ok"))
    }

    @Test
    fun aDefinite400ClearsTheAttempt() = testApplication {
        val rig = Rig()
        start(rig)
        rig.fake.createReply = { 400 to """{"error":"invalid: color"}""" }
        assertEquals(HttpStatusCode.BadRequest, client.createEvent(minimal("b400")).status)
        assertNull(rig.kv("calattempt:b400"))
    }

    @Test
    fun connectionRefusedAndUnknownHostClearTheAttempt() = testApplication {
        val rig = Rig()
        start(rig)
        rig.fake.failPaths = setOf("/prefix/v1/events")
        for ((i, failure) in listOf<Exception>(
            java.net.ConnectException("Connection refused"),
            java.net.UnknownHostException("hp.example"),
            IOException("wrapped", java.net.ConnectException("Connection refused: no further information")),
        ).withIndex()) {
            rig.fake.failure = failure
            assertEquals(HttpStatusCode.BadGateway, client.createEvent(minimal("c$i")).status, "case $i")
            assertNull(rig.kv("calattempt:c$i"), "case $i")
        }
        // the same key can be tried again, and succeeds once the network is back
        rig.fake.failPaths = emptySet()
        assertEquals(HttpStatusCode.Created, client.createEvent(minimal("c0")).status)
        assertEquals(4, rig.fake.posts.size)
    }

    @Test
    fun upstreamFailuresGive502AndStoreNothing() = testApplication {
        val rig = Rig()
        start(rig)
        for ((i, reply) in listOf(500 to "{}", 401 to "", 403 to "{}", 201 to "{}", 201 to """{"id":""}""", 201 to "garbage").withIndex()) {
            rig.fake.createReply = { reply }
            val r = client.createEvent(minimal("u$i"))
            assertEquals(HttpStatusCode.BadGateway, r.status, "case $i")
            assertEquals("""{"error":"calendar-unreachable"}""", r.bodyAsText(), "case $i")
            assertNull(rig.kv("calcreate:u$i"), "case $i")
        }
        rig.fake.failPaths = setOf("/prefix/v1/events")
        val r = client.createEvent(minimal("net"))
        assertEquals(HttpStatusCode.BadGateway, r.status)
        assertNull(rig.kv("calcreate:net"))
    }

    @Test
    fun badRequestBodiesAreRefusedWithoutForwarding() = testApplication {
        val rig = Rig()
        start(rig)
        for (body in listOf("""["x"]""", "not json", "5", "")) {
            val r = client.createEvent(body)
            assertEquals(HttpStatusCode.BadRequest, r.status, body)
            assertEquals("""{"error":"invalid: body"}""", r.bodyAsText(), body)
        }
        val keys = listOf(
            """{"title":"T"}""",
            """{"clientKey":"","title":"T"}""",
            """{"clientKey":5,"title":"T"}""",
            """{"clientKey":"${"k".repeat(201)}","title":"T"}""",
        )
        for (body in keys) {
            val r = client.createEvent(body)
            assertEquals(HttpStatusCode.BadRequest, r.status, body)
            assertEquals("""{"error":"invalid: clientKey"}""", r.bodyAsText(), body)
        }
        assertEquals(HttpStatusCode.Created, client.createEvent("""{"clientKey":"${"k".repeat(200)}","title":"T","startAt":1,"endAt":2}""").status)
        assertEquals(1, rig.fake.posts.size)
    }

    @Test
    fun theInboundCalendarTokenIsForbiddenAndNoTokenIs401() = testApplication {
        val rig = Rig()
        start(rig)
        val get = client.get("/v1/calendar/dream-week") { header(HttpHeaders.Authorization, "Bearer $CAL_TOKEN") }
        assertEquals(HttpStatusCode.Forbidden, get.status)
        assertEquals(HttpStatusCode.Forbidden, client.createEvent(minimal("c"), token = CAL_TOKEN).status)
        assertEquals(HttpStatusCode.Unauthorized, client.createEvent(minimal("c"), token = null).status)
        assertEquals(HttpStatusCode.Unauthorized, client.get("/v1/calendar/dream-week").status)
        assertTrue(rig.fake.requests.isEmpty())
    }

    // ---- config and CLI ----

    @Test
    fun oldConfigWithCalendarIdsStillLoadsAndWallyCalUrlIsNormalized() {
        val c = parseConfig(
            """{"bindAddress":"1.2.3.4","dbPath":"/x.db","tokens":{"laptop":"a","calendar":"b"},
            "google":{"clientId":"i","clientSecret":"s","calendarIds":["primary","x"]},
            "wallycal":{"url":"  http://100.1.2.3:8081/ ","token":"t"}}""",
        )
        assertEquals("i", c.google?.clientId)
        assertEquals("http://100.1.2.3:8081", c.wallycal?.url)
        assertEquals("t", c.wallycal?.token)
        assertNull(parseConfig("""{"bindAddress":"1","dbPath":"/x","tokens":{}}""").wallycal)
    }

    @Test
    fun googleCalendarsCommandIsRejectedWithExit2() {
        val java = System.getProperty("java.home") + "/bin/java"
        val process = ProcessBuilder(java, "-cp", System.getProperty("java.class.path"), "dev.tim.assistant.server.MainKt", "google-calendars")
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().readText()
        assertEquals(2, process.waitFor())
        assertTrue(output.contains("usage: server [google-auth | spotify-auth]"), output)
        assertFalse(output.contains("google-calendars |"))
    }
}
