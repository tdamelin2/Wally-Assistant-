package dev.tim.assistant.server.spotify

import dev.tim.assistant.server.SpotifyConfig
import dev.tim.assistant.server.google.writeTokenFile
import io.ktor.client.HttpClient
import io.ktor.client.request.forms.submitForm
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.Parameters
import io.ktor.http.encodeURLParameter
import io.ktor.http.isSuccess
import io.ktor.http.parseQueryString
import java.io.File
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import java.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

const val SPOTIFY_CALLBACK_PORT = 8765
const val SPOTIFY_CALLBACK_PATH = "/callback"
const val SPOTIFY_AUTH_TIMEOUT_MS = 5L * 60 * 1000

private val b64 = Base64.getUrlEncoder().withoutPadding()
private val random = SecureRandom()
private val cliJson = Json { ignoreUnknownKeys = true }

private fun randomString(bytes: Int): String = ByteArray(bytes).also { random.nextBytes(it) }.let { b64.encodeToString(it) }

fun buildSpotifyAuthUrl(clientId: String, redirectUri: String, state: String): String {
    val params = listOf(
        "client_id" to clientId,
        "response_type" to "code",
        "redirect_uri" to redirectUri,
        "scope" to SPOTIFY_SCOPE,
        "state" to state,
    )
    return SPOTIFY_AUTH_ENDPOINT + "?" + params.joinToString("&") { (k, v) -> "$k=${v.encodeURLParameter()}" }
}

private sealed interface Callback {
    data class Code(val code: String) : Callback
    data object Denied : Callback
    data class Failed(val error: String) : Callback
    data object TimedOut : Callback
}

private fun reply(socket: Socket, status: String, text: String) {
    val body = "<html><body><p>$text</p></body></html>".toByteArray(StandardCharsets.UTF_8)
    val head = "HTTP/1.1 $status\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n"
    socket.getOutputStream().apply {
        write(head.toByteArray(StandardCharsets.US_ASCII))
        write(body)
        flush()
    }
}

/** Accepts only `GET /callback?...` whose state matches; anything else gets a 400/404 and the wait goes on. */
private suspend fun awaitCallback(server: ServerSocket, state: String, timeoutMs: Long): Callback {
    val deadline = System.currentTimeMillis() + timeoutMs
    while (true) {
        currentCoroutineContext().ensureActive()
        val remaining = deadline - System.currentTimeMillis()
        if (remaining <= 0) return Callback.TimedOut
        server.soTimeout = minOf(remaining, 1000L).toInt()
        val socket = try {
            server.accept()
        } catch (e: SocketTimeoutException) {
            continue
        }
        socket.use { s ->
            s.soTimeout = 5000
            val requestLine = try {
                s.getInputStream().bufferedReader(StandardCharsets.US_ASCII).readLine()
            } catch (e: IOException) {
                null
            } ?: return@use
            val parts = requestLine.split(" ")
            val target = parts.getOrNull(1) ?: return@use
            if (parts[0] != "GET" || target.substringBefore('?') != SPOTIFY_CALLBACK_PATH) {
                reply(s, "404 Not Found", "Not found.")
                return@use
            }
            val params = parseQueryString(target.substringAfter('?', ""))
            if (params["state"] != state) {
                reply(s, "400 Bad Request", "Unexpected request.")
                return@use
            }
            val error = params["error"]
            val code = params["code"]
            when {
                error != null -> {
                    reply(s, "200 OK", "Wally sign-in was not completed. You can close this tab.")
                    return if (error == "access_denied") Callback.Denied else Callback.Failed(error)
                }
                code != null -> {
                    reply(s, "200 OK", "Wally is connected to Spotify. You can close this tab.")
                    return Callback.Code(code)
                }
                else -> reply(s, "400 Bad Request", "Unexpected request.")
            }
        }
    }
}

/**
 * One-shot sign-in (authorization code flow). Binds the loopback listener first, then announces the
 * URL; the redirect is `http://127.0.0.1:<port>/callback` (port 8765 by default, as registered at
 * Spotify). Returns the process exit code. An existing token file is only replaced after a successful
 * exchange that returned a refresh token. Never prints tokens, codes or the secret.
 */
suspend fun runSpotifyAuth(
    config: SpotifyConfig,
    tokenFile: File,
    client: HttpClient,
    clock: () -> Long,
    announce: (String) -> Unit,
    out: (String) -> Unit,
    port: Int = SPOTIFY_CALLBACK_PORT,
    timeoutMs: Long = SPOTIFY_AUTH_TIMEOUT_MS,
    tokenEndpoint: String = SPOTIFY_TOKEN_ENDPOINT,
): Int {
    val server = try {
        ServerSocket(port, 1, InetAddress.getByName("127.0.0.1"))
    } catch (e: IOException) {
        out("cannot listen on 127.0.0.1:$port (is another spotify-auth running?)")
        return 1
    }
    server.use {
        val redirectUri = "http://127.0.0.1:${server.localPort}$SPOTIFY_CALLBACK_PATH"
        val state = randomString(24)
        announce(buildSpotifyAuthUrl(config.clientId, redirectUri, state))
        val callback = withContext(Dispatchers.IO) { awaitCallback(server, state, timeoutMs) }
        val code = when (callback) {
            is Callback.Code -> callback.code
            Callback.Denied -> {
                out("access was denied in the browser; nothing saved")
                return 1
            }
            is Callback.Failed -> {
                out("Spotify returned an error (${callback.error}); nothing saved")
                return 1
            }
            Callback.TimedOut -> {
                out("timed out waiting for the browser (5 minutes); nothing saved")
                return 1
            }
        }
        server.close()
        val response = try {
            client.submitForm(
                tokenEndpoint,
                Parameters.build {
                    append("grant_type", "authorization_code")
                    append("code", code)
                    append("redirect_uri", redirectUri)
                },
            ) { header(HttpHeaders.Authorization, basicAuthHeader(config)) }
        } catch (e: IOException) {
            out("token exchange failed (${e::class.simpleName}); nothing saved")
            return 1
        }
        val json: JsonObject? = try {
            cliJson.parseToJsonElement(response.bodyAsText()).jsonObject
        } catch (e: Exception) {
            null
        }
        if (!response.status.isSuccess()) {
            val error = (json?.get("error") as? JsonPrimitive)?.content
            out("token exchange failed (http ${response.status.value}${if (error != null) ", $error" else ""}); nothing saved")
            return 1
        }
        val refresh = (json?.get("refresh_token") as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() }
        if (refresh == null) {
            out("Spotify did not return a refresh token; nothing saved")
            return 1
        }
        writeTokenFile(tokenFile, refresh, clock())
        out("saved")
        out("the sign-in lasts about 6 months: run spotify-auth again before then")
        return 0
    }
}
