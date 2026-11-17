package dev.tim.assistant.server.chat

import dev.tim.assistant.db.Approval
import dev.tim.assistant.db.AssistantDb
import dev.tim.assistant.model.CardDisplayDto
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/** Plain-words display of a pending "move exam" card (Phase 18 contract). Anything else, or anything off, gives null (generic card). */
object CardDisplays {
    const val TITLE = "Move exam"
    const val YES_LABEL = "Yes, move it"
    private val DAY = DateTimeFormatter.ofPattern("EEE, MMM d", Locale.ENGLISH)
    private val TIME = Regex("^([01]\\d|2[0-3]):[0-5]\\d$")
    private val json = Json { ignoreUnknownKeys = true }

    private fun text(o: JsonObject, key: String): String? = (o[key] as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim()?.takeIf { it.isNotEmpty() }

    private fun label(date: LocalDate, time: String?): String = date.format(DAY) + (time?.let { " $it" } ?: "")

    /**
     * [approval] must be a PENDING `update_exam` card: its stored (already validated) payload and the live exam row give the old and
     * new date and time. A missing, deleted or different exam, an unreadable payload or a payload with no change gives null.
     */
    fun forExamCard(db: AssistantDb, approval: Approval): CardDisplayDto? {
        if (approval.tool != "update_exam") return null
        val payload = try { json.parseToJsonElement(approval.argsJson).jsonObject } catch (_: Exception) { return null }
        val id = text(payload, "id") ?: return null
        val exam = db.examQueries.selectById(id).executeAsOneOrNull()?.takeIf { it.deleted == 0L && it.id == id } ?: return null
        val newDateText = text(payload, "localDate")
        val newTime = text(payload, "time")
        if (newDateText == null && newTime == null) return null
        if (newTime != null && !TIME.matches(newTime)) return null
        val oldTime = exam.time?.takeIf { TIME.matches(it) }
        val oldDate = try { LocalDate.parse(exam.localDate) } catch (_: Exception) { return null }
        val newDate = if (newDateText == null) oldDate else try { LocalDate.parse(newDateText) } catch (_: Exception) { return null }
        val afterTime = newTime ?: oldTime
        return CardDisplayDto(TITLE, exam.title, label(oldDate, oldTime), label(newDate, afterTime), YES_LABEL)
    }
}
