package dev.tim.assistant.server.google

import dev.tim.assistant.db.Mail_draft
import dev.tim.assistant.model.PullResponse
import dev.tim.assistant.model.PushResponse
import dev.tim.assistant.model.SyncRow
import dev.tim.assistant.model.toSyncRow
import dev.tim.assistant.server.GoogleConfig
import dev.tim.assistant.server.ServerDb
import dev.tim.assistant.server.SyncStore
import dev.tim.assistant.server.module
import dev.tim.assistant.server.obj
import dev.tim.assistant.server.openDb
import dev.tim.assistant.server.pullRaw
import dev.tim.assistant.server.push
import dev.tim.assistant.server.tempDbPath
import dev.tim.assistant.server.testConfig
import dev.tim.assistant.server.testJson
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.server.testing.testApplication
import java.nio.file.Files
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

private const val BASE = "/gmail/v1/users/me"

/** A scripted Gmail that records every call. */
private class FakeGmail {
    val requests = ArrayList<HttpRequestData>()
    val bodies = ArrayList<String>()
    var sendReply: suspend () -> Pair<Int, String> = { 200 to """{"id":"sent-1","threadId":"thr-new"}""" }
    var modifyReply: Pair<Int, String> = 200 to """{"id":"x"}"""
    var original: Pair<Int, String> = 200 to originalJson()
    var fullMessage: Pair<Int, String> = 404 to "{}"
    var labels: Pair<Int, String> = 200 to """{"labels":[]}"""

    val sends get() = requests.indices.filter { requests[it].url.encodedPath == "$BASE/messages/send" }.map { bodies[it] }
    val modifies get() = requests.indices.filter { requests[it].url.encodedPath.endsWith("/modify") }

    fun engine() = MockEngine { request ->
        requests += request
        bodies += request.bodyText()
        val path = request.url.encodedPath
        val (status, body) = when {
            request.url.host == "oauth2.googleapis.com" -> 200 to OK_TOKEN_BODY
            path == "$BASE/messages/send" -> sendReply()
            path.endsWith("/modify") -> modifyReply
            path == "$BASE/labels" -> labels
            path.startsWith("$BASE/messages/") && request.url.parameters["format"] == "full" -> fullMessage
            path.startsWith("$BASE/messages/") -> original
            else -> 404 to "{}"
        }
        respond(body, HttpStatusCode.fromValue(status), headersOf(HttpHeaders.ContentType, "application/json"))
    }
}

private fun originalJson(
    subject: String = "Hello there",
    messageId: String = "<orig@mail.example>",
    references: String? = "<older@mail.example>",
    threadId: String = "thr-orig",
): String {
    val headers = buildString {
        append("""{"name":"Message-ID","value":"$messageId"},{"name":"Subject","value":"$subject"}""")
        if (references != null) append(""",{"name":"References","value":"$references"}""")
    }
    return """{"id":"orig1","threadId":"$threadId","payload":{"headers":[$headers]}}"""
}

private class GmailRig(val fake: FakeGmail = FakeGmail(), callTimeoutMs: Long = 2_000) {
    var now: Long = T0
    val sdb: ServerDb = openDb(tempDbPath())
    val store = SyncStore(sdb, { now })
    val tokenFile = Files.createTempDirectory("gmail-write-test").resolve("google-token.json").toFile()
    val client = HttpClient(fake.engine())
    val auth = GoogleAuth(GoogleConfig("cid", "secret"), tokenFile, client, { now })
    val worker = GmailWrite(sdb, store, client, auth, { now }, callTimeoutMs)
    val api = GoogleApi(client, auth)

    init {
        writeTokenFile(tokenFile, "refresh-1", T0)
    }

    fun draft(id: String) = sdb.db.mail_draftQueries.selectById(id).executeAsOne()
    fun action(id: String) = sdb.db.mail_actionQueries.selectById(id).executeAsOne()

    suspend fun push(vararg rows: SyncRow, calendar: Boolean = false): PushResponse = store.push(rows.toList(), calendar)

    suspend fun seedRaw(d: Mail_draft) {
        sdb.writeLock.withLock { sdb.db.transaction { store.forceWrite(toSyncRow(d)) } }
    }

    suspend fun seedMail(gmailId: String) {
        sdb.writeLock.withLock {
            sdb.db.transaction {
                store.writeIfNewer(
                    toSyncRow(dev.tim.assistant.db.Mail_item("mail:$gmailId", T0 - 1000, 0, 0, "SYNCED", gmailId, "s", "subj", "snip", T0 - 5000)),
                )
            }
        }
    }
}

private fun draftRow(
    id: String,
    status: String = "APPROVED",
    updatedAt: Long = T0,
    to: String = "ann@example.com",
    subject: String = "Hello there",
    body: String = "Hi Ann",
    replyTo: String? = null,
    deleted: Boolean = false,
    sentAt: Long? = null,
    error: String? = null,
) = SyncRow(
    "mail_draft", id, updatedAt, deleted,
    obj(
        "toAddr" to to, "subject" to subject, "body" to body, "replyToGmailId" to replyTo, "threadId" to null,
        "status" to status, "sentAt" to sentAt, "error" to error,
    ),
)

private fun actionRow(
    id: String,
    action: String = "ARCHIVE",
    status: String = "APPROVED",
    gmailId: String = "g1",
    labelId: String? = null,
    updatedAt: Long = T0,
) = SyncRow(
    "mail_action", id, updatedAt, false,
    obj("gmailId" to gmailId, "action" to action, "labelId" to labelId, "labelName" to labelId, "reason" to "because", "status" to status),
)

/** The headers and decoded text body of the message in a send request body. */
private class SentMail(val headers: Map<String, String>, val text: String, val threadId: String?, val rawMime: String)

private fun parseSent(requestBody: String): SentMail {
    val json = Json.parseToJsonElement(requestBody) as JsonObject
    val raw = String(Base64.getUrlDecoder().decode((json["raw"] as JsonPrimitive).content), Charsets.UTF_8)
    val (head, body) = raw.split("\r\n\r\n", limit = 2)
    // unfold continuation lines
    val unfolded = head.replace("\r\n ", "")
    val headers = unfolded.split("\r\n").associate { line -> line.substringBefore(": ") to line.substringAfter(": ") }
    val text = String(Base64.getMimeDecoder().decode(body.trim()), Charsets.UTF_8)
    return SentMail(headers, text, (json["threadId"] as? JsonPrimitive)?.content, raw)
}

class GmailWriteTest {
    // ---- sending ----------------------------------------------------------------------------

    @Test
    fun twoApprovedPushesSendExactlyOnce() = runBlocking<Unit> {
        val rig = GmailRig()
        rig.push(draftRow("maildraft:1"))
        rig.push(draftRow("maildraft:1", updatedAt = T0 + 1))
        rig.worker.sweep()
        rig.worker.sweep()
        assertEquals(1, rig.fake.sends.size)
        val d = rig.draft("maildraft:1")
        assertEquals("SENT", d.status)
        assertEquals(T0, d.sentAt)
        assertEquals("thr-new", d.threadId)
        assertNull(d.error)
        val sent = parseSent(rig.fake.sends.single())
        assertEquals("ann@example.com", sent.headers["To"])
        assertEquals("Hi Ann", sent.text)
        assertEquals("text/plain; charset=UTF-8", sent.headers["Content-Type"])
        assertTrue(sent.rawMime.contains("\r\n"))
    }

    @Test
    fun aDraftIsNeverSentAndConcurrentSweepsSendOnce() = runBlocking<Unit> {
        val rig = GmailRig()
        rig.push(draftRow("maildraft:d", status = "DRAFT"))
        rig.worker.sweep()
        assertEquals(0, rig.fake.sends.size)
        assertEquals("DRAFT", rig.draft("maildraft:d").status)

        rig.push(draftRow("maildraft:a", updatedAt = T0))
        val jobs = List(4) { CoroutineScope(Dispatchers.Default).launch { rig.worker.sweep() } }
        jobs.forEach { it.join() }
        assertEquals(1, rig.fake.sends.size)
    }

    @Test
    fun theContentSentIsWhatWasStoredWhenTheWorkerClaimedIt() = runBlocking<Unit> {
        val rig = GmailRig()
        rig.push(draftRow("maildraft:1", body = "first"))
        rig.push(draftRow("maildraft:1", body = "edited while approved", updatedAt = T0 + 5))
        rig.worker.sweep()
        assertEquals("edited while approved", parseSent(rig.fake.sends.single()).text)
    }

    @Test
    fun aDraftPushedAnHourAheadStillMovesToSendingAndSent() = runBlocking<Unit> {
        val rig = GmailRig()
        val ahead = T0 + 3_600_000
        rig.push(draftRow("maildraft:1", updatedAt = ahead))
        val states = ArrayList<Pair<String, Long>>()
        rig.fake.sendReply = {
            val during = rig.draft("maildraft:1")
            states += during.status to during.updatedAt
            200 to """{"id":"s","threadId":"t"}"""
        }
        rig.worker.sweep()
        assertEquals(listOf("SENDING" to ahead + 1), states)
        val done = rig.draft("maildraft:1")
        assertEquals("SENT", done.status)
        assertEquals(ahead + 2, done.updatedAt)
    }

    @Test
    fun aSendingDraftBecomesUnknownAtRestartAndIsNeverResent() = runBlocking<Unit> {
        val rig = GmailRig()
        rig.push(draftRow("maildraft:1"))
        val stored = rig.draft("maildraft:1")
        rig.seedRaw(stored.copy(status = "SENDING", updatedAt = stored.updatedAt + 1))
        rig.worker.recover()
        rig.worker.sweep()
        val d = rig.draft("maildraft:1")
        assertEquals("UNKNOWN", d.status)
        assertTrue(d.updatedAt > stored.updatedAt + 1)
        assertEquals(0, rig.fake.sends.size)
    }

    @Test
    fun startUpSweepsApprovedDraftsAndActionsAndTheWakeSignalDrivesLaterOnes() = runBlocking<Unit> {
        val rig = GmailRig()
        rig.push(draftRow("maildraft:old"), actionRow("mailaction:old:0"))
        rig.store.gmailWake = rig.worker::signal
        val scope = CoroutineScope(Dispatchers.Default)
        val job = scope.launch { rig.worker.run() }
        try {
            withTimeout(5_000) {
                while (rig.draft("maildraft:old").status != "SENT" || rig.action("mailaction:old:0").status != "DONE") delay(20)
            }
            rig.push(draftRow("maildraft:new"))
            withTimeout(5_000) { while (rig.draft("maildraft:new").status != "SENT") delay(20) }
            assertEquals(2, rig.fake.sends.size)
        } finally {
            job.cancelAndJoin()
        }
    }

    @Test
    fun aReplyCarriesThreadIdInReplyToAndReferences() = runBlocking<Unit> {
        val rig = GmailRig()
        rig.push(draftRow("maildraft:1", subject = "Re: Hello there", replyTo = "orig1"))
        rig.worker.sweep()
        val sent = parseSent(rig.fake.sends.single())
        assertEquals("thr-orig", sent.threadId)
        assertEquals("<orig@mail.example>", sent.headers["In-Reply-To"])
        assertEquals("<older@mail.example> <orig@mail.example>", sent.headers["References"])
        val metadata = rig.fake.requests.first { it.url.encodedPath.endsWith("/messages/orig1") }
        assertEquals(HttpMethod.Get, metadata.method)
        assertEquals("SENT", rig.draft("maildraft:1").status)
    }

    @Test
    fun aChangedSubjectSendsAsANewConversation() = runBlocking<Unit> {
        val rig = GmailRig()
        rig.push(draftRow("maildraft:1", subject = "Something else entirely", replyTo = "orig1"))
        rig.worker.sweep()
        val sent = parseSent(rig.fake.sends.single())
        assertNull(sent.threadId)
        assertNull(sent.headers["In-Reply-To"])
        assertNull(sent.headers["References"])
    }

    @Test
    fun aHebrewSubjectIsEncodedAndTheBodyIsUtf8() = runBlocking<Unit> {
        val rig = GmailRig()
        val subject = "שלום וברכה, זו כותרת ארוכה מאוד בעברית שדורשת כמה מילים מקודדות"
        rig.push(draftRow("maildraft:1", subject = subject, body = "שלום עולם"))
        rig.worker.sweep()
        val sent = parseSent(rig.fake.sends.single())
        val header = sent.headers.getValue("Subject")
        assertTrue(header.startsWith("=?UTF-8?B?"))
        assertEquals(subject, decodeMimeWords(header))
        assertEquals("שלום עולם", sent.text)
        // no encoded word is longer than 75 characters
        sent.rawMime.substringBefore("\r\n\r\n").lines().filter { it.contains("=?UTF-8") }.forEach { assertTrue(it.trim().length <= 75 + "Subject: ".length) }
    }

    @Test
    fun aHeaderWithALineBreakOrABadAddressIsRefusedWithoutSending() = runBlocking<Unit> {
        val rig = GmailRig()
        rig.push(draftRow("maildraft:crlf", subject = "ok\r\nBcc: x@evil.example"), draftRow("maildraft:bad", to = "not an address"))
        rig.worker.sweep()
        assertEquals(0, rig.fake.sends.size)
        assertEquals("FAILED", rig.draft("maildraft:crlf").status)
        assertEquals("FAILED", rig.draft("maildraft:bad").status)
    }

    // ---- error mapping -----------------------------------------------------------------------

    @Test
    fun definiteRefusalsBecomeFailed() = runBlocking<Unit> {
        val rig = GmailRig()
        rig.fake.sendReply = { 400 to """{"error":{"code":400,"message":"Invalid To header","status":"INVALID_ARGUMENT"}}""" }
        rig.push(draftRow("maildraft:1"))
        rig.worker.sweep()
        val d = rig.draft("maildraft:1")
        assertEquals("FAILED", d.status)
        assertTrue(d.error!!.contains("400"))
        assertNull(d.sentAt)
    }

    @Test
    fun missingPermissionBecomesFailedWithTheGoogleAuthMessage() = runBlocking<Unit> {
        val rig = GmailRig()
        rig.fake.sendReply = { 403 to """{"error":{"code":403,"errors":[{"reason":"insufficientPermissions"}],"status":"PERMISSION_DENIED"}}""" }
        rig.push(draftRow("maildraft:1"))
        rig.worker.sweep()
        assertEquals("FAILED", rig.draft("maildraft:1").status)
        assertEquals("Google permission missing - run google-auth again", rig.draft("maildraft:1").error)
    }

    @Test
    fun aMissingTokenFileBecomesFailedWithTheGoogleAuthMessage() = runBlocking<Unit> {
        val rig = GmailRig()
        rig.tokenFile.delete()
        rig.push(draftRow("maildraft:1"))
        rig.worker.sweep()
        assertEquals("FAILED", rig.draft("maildraft:1").status)
        assertEquals(PERMISSION_MISSING, rig.draft("maildraft:1").error)
        assertEquals(0, rig.fake.sends.size)
    }

    @Test
    fun possiblySentOutcomesBecomeUnknownAndAreNeverRetried() = runBlocking<Unit> {
        val cases: Map<String, suspend () -> Pair<Int, String>> = mapOf(
            "http500" to { 500 to "oops" },
            "unreadable200" to { 200 to "not json" },
            "transport" to { throw java.io.IOException("connection reset") },
        )
        for ((name, reply) in cases) {
            val rig = GmailRig()
            rig.fake.sendReply = reply
            rig.push(draftRow("maildraft:$name"))
            rig.worker.sweep()
            rig.worker.sweep()
            assertEquals("UNKNOWN", rig.draft("maildraft:$name").status, name)
            assertEquals(1, rig.fake.sends.size, name)
        }
    }

    @Test
    fun aTimeoutBecomesUnknown() = runBlocking<Unit> {
        val rig = GmailRig(callTimeoutMs = 200)
        rig.fake.sendReply = {
            delay(5_000)
            200 to """{"id":"late"}"""
        }
        rig.push(draftRow("maildraft:1"))
        rig.worker.sweep()
        assertEquals("UNKNOWN", rig.draft("maildraft:1").status)
        assertEquals(1, rig.fake.sends.size)
    }

    // ---- the state fence ---------------------------------------------------------------------

    @Test
    fun aClientCanNeverPushSentOrWorkerStatuses() = runBlocking<Unit> {
        val rig = GmailRig()
        for (status in listOf("SENT", "SENDING", "FAILED", "UNKNOWN")) {
            val r = rig.push(draftRow("maildraft:$status", status = status))
            assertEquals(listOf("maildraft:$status"), r.rejected.map { it.id }, status)
        }
        assertTrue(rig.push(draftRow("maildraft:err", status = "DRAFT", error = "x")).rejected.isNotEmpty())
        assertTrue(rig.push(draftRow("maildraft:sent", status = "DRAFT", sentAt = 5)).rejected.isNotEmpty())
        assertTrue(rig.push(actionRow("mailaction:x:0", status = "DONE")).rejected.isNotEmpty())
        assertTrue(rig.push(actionRow("mailaction:x:1", status = "FAILED")).rejected.isNotEmpty())
        assertTrue(rig.push(actionRow("mailaction:x:2", status = "SUGGESTED")).rejected.isEmpty())
        assertTrue(rig.push(actionRow("mailaction:x:3", status = "REJECTED")).rejected.isEmpty())
    }

    @Test
    fun aRefusedPushRestampsTheStoredRowSoItWinsTheNextPull() = runBlocking<Unit> {
        val rig = GmailRig()
        rig.push(draftRow("maildraft:1"))
        rig.worker.sweep()
        val sent = rig.draft("maildraft:1")
        val pushedAt = sent.updatedAt + 10_000_000
        val r = rig.push(draftRow("maildraft:1", status = "DRAFT", body = "rewrite", updatedAt = pushedAt))
        assertEquals(1, r.rejected.size)
        val after = rig.draft("maildraft:1")
        assertEquals("SENT", after.status)
        assertEquals("Hi Ann", after.body)
        assertEquals(pushedAt + 1, after.updatedAt)
        assertTrue(after.serverSeq > sent.serverSeq)
        val pulled = rig.store.pull(sent.serverSeq).rows.single { it.id == "maildraft:1" }
        assertEquals(pushedAt + 1, pulled.updatedAt)
        assertEquals("SENT", (pulled.data["status"] as JsonPrimitive).content)
        assertEquals(1, rig.fake.sends.size)
    }

    @Test
    fun aPureDeleteOfASentDraftIsAllowedButAnEditedDeleteIsNot() = runBlocking<Unit> {
        val rig = GmailRig()
        rig.push(draftRow("maildraft:1"))
        rig.worker.sweep()
        val sent = rig.draft("maildraft:1")
        val storedRow = toSyncRow(sent)
        val edited = rig.push(storedRow.copy(updatedAt = sent.updatedAt + 5, deleted = true, data = JsonObject(storedRow.data + ("body" to JsonPrimitive("changed")))))
        assertEquals(1, edited.rejected.size)
        assertEquals(0L, rig.draft("maildraft:1").deleted)
        val plain = rig.push(storedRow.copy(updatedAt = sent.updatedAt + 20, deleted = true))
        assertTrue(plain.rejected.isEmpty())
        assertEquals(1L, rig.draft("maildraft:1").deleted)
        assertEquals("SENT", rig.draft("maildraft:1").status)
    }

    @Test
    fun aFailedDraftCanBeEditedAndSentAgain() = runBlocking<Unit> {
        val rig = GmailRig()
        rig.fake.sendReply = { 400 to """{"error":{"message":"bad"}}""" }
        rig.push(draftRow("maildraft:1"))
        rig.worker.sweep()
        assertEquals("FAILED", rig.draft("maildraft:1").status)
        rig.fake.sendReply = { 200 to """{"id":"ok","threadId":"t2"}""" }
        val r = rig.push(draftRow("maildraft:1", to = "bob@example.com", updatedAt = rig.draft("maildraft:1").updatedAt + 1))
        assertTrue(r.rejected.isEmpty())
        rig.worker.sweep()
        val d = rig.draft("maildraft:1")
        assertEquals("SENT", d.status)
        assertNull(d.error)
        assertEquals("bob@example.com", d.toAddr)
    }

    @Test
    fun newTableRowsAreValidated() = runBlocking<Unit> {
        val rig = GmailRig()
        assertTrue(rig.push(draftRow("maildraft:a", to = "a".repeat(321))).rejected.isNotEmpty())
        assertTrue(rig.push(draftRow("maildraft:b", to = "a".repeat(320))).rejected.isEmpty())
        assertTrue(rig.push(draftRow("maildraft:c", subject = "s".repeat(501))).rejected.isNotEmpty())
        assertTrue(rig.push(draftRow("maildraft:d", body = "b".repeat(50_001))).rejected.isNotEmpty())
        val longReason = actionRow("mailaction:a:0").let { it.copy(data = JsonObject(it.data + ("reason" to JsonPrimitive("r".repeat(501))))) }
        assertTrue(rig.push(longReason).rejected.isNotEmpty())
        val hw = SyncRow("hw_check", "hw:1", T0, false, obj("takenAt" to 1, "resultJson" to "{}", "summary" to "ok"))
        assertTrue(rig.push(hw).rejected.isEmpty())
        assertTrue(rig.push(hw.copy(id = "hw:2", data = JsonObject(hw.data + ("summary" to JsonPrimitive("s".repeat(301)))))).rejected.isNotEmpty())
        assertTrue(rig.push(hw.copy(id = "hw:3", data = JsonObject(hw.data + ("resultJson" to JsonPrimitive("j".repeat(100_001)))))).rejected.isNotEmpty())
        val pulled = rig.store.pull(0).rows.map { it.table to it.id }
        assertTrue("hw_check" to "hw:1" in pulled)
        assertTrue("mail_draft" to "maildraft:b" in pulled)
    }

    // ---- the WallyCalendar token ---------------------------------------------------------------

    @Test
    fun theCalendarTokenCannotWriteMailRowsButOtherRowsStillWork() = testApplication {
        val rig = GmailRig()
        val config = testConfig(tempDbPath()).copy(tokens = mapOf("laptop" to "lap-token", "calendar" to "cal-token"))
        var woke = 0
        rig.store.gmailWake = { woke++ }
        application { module(config, rig.store, googleApi = rig.api) }
        val http = createClient { }
        val task = SyncRow("task", "task:1", T0, false, obj("title" to "Make Wally task", "dueDate" to null, "done" to false, "doneAt" to null, "goalId" to null))
        val response = http.push(listOf(draftRow("maildraft:1"), actionRow("mailaction:1:0", status = "APPROVED"), task), token = "cal-token")
        val parsed = testJson.decodeFromString(PushResponse.serializer(), response.bodyAsText())
        assertEquals(setOf("maildraft:1", "mailaction:1:0"), parsed.rejected.map { it.id }.toSet())
        assertTrue(parsed.rejected.all { it.reason == "forbidden" })
        assertEquals(listOf("task:1"), parsed.accepted)
        assertTrue(rig.sdb.db.mail_draftQueries.selectAll().executeAsList().isEmpty())
        assertTrue(rig.sdb.db.mail_actionQueries.selectAll().executeAsList().isEmpty())
        rig.worker.sweep()
        assertEquals(0, rig.fake.sends.size)
        assertEquals(0, woke)
        // the same row pushed with an ordinary token is accepted and wakes the worker
        val ok = http.push(listOf(draftRow("maildraft:1")), token = "lap-token")
        assertTrue(testJson.decodeFromString(PushResponse.serializer(), ok.bodyAsText()).rejected.isEmpty())
        assertEquals(1, woke)
    }

    // ---- actions -----------------------------------------------------------------------------

    @Test
    fun aLabelActionSendsTheLabelAndIsDone() = runBlocking<Unit> {
        val rig = GmailRig()
        rig.push(actionRow("mailaction:j:0", action = "LABEL", labelId = "Label_7", gmailId = "g9"))
        rig.worker.sweep()
        rig.worker.sweep()
        val call = rig.fake.modifies.single()
        assertEquals("$BASE/messages/g9/modify", rig.fake.requests[call].url.encodedPath)
        assertEquals(HttpMethod.Post, rig.fake.requests[call].method)
        assertEquals("""{"addLabelIds":["Label_7"]}""", rig.fake.bodies[call])
        assertEquals("DONE", rig.action("mailaction:j:0").status)
    }

    @Test
    fun anArchiveActionRemovesInboxAndSoftDeletesTheMailItem() = runBlocking<Unit> {
        val rig = GmailRig()
        rig.seedMail("g1")
        val before = rig.sdb.db.mail_itemQueries.selectById("mail:g1").executeAsOne()
        rig.push(actionRow("mailaction:j:0", action = "ARCHIVE", gmailId = "g1"))
        rig.worker.sweep()
        assertEquals("""{"removeLabelIds":["INBOX"]}""", rig.fake.bodies[rig.fake.modifies.single()])
        assertEquals("DONE", rig.action("mailaction:j:0").status)
        val item = rig.sdb.db.mail_itemQueries.selectById("mail:g1").executeAsOne()
        assertEquals(1L, item.deleted)
        assertTrue(item.updatedAt > before.updatedAt && item.serverSeq > before.serverSeq)
    }

    @Test
    fun anActionOnAMissingMessageFailsWithMessageNotFoundAndKeepsTheMailItem() = runBlocking<Unit> {
        val rig = GmailRig()
        rig.seedMail("g1")
        rig.fake.modifyReply = 404 to """{"error":{"code":404}}"""
        rig.push(actionRow("mailaction:j:0", gmailId = "g1"))
        rig.worker.sweep()
        assertEquals("FAILED", rig.action("mailaction:j:0").status)
        assertEquals(0L, rig.sdb.db.mail_itemQueries.selectById("mail:g1").executeAsOne().deleted)
    }

    @Test
    fun suggestedAndRejectedActionsAreNeverRun() = runBlocking<Unit> {
        val rig = GmailRig()
        rig.push(actionRow("mailaction:a:0", status = "SUGGESTED"), actionRow("mailaction:a:1", status = "REJECTED"))
        rig.worker.sweep()
        assertEquals(0, rig.fake.modifies.size)
    }

    @Test
    fun aDoneActionCannotBeApprovedAgain() = runBlocking<Unit> {
        val rig = GmailRig()
        rig.push(actionRow("mailaction:a:0"))
        rig.worker.sweep()
        val done = rig.action("mailaction:a:0")
        val r = rig.push(actionRow("mailaction:a:0", updatedAt = done.updatedAt + 100))
        assertEquals(1, r.rejected.size)
        rig.worker.sweep()
        assertEquals(1, rig.fake.modifies.size)
        assertEquals("DONE", rig.action("mailaction:a:0").status)
    }

    // ---- routes ------------------------------------------------------------------------------

    private fun plainMessage(text: String) =
        """{"id":"g1","internalDate":"1700000000000","payload":{"mimeType":"multipart/alternative","headers":[
        {"name":"From","value":"Ann <ann@example.com>"},{"name":"To","value":"me@example.com"},
        {"name":"Subject","value":"=?UTF-8?B?${Base64.getEncoder().encodeToString("שלום".toByteArray())}?="}],
        "parts":[{"mimeType":"text/html","body":{"data":"${b64("<p>html version</p>")}"}},
        {"mimeType":"text/plain","body":{"data":"${b64(text)}"}},
        {"mimeType":"application/pdf","filename":"a.pdf","body":{"attachmentId":"x"}}]}}"""

    private fun b64(s: String) = Base64.getUrlEncoder().withoutPadding().encodeToString(s.toByteArray())

    @Test
    fun theBodyRouteReturnsTheMailAndMapsErrors() = testApplication {
        val rig = GmailRig()
        val config = testConfig(tempDbPath()).copy(tokens = mapOf("laptop" to "lap-token", "calendar" to "cal-token"))
        application { module(config, rig.store, googleApi = rig.api) }
        val http = createClient { }
        suspend fun body(id: String, token: String = "lap-token") =
            http.get("/v1/mail/$id/body") { header(HttpHeaders.Authorization, "Bearer $token") }

        rig.fake.fullMessage = 200 to plainMessage("plain text version")
        val ok = body("g1")
        assertEquals(HttpStatusCode.OK, ok.status)
        val mail = testJson.decodeFromString(dev.tim.assistant.model.MailBody.serializer(), ok.bodyAsText())
        assertEquals("Ann <ann@example.com>", mail.from)
        assertEquals("me@example.com", mail.to)
        assertEquals("שלום", mail.subject)
        assertEquals(1_700_000_000_000L, mail.date)
        assertEquals("plain text version", mail.text)

        rig.fake.fullMessage = 200 to plainMessage("x".repeat(25_000))
        assertEquals(20_000, testJson.decodeFromString(dev.tim.assistant.model.MailBody.serializer(), body("g1").bodyAsText()).text.length)

        rig.fake.fullMessage = 404 to "{}"
        val missing = body("gone")
        assertEquals(HttpStatusCode.NotFound, missing.status)
        assertEquals("""{"error":"not-found"}""", missing.bodyAsText())

        rig.fake.fullMessage = 500 to "{}"
        val bad = body("g1")
        assertEquals(HttpStatusCode.BadGateway, bad.status)
        assertEquals("""{"error":"google-unavailable"}""", bad.bodyAsText())

        assertEquals(HttpStatusCode.Forbidden, body("g1", "cal-token").status)
    }

    @Test
    fun theLabelsRouteListsUserLabelsOnly() = testApplication {
        val rig = GmailRig()
        val config = testConfig(tempDbPath()).copy(tokens = mapOf("laptop" to "lap-token", "calendar" to "cal-token"))
        application { module(config, rig.store, googleApi = rig.api) }
        val http = createClient { }
        rig.fake.labels = 200 to """{"labels":[{"id":"INBOX","name":"INBOX","type":"system"},{"id":"Label_1","name":"Bills","type":"user"},{"id":"Label_2","name":"Trips","type":"user"}]}"""
        val ok = http.get("/v1/mail/labels") { header(HttpHeaders.Authorization, "Bearer lap-token") }
        assertEquals(HttpStatusCode.OK, ok.status)
        assertEquals(
            listOf("Label_1" to "Bills", "Label_2" to "Trips"),
            testJson.decodeFromString(kotlinx.serialization.builtins.ListSerializer(dev.tim.assistant.model.MailLabel.serializer()), ok.bodyAsText()).map { it.id to it.name },
        )
        rig.fake.labels = 503 to "{}"
        assertEquals(HttpStatusCode.BadGateway, http.get("/v1/mail/labels") { header(HttpHeaders.Authorization, "Bearer lap-token") }.status)
        assertEquals(HttpStatusCode.Forbidden, http.get("/v1/mail/labels") { header(HttpHeaders.Authorization, "Bearer cal-token") }.status)
    }

    @Test
    fun theMailRoutesAnswer503WhenGoogleIsNotConfigured() = testApplication {
        val rig = GmailRig()
        application { module(testConfig(tempDbPath()), rig.store) }
        val http = createClient { }
        val labels = http.get("/v1/mail/labels") { header(HttpHeaders.Authorization, "Bearer test-token-laptop") }
        assertEquals(HttpStatusCode.ServiceUnavailable, labels.status)
        assertEquals("""{"error":"google-not-configured"}""", labels.bodyAsText())
        assertEquals(
            HttpStatusCode.ServiceUnavailable,
            http.get("/v1/mail/g1/body") { header(HttpHeaders.Authorization, "Bearer test-token-laptop") }.status,
        )
        assertEquals(HttpStatusCode.Unauthorized, http.get("/v1/mail/labels").status)
    }

    // ---- pure helpers ------------------------------------------------------------------------

    @Test
    fun replyPrefixAndConversationRules() {
        assertEquals("Hello", stripReplyPrefix("Re: RE: re:Hello"))
        assertTrue(staysInConversation("Re: Hello", "Hello"))
        assertTrue(staysInConversation("Re: Hello", "Re: Hello"))
        assertFalse(staysInConversation("Hello again", "Hello"))
        assertTrue(isValidAddress("a@b.co"))
        assertFalse(isValidAddress("a@b"))
        assertFalse(isValidAddress("Ann <a@b.co>"))
        assertFalse(isValidAddress(""))
    }

    @Test
    fun htmlIsStrippedWhenThereIsNoPlainPart() {
        val message = Json.parseToJsonElement(
            """{"internalDate":"5","payload":{"mimeType":"text/html","headers":[],"body":{"data":"${b64("<style>p{}</style><p>Hello &amp; welcome</p><script>x()</script><div>line two</div>")}"}}}""",
        ) as JsonObject
        val mail = assertNotNull(parseMailBody(message))
        assertEquals("Hello & welcome\nline two", mail.text)
    }

    @Test
    fun fromIsReplyToWhenPresentElseFrom() {
        fun parse(headers: String) = assertNotNull(
            parseMailBody(
                Json.parseToJsonElement(
                    """{"internalDate":"5","payload":{"mimeType":"text/plain","headers":[$headers],"body":{"data":"${b64("hi")}"}}}""",
                ) as JsonObject,
            ),
        )
        val from = """{"name":"From","value":"Ann <ann@x.co>"}"""
        assertEquals("Reply <r@x.co>", parse("""$from,{"name":"reply-to","value":" Reply <r@x.co> "}""").from)
        assertEquals("Ann <ann@x.co>", parse("""$from,{"name":"Reply-To","value":"  "}""").from)
        assertEquals("Ann <ann@x.co>", parse(from).from)
    }

    @Test
    fun qEncodedHeadersAreDecoded() {
        assertEquals("Café menu", decodeMimeWords("=?UTF-8?Q?Caf=C3=A9_menu?="))
        assertEquals("plain", decodeMimeWords("plain"))
    }
}
