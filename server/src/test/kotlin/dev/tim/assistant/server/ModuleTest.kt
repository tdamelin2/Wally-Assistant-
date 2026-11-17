package dev.tim.assistant.server

import dev.tim.assistant.server.google.GoogleStatus
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ModuleTest {
    @Test
    fun rootRouteIsNotServed() = testApplication {
        val db = openDb(tempDbPath())
        serverWith(SyncStore(db), testConfig("unused"))
        assertEquals(HttpStatusCode.NotFound, client.get("/").status)
    }

    @Test
    fun configParsesWithNullableGoogleAndOllama() {
        val c = parseConfig(
            """{"bindAddress":"100.1.2.3","port":8080,"dbPath":"/x.db","tokens":{"phone":"a","laptop":"b"}}""",
        )
        assertEquals("100.1.2.3", c.bindAddress)
        assertNull(c.google)
        assertNull(c.ollama)
        assertEquals(2, c.tokens.size)
    }

    @Test
    fun healthReturnsTheGoogleStatusSnapshot() = testApplication {
        val db = openDb(tempDbPath())
        val status = GoogleStatus()
        application { module(testConfig("unused"), SyncStore(db), googleStatus = status) }
        suspend fun health() = client.get("/v1/health") { header(HttpHeaders.Authorization, "Bearer $TOKEN") }.bodyAsText()
        assertEquals(true, health().contains("\"googleOk\":null") && health().contains("\"googleLastFetchAt\":null"))
        status.update(false, 1234L)
        val body = health()
        assertEquals(true, body.contains("\"googleOk\":false"))
        assertEquals(true, body.contains("\"googleLastFetchAt\":1234"))
        status.update(true, 99L)
        assertEquals(true, health().contains("\"googleOk\":true"))
    }
}
