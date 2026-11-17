package dev.tim.assistant.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

class SyncRowTest {
    private val json = Json

    private val hebrew = SyncRow(
        table = "journal",
        id = "j1",
        updatedAt = 1_700_000_000_000,
        deleted = false,
        data = JsonObject(mapOf("text" to JsonPrimitive("שלום 👋"), "createdAt" to JsonPrimitive(5L), "x" to JsonNull)),
    )

    @Test
    fun syncRow_roundTripKeepsHebrewAndEmoji() {
        val text = json.encodeToString(hebrew)
        assertEquals(hebrew, json.decodeFromString<SyncRow>(text))
        assertEquals("שלום 👋", (hebrew.data["text"] as JsonPrimitive).content)
    }

    @Test
    fun pushRequest_roundTrip() {
        val v = PushRequest(listOf(hebrew, hebrew.copy(id = "j2", deleted = true)))
        assertEquals(v, json.decodeFromString<PushRequest>(json.encodeToString(v)))
    }

    @Test
    fun pushResponse_roundTrip() {
        val v = PushResponse(listOf("a", "b"), listOf(Rejected("c", "שגיאה 👋")))
        assertEquals(v, json.decodeFromString<PushResponse>(json.encodeToString(v)))
    }

    @Test
    fun pullResponse_roundTrip() {
        val v = PullResponse(listOf(hebrew), Long.MAX_VALUE)
        assertEquals(v, json.decodeFromString<PullResponse>(json.encodeToString(v)))
    }

    @Test
    fun healthResponse_roundTrip() {
        val withValues = HealthResponse("ok", 1_700_000_000_000, true, 1_699_999_999_000)
        val withNulls = HealthResponse("ok", 1L, null, null)
        assertEquals(withValues, json.decodeFromString<HealthResponse>(json.encodeToString(withValues)))
        assertEquals(withNulls, json.decodeFromString<HealthResponse>(json.encodeToString(withNulls)))
    }
}
