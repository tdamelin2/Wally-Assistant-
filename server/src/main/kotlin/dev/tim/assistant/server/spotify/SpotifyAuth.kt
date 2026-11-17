package dev.tim.assistant.server.spotify

import dev.tim.assistant.server.SpotifyConfig
import dev.tim.assistant.server.google.TokenFileContent
import dev.tim.assistant.server.google.writeTokenFile
import io.ktor.client.HttpClient
import io.ktor.client.request.forms.submitForm
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.Parameters
import io.ktor.http.isSuccess
import java.io.File
import java.nio.charset.StandardCharsets
import java.util.Base64
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

const val SPOTIFY_AUTH_ENDPOINT = "https://accounts.spotify.com/authorize"
const val SPOTIFY_TOKEN_ENDPOINT = "https://accounts.spotify.com/api/token"
const val SPOTIFY_API_BASE = "https://api.spotify.com"
const val DEFAULT_SPOTIFY_TOKEN_PATH = "/var/lib/assistant/spotify-token.json"
const val SPOTIFY_SCOPE = "user-read-recently-played"

/** The refresh token is missing, expired, revoked, or Spotify refuses this app or user: only `spotify-auth` fixes it. */
class SpotifyAuthException(message: String) : Exception(message)

private val tokenJson = Json { ignoreUnknownKeys = true }

/** ASSISTANT_SPOTIFY_TOKEN overrides the default token path (development and tests). */
fun spotifyTokenFilePath(env: Map<String, String> = System.getenv()): File =
    File(env["ASSISTANT_SPOTIFY_TOKEN"]?.takeIf { it.isNotBlank() } ?: DEFAULT_SPOTIFY_TOKEN_PATH)

/** Basic header value for the token endpoint. */
internal fun basicAuthHeader(config: SpotifyConfig): String =
    "Basic " + Base64.getEncoder().encodeToString("${config.clientId}:${config.clientSecret}".toByteArray(StandardCharsets.UTF_8))

/** Thread-safe Spotify state for /v1/health: null unknown or not set up, true working, false needs `spotify-auth`. */
class SpotifyStatus {
    private val lock = Any()
    private var ok: Boolean? = null

    fun get(): Boolean? = synchronized(lock) { ok }

    fun set(value: Boolean?) = synchronized(lock) { ok = value }
}

/**
 * Turns the stored refresh token into short-lived access tokens. Refreshes are serialised, the token
 * file is re-read on every refresh, an access token is cached until 60 s before it expires, and a
 * rotated refresh token from the response is written back atomically (the old one is kept when the
 * response has none). Tokens and the secret are never logged or put in exception messages.
 */
class SpotifyAuth(
    private val config: SpotifyConfig,
    private val tokenFile: File,
    private val client: HttpClient,
    private val clock: () -> Long,
    private val tokenEndpoint: String = SPOTIFY_TOKEN_ENDPOINT,
) {
    private class Cached(val token: String, val expiresAt: Long)

    private val mutex = Mutex()

    @Volatile
    private var cached: Cached? = null

    /** True when the token file exists and holds a refresh token. */
    fun hasToken(): Boolean = readRefreshToken() != null

    fun invalidate() {
        cached = null
    }

    suspend fun accessToken(): String = mutex.withLock {
        val c = cached
        if (c != null && clock() < c.expiresAt - EXPIRY_MARGIN_MS) return c.token
        refresh()
    }

    /** Drops the cached access token and refreshes right away (used once after a 401). */
    suspend fun forceRefresh(): String = mutex.withLock {
        cached = null
        refresh()
    }

    private fun readRefreshToken(): String? = try {
        tokenJson.decodeFromString(TokenFileContent.serializer(), tokenFile.readText(StandardCharsets.UTF_8))
            .refreshToken.takeIf { it.isNotBlank() }
    } catch (e: Exception) {
        null
    }

    private suspend fun refresh(): String {
        val refreshToken = readRefreshToken() ?: throw SpotifyAuthException("token file missing, unreadable or empty")
        val response = client.submitForm(
            tokenEndpoint,
            Parameters.build {
                append("grant_type", "refresh_token")
                append("refresh_token", refreshToken)
            },
        ) { header(HttpHeaders.Authorization, basicAuthHeader(config)) }
        val json: JsonObject? = try {
            tokenJson.parseToJsonElement(response.bodyAsText()).jsonObject
        } catch (e: Exception) {
            null
        }
        if (!response.status.isSuccess()) {
            val error = (json?.get("error") as? JsonPrimitive)?.content
            if (error == "invalid_grant" || error == "invalid_client" || error == "unauthorized_client") {
                cached = null
                throw SpotifyAuthException("token endpoint refused the refresh token: $error")
            }
            throw IllegalStateException("token endpoint http ${response.status.value}")
        }
        val access = (json?.get("access_token") as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() }
            ?: throw IllegalStateException("token endpoint response has no access token")
        val expiresIn = (json["expires_in"] as? JsonPrimitive)?.content?.toLongOrNull() ?: 3600L
        val rotated = (json["refresh_token"] as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() }
        if (rotated != null && rotated != refreshToken) writeTokenFile(tokenFile, rotated, clock())
        cached = Cached(access, clock() + expiresIn * 1000)
        return access
    }

    private companion object {
        const val EXPIRY_MARGIN_MS = 60_000L
    }
}
