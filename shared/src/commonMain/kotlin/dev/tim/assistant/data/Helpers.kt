package dev.tim.assistant.data

import kotlinx.datetime.LocalDate
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

const val DOC_BODY_MAX = 200_000
const val DREAM_TAGS_MAX = 500
const val GOAL_STEPS_MIN = 3
const val GOAL_STEPS_MAX = 10

/** Dream tags: split on commas, trim, lowercase, drop empties, joined by "," and kept within [DREAM_TAGS_MAX] characters. */
fun normalizeTags(raw: String): String {
    val out = StringBuilder()
    for (part in raw.split(',')) {
        val tag = part.trim().lowercase()
        if (tag.isEmpty()) continue
        val extra = if (out.isEmpty()) tag.length else tag.length + 1
        if (out.length + extra > DREAM_TAGS_MAX) break
        if (out.isNotEmpty()) out.append(',')
        out.append(tag)
    }
    return out.toString()
}

/** True when [body] is longer than a doc may be (200 000 characters). */
fun docBodyTooLong(body: String): Boolean = body.length > DOC_BODY_MAX

/** One proposed step from the GOAL_STEPS job. */
data class GoalStep(val title: String, val dueDate: String?)

private fun realDate(text: String): Boolean {
    if (!Regex("""\d{4}-\d{2}-\d{2}""").matches(text)) return false
    return try {
        LocalDate.parse(text)
        true
    } catch (e: IllegalArgumentException) {
        false
    }
}

/**
 * The steps in a GOAL_STEPS result `{"steps":[{"title":..,"dueDate":..|null}]}`. Returns an empty list unless
 * the whole result is valid: 3..10 steps, each title 1..200 characters and not blank, dueDate null/blank or a real date.
 */
fun parseGoalSteps(resultJson: String?): List<GoalStep> {
    if (resultJson == null) return emptyList()
    val steps = try {
        (Json.parseToJsonElement(resultJson) as? JsonObject)?.get("steps") as? JsonArray
    } catch (e: Exception) {
        null
    } ?: return emptyList()
    if (steps.size !in GOAL_STEPS_MIN..GOAL_STEPS_MAX) return emptyList()
    val out = ArrayList<GoalStep>()
    for (element in steps) {
        val obj = element as? JsonObject ?: return emptyList()
        val titleEl = obj["title"] as? JsonPrimitive ?: return emptyList()
        if (titleEl is JsonNull || !titleEl.isString) return emptyList()
        val title = titleEl.content.trim()
        if (title.isEmpty() || title.length > 200) return emptyList()
        val dueEl = obj["dueDate"]
        val due: String? = when {
            dueEl == null || dueEl is JsonNull -> null
            dueEl is JsonPrimitive && dueEl.isString -> dueEl.content.trim().ifEmpty { null }
            else -> return emptyList()
        }
        if (due != null && !realDate(due)) return emptyList()
        out += GoalStep(title, due)
    }
    return out
}

/** The normalized GOAL_STEPS result JSON for [steps] (array order is the step index). */
fun goalStepsJson(steps: List<GoalStep>): String = buildJsonObject {
    put("steps", buildJsonArray {
        for (s in steps) add(buildJsonObject {
            put("title", s.title)
            if (s.dueDate == null) put("dueDate", JsonNull) else put("dueDate", s.dueDate)
        })
    })
}.toString()
