package dev.tim.assistant.server.spotify

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.isSuccess
import java.security.MessageDigest
import java.time.OffsetDateTime
import java.time.ZoneId
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

const val SPOTIFY_MAX_PAGES = 10
const val SPOTIFY_PAGE_LIMIT = 50
private const val MAX_TEXT = 500
private val JERUSALEM: ZoneId = ZoneId.of("Asia/Jerusalem")
private val json = Json { ignoreUnknownKeys = true }

/** One play from the recently-played list, already shaped for the listen table. */
data class Play(
    val id: String,
    val trackName: String,
    val artist: String,
    val durationMs: Long,
    val playedAt: Long,
    val localDate: String,
)

/** Spotify answered 429, 5xx or something unexpected: nothing to commit, try again next round. */
class SpotifyTransientException(message: String) : Exception(message)

/** Plays found in one run; [malformed] counts items skipped because a needed field was missing. */
class PlaysResult(val plays: List<Play>, val malformed: Int)

/** local files have no track id: a stable id from the name and artist keeps replays idempotent. */
internal fun localTrackId(name: String, artist: String): String {
    val digest = MessageDigest.getInstance("SHA-256").digest("$name|$artist".toByteArray(Charsets.UTF_8))
    return "local:" + digest.joinToString("") { "%02x".format(it) }.take(16)
}

/**
 * Reads GET /v1/me/player/recently-played. A 401 is retried once with a freshly refreshed token; 401
 * again, or 403 (not on the app's allowlist, or Premium lapsed), throws [SpotifyAuthException]. 429, 5xx
 * and other answers throw [SpotifyTransientException]. The bearer token is only ever sent to [apiBase].
 */
class SpotifyClient(
    private val client: HttpClient,
    private val auth: SpotifyAuth,
    private val apiBase: String = SPOTIFY_API_BASE,
) {
    suspend fun recentlyPlayed(after: Long?): PlaysResult {
        val plays = LinkedHashMap<String, Play>()
        var malformed = 0
        var url: String? = "$apiBase/v1/me/player/recently-played?limit=$SPOTIFY_PAGE_LIMIT" +
            (if (after != null) "&after=$after" else "")
        var token = auth.accessToken()
        var pages = 0
        while (url != null && pages < SPOTIFY_MAX_PAGES) {
            var response = get(url, token)
            if (response.status == HttpStatusCode.Unauthorized) {
                token = auth.forceRefresh()
                response = get(url, token)
            }
            when {
                response.status == HttpStatusCode.Unauthorized -> throw SpotifyAuthException("api refused the token (401)")
                response.status == HttpStatusCode.Forbidden -> throw SpotifyAuthException("api refused the user (403)")
                !response.status.isSuccess() -> throw SpotifyTransientException("api http ${response.status.value}")
            }
            val root = try {
                json.parseToJsonElement(response.bodyAsText()).jsonObject
            } catch (e: Exception) {
                throw SpotifyTransientException("api body is not json")
            }
            val items = root["items"] as? JsonArray ?: throw SpotifyTransientException("api body has no items")
            for (item in items) {
                val play = parsePlay(item as? JsonObject)
                if (play == null) malformed++ else plays.putIfAbsent(play.id, play)
            }
            pages++
            val next = (root["next"] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content
            url = next?.takeIf { it.startsWith("$apiBase/") }
        }
        return PlaysResult(plays.values.toList(), malformed)
    }

    private suspend fun get(url: String, token: String): HttpResponse =
        client.get(url) { header(HttpHeaders.Authorization, "Bearer $token") }

    private fun parsePlay(item: JsonObject?): Play? {
        item ?: return null
        val playedAtText = (item["played_at"] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content ?: return null
        val playedAt = try {
            OffsetDateTime.parse(playedAtText).toInstant().toEpochMilli()
        } catch (e: java.time.DateTimeException) {
            return null
        }
        val track = item["track"] as? JsonObject ?: return null
        val name = (track["name"] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content ?: return null
        val artists = (track["artists"] as? JsonArray).orEmpty()
            .mapNotNull { ((it as? JsonObject)?.get("name") as? JsonPrimitive)?.takeIf { p -> p !is JsonNull }?.content }
        val artist = artists.joinToString(", ")
        val duration = (track["duration_ms"] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content?.toLongOrNull() ?: 0L
        val trackId = (track["id"] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content?.takeIf { it.isNotBlank() }
            ?: localTrackId(name, artist)
        val date = java.time.Instant.ofEpochMilli(playedAt).atZone(JERUSALEM).toLocalDate().toString()
        return Play(
            id = "listen:$playedAt:$trackId",
            trackName = name.take(MAX_TEXT),
            artist = artist.take(MAX_TEXT),
            durationMs = duration,
            playedAt = playedAt,
            localDate = date,
        )
    }
}
