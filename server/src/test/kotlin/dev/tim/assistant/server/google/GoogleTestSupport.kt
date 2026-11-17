package dev.tim.assistant.server.google

import dev.tim.assistant.model.SyncRow
import dev.tim.assistant.model.toSyncRow
import dev.tim.assistant.db.Cal_event
import dev.tim.assistant.db.Mail_item
import dev.tim.assistant.server.GoogleConfig
import dev.tim.assistant.server.ServerDb
import dev.tim.assistant.server.SyncStore
import dev.tim.assistant.server.openDb
import dev.tim.assistant.server.tempDbPath
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import java.io.File
import java.net.URLEncoder
import java.nio.file.Files
import java.time.Instant
import kotlinx.coroutines.sync.withLock

const val DAY = 24L * 60 * 60 * 1000
val T0: Long = Instant.parse("2026-11-08T10:00:00Z").toEpochMilli()
const val OK_TOKEN_BODY = """{"access_token":"at-1","expires_in":3600,"token_type":"Bearer"}"""

fun enc(s: String): String = URLEncoder.encode(s, "UTF-8").replace("+", "%20")

fun pageJson(items: String, next: String? = null, key: String = "items"): String =
    """{"$key":[$items]${if (next != null) ""","nextPageToken":"$next"""" else ""}}"""

fun messageJson(id: String, internalDate: Long, from: String = "Ann <ann@example.com>", subject: String = "Subject $id", snippet: String = "snippet $id") =
    """{"id":"$id","snippet":"$snippet","internalDate":"$internalDate","payload":{"headers":[{"name":"From","value":"$from"},{"name":"Subject","value":"$subject"},{"name":"Date","value":"x"}]}}"""

fun idsPage(ids: List<String>, next: String? = null) = pageJson(ids.joinToString(",") { """{"id":"$it","threadId":"$it"}""" }, next, "messages")

/** A scripted Google: pages are chosen by the pageToken query parameter ("" = first page). */
class FakeGoogle {
    val requests = ArrayList<HttpRequestData>()
    val tokenCalls get() = requests.count { it.url.host == "oauth2.googleapis.com" }
    var tokenResponse: Pair<Int, String> = 200 to OK_TOKEN_BODY

    var unreadPages: List<Pair<Int, String>> = listOf(200 to idsPage(emptyList()))
    val messages = HashMap<String, Pair<Int, String>>()
    var defaultMessageInternalDate: Long? = null

    fun engine() = MockEngine { request ->
        requests += request
        val path = request.url.encodedPath
        val token = request.url.parameters["pageToken"]
        val index = token?.removePrefix("p")?.toIntOrNull() ?: 0
        val (status, body) = when {
            request.url.host == "oauth2.googleapis.com" -> tokenResponse
            path == "/gmail/v1/users/me/messages" -> unreadPages.getOrNull(index) ?: (404 to "{}")
            path.startsWith("/gmail/v1/users/me/messages/") -> {
                val id = path.removePrefix("/gmail/v1/users/me/messages/")
                messages[id] ?: defaultMessageInternalDate?.let { 200 to messageJson(id, it) } ?: (404 to "{}")
            }
            else -> 404 to "{}"
        }
        respond(body, HttpStatusCode.fromValue(status), headersOf(HttpHeaders.ContentType, "application/json"))
    }
}

class Rig(val fake: FakeGoogle = FakeGoogle(), writeToken: Boolean = true) {
    var now: Long = T0
    val sdb: ServerDb = openDb(tempDbPath())
    val store = SyncStore(sdb)
    val status = GoogleStatus()
    val tokenFile: File = Files.createTempDirectory("google-token-test").resolve("google-token.json").toFile()
    val client = HttpClient(fake.engine())
    val auth = GoogleAuth(GoogleConfig("cid", "secret"), tokenFile, client, { now })
    val api = GoogleApi(client, auth)
    val poller = GooglePoller(sdb, store, api, status) { now }

    init {
        if (writeToken) writeTokenFile(tokenFile, "refresh-1", T0)
    }

    suspend fun poll(at: Long = now) {
        now = at
        poller.run()
    }

    fun events(): List<Cal_event> = sdb.db.cal_eventQueries.selectAll().executeAsList().sortedBy { it.id }
    fun mail(): List<Mail_item> = sdb.db.mail_itemQueries.selectAll().executeAsList().sortedBy { it.id }
    fun seqCounter(): String? = sdb.db.kvQueries.get("seqCounter").executeAsOneOrNull()

    suspend fun seed(row: SyncRow) {
        sdb.writeLock.withLock { sdb.db.transaction { store.writeIfNewer(row) } }
    }

    suspend fun seedEvent(id: String, calendarId: String, eventId: String, startAt: Long, endAt: Long, updatedAt: Long = T0 - 1000) = seed(
        toSyncRow(Cal_event(id, updatedAt, 0, 0, "SYNCED", calendarId, eventId, "Seed $eventId", startAt, endAt, 0, null)),
    )

    suspend fun seedMail(gmailId: String, receivedAt: Long, updatedAt: Long = T0 - 1000) = seed(
        toSyncRow(Mail_item("mail:$gmailId", updatedAt, 0, 0, "SYNCED", gmailId, "s", "subj", "snip", receivedAt)),
    )
}

suspend fun HttpRequestData.bodyText(): String = String(body.toByteArray(), Charsets.UTF_8)
