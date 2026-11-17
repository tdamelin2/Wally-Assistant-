package dev.tim.assistant.server.google

import dev.tim.assistant.server.GoogleConfig
import io.ktor.client.HttpClient
import io.ktor.client.request.forms.submitForm
import io.ktor.client.statement.bodyAsText
import io.ktor.http.Parameters
import io.ktor.http.encodeURLParameter
import io.ktor.http.isSuccess
import io.ktor.http.parseQueryString
import java.io.File
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketTimeoutException
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
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

const val AUTH_CALLBACK_PORT = 8765
const val AUTH_TIMEOUT_MS = 10L * 60 * 1000

private val b64 = Base64.getUrlEncoder().withoutPadding()
private val random = SecureRandom()
private val cliJson = Json { ignoreUnknownKeys = true }

fun pkceChallenge(verifier: String): String =
    b64.encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(StandardCharsets.US_ASCII)))

private fun randomString(bytes: Int): String = ByteArray(bytes).also { random.nextBytes(it) }.let { b64.encodeToString(it) }

fun buildAuthUrl(clientId: String, redirectUri: String, state: String, challenge: String): String {
    val params = listOf(
        "client_id" to clientId,
        "redirect_uri" to redirectUri,
        "response_type" to "code",
        "scope" to GOOGLE_SCOPES.joinToString(" "),
        "access_type" to "offline",
        "prompt" to "consent",
        "state" to state,
        "code_challenge" to challenge,
        "code_challenge_method" to "S256",
    )
    return GOOGLE_AUTH_ENDPOINT + "?" + params.joinToString("&") { (k, v) -> "$k=${v.encodeURLParameter()}" }
}

private sealed interface Callback {
    data class Code(val code: String) : Callback
    data object Denied : Callback
    data class Failed(val error: String) : Callback
    data object TimedOut : Callback
}

private fun reply(socket: java.net.Socket, status: String, text: String) {
    val body = "<html><body><p>$text</p></body></html>".toByteArray(StandardCharsets.UTF_8)
    val head = "HTTP/1.1 $status\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n"
    socket.getOutputStream().apply {
        write(head.toByteArray(StandardCharsets.US_ASCII))
        write(body)
        flush()
    }
}

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
            val target = requestLine.split(" ").getOrNull(1) ?: return@use
            val query = target.substringAfter('?', "")
            val params = parseQueryString(query)
            val error = params["error"]
            val code = params["code"]
            when {
                error != null -> {
                    reply(s, "200 OK", "Wally sign-in was not completed. You can close this tab.")
                    return if (error == "access_denied") Callback.Denied else Callback.Failed(error)
                }
                code != null && params["state"] == state -> {
                    reply(s, "200 OK", "Wally is connected to Google. You can close this tab.")
                    return Callback.Code(code)
                }
                code != null -> reply(s, "400 Bad Request", "Unexpected request.")
                else -> reply(s, "404 Not Found", "Not found.")
            }
        }
    }
}

/**
 * One-shot sign-in. Binds the loopback listener first, then announces the URL. Returns the process
 * exit code. An existing token file is only replaced after a successful exchange that returned a
 * refresh token. Never prints tokens, codes or the secret.
 */
suspend fun runGoogleAuth(
    config: GoogleConfig,
    tokenFile: File,
    client: HttpClient,
    clock: () -> Long,
    announce: (String) -> Unit,
    out: (String) -> Unit,
    port: Int = AUTH_CALLBACK_PORT,
    timeoutMs: Long = AUTH_TIMEOUT_MS,
    tokenEndpoint: String = GOOGLE_TOKEN_ENDPOINT,
): Int {
    val server = try {
        ServerSocket(port, 1, InetAddress.getByName("127.0.0.1"))
    } catch (e: IOException) {
        out("cannot listen on 127.0.0.1:$port (is another google-auth running?)")
        return 1
    }
    server.use {
        val redirectUri = "http://127.0.0.1:${server.localPort}"
        val verifier = randomString(48)
        val state = randomString(24)
        announce(buildAuthUrl(config.clientId, redirectUri, state, pkceChallenge(verifier)))
        val callback = withContext(Dispatchers.IO) { awaitCallback(server, state, timeoutMs) }
        val code = when (callback) {
            is Callback.Code -> callback.code
            Callback.Denied -> {
                out("access was denied in the browser; nothing saved")
                return 1
            }
            is Callback.Failed -> {
                out("Google returned an error (${callback.error}); nothing saved")
                return 1
            }
            Callback.TimedOut -> {
                out("timed out waiting for the browser (10 minutes); nothing saved")
                return 1
            }
        }
        server.close()
        val response = try {
            client.submitForm(
                tokenEndpoint,
                Parameters.build {
                    append("client_id", config.clientId)
                    append("client_secret", config.clientSecret)
                    append("code", code)
                    append("code_verifier", verifier)
                    append("redirect_uri", redirectUri)
                    append("grant_type", "authorization_code")
                },
            )
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
            out("Google did not return a refresh token; nothing saved. Remove Wally at https://myaccount.google.com/permissions and run google-auth again")
            return 1
        }
        writeTokenFile(tokenFile, refresh, clock())
        out("saved")
        out("refresh_token_expires_in present: ${if (json.containsKey("refresh_token_expires_in")) "yes" else "no"}")
        return 0
    }
}
