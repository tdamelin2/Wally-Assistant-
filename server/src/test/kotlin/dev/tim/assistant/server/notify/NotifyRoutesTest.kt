package dev.tim.assistant.server.notify

import dev.tim.assistant.model.NotificationsResponse
import dev.tim.assistant.model.NotifySettingsDto
import dev.tim.assistant.server.TOKEN
import dev.tim.assistant.server.chat.CAL_TOKEN
import dev.tim.assistant.server.chat.CHAT_T0
import dev.tim.assistant.server.chat.ChatRig
import dev.tim.assistant.server.module
import dev.tim.assistant.server.testConfig
import dev.tim.assistant.server.testJson
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NotifyRoutesTest {
    private fun ApplicationTestBuilder.start(rig: ChatRig) {
        val config = testConfig("unused").let { it.copy(tokens = it.tokens + ("calendar" to CAL_TOKEN)) }
        application { module(config, rig.store, { rig.now }, notify = NotifyService(rig.sdb, rig.writer) { rig.now }) }
    }

    private suspend fun HttpClient.get(path: String, token: String? = TOKEN): HttpResponse =
        get(path) { if (token != null) header(HttpHeaders.Authorization, "Bearer $token") }

    private suspend fun HttpClient.putSettings(body: String, token: String? = TOKEN): HttpResponse =
        put("/v1/notify-settings") {
            if (token != null) header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody(body)
        }

    private fun ChatRig.queue(kind: String, ref: String?, key: String, fireAt: Long = CHAT_T0) {
        sdb.db.chatQueries.insertNotification(kind, "title $key", "body $key", ref, fireAt, CHAT_T0, key)
    }

    @Test
    fun theReplayReturnsEveryRowAfterTheCursorWithTheContractTargets() = testApplication {
        val rig = ChatRig()
        start(rig)
        rig.queue("CHECKIN", null, "k1")
        rig.queue("MORNING", null, "k2")
        rig.queue("HEADS_UP", "exam:e1", "k3")
        rig.queue("IDEA", null, "k4")
        rig.queue("WEEKLY", "weekly-report", "k5")
        val all = testJson.decodeFromString(NotificationsResponse.serializer(), client.get("/v1/notifications?since=0").bodyAsText()).items
        assertEquals(listOf("CHECKIN", "MORNING", "HEADS_UP", "IDEA", "WEEKLY"), all.map { it.kind })
        assertEquals(
            listOf("screen:checkin-add", "screen:today", "exam:e1", "idea:${all[3].id}", "chat:weekly-report"),
            all.map { it.target },
        )
        // a repeated call with the same cursor returns the same rows again (delivered_at is only "offered at")
        val again = testJson.decodeFromString(NotificationsResponse.serializer(), client.get("/v1/notifications?since=0").bodyAsText()).items
        assertEquals(all.map { it.id }, again.map { it.id })
        val after = testJson.decodeFromString(NotificationsResponse.serializer(), client.get("/v1/notifications?since=${all[2].id}").bodyAsText()).items
        assertEquals(listOf("IDEA", "WEEKLY"), after.map { it.kind })
        assertNotNull(rig.sdb.db.chatQueries.selectNotificationById(all[0].id).executeAsOne().delivered_at)
        rig.close()
    }

    @Test
    fun aMissingCursorMeansFromTheStartAndABadOneIs400() = testApplication {
        val rig = ChatRig()
        start(rig)
        rig.queue("CHECKIN", null, "k1")
        val noCursor = testJson.decodeFromString(NotificationsResponse.serializer(), client.get("/v1/notifications").bodyAsText())
        assertEquals(1, noCursor.items.size)
        assertEquals(HttpStatusCode.BadRequest, client.get("/v1/notifications?since=abc").status)
        assertEquals(HttpStatusCode.BadRequest, client.get("/v1/notifications?since=-1").status)
        rig.close()
    }

    @Test
    fun settingsDefaultToAllOnAndRoundTripThroughKv() = testApplication {
        val rig = ChatRig()
        start(rig)
        assertEquals(NotifySettingsDto(), testJson.decodeFromString(NotifySettingsDto.serializer(), client.get("/v1/notify-settings").bodyAsText()))
        val body = """{"checkinTime":"20:30","quietStart":"22:00","quietEnd":"06:30","checkin":false,"morningList":true,"headsUp":false,"ideas":true,"weeklyReport":false}"""
        val saved = client.putSettings(body)
        assertEquals(HttpStatusCode.OK, saved.status)
        val want = NotifySettingsDto("20:30", "22:00", "06:30", checkin = false, morningList = true, headsUp = false, ideas = true, weeklyReport = false)
        assertEquals(want, testJson.decodeFromString(NotifySettingsDto.serializer(), saved.bodyAsText()))
        assertEquals(want, testJson.decodeFromString(NotifySettingsDto.serializer(), client.get("/v1/notify-settings").bodyAsText()))
        assertEquals("20:30", rig.sdb.db.kvQueries.get(KV_CHECKIN_TIME).executeAsOneOrNull())
        assertEquals("morningList,ideas", rig.sdb.db.kvQueries.get(KV_KINDS).executeAsOneOrNull())
        // the scheduler reads the same values
        assertEquals(want, loadNotifySettings(rig.sdb.db))
        rig.close()
    }

    @Test
    fun allSwitchesOffAreStoredAsAnEmptyListAndStayOff() = testApplication {
        val rig = ChatRig()
        start(rig)
        client.putSettings("""{"checkin":false,"morningList":false,"headsUp":false,"ideas":false,"weeklyReport":false}""")
        assertEquals("", rig.sdb.db.kvQueries.get(KV_KINDS).executeAsOneOrNull())
        val s = loadNotifySettings(rig.sdb.db)
        assertTrue(!s.checkin && !s.morningList && !s.headsUp && !s.ideas && !s.weeklyReport)
        rig.close()
    }

    @Test
    fun badInputIs400AndNothingIsStored() = testApplication {
        val rig = ChatRig()
        start(rig)
        for (bad in listOf(
            """{"checkinTime":"25:00"}""", """{"checkinTime":"9:5"}""", """{"quietStart":"abc"}""", """{"quietEnd":"24:00"}""",
            """{"checkin":"yes"}""", "not json", """{"checkinTime":""}""",
        )) {
            assertEquals(HttpStatusCode.BadRequest, client.putSettings(bad).status, bad)
        }
        assertNull(rig.sdb.db.kvQueries.get(KV_CHECKIN_TIME).executeAsOneOrNull())
        assertNull(rig.sdb.db.kvQueries.get(KV_KINDS).executeAsOneOrNull())
        rig.close()
    }

    @Test
    fun theCalendarTokenGets403AndAnUnknownOrMissingTokenGets401() = testApplication {
        val rig = ChatRig()
        start(rig)
        assertEquals(HttpStatusCode.Forbidden, client.get("/v1/notifications?since=0", CAL_TOKEN).status)
        assertEquals(HttpStatusCode.Forbidden, client.get("/v1/notify-settings", CAL_TOKEN).status)
        assertEquals(HttpStatusCode.Forbidden, client.putSettings("{}", CAL_TOKEN).status)
        assertEquals(HttpStatusCode.Unauthorized, client.get("/v1/notifications?since=0", "wrong").status)
        assertEquals(HttpStatusCode.Unauthorized, client.get("/v1/notify-settings", null).status)
        assertEquals(HttpStatusCode.Unauthorized, client.putSettings("{}", null).status)
        assertNull(rig.sdb.db.kvQueries.get(KV_KINDS).executeAsOneOrNull())
        rig.close()
    }
}
