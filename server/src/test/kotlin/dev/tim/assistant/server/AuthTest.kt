package dev.tim.assistant.server

import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AuthTest {
    @Test
    fun noHeaderIs401() = testApplication {
        serverWith(SyncStore(openDb(tempDbPath())), testConfig("unused"))
        assertEquals(HttpStatusCode.Unauthorized, client.get("/v1/health").status)
    }

    @Test
    fun wrongTokenIs401() = testApplication {
        serverWith(SyncStore(openDb(tempDbPath())), testConfig("unused"))
        val r = client.get("/v1/health") { header(HttpHeaders.Authorization, "Bearer nope") }
        assertEquals(HttpStatusCode.Unauthorized, r.status)
    }

    @Test
    fun rightTokenIs200WithStatusOk() = testApplication {
        serverWith(SyncStore(openDb(tempDbPath())), testConfig("unused"))
        val r = client.get("/v1/health") { header(HttpHeaders.Authorization, "Bearer $TOKEN") }
        assertEquals(HttpStatusCode.OK, r.status)
        assertTrue(r.bodyAsText().contains("\"status\":\"ok\""))
    }

    @Test
    fun syncRoutesRequireTokenToo() = testApplication {
        serverWith(SyncStore(openDb(tempDbPath())), testConfig("unused"))
        assertEquals(HttpStatusCode.Unauthorized, client.pullRaw("?since=0", token = null).status)
        assertEquals(HttpStatusCode.Unauthorized, client.push(listOf(spendingRow("a", 1)), token = "bad").status)
    }
}
