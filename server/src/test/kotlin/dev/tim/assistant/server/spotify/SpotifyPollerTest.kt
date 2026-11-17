package dev.tim.assistant.server.spotify

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

class SpotifyPollerTest {
    private val t1 = S0 - 3_000_000
    private val t2 = S0 - 2_000_000
    private val t3 = S0 - 1_000_000

    @Test
    fun twoPollsWriteNoDuplicatesAndTheReplayDoesNotBumpServerSeq() = runBlocking<Unit> {
        val rig = SpotifyRig()
        rig.fake.api = { _, _ -> 200 to playsPage(listOf(playItem(t2, "b"), playItem(t1, "a", artists = listOf("X", "Y")))) }
        rig.poll()
        val first = rig.listens()
        assertEquals(listOf("listen:$t1:a", "listen:$t2:b"), first.map { it.id })
        assertEquals("X, Y", first[0].artist)
        assertEquals("Song a", first[0].trackName)
        assertEquals(180_000L, first[0].durationMs)
        assertEquals(t1, first[0].playedAt)
        assertEquals("2026-11-08", first[0].localDate)
        assertTrue(first.all { it.syncState == "SYNCED" && it.serverSeq > 0 && it.updatedAt == S0 })
        val seq = rig.seq()
        assertEquals(t2.toString(), rig.cursor())
        rig.poll(S0 + 1_800_000)
        assertEquals(first, rig.listens())
        assertEquals(seq, rig.seq())
        assertEquals(t2.toString(), rig.cursor())
        assertEquals(true, rig.status.get())
        // the second call sent the cursor
        assertEquals(t2.toString(), rig.fake.apiCalls[1].url.parameters["after"])
        assertEquals("50", rig.fake.apiCalls[1].url.parameters["limit"])
        assertNull(rig.fake.apiCalls[0].url.parameters["after"])
        assertEquals("Bearer at-1", rig.fake.apiCalls[0].headers["Authorization"])
    }

    @Test
    fun localTrackGetsAStableHashedId() = runBlocking<Unit> {
        val rig = SpotifyRig()
        rig.fake.api = { _, _ -> 200 to playsPage(listOf(playItem(t1, null, name = "My File", artists = listOf("Me")))) }
        rig.poll()
        val id = rig.listens().single().id
        assertTrue(Regex("listen:$t1:local:[0-9a-f]{16}").matches(id), id)
        val digest = java.security.MessageDigest.getInstance("SHA-256").digest("My File|Me".toByteArray())
        assertEquals("listen:$t1:local:" + digest.joinToString("") { "%02x".format(it) }.take(16), id)
        rig.poll(S0 + 1000)
        assertEquals(1, rig.listens().size)
    }

    @Test
    fun changedPayloadIsRewrittenWithAHigherUpdatedAtAndANewSeq() = runBlocking<Unit> {
        val rig = SpotifyRig()
        rig.fake.api = { _, _ -> 200 to playsPage(listOf(playItem(t1, "a", name = "Old"))) }
        rig.poll()
        val before = rig.listens().single()
        rig.fake.api = { _, _ -> 200 to playsPage(listOf(playItem(t1, "a", name = "New"))) }
        rig.poll(S0 - 5000) // a clock behind the stored stamp must still produce a higher updatedAt
        val after = rig.listens().single()
        assertEquals("New", after.trackName)
        assertEquals(before.updatedAt + 1, after.updatedAt)
        assertTrue(after.serverSeq > before.serverSeq)
    }

    @Test
    fun status429SkipsTheRoundAndLeavesCursorAndStatusAlone() = runBlocking<Unit> {
        val rig = SpotifyRig()
        rig.fake.api = { _, _ -> 200 to playsPage(listOf(playItem(t1, "a"))) }
        rig.poll()
        val seq = rig.seq()
        rig.fake.api = { _, _ -> 429 to """{"error":{"status":429}}""" }
        rig.poll(S0 + 1000)
        assertEquals(true, rig.status.get())
        assertEquals(t1.toString(), rig.cursor())
        assertEquals(seq, rig.seq())
        assertEquals(1, rig.listens().size)
    }

    @Test
    fun serverErrorAndSecondPageFailureCommitNothing() = runBlocking<Unit> {
        val rig = SpotifyRig()
        rig.fake.api = { _, _ -> 503 to "{}" }
        rig.poll()
        assertNull(rig.status.get())
        assertTrue(rig.listens().isEmpty())
        assertNull(rig.cursor())
        rig.fake.api = { i, _ ->
            if (i == 2) 500 to "{}" else 200 to playsPage(listOf(playItem(t1, "a")), next = "https://api.spotify.com/v1/me/player/recently-played?p=2")
        }
        rig.poll(S0 + 1000)
        assertTrue(rig.listens().isEmpty())
        assertNull(rig.cursor())
        assertNull(rig.status.get())
    }

    @Test
    fun noSpotifyBlockMeansNoCallsAndNullStatus() = runBlocking<Unit> {
        val rig = SpotifyRig(configured = false)
        rig.status.set(true)
        rig.poll()
        assertTrue(rig.fake.requests.isEmpty())
        assertNull(rig.status.get())
    }

    @Test
    fun noTokenFileMeansNoCallsAndNullStatus() = runBlocking<Unit> {
        val rig = SpotifyRig(writeToken = false)
        rig.poll()
        assertTrue(rig.fake.requests.isEmpty())
        assertNull(rig.status.get())
    }

    @Test
    fun emptyOkResponseMakesStatusTrueWithoutACursor() = runBlocking<Unit> {
        val rig = SpotifyRig()
        rig.poll()
        assertEquals(true, rig.status.get())
        assertNull(rig.cursor())
        assertTrue(rig.listens().isEmpty())
    }

    @Test
    fun cursorStaysPutWhenNothingNewComesBack() = runBlocking<Unit> {
        val rig = SpotifyRig()
        rig.fake.api = { _, _ -> 200 to playsPage(listOf(playItem(t1, "a"), playItem(t3, "c"))) }
        rig.poll()
        assertEquals(t3.toString(), rig.cursor())
        rig.fake.api = { _, _ -> 200 to EMPTY_PAGE }
        rig.poll(S0 + 1000)
        assertEquals(t3.toString(), rig.cursor())
        // an older play showing up late is written but cannot pull the cursor back
        rig.fake.api = { _, _ -> 200 to playsPage(listOf(playItem(t2, "b"))) }
        rig.poll(S0 + 2000)
        assertEquals(3, rig.listens().size)
        assertEquals(t3.toString(), rig.cursor())
    }

    @Test
    fun unauthorizedAfterOneRefreshRetryMakesStatusFalse() = runBlocking<Unit> {
        val rig = SpotifyRig()
        rig.fake.api = { _, _ -> 401 to """{"error":{"status":401}}""" }
        rig.poll()
        assertEquals(false, rig.status.get())
        assertEquals(2, rig.fake.tokenCalls) // first token plus exactly one forced refresh
        assertEquals(2, rig.fake.apiCalls.size)
        assertTrue(rig.listens().isEmpty())
    }

    @Test
    fun unauthorizedOnceThenOkRecoversWithTheRefreshedToken() = runBlocking<Unit> {
        val rig = SpotifyRig()
        rig.fake.api = { i, _ -> if (i == 0) 401 to "{}" else 200 to playsPage(listOf(playItem(t1, "a"))) }
        rig.poll()
        assertEquals(true, rig.status.get())
        assertEquals(1, rig.listens().size)
    }

    @Test
    fun forbiddenMakesStatusFalse() = runBlocking<Unit> {
        val rig = SpotifyRig()
        rig.fake.api = { _, _ -> 403 to """{"error":{"status":403}}""" }
        rig.poll()
        assertEquals(false, rig.status.get())
        assertEquals(1, rig.fake.apiCalls.size)
        assertTrue(rig.listens().isEmpty())
    }

    @Test
    fun invalidGrantMakesStatusFalseWithoutCallingTheApi() = runBlocking<Unit> {
        val rig = SpotifyRig()
        rig.fake.tokenResponse = 400 to """{"error":"invalid_grant","error_description":"Refresh token revoked"}"""
        rig.poll()
        assertEquals(false, rig.status.get())
        assertTrue(rig.fake.apiCalls.isEmpty())
    }

    @Test
    fun aNewTokenFileIsPickedUpWithoutARestartAfterAFailure() = runBlocking<Unit> {
        val rig = SpotifyRig()
        rig.fake.tokenResponse = 400 to """{"error":"invalid_grant"}"""
        rig.poll()
        assertEquals(false, rig.status.get())
        rig.fake.tokenResponse = 200 to SP_TOKEN_BODY
        dev.tim.assistant.server.google.writeTokenFile(rig.tokenFile, "refresh-2", S0 + 5)
        rig.poll(S0 + 1000)
        assertEquals(true, rig.status.get())
        assertTrue(rig.fake.bodies.any { "refresh_token=refresh-2" in it })
    }

    @Test
    fun rotatedRefreshTokenIsPersistedAndAnOmittedOneKeepsTheOld() = runBlocking<Unit> {
        val rig = SpotifyRig()
        rig.fake.tokenResponse = 200 to """{"access_token":"at-2","expires_in":30,"refresh_token":"refresh-NEW"}"""
        rig.poll()
        assertTrue("refresh-NEW" in rig.tokenFile.readText())
        // expires_in 30 s is inside the 60 s margin, so the next call refreshes again, now with the new token
        rig.fake.tokenResponse = 200 to """{"access_token":"at-3","expires_in":3600}"""
        rig.poll(S0 + 1000)
        assertTrue(rig.fake.bodies.any { "refresh_token=refresh-NEW" in it })
        assertTrue("refresh-NEW" in rig.tokenFile.readText())
        assertFalse("refresh-1" in rig.tokenFile.readText())
        val basic = rig.fake.requests.first { it.url.host == "accounts.spotify.com" }.headers["Authorization"]
        assertEquals("Basic " + java.util.Base64.getEncoder().encodeToString("cid:secret".toByteArray()), basic)
    }

    @Test
    fun followsNextPagesUpToTenAndNeverLeavesTheApiHost() = runBlocking<Unit> {
        val rig = SpotifyRig()
        rig.fake.api = { i, _ ->
            200 to playsPage(listOf(playItem(t1 + i, "t$i")), next = "https://api.spotify.com/v1/me/player/recently-played?page=${i + 1}")
        }
        rig.poll()
        assertEquals(10, rig.fake.apiCalls.size)
        assertEquals(10, rig.listens().size)
        val rig2 = SpotifyRig()
        rig2.fake.api = { _, _ -> 200 to playsPage(listOf(playItem(t1, "a")), next = "https://evil.example/steal") }
        rig2.poll()
        assertEquals(1, rig2.fake.apiCalls.size)
        assertTrue(rig2.fake.requests.none { it.url.host == "evil.example" })
    }

    @Test
    fun malformedItemsAreSkippedAndTheRestIsStored() = runBlocking<Unit> {
        val rig = SpotifyRig()
        rig.fake.api = { _, _ ->
            200 to playsPage(listOf("""{"played_at":"nope","track":{"name":"x"}}""", """{"track":null}""", playItem(t1, "a")))
        }
        rig.poll()
        assertEquals(listOf("listen:$t1:a"), rig.listens().map { it.id })
        assertEquals(true, rig.status.get())
    }

    @Test
    fun trackAndArtistAreCutAtFiveHundredCharacters() = runBlocking<Unit> {
        val rig = SpotifyRig()
        rig.fake.api = { _, _ -> 200 to playsPage(listOf(playItem(t1, "a", name = "n".repeat(600), artists = listOf("a".repeat(400), "b".repeat(400))))) }
        rig.poll()
        val row = rig.listens().single()
        assertEquals(500, row.trackName.length)
        assertEquals(500, row.artist.length)
    }

    @Test
    fun noSecretOrTokenLeaksIntoTheTokenFileBesidesTheRefreshToken() = runBlocking<Unit> {
        val rig = SpotifyRig()
        rig.poll()
        val text = rig.tokenFile.readText()
        assertFalse("secret" in text)
        assertFalse("at-1" in text)
    }
}
