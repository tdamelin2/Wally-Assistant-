package dev.tim.assistant.data

import dev.tim.assistant.google.hhmm
import dev.tim.assistant.time.JERUSALEM
import kotlin.time.Instant
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.Serializable

/** The Dream week sent from Wally Calendar: live blocks plus the time of the last send. */
@Serializable
data class DreamWeek(val sentAt: Long, val blocks: List<DreamBlock>)

/** One block; [dayOfWeek] 1 = Monday .. 7 = Sunday, minutes since midnight (endMinute 1440 = 24:00). */
@Serializable
data class DreamBlock(
    val id: String,
    val title: String,
    val dayOfWeek: Int,
    val startMinute: Int,
    val endMinute: Int,
    val color: String? = null,
    val note: String? = null,
)

sealed interface DreamWeekResult {
    data class Sent(val week: DreamWeek) : DreamWeekResult
    data object NotSent : DreamWeekResult
    data object Unauthorized : DreamWeekResult
    data class Unavailable(val message: String) : DreamWeekResult
}

/** "yyyy-MM-dd HH:mm" in Jerusalem time. */
fun dateTimeLabel(ms: Long): String {
    val t = Instant.fromEpochMilliseconds(ms).toLocalDateTime(JERUSALEM)
    return "${t.date} ${hhmm(ms)}"
}

/** The line under the Goals button; null means the check is still loading. */
fun dreamWeekLine(result: DreamWeekResult?): String =
    when (result) {
        null -> ""
        is DreamWeekResult.Sent -> "Last sent from Wally Calendar: ${dateTimeLabel(result.week.sentAt)}"
        DreamWeekResult.NotSent -> "Dream week not sent from Wally Calendar yet"
        DreamWeekResult.Unauthorized, is DreamWeekResult.Unavailable -> "Wally Calendar not reachable"
    }
