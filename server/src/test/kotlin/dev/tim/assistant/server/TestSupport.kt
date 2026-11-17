package dev.tim.assistant.server

import dev.tim.assistant.model.PushRequest
import dev.tim.assistant.model.SyncRow
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.server.testing.ApplicationTestBuilder
import java.nio.file.Files
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

const val TOKEN = "test-token-laptop"

fun testConfig(dbPath: String) = Config(
    bindAddress = "127.0.0.1",
    port = 0,
    dbPath = dbPath,
    tokens = mapOf("phone" to "test-token-phone", "laptop" to TOKEN),
)

fun tempDbPath(): String = Files.createTempDirectory("assistant-server-test").resolve("test.db").toString()

val testJson = Json { ignoreUnknownKeys = true }

fun obj(vararg pairs: Pair<String, Any?>): JsonObject = JsonObject(
    pairs.associate { (k, v) ->
        k to when (v) {
            null -> JsonNull
            is JsonElement -> v
            is Boolean -> JsonPrimitive(v)
            is Number -> JsonPrimitive(v)
            else -> JsonPrimitive(v.toString())
        }
    },
)

fun spendingRow(id: String, updatedAt: Long, amount: Long = 1250, category: String = "Food", deleted: Boolean = false) =
    SyncRow(
        "spending", id, updatedAt, deleted,
        obj("amountAgorot" to amount, "category" to category, "note" to null, "localDate" to "2026-11-05"),
    )

fun journalRow(id: String, updatedAt: Long, text: String = "hello") =
    SyncRow("journal", id, updatedAt, false, obj("text" to text, "createdAt" to updatedAt))

fun checkinRow(date: String, updatedAt: Long, mood: Int = 3) = SyncRow(
    "checkin", "checkin:$date", updatedAt, false,
    obj("localDate" to date, "mood" to mood, "energy" to 3, "sleepQuality" to 3, "stress" to 3, "note" to null),
)

fun mealRow(
    id: String,
    updatedAt: Long,
    type: String = "MEAL",
    text: String = "pasta",
    kcal: Long? = null,
    deleted: Boolean = false,
) = SyncRow(
    "meal", id, updatedAt, deleted,
    obj(
        "type" to type, "text" to text, "eatenAt" to 1_700_000_000_000L, "localDate" to "2023-11-15",
        "kcal" to kcal, "proteinG" to null, "carbsG" to null, "fatG" to null, "estimateModel" to null,
    ),
)

suspend fun HttpClient.push(rows: List<SyncRow>, token: String? = TOKEN): HttpResponse =
    post("/v1/sync/push") {
        if (token != null) header(HttpHeaders.Authorization, "Bearer $token")
        contentType(ContentType.Application.Json)
        setBody(testJson.encodeToString(PushRequest.serializer(), PushRequest(rows)))
    }

suspend fun HttpClient.pushRaw(body: String, token: String? = TOKEN): HttpResponse =
    post("/v1/sync/push") {
        if (token != null) header(HttpHeaders.Authorization, "Bearer $token")
        contentType(ContentType.Application.Json)
        setBody(body)
    }

suspend fun HttpClient.pullRaw(query: String, token: String? = TOKEN): HttpResponse =
    get("/v1/sync/pull$query") {
        if (token != null) header(HttpHeaders.Authorization, "Bearer $token")
    }

fun ApplicationTestBuilder.serverWith(store: SyncStore, config: Config) {
    application { module(config, store) }
}
