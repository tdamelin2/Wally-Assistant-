package dev.tim.assistant.ai

import dev.tim.assistant.model.CardDisplayDto
import dev.tim.assistant.model.CardDto
import dev.tim.assistant.model.ChatMessageDto
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CardDisplayDtoTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun oldCardJsonWithoutDisplayStillDecodes() {
        val c = json.decodeFromString<CardDto>(
            """{"approvalId":"a1","tool":"update_exam","title":"t","summary":"s","status":"PENDING"}""",
        )
        assertNull(c.display)
        assertEquals("PENDING", c.status)
    }

    @Test
    fun displayRoundTrips() {
        val d = CardDisplayDto("Move exam", "Algebra exam", "Mon, Dec 1 09:00", "Tue, Dec 2 10:00", "Yes, move it")
        val c = CardDto("a1", "update_exam", "t", "s", "PENDING", d)
        val back = json.decodeFromString<CardDto>(json.encodeToString(c))
        assertEquals(d, back.display)
        assertEquals(c, back)
    }

    @Test
    fun messageWithUnknownKeysAndDisplayDecodes() {
        val m = json.decodeFromString<ChatMessageDto>(
            """{"id":"m","role":"assistant","text":"x","createdAt":1,"brain":"code","pending":{"tool":"t","args":{}},
            |"cards":[{"approvalId":"a","tool":"update_exam","title":"t","summary":"s","status":"PENDING",
            |"display":{"title":"Move exam","itemName":"Algebra exam","before":"b","after":"a","yesLabel":"Yes, move it","future":1}}]}""".trimMargin(),
        )
        assertEquals("Algebra exam", m.cards.single().display?.itemName)
    }
}
