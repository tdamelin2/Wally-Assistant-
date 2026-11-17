package dev.tim.assistant.ui.forms

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put

const val JOURNAL_DRAFT_KEY = "draft.journal"
const val DREAM_DRAFT_KEY = "draft.dream"

/** Unsaved writer text kept in the settings table: `DraftStore(repo::setting, repo::putSetting)`. */
class DraftStore(private val get: (String) -> String?, private val put: (String, String) -> Unit) {
    /** The stored draft JSON, or null when there is none (missing or empty). */
    fun load(key: String): String? = get(key)?.takeIf { it.isNotEmpty() }

    fun save(key: String, json: String) = put(key, json)

    fun clear(key: String) = put(key, "")
}

/** An editing session shows the entry; a new one shows the draft, else nothing. */
fun initialWriterText(existing: String?, draft: String?): String = existing ?: draft ?: ""

/** A dream writer's unsaved fields. */
data class DreamDraft(val text: String = "", val mood: Int = 3, val lucid: Boolean = false, val tags: String = "")

private fun parseObject(json: String?): JsonObject? =
    if (json.isNullOrEmpty()) null else try { Json.parseToJsonElement(json) as? JsonObject } catch (e: Exception) { null }

fun encodeJournalDraft(text: String): String = buildJsonObject { put("text", text) }.toString()

/** The journal draft text; null for a missing, empty or malformed draft. */
fun decodeJournalDraft(json: String?): String? =
    (parseObject(json)?.get("text") as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotEmpty() }

fun encodeDreamDraft(d: DreamDraft): String = buildJsonObject {
    put("text", d.text)
    put("mood", d.mood)
    put("lucid", d.lucid)
    put("tags", d.tags)
}.toString()

/** The dream draft; null for a missing or malformed draft, and bad single fields fall back to the defaults. */
fun decodeDreamDraft(json: String?): DreamDraft? {
    val o = parseObject(json) ?: return null
    val text = (o["text"] as? JsonPrimitive)?.contentOrNull ?: ""
    val mood = ((o["mood"] as? JsonPrimitive)?.intOrNull ?: 3).coerceIn(1, 5)
    val lucid = (o["lucid"] as? JsonPrimitive)?.booleanOrNull ?: false
    val tags = (o["tags"] as? JsonPrimitive)?.contentOrNull ?: ""
    return DreamDraft(text, mood, lucid, tags)
}
