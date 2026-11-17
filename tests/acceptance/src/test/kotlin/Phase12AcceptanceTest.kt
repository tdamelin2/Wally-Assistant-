import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import dev.tim.assistant.data.LocalStore
import dev.tim.assistant.data.MailActionDraft
import dev.tim.assistant.data.Repository
import dev.tim.assistant.data.SettingKeys
import dev.tim.assistant.data.SyncClient
import dev.tim.assistant.data.SyncEngine
import dev.tim.assistant.db.AssistantDb
import dev.tim.assistant.db.Mail_draft
import dev.tim.assistant.db.Mail_item
import dev.tim.assistant.hardware.hardwareSections
import dev.tim.assistant.hardware.hardwareSummary
import dev.tim.assistant.hardware.hwExplainText
import dev.tim.assistant.hardware.hwTakenAt
import dev.tim.assistant.mail.actionCanDelete
import dev.tim.assistant.mail.canSendDraft
import dev.tim.assistant.mail.draftCanDelete
import dev.tim.assistant.mail.draftIsEditable
import dev.tim.assistant.mail.draftStatusLine
import dev.tim.assistant.mail.extractAddress
import dev.tim.assistant.mail.isValidRecipient
import dev.tim.assistant.mail.parseTriage
import dev.tim.assistant.mail.replySubject
import dev.tim.assistant.mail.startsNewConversation
import dev.tim.assistant.model.MailLabel
import dev.tim.assistant.model.PullResponse
import dev.tim.assistant.model.PushRequest
import dev.tim.assistant.model.PushResponse
import dev.tim.assistant.model.SyncRow
import dev.tim.assistant.model.toSyncRow
import dev.tim.assistant.server.Config
import dev.tim.assistant.server.GoogleConfig
import dev.tim.assistant.server.ServerDb
import dev.tim.assistant.server.SyncStore
import dev.tim.assistant.server.google.GmailWrite
import dev.tim.assistant.server.google.GoogleApi
import dev.tim.assistant.server.google.GoogleAuth
import dev.tim.assistant.server.google.writeTokenFile
import dev.tim.assistant.server.module
import dev.tim.assistant.server.openDb
import dev.tim.assistant.ui.NavTab
import dev.tim.assistant.ui.addActionFor
import dev.tim.assistant.ui.hubChips
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.client.statement.readRawBytes
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.content.TextContent
import io.ktor.http.contentType
import io.ktor.http.fullPath
import io.ktor.http.headersOf
import io.ktor.server.testing.testApplication
import java.io.File
import java.nio.file.Files
import java.time.Instant
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

private const val OAUTH_OK = """{"access_token":"at-1","expires_in":3600,"token_type":"Bearer"}"""
private val P12_T0: Long = Instant.parse("2026-11-10T10:00:00Z").toEpochMilli()
private val p12Json = Json { ignoreUnknownKeys = true }

private fun p12Root(): File {
    var d: File? = File(".").absoluteFile
    while (d != null && !File(d, "settings.gradle.kts").exists()) d = d.parentFile
    return d ?: error("repo root not found")
}

private fun p12Text(rel: String) = File(p12Root(), rel).readText()

private fun p12Obj(vararg pairs: Pair<String, Any?>): JsonObject = JsonObject(
    pairs.associate { (k, v) ->
        k to when (v) {
            null -> JsonNull
            is JsonElement -> v
            is Boolean -> JsonPrimitive(v)
            is Number -> JsonPrimitive(v)
            else -> JsonPrimitive(v.toString())
        }
    },
)

private fun b64url(text: String) = Base64.getUrlEncoder().withoutPadding().encodeToString(text.toByteArray(Charsets.UTF_8))

/** A scripted Gmail that records every call. */
private class P12Gmail {
    val requests = ArrayList<HttpRequestData>()
    val bodies = ArrayList<String>()
    var sendReply: suspend () -> Pair<Int, String> = { 200 to """{"id":"sent-1","threadId":"thr-new"}""" }
    var labelsBody = """{"labels":[{"id":"INBOX","name":"INBOX","type":"system"},{"id":"Label_1","name":"Bills","type":"user"}]}"""
    var fullBody = """{"id":"g9","internalDate":"1700000000000","payload":{"mimeType":"text/plain","headers":[""" +
        """{"name":"From","value":"News <news@example.com>"},{"name":"Reply-To","value":"Help Desk <help@example.com>"},""" +
        """{"name":"To","value":"tim@example.com"},{"name":"Subject","value":"Hello"}],"body":{"data":"${b64url("Hello body")}"}}}"""
    var original = """{"id":"orig1","threadId":"thr-orig","payload":{"headers":[""" +
        """{"name":"Message-ID","value":"<orig@mail.example>"},{"name":"Subject","value":"Hello there"},""" +
        """{"name":"From","value":"Ann <ann@example.com>"}]}}"""

    val sends get() = requests.indices.filter { requests[it].url.encodedPath.endsWith("/messages/send") }.map { bodies[it] }
    val modifies get() = requests.indices.filter { requests[it].url.encodedPath.endsWith("/modify") }.map { bodies[it] }

    fun engine() = MockEngine { request ->
        requests += request
        bodies += when (val b = request.body) {
            is TextContent -> b.text
            is OutgoingContent.ByteArrayContent -> String(b.bytes(), Charsets.UTF_8)
            else -> ""
        }
        val path = request.url.encodedPath
        val (status, body) = when {
            request.url.host == "oauth2.googleapis.com" -> 200 to OAUTH_OK
            path.endsWith("/messages/send") -> sendReply()
            path.endsWith("/modify") -> 200 to """{"id":"x"}"""
            path.endsWith("/labels") -> 200 to labelsBody
            path.contains("/messages/") && request.url.parameters["format"] == "full" -> 200 to fullBody
            path.contains("/messages/") -> 200 to original
            else -> 404 to "{}"
        }
        respond(body, HttpStatusCode.fromValue(status), headersOf(HttpHeaders.ContentType, "application/json"))
    }
}

private class P12Rig(val gmail: P12Gmail = P12Gmail(), callTimeoutMs: Long = 2_000) {
    var now: Long = P12_T0
    val sdb: ServerDb = openDb(Files.createTempDirectory("p12").resolve("t.db").toString())
    val store = SyncStore(sdb, { now })
    private val tokenFile = Files.createTempDirectory("p12tok").resolve("google-token.json").toFile()
    private val client = HttpClient(gmail.engine())
    private val auth = GoogleAuth(GoogleConfig("cid", "secret"), tokenFile, client, { now })
    val worker = GmailWrite(sdb, store, client, auth, { now }, callTimeoutMs)
    val api = GoogleApi(client, auth)

    init {
        writeTokenFile(tokenFile, "refresh-1", P12_T0)
    }

    fun draft(id: String) = sdb.db.mail_draftQueries.selectById(id).executeAsOne()
    fun action(id: String) = sdb.db.mail_actionQueries.selectById(id).executeAsOne()

    suspend fun push(vararg rows: SyncRow, calendar: Boolean = false): PushResponse = store.push(rows.toList(), calendar)

    fun seedMail(gmailId: String) {
        sdb.db.mail_itemQueries.upsert(Mail_item("mail:$gmailId", P12_T0 - 1000, 0, 1, "SYNCED", gmailId, "s", "subj", "snip", P12_T0 - 5000))
    }

    fun config(withCalendar: Boolean = true) = Config(
        bindAddress = "127.0.0.1", dbPath = "x",
        tokens = if (withCalendar) mapOf("laptop" to "lap-token", "calendar" to "cal-token") else mapOf("laptop" to "lap-token"),
        google = GoogleConfig("cid", "secret"),
    )
}

private fun draftRow(
    id: String,
    status: String = "APPROVED",
    updatedAt: Long = P12_T0,
    to: String = "ann@example.com",
    subject: String = "Hello there",
    body: String = "Hi Ann",
    replyTo: String? = null,
    deleted: Boolean = false,
) = SyncRow(
    "mail_draft", id, updatedAt, deleted,
    p12Obj(
        "toAddr" to to, "subject" to subject, "body" to body, "replyToGmailId" to replyTo, "threadId" to null,
        "status" to status, "sentAt" to null, "error" to null,
    ),
)

private fun actionRow(id: String, action: String, gmailId: String, labelId: String? = null, status: String = "APPROVED") = SyncRow(
    "mail_action", id, P12_T0, false,
    p12Obj("gmailId" to gmailId, "action" to action, "labelId" to labelId, "labelName" to labelId, "reason" to "because", "status" to status),
)

private fun rawMime(sendBody: String): String {
    val j = p12Json.parseToJsonElement(sendBody).jsonObject
    return String(Base64.getUrlDecoder().decode(j["raw"]!!.jsonPrimitive.content), Charsets.UTF_8)
}

class Phase12AcceptanceTest {

    // ------------------------------------------------------------ schema (AC-6/7 support)

    @Test
    fun test_AC6_schema_is_version_6_with_28_tables_including_the_three_new_ones() {
        val d = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        AssistantDb.Schema.create(d)
        assertEquals(8L, AssistantDb.Schema.version)
        val names = ArrayList<String>()
        d.executeQuery(
            null, "SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%'", { c ->
                while (c.next().value) names += c.getString(0)!!
                app.cash.sqldelight.db.QueryResult.Unit
            }, 0,
        )
        assertEquals(34, names.size, "tables: $names")
        for (n in listOf("mail_draft", "mail_action", "hw_check")) assertTrue(n in names, "table $n")
    }

    // ------------------------------------------------------------ AC-2: server

    @Test
    fun test_AC2_two_APPROVED_pushes_give_exactly_one_gmail_send() = runBlocking<Unit> {
        val rig = P12Rig()
        rig.push(draftRow("maildraft:a"))
        rig.push(draftRow("maildraft:a", updatedAt = P12_T0 + 1))
        rig.worker.sweep()
        rig.worker.sweep()
        assertEquals(1, rig.gmail.sends.size)
        assertEquals("SENT", rig.draft("maildraft:a").status)
    }

    @Test
    fun test_AC2_a_DRAFT_is_never_sent() = runBlocking<Unit> {
        val rig = P12Rig()
        rig.push(draftRow("maildraft:d", status = "DRAFT"))
        rig.worker.sweep()
        assertEquals(0, rig.gmail.sends.size)
        assertEquals("DRAFT", rig.draft("maildraft:d").status)
    }

    @Test
    fun test_AC2_a_draft_pushed_one_hour_ahead_still_moves_to_SENT() = runBlocking<Unit> {
        val rig = P12Rig()
        rig.push(draftRow("maildraft:f", updatedAt = P12_T0 + 3_600_000))
        rig.worker.sweep()
        assertEquals(1, rig.gmail.sends.size)
        assertEquals("SENT", rig.draft("maildraft:f").status)
        assertTrue(rig.draft("maildraft:f").updatedAt > P12_T0 + 3_600_000)
    }

    @Test
    fun test_AC2_hebrew_subject_is_rfc2047_encoded_and_body_is_utf8_base64() = runBlocking<Unit> {
        val rig = P12Rig()
        rig.push(draftRow("maildraft:h", subject = "שלום עולם", body = "תודה רבה"))
        rig.worker.sweep()
        val mime = rawMime(rig.gmail.sends.single())
        assertTrue(mime.contains("\r\n"), "CRLF line ends")
        assertTrue(Regex("(?im)^Subject: =\\?UTF-8\\?B\\?").containsMatchIn(mime), mime)
        assertTrue(mime.contains("charset=UTF-8", ignoreCase = true))
        assertTrue(mime.lowercase().contains("to: ann@example.com"))
    }

    @Test
    fun test_AC2_reply_carries_in_reply_to_references_and_thread_id() = runBlocking<Unit> {
        val rig = P12Rig()
        rig.push(draftRow("maildraft:r", subject = "Re: Hello there", replyTo = "orig1"))
        rig.worker.sweep()
        val sent = rig.gmail.sends.single()
        val mime = rawMime(sent)
        assertTrue(mime.contains("In-Reply-To: <orig@mail.example>"), mime)
        assertTrue(mime.contains("References:"), mime)
        assertEquals("thr-orig", p12Json.parseToJsonElement(sent).jsonObject["threadId"]!!.jsonPrimitive.content)
    }

    @Test
    fun test_AC2_changed_subject_reply_goes_out_as_a_new_conversation() = runBlocking<Unit> {
        val rig = P12Rig()
        rig.push(draftRow("maildraft:n", subject = "Something else", replyTo = "orig1"))
        rig.worker.sweep()
        val sent = rig.gmail.sends.single()
        assertFalse(rawMime(sent).contains("In-Reply-To"))
        assertNull(p12Json.parseToJsonElement(sent).jsonObject["threadId"]?.takeIf { it !is JsonNull })
    }

    @Test
    fun test_AC2_gmail_refusal_gives_FAILED_with_an_error_text() = runBlocking<Unit> {
        val rig = P12Rig()
        rig.gmail.sendReply = { 400 to """{"error":{"code":400,"message":"Invalid To header"}}""" }
        rig.push(draftRow("maildraft:x"))
        rig.worker.sweep()
        val d = rig.draft("maildraft:x")
        assertEquals("FAILED", d.status)
        assertTrue(!d.error.isNullOrBlank())
        assertEquals(1, rig.gmail.sends.size, "never retried")
    }

    @Test
    fun test_AC2_timeout_gives_UNKNOWN_and_is_not_retried() = runBlocking<Unit> {
        val gm = P12Gmail()
        gm.sendReply = { delay(5_000); 200 to """{"id":"late"}""" }
        val rig = P12Rig(gm, callTimeoutMs = 300)
        rig.push(draftRow("maildraft:t"))
        rig.worker.sweep()
        assertEquals("UNKNOWN", rig.draft("maildraft:t").status)
        rig.worker.sweep()
        assertEquals(1, gm.sends.size)
    }

    @Test
    fun test_AC2_SENDING_becomes_UNKNOWN_on_restart_and_is_not_sent_again() = runBlocking<Unit> {
        val rig = P12Rig()
        rig.sdb.db.mail_draftQueries.upsert(
            Mail_draft("maildraft:s", P12_T0, 0, 1, "SYNCED", "ann@example.com", "S", "B", null, null, "SENDING", null, null),
        )
        rig.worker.recover()
        rig.worker.sweep()
        assertEquals("UNKNOWN", rig.draft("maildraft:s").status)
        assertEquals(0, rig.gmail.sends.size)
    }

    @Test
    fun test_AC2_APPROVED_draft_left_over_from_before_a_restart_is_sent_once() = runBlocking<Unit> {
        val rig = P12Rig()
        rig.sdb.db.mail_draftQueries.upsert(
            Mail_draft("maildraft:p", P12_T0, 0, 1, "SYNCED", "ann@example.com", "S", "B", null, null, "APPROVED", null, null),
        )
        rig.worker.recover()
        rig.worker.sweep()
        assertEquals("SENT", rig.draft("maildraft:p").status)
        assertEquals(1, rig.gmail.sends.size)
    }

    @Test
    fun test_AC2_client_push_of_SENT_SENDING_or_UNKNOWN_is_refused() = runBlocking<Unit> {
        val rig = P12Rig()
        for (s in listOf("SENT", "SENDING", "UNKNOWN", "FAILED")) {
            val r = rig.push(draftRow("maildraft:$s", status = s))
            assertTrue(r.accepted.isEmpty(), "status $s accepted")
            assertEquals(listOf("maildraft:$s"), r.rejected.map { it.id })
        }
        for (s in listOf("DONE", "FAILED")) {
            val r = rig.push(actionRow("mailaction:z:$s", "ARCHIVE", "g1", status = s))
            assertTrue(r.accepted.isEmpty(), "action status $s accepted")
        }
    }

    @Test
    fun test_AC2_refused_edit_of_a_SENT_draft_restamps_the_stored_row_newer_than_the_push() = runBlocking<Unit> {
        val rig = P12Rig()
        rig.push(draftRow("maildraft:e"))
        rig.worker.sweep()
        assertEquals("SENT", rig.draft("maildraft:e").status)
        val pushedAt = rig.draft("maildraft:e").updatedAt + 5
        val r = rig.push(draftRow("maildraft:e", updatedAt = pushedAt, body = "tampered"))
        assertEquals(listOf("maildraft:e"), r.rejected.map { it.id })
        val pulled = rig.store.pull(0).rows.single { it.id == "maildraft:e" }
        assertTrue(pulled.updatedAt > pushedAt, "pulled ${pulled.updatedAt} vs pushed $pushedAt")
        assertEquals("SENT", pulled.data["status"]!!.jsonPrimitive.content)
        assertEquals("Hi Ann", pulled.data["body"]!!.jsonPrimitive.content)
    }

    @Test
    fun test_AC2_pure_delete_of_a_SENT_draft_is_accepted_but_an_edit_is_not() = runBlocking<Unit> {
        val rig = P12Rig()
        rig.push(draftRow("maildraft:del"))
        rig.worker.sweep()
        val stored = toSyncRow(rig.draft("maildraft:del"))
        val edit = rig.push(stored.copy(updatedAt = stored.updatedAt + 10, deleted = true, data = JsonObject(stored.data + ("body" to JsonPrimitive("x")))))
        assertEquals(1, edit.rejected.size)
        assertEquals(0L, rig.draft("maildraft:del").deleted)
        val storedAgain = toSyncRow(rig.draft("maildraft:del"))
        val del = rig.push(storedAgain.copy(updatedAt = storedAgain.updatedAt + 10, deleted = true))
        assertEquals(listOf("maildraft:del"), del.accepted, "rejected: ${del.rejected}")
        assertEquals(1L, rig.draft("maildraft:del").deleted)
    }

    @Test
    fun test_AC2_label_action_sends_the_label_in_a_modify_body() = runBlocking<Unit> {
        val rig = P12Rig()
        rig.push(actionRow("mailaction:j:0", "LABEL", "g1", labelId = "Label_1"))
        rig.worker.sweep()
        assertEquals("DONE", rig.action("mailaction:j:0").status)
        val body = p12Json.parseToJsonElement(rig.gmail.modifies.single()).jsonObject
        assertTrue(body.toString().contains("addLabelIds") && body.toString().contains("Label_1"), body.toString())
    }

    @Test
    fun test_AC2_archive_removes_INBOX_and_soft_deletes_the_mail_item() = runBlocking<Unit> {
        val rig = P12Rig()
        rig.seedMail("g7")
        rig.push(actionRow("mailaction:k:0", "ARCHIVE", "g7"))
        rig.worker.sweep()
        assertEquals("DONE", rig.action("mailaction:k:0").status)
        val body = rig.gmail.modifies.single()
        assertTrue(body.contains("removeLabelIds") && body.contains("INBOX"), body)
        assertEquals(1L, rig.sdb.db.mail_itemQueries.selectById("mail:g7").executeAsOne().deleted)
    }

    // ------------------------------------------------------------ AC-2: routes and tokens

    @Test
    fun test_AC2_calendar_token_gets_403_on_mail_routes() = testApplication {
        val rig = P12Rig()
        application { module(rig.config(), rig.store, googleApi = rig.api) }
        val http = createClient { }
        assertEquals(HttpStatusCode.Forbidden, http.get("/v1/mail/labels") { header(HttpHeaders.Authorization, "Bearer cal-token") }.status)
        assertEquals(HttpStatusCode.Forbidden, http.get("/v1/mail/g9/body") { header(HttpHeaders.Authorization, "Bearer cal-token") }.status)
        assertEquals(HttpStatusCode.OK, http.get("/v1/mail/labels") { header(HttpHeaders.Authorization, "Bearer lap-token") }.status)
    }

    @Test
    fun test_AC2_calendar_token_mail_rows_are_forbidden_with_no_send_but_other_rows_work() = testApplication {
        val rig = P12Rig()
        application { module(rig.config(), rig.store, googleApi = rig.api) }
        val http = createClient { }
        val task = SyncRow("task", "task:1", P12_T0, false, p12Obj("title" to "Make Wally task", "dueDate" to null, "done" to false, "doneAt" to null, "goalId" to null))
        val body = p12Json.encodeToString(PushRequest.serializer(), PushRequest(listOf(draftRow("maildraft:c"), actionRow("mailaction:c:0", "ARCHIVE", "g1"), task)))
        val resp = http.post("/v1/sync/push") {
            header(HttpHeaders.Authorization, "Bearer cal-token"); contentType(ContentType.Application.Json); setBody(body)
        }
        val parsed = p12Json.decodeFromString(PushResponse.serializer(), resp.bodyAsText())
        assertEquals(setOf("maildraft:c", "mailaction:c:0"), parsed.rejected.map { it.id }.toSet())
        assertTrue(parsed.rejected.all { it.reason == "forbidden" })
        assertEquals(listOf("task:1"), parsed.accepted)
        rig.worker.sweep()
        assertEquals(0, rig.gmail.sends.size)
        assertEquals(0, rig.gmail.modifies.size)
    }

    @Test
    fun test_AC2_body_route_returns_reply_to_in_from_and_the_text() = testApplication {
        val rig = P12Rig()
        application { module(rig.config(), rig.store, googleApi = rig.api) }
        val http = createClient { }
        val resp = http.get("/v1/mail/g9/body") { header(HttpHeaders.Authorization, "Bearer lap-token") }
        assertEquals(HttpStatusCode.OK, resp.status)
        val j = p12Json.parseToJsonElement(resp.bodyAsText()).jsonObject
        assertTrue(j["from"]!!.jsonPrimitive.content.contains("help@example.com"), j.toString())
        assertFalse(j["from"]!!.jsonPrimitive.content.contains("news@example.com"))
        assertEquals("Hello", j["subject"]!!.jsonPrimitive.content)
        assertEquals("Hello body", j["text"]!!.jsonPrimitive.content.trim())
    }

    @Test
    fun test_AC2_labels_route_returns_only_user_labels() = testApplication {
        val rig = P12Rig()
        application { module(rig.config(), rig.store, googleApi = rig.api) }
        val http = createClient { }
        val resp = http.get("/v1/mail/labels") { header(HttpHeaders.Authorization, "Bearer lap-token") }
        val labels = p12Json.decodeFromString<List<MailLabel>>(resp.bodyAsText())
        assertEquals(listOf(MailLabel("Label_1", "Bills")), labels)
    }

    @Test
    fun test_AC2_mail_routes_are_503_google_not_configured_without_a_google_block() = testApplication {
        val rig = P12Rig()
        application { module(rig.config().copy(google = null), rig.store) }
        val http = createClient { }
        for (path in listOf("/v1/mail/labels", "/v1/mail/g9/body")) {
            val resp = http.get(path) { header(HttpHeaders.Authorization, "Bearer lap-token") }
            assertEquals(HttpStatusCode.ServiceUnavailable, resp.status, path)
            assertTrue(resp.bodyAsText().contains("google-not-configured"))
        }
    }

    // ------------------------------------------------------------ client round trip

    @Test
    fun test_AC3_draft_round_trip_DRAFT_to_Send_to_SENT_and_delete_accepted() = testApplication {
        val rig = P12Rig()
        application { module(rig.config(), rig.store, googleApi = rig.api) }
        val http = createClient { }
        val bridge = MockEngine { req ->
            val bytes = when (val b = req.body) {
                is TextContent -> b.text.toByteArray()
                is OutgoingContent.ByteArrayContent -> b.bytes()
                else -> ByteArray(0)
            }
            val r = http.request(req.url.fullPath) {
                method = req.method
                header(HttpHeaders.Authorization, req.headers[HttpHeaders.Authorization] ?: "")
                req.body.contentType?.let { contentType(it) }
                if (bytes.isNotEmpty()) setBody(bytes)
            }
            respond(r.readRawBytes(), r.status, headersOf(HttpHeaders.ContentType, r.headers[HttpHeaders.ContentType] ?: "application/json"))
        }
        var clock = P12_T0
        val db = AssistantDb(JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).also { AssistantDb.Schema.create(it) })
        val local = LocalStore(db)
        local.putSetting(SettingKeys.SERVER_URL, "http://localhost")
        local.putSetting(SettingKeys.DEVICE_TOKEN, "lap-token")
        val repo = Repository(local, now = { clock }, today = { "2026-11-10" })
        val engine = SyncEngine(local, SyncClient(bridge), now = { clock })
        // what the MAIL_DRAFT job leaves behind: a DRAFT waiting to be synced
        db.mail_draftQueries.upsert(Mail_draft("maildraft:j1", clock, 0, 0, "PENDING", "ann@example.com", "Hello there", "Hi", null, null, "DRAFT", null, null))
        engine.sync()
        assertEquals("DRAFT", rig.draft("maildraft:j1").status)
        rig.worker.sweep()
        assertEquals(0, rig.gmail.sends.size, "a DRAFT is never sent")

        clock += 1000
        assertTrue(repo.saveMailDraft("maildraft:j1", "ann@example.com", "Hello there", "Hi Ann", send = true))
        engine.sync()
        assertEquals("APPROVED", rig.draft("maildraft:j1").status)
        rig.worker.sweep()
        assertEquals("SENT", rig.draft("maildraft:j1").status)
        assertEquals(1, rig.gmail.sends.size)

        clock += 1000
        engine.sync()
        val local1 = db.mail_draftQueries.selectById("maildraft:j1").executeAsOne()
        assertEquals("SENT", local1.status)
        assertNotNull(local1.sentAt)

        clock += 1000
        assertTrue(repo.deleteMailDraft("maildraft:j1"))
        engine.sync()
        assertEquals(1L, rig.draft("maildraft:j1").deleted, "server accepted the pure delete of a SENT draft")
        assertEquals("SYNCED", db.mail_draftQueries.selectById("maildraft:j1").executeAsOne().syncState)
        assertEquals(1, rig.gmail.sends.size)
    }

    // ------------------------------------------------------------ pure logic

    @Test
    fun test_AC3_reply_subject_has_no_double_Re_and_address_comes_from_the_header() {
        assertEquals("Re: Hello", replySubject("Hello"))
        assertEquals("Re: Hello", replySubject("Re: Hello"))
        assertFalse(replySubject("RE: Hello").lowercase().startsWith("re: re:"))
        assertEquals("help@example.com", extractAddress("Help Desk <help@example.com>"))
        assertEquals("a@b.com", extractAddress("  a@b.com "))
    }

    @Test
    fun test_AC3_new_conversation_note_only_when_the_subject_really_changed() {
        assertFalse(startsNewConversation("Re: Hello", "Hello"))
        assertFalse(startsNewConversation("Hello", "Hello"))
        assertTrue(startsNewConversation("Something else", "Hello"))
    }

    @Test
    fun test_AC3_send_is_disabled_for_an_empty_or_invalid_to() {
        assertFalse(canSendDraft("", "s", "b"))
        assertFalse(canSendDraft("abc", "s", "b"))
        assertFalse(isValidRecipient("a@b"))
        assertFalse(canSendDraft("a@b.com, c@d.com", "s", "b"))
        assertTrue(canSendDraft("ann@example.com", "s", "b"))
    }

    @Test
    fun test_AC3_status_lines_editing_and_delete_rules() {
        assertEquals("Draft", draftStatusLine("DRAFT", null))
        assertTrue(draftStatusLine("SENDING", null).startsWith("Sending"))
        assertTrue(draftStatusLine("APPROVED", null).startsWith("Sending"))
        assertEquals("Sent", draftStatusLine("SENT", null))
        assertEquals("Failed: boom", draftStatusLine("FAILED", "boom"))
        assertEquals("Unknown - check Gmail Sent", draftStatusLine("UNKNOWN", null))
        assertTrue(draftIsEditable("DRAFT") && draftIsEditable("FAILED"))
        for (s in listOf("APPROVED", "SENDING", "SENT", "UNKNOWN")) assertFalse(draftIsEditable(s), s)
        for (s in listOf("DRAFT", "FAILED", "SENT", "UNKNOWN")) assertTrue(draftCanDelete(s), s)
        for (s in listOf("APPROVED", "SENDING")) assertFalse(draftCanDelete(s), s)
        assertTrue(actionCanDelete("DONE") && actionCanDelete("REJECTED") && actionCanDelete("FAILED"))
        assertFalse(actionCanDelete("SUGGESTED") || actionCanDelete("APPROVED"))
    }

    @Test
    fun test_AC3_triage_drops_unknown_mail_unknown_label_label_without_id_and_unknown_action() {
        val labels = listOf(MailLabel("Label_1", "Bills"))
        val json = """{"actions":[
            {"gmailId":"g1","action":"LABEL","labelId":"Label_1","reason":"invoice"},
            {"gmailId":"g1","action":"LABEL","labelId":"Label_X","reason":"invented label"},
            {"gmailId":"g2","action":"LABEL","reason":"no label id"},
            {"gmailId":"nope","action":"ARCHIVE","reason":"unknown mail"},
            {"gmailId":"g2","action":"DELETE","reason":"unknown action"},
            {"gmailId":"g2","action":"ARCHIVE","reason":"newsletter"}
        ]}"""
        val out = parseTriage(json, setOf("g1", "g2"), labels)!!
        assertEquals(
            listOf(
                MailActionDraft("g1", "LABEL", "Label_1", "Bills", "invoice"),
                MailActionDraft("g2", "ARCHIVE", null, null, "newsletter"),
            ),
            out,
        )
        assertNull(parseTriage("not json", setOf("g1"), labels))
    }

    @Test
    fun test_AC4_hardware_view_keeps_other_sections_when_one_is_unreadable() {
        val json = """{"takenAt":1700000000000,"computer":{"ok":false,"error":"Not available"},
            "bios":{"ok":true,"version":"1.2.3","releaseDate":1690000000000},
            "battery":{"ok":true,"batteries":[]},
            "disks":{"ok":true,"disks":[{"name":"D0","health":"Healthy"}]},
            "events":{"ok":true,"total":0,"days":7}}"""
        assertEquals(1700000000000L, hwTakenAt(json))
        val sections = hardwareSections(json)
        val computer = sections.first { it.title == "Computer" }
        assertTrue(computer.lines.any { it.contains("Not available") })
        val bios = sections.first { it.title == "BIOS" }
        assertTrue(bios.lines.any { it.contains("1.2.3") })
        assertTrue(hardwareSummary(json).isNotBlank())
        assertTrue(hardwareSummary(json).length <= 300)
    }

    @Test
    fun test_AC4_unreadable_result_json_does_not_crash_the_view() {
        assertNull(hwTakenAt("not json"))
        assertTrue(hardwareSections("not json").isNotEmpty())
        assertTrue(hardwareSummary("not json").isNotBlank())
    }

    @Test
    fun test_AC5_hw_explain_text_reads_the_job_result() {
        assertEquals("Battery wear is high", hwExplainText("""{"text":"Battery wear is high"}"""))
        assertNull(hwExplainText(null))
        assertNull(hwExplainText("""{"text":"  "}"""))
    }

    // ------------------------------------------------------------ navigation, release, ops files

    @Test
    fun test_AC5_mail_and_hardware_are_browse_chips_with_no_add_target() {
        // Phase 15: Mail + starts a new email (NewEmail); Hardware still has no +.
        val browse = hubChips.getValue(dev.tim.assistant.ui.Hub.Browse)
        assertEquals(listOf(NavTab.Mail, NavTab.Hardware), browse.subList(2, 4))
        assertEquals(dev.tim.assistant.ui.AddAction.NewEmail, addActionFor(NavTab.Mail))
        assertNull(addActionFor(NavTab.Hardware))
        assertTrue(addActionFor(NavTab.Today) != null)
    }

    @Test
    fun test_AC7_release_is_1_10_0_versionCode_6_with_same_identity() {
        val g = p12Text("desktopApp/build.gradle.kts")
        assertTrue(g.contains("packageVersion = \"1.18.0\""))
        assertTrue(g.contains("upgradeUuid"))
        val a = p12Text("androidApp/build.gradle.kts")
        assertTrue(a.contains("versionName = \"1.18.0\""))
        assertTrue(Regex("versionCode\\s*=\\s*14\\b").containsMatchIn(a))
    }

    @Test
    fun test_AC6_checklists_name_1_10_0_and_historical_ones_stay() {
        for (f in listOf("phase1-e2e", "phase4", "phase6", "nutrition")) {
            assertTrue(p12Text("ops/checklists/$f.md").contains("1.18.0"), "$f.md names 1.18.0")
        }
        assertTrue(p12Text("ops/checklists/phase1-e2e.md").contains("Wally-1.18.0.msi"))
        assertTrue(p12Text("ops/checklists/w9-study.md").contains("1.7.0"))
        assertTrue(p12Text("ops/checklists/w13-calendar.md").contains("1.12.0"))
    }

    @Test
    fun test_AC6_w11_checklist_has_the_manual_lines() {
        val t = p12Text("ops/checklists/w11.md").lowercase()
        for (k in listOf("draft reply", "new email", "organise inbox", "archive", "label", "hardware", "run check", "explain", "phone", "hebrew", "skipped", "second")) {
            assertTrue(t.contains(k), "w11.md lacks '$k'")
        }
    }

    @Test
    fun test_AC1_setup_guide_names_both_new_scopes_and_the_google_auth_rerun() {
        val g = p12Text("ops/google-setup.md")
        assertTrue(g.contains("gmail.send") && g.contains("gmail.modify") && g.contains("gmail.readonly"))
        assertTrue(g.contains("google-auth"))
        assertFalse(Regex("GOCSPX-[A-Za-z0-9_-]{10,}").containsMatchIn(g))
    }

    @Test
    fun test_AC2_server_requests_all_three_gmail_scopes_and_no_wider_ones() {
        val src = File(p12Root(), "server/src/main/kotlin").walkTopDown().filter { it.extension == "kt" }.joinToString("\n") { it.readText() }
        for (s in listOf("gmail.readonly", "gmail.send", "gmail.modify")) assertTrue(src.contains(s), s)
        assertFalse(src.contains("mail.google.com/\""))
        assertFalse(src.contains("auth/calendar\""))
    }

    @Test
    fun test_AC6_restore_helper_lists_28_tables_with_the_three_new_ones() {
        val t = p12Text("ops/assistant-restore-test.sh")
        val tables = Regex("^TABLES=\"([^\"]*)\"", RegexOption.MULTILINE).find(t)!!.groupValues[1].trim().split(Regex("\\s+"))
        assertEquals(34, tables.size, "restore helper TABLES: $tables")
        for (n in listOf("mail_draft", "mail_action", "hw_check")) assertTrue(n in tables, n)
        assertFalse(t.contains('\r'))
    }

    @Test
    fun test_AC6_ops_notes_cover_backup_restore_warning_and_calendar_token_limit() {
        assertTrue(p12Text("ops/backup-setup.md").contains("draft", ignoreCase = true))
        val w = p12Text("ops/wallycal.md").lowercase()
        assertTrue(w.contains("mail"))
        assertTrue(File(p12Root(), "ops/hardware.md").exists())
    }
}
