package dev.tim.assistant.ai

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import dev.tim.assistant.ai.jobs.ModelClient
import dev.tim.assistant.data.LocalStore
import dev.tim.assistant.data.Repository
import dev.tim.assistant.db.AssistantDb
import kotlin.time.Instant
import kotlinx.serialization.json.JsonObject

/** 2026-11-09 10:00 in Asia/Jerusalem (UTC+2). */
val NOW: Long = Instant.parse("2026-11-09T08:00:00Z").toEpochMilliseconds()
const val TODAY = "2026-11-09"

/** An in-memory database, a repository and one shared fake clock. */
class JobRig {
    var clock: Long = NOW
    val db = AssistantDb(JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).also { AssistantDb.Schema.create(it) })
    val store = LocalStore(db)
    val repo = Repository(store, now = { clock }, today = { TODAY })

    fun job(id: String) = db.ai_jobQueries.selectById(id).executeAsOne()
}

/** A scripted model that records every prompt it was given. */
class FakeModel(var script: suspend (List<ChatMessage>, JsonObject?) -> Completion = { _, _ -> Completion.Ok("ok") }) : ModelClient {
    val prompts = mutableListOf<List<ChatMessage>>()
    val formats = mutableListOf<JsonObject?>()

    override suspend fun complete(messages: List<ChatMessage>, format: JsonObject?): Completion {
        prompts += messages
        formats += format
        return script(messages, format)
    }

    /** Every character the model was sent. */
    fun allText(): String = prompts.joinToString("\n") { m -> m.joinToString("\n") { it.role + ": " + it.content } }
}
