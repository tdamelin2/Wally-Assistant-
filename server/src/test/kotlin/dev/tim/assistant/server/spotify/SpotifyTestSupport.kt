package dev.tim.assistant.server.spotify

import dev.tim.assistant.db.Listen
import dev.tim.assistant.server.ServerDb
import dev.tim.assistant.server.SpotifyConfig
import dev.tim.assistant.server.SyncStore
import dev.tim.assistant.server.google.bodyText
import dev.tim.assistant.server.google.writeTokenFile
import dev.tim.assistant.server.openDb
import dev.tim.assistant.server.tempDbPath
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import java.io.File
import java.nio.file.Files
import java.time.Instant

val S0: Long = Instant.parse("2026-11-08T10:00:00Z").toEpochMilli()
const val SP_TOKEN_BODY = """{"access_token":"at-1","expires_in":3600,"token_type":"Bearer","scope":"user-read-recently-played"}"""
const val EMPTY_PAGE = """{"items":[],"next":null,"cursors":null,"limit":50}"""

fun iso(ms: Long): String = Instant.ofEpochMilli(ms).toString()

fun playItem(playedAt: Long, trackId: String?, name: String = "Song $trackId", artists: List<String> = listOf("Band"), duration: Long = 180_000): String {
    val id = if (trackId == null) "null" else "\"$trackId\""
    val names = artists.joinToString(",") { """{"name":"$it","id":"a"}""" }
    return """{"played_at":"${iso(playedAt)}","track":{"id":$id,"name":"$name","duration_ms":$duration,"artists":[$names],"is_local":${trackId == null}}}"""
}

fun playsPage(items: List<String>, next: String? = null): String =
    """{"items":[${items.joinToString(",")}],"next":${if (next != null) "\"$next\"" else "null"},"limit":50}"""

/** A scripted Spotify: [api] answers each recently-played call (index counts from 0), [tokenResponse] each token call. */
class FakeSpotify {
    val requests = ArrayList<HttpRequestData>()
    val bodies = ArrayList<String>()
    val apiCalls get() = requests.filter { it.url.host == "api.spotify.com" }
    val tokenCalls get() = requests.count { it.url.host == "accounts.spotify.com" }
    var tokenResponse: Pair<Int, String> = 200 to SP_TOKEN_BODY
    var api: (Int, HttpRequestData) -> Pair<Int, String> = { _, _ -> 200 to EMPTY_PAGE }

    fun engine() = MockEngine { request ->
        requests += request
        bodies += request.bodyText()
        val (status, body) = if (request.url.host == "accounts.spotify.com") {
            tokenResponse
        } else {
            api(requests.count { it.url.host == "api.spotify.com" } - 1, request)
        }
        respond(body, HttpStatusCode.fromValue(status), headersOf(HttpHeaders.ContentType, "application/json"))
    }
}

class SpotifyRig(configured: Boolean = true, writeToken: Boolean = true) {
    var now: Long = S0
    val fake = FakeSpotify()
    val sdb: ServerDb = openDb(tempDbPath())
    val store = SyncStore(sdb)
    val status = SpotifyStatus()
    val tokenFile: File = Files.createTempDirectory("spotify-token-test").resolve("spotify-token.json").toFile()
    val client = HttpClient(fake.engine())
    val auth = SpotifyAuth(SpotifyConfig("cid", "secret"), tokenFile, client, { now })
    val poller = if (configured) {
        SpotifyPoller(sdb, store, SpotifyClient(client, auth), auth, status) { now }
    } else {
        SpotifyPoller(sdb, store, null, null, status) { now }
    }

    init {
        if (writeToken) writeTokenFile(tokenFile, "refresh-1", S0)
    }

    suspend fun poll(at: Long = now) {
        now = at
        poller.run()
    }

    fun listens(): List<Listen> = sdb.db.listenQueries.selectAll().executeAsList().sortedBy { it.id }
    fun seq(): String? = sdb.db.kvQueries.get("seqCounter").executeAsOneOrNull()
    fun cursor(): String? = sdb.db.kvQueries.get(SPOTIFY_CURSOR_KEY).executeAsOneOrNull()
}
