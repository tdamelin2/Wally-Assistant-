package dev.tim.assistant.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class ChatDtosTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun missingRoute_decodesToAuto() {
        val old = """{"messageId":"m1","text":"hi","createdAt":5}"""
        assertEquals(ROUTE_AUTO, json.decodeFromString<SendMessageRequest>(old).route)
    }

    @Test
    fun everyRoute_roundTrips() {
        for (route in ROUTES) {
            val request = SendMessageRequest(messageId = "m1", text = "hi", createdAt = 5, route = route)
            val back = json.decodeFromString<SendMessageRequest>(json.encodeToString(request))
            assertEquals(route, back.route)
            assertEquals(request, back)
        }
    }

    @Test
    fun routeConstants_haveTheContractValues() {
        assertEquals(setOf("auto", "model", "codex"), ROUTES)
        assertEquals("auto", ROUTE_AUTO)
        assertEquals("model", ROUTE_MODEL)
        assertEquals("codex", ROUTE_CODEX)
        assertTrue(CODEX_ASKED_TEXT.startsWith("Asked Codex."))
    }

    @Test
    fun suggestAndSlotChoices_roundTrip() {
        for (choice in listOf(
            ChoiceDto(kind = "suggest", id = "0", name = "Add task: milk", token = "t1"),
            ChoiceDto(kind = "suggest", id = "else", name = "Something else", token = "t2"),
            ChoiceDto(kind = "slot", id = "category=food", name = "Food", token = "t3"),
        )) {
            assertEquals(choice, json.decodeFromString<ChoiceDto>(json.encodeToString(choice)))
        }
    }
}
