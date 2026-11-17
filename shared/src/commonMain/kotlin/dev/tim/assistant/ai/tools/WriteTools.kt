package dev.tim.assistant.ai.tools

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/** What a write tool tells the model after it created its approval. */
const val WAITING_FOR_APPROVAL = "Waiting for Tim's approval"

/** Parses frozen approval args, an empty object when they are not a JSON object. */
internal fun parseArgs(argsJson: String): JsonObject =
    try {
        kotlinx.serialization.json.Json.parseToJsonElement(argsJson).jsonObject
    } catch (e: Exception) {
        JsonObject(emptyMap())
    }

/** APPEND adds the new text on a new line after the old text (the old text is kept as it is). */
internal fun appendedBody(old: String, added: String): String = if (old.isBlank()) added else old + "\n" + added
