package dev.tim.assistant.server.google

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

class GooglePollerTest {
    private fun rigWith(setup: FakeGoogle.() -> Unit = {}, writeToken: Boolean = true): Rig {
        val fake = FakeGoogle()
        val rig = Rig(fake, writeToken)
        fake.setup()
        return rig
    }

    @Test
    fun newMailIsInserted() = runBlocking<Unit> {
        val rig = rigWith({
            unreadPages = listOf(200 to idsPage(listOf("m1")))
            messages["m1"] = 200 to messageJson("m1", T0 - 1000, from = "Bob <bob@example.com>", subject = "Hi")
        })
        rig.poll()
        val mail = rig.mail().single()
        assertEquals("mail:m1", mail.id)
        assertEquals("Bob <bob@example.com>", mail.sender)
        assertEquals("Hi", mail.subject)
        assertEquals(T0 - 1000, mail.receivedAt)
        assertTrue(mail.syncState == "SYNCED" && mail.serverSeq > 0)
        assertEquals(true to T0, rig.status.snapshot().let { (it.first ?: false) to it.second })
    }

    @Test
    fun readMailIsSoftDeleted() = runBlocking<Unit> {
        val rig = rigWith({
            unreadPages = listOf(200 to idsPage(listOf("m1")))
            messages["m1"] = 200 to messageJson("m1", T0 - 1000)
        })
        rig.poll()
        rig.fake.unreadPages = listOf(200 to idsPage(emptyList()))
        rig.poll(T0 + 60_000)
        assertEquals(1L, rig.mail().single().deleted)
    }

    @Test
    fun readThenUnreadMailIsUndeleted() = runBlocking<Unit> {
        val rig = rigWith({
            unreadPages = listOf(200 to idsPage(listOf("m1")))
            messages["m1"] = 200 to messageJson("m1", T0 - 1000)
        })
        rig.poll()
        rig.fake.unreadPages = listOf(200 to idsPage(emptyList()))
        rig.poll(T0 + 60_000)
        rig.fake.unreadPages = listOf(200 to idsPage(listOf("m1")))
        rig.poll(T0 + 120_000)
        assertEquals(0L, rig.mail().single().deleted)
    }

    @Test
    fun unreadMailOlderThan30DaysStaysDeleted() = runBlocking<Unit> {
        val rig = rigWith({
            unreadPages = listOf(200 to idsPage(listOf("m1")))
            messages["m1"] = 200 to messageJson("m1", T0 - 29 * DAY)
        })
        rig.poll()
        assertEquals(0L, rig.mail().single().deleted)
        rig.fake.unreadPages = listOf(200 to idsPage(emptyList()))
        rig.poll(T0 + 60_000)
        assertEquals(1L, rig.mail().single().deleted)
        rig.fake.unreadPages = listOf(200 to idsPage(listOf("m1")))
        rig.poll(T0 + 2 * DAY)
        assertEquals(1L, rig.mail().single().deleted)
    }

    @Test
    fun secondIdenticalPollChangesNothing() = runBlocking<Unit> {
        val rig = rigWith({
            unreadPages = listOf(200 to idsPage(listOf("m1")))
            messages["m1"] = 200 to messageJson("m1", T0 - 1000)
        })
        rig.poll()
        val seqBefore = rig.seqCounter()
        val serverSeqs = rig.mail().map { it.serverSeq }
        rig.poll(T0 + 15 * 60_000)
        assertEquals(seqBefore, rig.seqCounter())
        assertEquals(serverSeqs, rig.mail().map { it.serverSeq })
        assertEquals(T0 + 15 * 60_000, rig.status.snapshot().second)
    }

    @Test
    fun failedSecondMailPageDeletesNothing() = runBlocking<Unit> {
        val rig = rigWith({
            unreadPages = listOf(200 to idsPage(listOf("m1", "m2")))
            defaultMessageInternalDate = T0 - 1000
        })
        rig.poll()
        val seq = rig.seqCounter()
        rig.fake.unreadPages = listOf(200 to idsPage(listOf("m1"), next = "p1"), 500 to "{}")
        rig.poll(T0 + 60_000)
        assertTrue(rig.mail().all { it.deleted == 0L })
        assertEquals(seq, rig.seqCounter())
        assertEquals(true to T0, rig.status.snapshot().let { (it.first ?: false) to it.second })
    }

    @Test
    fun unauthorizedFromGoogleSetsNotOkAndChangesNoRows() = runBlocking<Unit> {
        val rig = rigWith({
            unreadPages = listOf(200 to idsPage(listOf("m1")))
            messages["m1"] = 200 to messageJson("m1", T0 - 1000)
        })
        rig.poll()
        val seq = rig.seqCounter()
        rig.fake.unreadPages = listOf(401 to "{}")
        rig.poll(T0 + 60_000)
        assertEquals(false, rig.status.snapshot().first)
        assertEquals(T0, rig.status.snapshot().second)
        assertEquals(seq, rig.seqCounter())
        assertEquals(0L, rig.mail().single().deleted)
        // the access token was dropped, so the next round asks for a new one
        val tokenCallsBefore = rig.fake.tokenCalls
        rig.fake.unreadPages = listOf(200 to idsPage(listOf("m1")))
        rig.poll(T0 + 120_000)
        assertEquals(tokenCallsBefore + 1, rig.fake.tokenCalls)
        assertEquals(true, rig.status.snapshot().first)
    }

    @Test
    fun missingTokenFileMeansNotOk() = runBlocking<Unit> {
        val rig = rigWith(writeToken = false)
        rig.poll()
        assertEquals(false, rig.status.snapshot().first)
        assertNull(rig.status.snapshot().second)
        assertTrue(rig.mail().isEmpty())
    }

    @Test
    fun lateMetadataUnauthorizedAbortsTheWholeRound() = runBlocking<Unit> {
        val rig = rigWith({
            unreadPages = listOf(200 to idsPage(listOf("m1", "m2")))
            messages["m1"] = 200 to messageJson("m1", T0 - 1000)
            messages["m2"] = 401 to "{}"
        })
        rig.seedMail("old", T0 - 1000)
        val seq = rig.seqCounter()
        rig.poll()
        assertEquals(seq, rig.seqCounter())
        assertEquals(listOf("mail:old"), rig.mail().map { it.id })
        assertTrue(rig.mail().all { it.deleted == 0L })
        assertEquals(false, rig.status.snapshot().first)
    }

    @Test
    fun idCapMeansNoAbsenceDeletesAndStillSucceeds() = runBlocking<Unit> {
        val ids1 = (1..100).map { "a$it" }
        val ids2 = (1..100).map { "b$it" }
        val ids3 = (1..100).map { "c$it" }
        val rig = rigWith({
            unreadPages = listOf(
                200 to idsPage(ids1, next = "p1"),
                200 to idsPage(ids2, next = "p2"),
                200 to idsPage(ids3, next = "p3"),
            )
            defaultMessageInternalDate = T0 - 1000
        })
        rig.seedMail("stored-but-absent", T0 - 1000)
        rig.poll()
        val mail = rig.mail()
        assertEquals(201, mail.size)
        assertEquals(0L, mail.single { it.gmailId == "stored-but-absent" }.deleted)
        assertEquals(200, mail.count { it.gmailId != "stored-but-absent" })
        assertEquals(true, rig.status.snapshot().first)
        assertEquals(T0, rig.status.snapshot().second)
    }

    @Test
    fun rateLimitKeepsStatusAndRows() = runBlocking<Unit> {
        val rig = rigWith({
            unreadPages = listOf(200 to idsPage(listOf("m1")))
            messages["m1"] = 200 to messageJson("m1", T0 - 1000)
        })
        rig.poll()
        val seq = rig.seqCounter()
        rig.fake.unreadPages = listOf(403 to """{"error":{"errors":[{"reason":"rateLimitExceeded"}],"status":"PERMISSION_DENIED"}}""")
        rig.poll(T0 + 60_000)
        assertEquals(true, rig.status.snapshot().first)
        assertEquals(T0, rig.status.snapshot().second)
        assertEquals(seq, rig.seqCounter())
        assertEquals(0L, rig.mail().single().deleted)
        assertTrue(rig.fake.tokenCalls >= 1)
    }

    @Test
    fun insufficientPermissionsIsAnAuthFailure() = runBlocking<Unit> {
        val rig = rigWith({ unreadPages = listOf(403 to """{"error":{"errors":[{"reason":"insufficientPermissions"}]}}""") })
        rig.poll()
        assertEquals(false, rig.status.snapshot().first)
    }

    @Test
    fun oldMailExpiresAsTombstones() = runBlocking<Unit> {
        val rig = rigWith()
        rig.seedMail("oldMail", T0 - 31 * DAY)
        rig.seedMail("freshMail", T0 - 5 * DAY)
        rig.fake.unreadPages = listOf(200 to idsPage(listOf("freshMail")))
        rig.poll()
        val mail = rig.mail().associateBy { it.gmailId }
        assertEquals(1L, mail.getValue("oldMail").deleted)
        assertEquals(0L, mail.getValue("freshMail").deleted)
        assertNotNull(mail["oldMail"])
    }

    @Test
    fun gmailPollNeverTouchesCalendarRows() = runBlocking<Unit> {
        val rig = rigWith({
            unreadPages = listOf(200 to idsPage(listOf("m1")))
            messages["m1"] = 200 to messageJson("m1", T0 - 1000)
        })
        // a wcal row far in the past and an old Google copy: neither is changed by a Gmail poll
        rig.seedEvent("wcal:e1:2026-01-01", "wallycal", "e1", T0 - 90 * DAY, T0 - 89 * DAY)
        rig.seedEvent("cal:primary:g1", "primary", "g1", T0 - 90 * DAY, T0 - 89 * DAY)
        val before = rig.events()
        rig.poll()
        assertEquals(before, rig.events())
        assertTrue(rig.events().all { it.deleted == 0L })
    }

    @Test
    fun onlyGetRequestsGoToGoogleApisAndPostOnlyToTokenEndpoint() = runBlocking<Unit> {
        val rig = rigWith({
            unreadPages = listOf(200 to idsPage(listOf("m1")))
            messages["m1"] = 200 to messageJson("m1", T0 - 1000)
        })
        rig.poll()
        val requests = rig.fake.requests
        assertTrue(requests.isNotEmpty())
        for (r in requests) {
            if (r.url.host == "oauth2.googleapis.com") {
                assertEquals("POST", r.method.value)
            } else {
                assertEquals("GET", r.method.value)
                assertEquals("gmail.googleapis.com", r.url.host)
            }
        }
        val gmail = requests.first { it.url.encodedPath.endsWith("/messages/m1") }.url.parameters
        assertEquals("metadata", gmail["format"])
        assertEquals(listOf("From", "Subject"), gmail.getAll("metadataHeaders"))
        val list = requests.first { it.url.encodedPath == "/gmail/v1/users/me/messages" }.url.parameters
        assertEquals("is:unread in:inbox newer_than:30d", list["q"])
        assertEquals("100", list["maxResults"])
    }
}
