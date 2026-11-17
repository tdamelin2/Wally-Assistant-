package dev.tim.assistant.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

@Serializable
data class SyncRow(
    val table: String,
    val id: String,
    val updatedAt: Long,
    val deleted: Boolean,
    val data: JsonObject,
)

@Serializable
data class PushRequest(val rows: List<SyncRow>)

@Serializable
data class Rejected(val id: String, val reason: String)

@Serializable
data class PushResponse(val accepted: List<String>, val rejected: List<Rejected>)

@Serializable
data class PullResponse(val rows: List<SyncRow>, val cursor: Long)

@Serializable
data class HealthResponse(
    val status: String,
    val serverTime: Long,
    val googleOk: Boolean?,
    val googleLastFetchAt: Long?,
    val moodleOk: Boolean? = null,
    val moodleBaseUrl: String? = null,
    val spotifyOk: Boolean? = null,
    val calendarOk: Boolean? = null,
)
