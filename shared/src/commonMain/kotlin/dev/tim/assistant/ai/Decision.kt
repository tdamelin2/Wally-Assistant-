package dev.tim.assistant.ai

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Phase 18: the fixed shape a model brain answers in. [kind] is "answer", "action" or "needs_laptop".
 * [target] names an existing item (for edits and find). Parsing a decision never authorises an action:
 * the server validates every field before it writes anything.
 */
@Serializable
data class ModelDecision(
    val kind: String,
    val reply: String? = null,
    val action: String? = null,
    val target: String? = null,
    val args: Map<String, String> = emptyMap(),
)

/** The allowed `action` values, in schema order. `add_dream` is allowed only when the message mentions dreams. */
val DECISION_ACTIONS: List<String> = listOf(
    "create_task", "add_spending", "add_meal", "add_checkin", "add_journal", "add_dream", "find",
    "update_task", "update_exam", "update_meal", "update_spending", "update_checkin", "update_journal",
    "update_goal", "update_doc",
)

private val DECISION_KINDS = listOf("answer", "action", "needs_laptop")

/** The JSON schema sent as the model's `format`. `add_dream` is in the action enum only when [includeDream] is true. */
fun decisionSchema(includeDream: Boolean): JsonObject {
    val actions = if (includeDream) DECISION_ACTIONS else DECISION_ACTIONS.filter { it != "add_dream" }
    return buildJsonObject {
        put("type", "object")
        put("properties", buildJsonObject {
            put("kind", buildJsonObject {
                put("type", "string")
                put("enum", buildJsonArray { DECISION_KINDS.forEach { add(JsonPrimitive(it)) } })
            })
            put("reply", buildJsonObject { put("type", "string") })
            put("action", buildJsonObject {
                put("type", "string")
                put("enum", buildJsonArray { actions.forEach { add(JsonPrimitive(it)) } })
            })
            put("target", buildJsonObject { put("type", "string") })
            put("args", buildJsonObject {
                put("type", "object")
                put("additionalProperties", buildJsonObject { put("type", "string") })
            })
        })
        put("required", buildJsonArray { add(JsonPrimitive("kind")) })
    }
}

private val lenientJson = Json { ignoreUnknownKeys = true }

/**
 * Reads a model reply. Null on a parse error, a non-object, a missing or unknown `kind`, a non-text
 * reply/action/target, a non-object `args`, or an `args` value that is an object, array or null.
 * Numbers and booleans in `args` become their text. Unknown top-level fields are ignored.
 */
fun parseDecision(raw: String): ModelDecision? {
    val root = try {
        lenientJson.parseToJsonElement(raw.trim())
    } catch (e: Exception) {
        return null
    } as? JsonObject ?: return null

    val kind = (root["kind"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return null
    if (kind !in DECISION_KINDS) return null

    fun optText(key: String): Result<String?> {
        val v = root[key] ?: return Result.success(null)
        if (v is JsonNull) return Result.success(null)
        if (v is JsonPrimitive && v.isString) return Result.success(v.content)
        return Result.failure(IllegalArgumentException(key))
    }
    val reply = optText("reply").getOrElse { return null }
    val action = optText("action").getOrElse { return null }
    val target = optText("target").getOrElse { return null }

    val args = LinkedHashMap<String, String>()
    when (val a = root["args"]) {
        null, JsonNull -> Unit
        is JsonObject -> for ((k, v) in a) {
            if (v !is JsonPrimitive || v is JsonNull) return null
            args[k] = v.content
        }
        is JsonArray, is JsonPrimitive -> return null
    }
    return ModelDecision(kind, reply, action, target, args)
}
