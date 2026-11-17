package dev.tim.assistant.server.google

import dev.tim.assistant.server.GoogleConfig
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.http.parseQueryString
import java.net.HttpURLConnection
import java.net.URI
import java.nio.file.Files
import java.security.MessageDigest
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking

class GoogleAuthTest {
    private val config = GoogleConfig("client-id", "client-secret")

    private fun tokenFile() = Files.createTempDirectory("google-auth-test").resolve("google-token.json").toFile()

    private class TokenServer(var status: Int = 200, var body: String = OK_TOKEN_BODY) {
        val requests = ArrayList<HttpRequestData>()
        val bodies = ArrayList<String>()
        fun client() = HttpClient(
            MockEngine { request ->
                requests += request
                bodies += request.bodyText()
                respond(body, HttpStatusCode.fromValue(status), headersOf(HttpHeaders.ContentType, "application/json"))
            },
        )
    }

    @Test
    fun authUrlHasRequiredParametersAndS256Challenge() {
        val verifier = "verifier-" + "x".repeat(40)
        val challenge = Base64.getUrlEncoder().withoutPadding()
            .encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray()))
        assertEquals(challenge, pkceChallenge(verifier))
        val url = buildAuthUrl("client-id", "http://127.0.0.1:8765", "state-1", challenge)
        assertTrue(url.startsWith("https://accounts.google.com/o/oauth2/v2/auth?"))
        val p = parseQueryString(url.substringAfter('?'))
        assertEquals("client-id", p["client_id"])
        assertEquals("http://127.0.0.1:8765", p["redirect_uri"])
        assertEquals("code", p["response_type"])
        assertEquals(
            "https://www.googleapis.com/auth/gmail.readonly https://www.googleapis.com/auth/gmail.send " +
                "https://www.googleapis.com/auth/gmail.modify",
            p["scope"],
        )
        assertEquals("offline", p["access_type"])
        assertEquals("consent", p["prompt"])
        assertEquals("state-1", p["state"])
        assertEquals(challenge, p["code_challenge"])
        assertEquals("S256", p["code_challenge_method"])
    }

    @Test
    fun accessTokenIsCachedUntil60SecondsBeforeExpiryAndTokenFileIsReRead() = runBlocking<Unit> {
        val file = tokenFile()
        writeTokenFile(file, "refresh-A", 1)
        val server = TokenServer()
        var now = 1_000_000L
        val auth = GoogleAuth(config, file, server.client(), { now })
        assertEquals("at-1", auth.accessToken())
        now += 3_500_000 // 3540 s lifetime left is 100 s -> still cached (expiry 3600 s - 60 s margin)
        assertEquals("at-1", auth.accessToken())
        assertEquals(1, server.requests.size)
        now = 1_000_000L + 3_540_000 // exactly expiry - 60 s -> refresh
        writeTokenFile(file, "refresh-B", 2)
        auth.accessToken()
        assertEquals(2, server.requests.size)
        assertTrue("refresh_token=refresh-A" in server.bodies[0])
        assertTrue("refresh_token=refresh-B" in server.bodies[1])
        assertTrue("grant_type=refresh_token" in server.bodies[1])
        auth.invalidate()
        auth.accessToken()
        assertEquals(3, server.requests.size)
    }

    @Test
    fun invalidGrantAndMissingFileAreAuthExceptions() = runBlocking<Unit> {
        val file = tokenFile()
        writeTokenFile(file, "refresh-A", 1)
        val server = TokenServer(400, """{"error":"invalid_grant","error_description":"Token has been expired or revoked."}""")
        val auth = GoogleAuth(config, file, server.client(), { 0L })
        assertFailsWith<GoogleAuthException> { auth.accessToken() }
        val missing = GoogleAuth(config, tokenFile(), server.client(), { 0L })
        assertFailsWith<GoogleAuthException> { missing.accessToken() }
    }

    private suspend fun signIn(file: java.io.File, server: TokenServer, state: (String) -> String = { it }, onOut: (String) -> Unit): Int {
        val url = CompletableDeferred<String>()
        val code = kotlinx.coroutines.coroutineScope {
            val run = async {
                runGoogleAuth(config, file, server.client(), { 42L }, announce = { url.complete(it) }, out = onOut, port = 0, timeoutMs = 20_000)
            }
            val auth = url.await()
            val p = parseQueryString(auth.substringAfter('?'))
            val redirect = p["redirect_uri"]!!
            val callback = "$redirect/?code=the-code&state=${state(p["state"]!!)}"
            async(Dispatchers.IO) {
                // a request with a wrong state is ignored; the right one finishes the flow
                (URI("$redirect/?code=bad&state=wrong").toURL().openConnection() as HttpURLConnection).responseCode
                (URI(callback).toURL().openConnection() as HttpURLConnection).responseCode
            }.await()
            run.await()
        }
        return code
    }

    @Test
    fun successfulSignInSavesRefreshTokenAndReportsExpiryField() = runBlocking<Unit> {
        val file = tokenFile()
        val server = TokenServer(200, """{"access_token":"a","refresh_token":"new-refresh","expires_in":3600,"refresh_token_expires_in":604800}""")
        val out = ArrayList<String>()
        assertEquals(0, signIn(file, server) { out += it })
        assertTrue("new-refresh" in file.readText())
        assertTrue("saved" in out)
        assertTrue("refresh_token_expires_in present: yes" in out)
        val body = server.bodies.single()
        assertTrue("code=the-code" in body && "code_verifier=" in body && "grant_type=authorization_code" in body)
        assertTrue(out.none { "new-refresh" in it || "the-code" in it || "client-secret" in it })
    }

    @Test
    fun failedExchangeOrMissingRefreshTokenLeavesExistingTokenFileUnchanged() = runBlocking<Unit> {
        val file = tokenFile()
        writeTokenFile(file, "keep-me", 7)
        val before = file.readText()
        assertEquals(1, signIn(file, TokenServer(400, """{"error":"invalid_grant"}""")) { })
        assertEquals(before, file.readText())
        assertEquals(1, signIn(file, TokenServer(200, """{"access_token":"a","expires_in":3600}""")) { })
        assertEquals(before, file.readText())
        assertFalse(file.readText().contains("a\""))
    }

    @Test
    fun deniedAccessSavesNothing() = runBlocking<Unit> {
        val file = tokenFile()
        val server = TokenServer()
        val out = ArrayList<String>()
        val url = CompletableDeferred<String>()
        val code = kotlinx.coroutines.coroutineScope {
            val run = async {
                runGoogleAuth(config, file, server.client(), { 0L }, announce = { url.complete(it) }, out = { out += it }, port = 0, timeoutMs = 20_000)
            }
            val redirect = parseQueryString(url.await().substringAfter('?'))["redirect_uri"]!!
            async(Dispatchers.IO) { (URI("$redirect/?error=access_denied").toURL().openConnection() as HttpURLConnection).responseCode }.await()
            run.await()
        }
        assertEquals(1, code)
        assertFalse(file.exists())
        assertTrue(server.requests.isEmpty())
    }

    @Test
    fun timeoutStopsListenerAndSavesNothing() = runBlocking<Unit> {
        val file = tokenFile()
        val code = runGoogleAuth(config, file, TokenServer().client(), { 0L }, announce = { }, out = { }, port = 0, timeoutMs = 300)
        assertEquals(1, code)
        assertFalse(file.exists())
    }

    @Test
    fun tokenFileIsWrittenAtomicallyAsJson() {
        val file = tokenFile()
        writeTokenFile(file, "r1", 5)
        writeTokenFile(file, "r2", 6)
        assertEquals("""{"refresh_token":"r2","obtained_at":6}""", file.readText())
        assertEquals(listOf("google-token.json"), file.parentFile.list()!!.toList())
    }
}
