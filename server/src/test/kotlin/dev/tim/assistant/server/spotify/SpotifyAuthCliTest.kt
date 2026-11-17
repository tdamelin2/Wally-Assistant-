package dev.tim.assistant.server.spotify

import dev.tim.assistant.server.Config
import dev.tim.assistant.server.SpotifyConfig
import dev.tim.assistant.server.module
import dev.tim.assistant.server.parseConfig
import dev.tim.assistant.server.SyncStore
import dev.tim.assistant.server.openDb
import dev.tim.assistant.server.tempDbPath
import dev.tim.assistant.server.testConfig
import dev.tim.assistant.model.HealthResponse
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.parseQueryString
import io.ktor.server.testing.testApplication
import java.net.HttpURLConnection
import java.net.URI
import java.nio.file.Files
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json

class SpotifyAuthCliTest {
    private val config = SpotifyConfig("cid", "secret")

    private fun statusOf(url: String): Int {
        val c = URI(url).toURL().openConnection() as HttpURLConnection
        return try {
            c.responseCode
        } finally {
            c.disconnect()
        }
    }

    private class Run(val exit: Int, val announced: List<String>, val out: List<String>, val fake: FakeSpotify, val statuses: List<Int>)

    private fun runAuth(
        tokenResponse: Pair<Int, String>,
        tokenFile: java.io.File,
        browser: (redirect: String, state: String) -> List<Int>,
        timeoutMs: Long = 20_000,
    ): Run {
        val fake = FakeSpotify().also { it.tokenResponse = tokenResponse }
        val announced = ArrayList<String>()
        val out = ArrayList<String>()
        var statuses = emptyList<Int>()
        var worker: Thread? = null
        val exit = runBlocking {
            runSpotifyAuth(
                config, tokenFile, HttpClient(fake.engine()), { 1234L },
                announce = { url ->
                    announced += url
                    val p = parseQueryString(url.substringAfter('?'))
                    worker = thread { statuses = browser(p["redirect_uri"]!!, p["state"]!!) }
                },
                out = { out += it },
                port = 0,
                timeoutMs = timeoutMs,
            )
        }
        worker?.join()
        return Run(exit, announced, out, fake, statuses)
    }

    @Test
    fun authUrlUsesTheExactRedirectScopeAndState() {
        val url = buildSpotifyAuthUrl("cid", "http://127.0.0.1:8765/callback", "st")
        assertTrue(url.startsWith("https://accounts.spotify.com/authorize?"))
        val p = parseQueryString(url.substringAfter('?'))
        assertEquals("cid", p["client_id"])
        assertEquals("code", p["response_type"])
        assertEquals("http://127.0.0.1:8765/callback", p["redirect_uri"])
        assertEquals("user-read-recently-played", p["scope"])
        assertEquals("st", p["state"])
    }

    @Test
    fun onlyTheCallbackPathWithTheRightStateIsAccepted() {
        val file = Files.createTempDirectory("spotify-cli").resolve("spotify-token.json").toFile()
        val run = runAuth(200 to """{"access_token":"a","refresh_token":"refresh-OK","expires_in":3600}""", file, { redirect, state ->
            val base = redirect.removeSuffix("/callback")
            listOf(
                statusOf("$base/other?code=x&state=$state"),
                statusOf("$redirect?code=x&state=wrong"),
                statusOf("$redirect?code=x"),
                statusOf("$redirect?code=thecode&state=$state"),
            )
        })
        assertEquals(listOf(404, 400, 400, 200), run.statuses)
        assertEquals(0, run.exit)
        assertTrue(run.announced.single().contains("redirect_uri=http%3A%2F%2F127.0.0.1%3A"))
        assertTrue("refresh-OK" in file.readText())
        assertEquals("saved", run.out.first())
        val exchange = run.fake.bodies.single()
        assertTrue("grant_type=authorization_code" in exchange)
        assertTrue("code=thecode" in exchange)
        assertTrue(run.fake.requests.single().headers[HttpHeaders.Authorization]!!.startsWith("Basic "))
        assertFalse(run.out.any { "refresh-OK" in it || "secret" in it || "thecode" in it })
    }

    @Test
    fun deniedAndMissingRefreshTokenSaveNothing() {
        val file = Files.createTempDirectory("spotify-cli").resolve("spotify-token.json").toFile()
        val denied = runAuth(200 to "{}", file, { redirect, state -> listOf(statusOf("$redirect?error=access_denied&state=$state")) })
        assertEquals(1, denied.exit)
        assertFalse(file.exists())
        val noRefresh = runAuth(200 to """{"access_token":"a","expires_in":3600}""", file, { redirect, state ->
            listOf(statusOf("$redirect?code=c&state=$state"))
        })
        assertEquals(1, noRefresh.exit)
        assertFalse(file.exists())
        val refused = runAuth(400 to """{"error":"invalid_grant"}""", file, { redirect, state ->
            listOf(statusOf("$redirect?code=c&state=$state"))
        })
        assertEquals(1, refused.exit)
        assertFalse(file.exists())
    }

    @Test
    fun anExistingTokenSurvivesAFailedSignIn() {
        val file = Files.createTempDirectory("spotify-cli").resolve("spotify-token.json").toFile()
        dev.tim.assistant.server.google.writeTokenFile(file, "refresh-OLD", 1)
        val run = runAuth(400 to """{"error":"invalid_grant"}""", file, { redirect, state -> listOf(statusOf("$redirect?code=c&state=$state")) })
        assertEquals(1, run.exit)
        assertTrue("refresh-OLD" in file.readText())
    }

    @Test
    fun timesOutWithoutABrowser() {
        val file = Files.createTempDirectory("spotify-cli").resolve("spotify-token.json").toFile()
        val run = runAuth(200 to "{}", file, { _, _ -> emptyList() }, timeoutMs = 300)
        assertEquals(1, run.exit)
        assertFalse(file.exists())
    }

    @Test
    fun configParsesTheSpotifyBlockAndDefaultsToNull() {
        val base = """{"bindAddress":"127.0.0.1","dbPath":"/x","tokens":{}"""
        assertNull(parseConfig("$base}").spotify)
        assertEquals(SpotifyConfig("a", "b"), parseConfig("$base,\"spotify\":{\"clientId\":\"a\",\"clientSecret\":\"b\"}}").spotify)
    }

    @Test
    fun healthReportsSpotifyOkOnlyWhenConfigured() = testApplication {
        val status = SpotifyStatus()
        val store = SyncStore(openDb(tempDbPath()))
        val configured: Config = testConfig("unused").copy(spotify = config)
        application { module(configured, store, spotifyStatus = status) }
        suspend fun health(): HealthResponse {
            val r = client.get("/v1/health") { header(HttpHeaders.Authorization, "Bearer ${dev.tim.assistant.server.TOKEN}") }
            return Json { ignoreUnknownKeys = true }.decodeFromString(HealthResponse.serializer(), r.bodyAsText())
        }
        assertNull(health().spotifyOk)
        status.set(true)
        assertEquals(true, health().spotifyOk)
        status.set(false)
        assertEquals(false, health().spotifyOk)
    }

    @Test
    fun healthSpotifyOkIsNullWithoutAConfigBlockEvenIfStatusIsSet() = testApplication {
        val status = SpotifyStatus().also { it.set(true) }
        val store = SyncStore(openDb(tempDbPath()))
        application { module(testConfig("unused"), store, spotifyStatus = status) }
        val r = client.get("/v1/health") { header(HttpHeaders.Authorization, "Bearer ${dev.tim.assistant.server.TOKEN}") }
        val health = Json { ignoreUnknownKeys = true }.decodeFromString(HealthResponse.serializer(), r.bodyAsText())
        assertNull(health.spotifyOk)
    }
}
