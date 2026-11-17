package dev.tim.assistant.server.google

import dev.tim.assistant.server.GoogleConfig
import io.ktor.client.HttpClient
import io.ktor.client.request.forms.submitForm
import io.ktor.client.statement.bodyAsText
import io.ktor.http.Parameters
import io.ktor.http.isSuccess
import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermissions
import java.util.concurrent.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

const val GOOGLE_AUTH_ENDPOINT = "https://accounts.google.com/o/oauth2/v2/auth"
const val GOOGLE_TOKEN_ENDPOINT = "https://oauth2.googleapis.com/token"
const val DEFAULT_TOKEN_PATH = "/var/lib/assistant/google-token.json"
val GOOGLE_SCOPES = listOf(
    "https://www.googleapis.com/auth/gmail.readonly",
    "https://www.googleapis.com/auth/gmail.send",
    "https://www.googleapis.com/auth/gmail.modify",
)

/** The refresh token is missing, unreadable, revoked or expired: only `google-auth` can fix it. */
class GoogleAuthException(message: String) : Exception(message)

@Serializable
data class TokenFileContent(
    @SerialName("refresh_token") val refreshToken: String,
    @SerialName("obtained_at") val obtainedAt: Long,
)

private val tokenJson = Json { ignoreUnknownKeys = true }

/** ASSISTANT_GOOGLE_TOKEN overrides the default path (development only). */
fun tokenFilePath(env: Map<String, String> = System.getenv()): File =
    File(env["ASSISTANT_GOOGLE_TOKEN"]?.takeIf { it.isNotBlank() } ?: DEFAULT_TOKEN_PATH)

/** Writes the token file through a temp file in the same folder, then moves it into place atomically. */
fun writeTokenFile(file: File, refreshToken: String, obtainedAt: Long) {
    val target = file.absoluteFile.toPath()
    val dir = target.parent
    Files.createDirectories(dir)
    val tmp = Files.createTempFile(dir, "google-token", ".tmp")
    try {
        val text = tokenJson.encodeToString(TokenFileContent.serializer(), TokenFileContent(refreshToken, obtainedAt))
        Files.write(tmp, text.toByteArray(StandardCharsets.UTF_8))
        if ("posix" in FileSystems.getDefault().supportedFileAttributeViews()) {
            Files.setPosixFilePermissions(tmp, PosixFilePermissions.fromString("rw-------"))
        }
        Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    } finally {
        Files.deleteIfExists(tmp)
    }
}

/**
 * Turns the stored refresh token into short-lived access tokens. Refreshes are serialised, the token
 * file is re-read on every refresh, and an access token is cached until 60 s before it expires.
 */
class GoogleAuth(
    private val config: GoogleConfig,
    private val tokenFile: File,
    private val client: HttpClient,
    private val clock: () -> Long,
    private val tokenEndpoint: String = GOOGLE_TOKEN_ENDPOINT,
) {
    private class Cached(val token: String, val expiresAt: Long)

    private val mutex = Mutex()

    @Volatile
    private var cached: Cached? = null

    suspend fun accessToken(): String = mutex.withLock {
        val c = cached
        if (c != null && clock() < c.expiresAt - EXPIRY_MARGIN_MS) return c.token
        refresh()
    }

    fun invalidate() {
        cached = null
    }

    private fun readRefreshToken(): String {
        val text = try {
            tokenFile.readText(StandardCharsets.UTF_8)
        } catch (e: Exception) {
            throw GoogleAuthException("token file missing or unreadable")
        }
        val content = try {
            tokenJson.decodeFromString(TokenFileContent.serializer(), text)
        } catch (e: Exception) {
            throw GoogleAuthException("token file is not valid")
        }
        if (content.refreshToken.isBlank()) throw GoogleAuthException("token file has no refresh token")
        return content.refreshToken
    }

    private suspend fun refresh(): String {
        val refreshToken = readRefreshToken()
        val response = try {
            client.submitForm(
                tokenEndpoint,
                Parameters.build {
                    append("client_id", config.clientId)
                    append("client_secret", config.clientSecret)
                    append("refresh_token", refreshToken)
                    append("grant_type", "refresh_token")
                },
            )
        } catch (e: CancellationException) {
            throw e
        }
        val body = response.bodyAsText()
        val json: JsonObject? = try {
            tokenJson.parseToJsonElement(body).jsonObject
        } catch (e: Exception) {
            null
        }
        if (!response.status.isSuccess()) {
            val error = (json?.get("error") as? JsonPrimitive)?.content
            if (error == "invalid_grant" || error == "invalid_client" || error == "unauthorized_client") {
                cached = null
                throw GoogleAuthException("token endpoint refused the refresh token: $error")
            }
            throw IllegalStateException("token endpoint http ${response.status.value}")
        }
        val access = (json?.get("access_token") as? JsonPrimitive)?.content
            ?: throw IllegalStateException("token endpoint response has no access token")
        val expiresIn = (json["expires_in"] as? JsonPrimitive)?.content?.toLongOrNull() ?: 3600L
        cached = Cached(access, clock() + expiresIn * 1000)
        return access
    }

    private companion object {
        const val EXPIRY_MARGIN_MS = 60_000L
    }
}
