package dev.tim.assistant.usage

import dev.tim.assistant.time.localDateOf
import kotlin.time.Instant

/** What the phone's usage log reports; everything else Android sends is ignored by the reader. */
enum class UsageEventType { RESUMED, PAUSED, SCREEN_OFF }

data class UsageEvent(val pkg: String, val type: UsageEventType, val at: Long)

/** Reads phone usage events at or after [fromMs]. Throws [UsageUnavailable] instead of returning a fake empty read. */
interface UsageSource {
    suspend fun readSince(fromMs: Long): List<UsageEvent>
}

/** Usage access is missing or the query failed. */
class UsageUnavailable(message: String) : Exception(message)

/** One screen-time interval as the app handles it (the same shape for phone and laptop). */
data class UsageRow(
    val id: String,
    val device: String,
    val app: String,
    val title: String?,
    val startAt: Long,
    val endAt: Long,
    val localDate: String,
)

data class PhoneSessions(val sessions: List<UsageRow>, val openStartAt: Long?)

const val DEVICE_PHONE = "PHONE"
const val DEVICE_LAPTOP = "LAPTOP"

/** Sessions shorter than this are dropped. */
const val MIN_SESSION_MS = 5_000L

/** Consecutive sessions of the same app this close together are one session. */
const val MERGE_GAP_MS = 2_000L

fun phoneSessionId(pkg: String, startAt: Long): String = "use:phone:$pkg:$startAt"

private class Span(val pkg: String, val start: Long, var end: Long)

/**
 * Turns phone usage events into sessions. Only events before [until] count. A session still open at the end
 * runs to [until] and its start is returned as [PhoneSessions.openStartAt] (even when it is too short to write yet).
 */
fun buildPhoneSessions(events: List<UsageEvent>, until: Long): PhoneSessions {
    val ordered = events.filter { it.at < until }.sortedBy { it.at }
    val spans = ArrayList<Span>()
    var openPkg: String? = null
    var openStart = 0L
    fun close(at: Long) {
        val pkg = openPkg ?: return
        spans += Span(pkg, openStart, at)
        openPkg = null
    }
    for (e in ordered) {
        when (e.type) {
            UsageEventType.RESUMED -> {
                if (openPkg == e.pkg) continue
                close(e.at)
                openPkg = e.pkg
                openStart = e.at
            }
            UsageEventType.PAUSED -> if (openPkg == e.pkg) close(e.at)
            UsageEventType.SCREEN_OFF -> close(e.at)
        }
    }
    val openPackage = openPkg
    if (openPackage != null) spans += Span(openPackage, openStart, until)
    val hasOpen = openPackage != null

    // Merge neighbours of the same app; remember whether the last merged span is the open one.
    val merged = ArrayList<Span>()
    for (s in spans) {
        val last = merged.lastOrNull()
        if (last != null && last.pkg == s.pkg && s.start - last.end <= MERGE_GAP_MS) last.end = maxOf(last.end, s.end)
        else merged += Span(s.pkg, s.start, s.end)
    }
    val openIndex = if (hasOpen) merged.lastIndex else -1
    val openStartAt = if (hasOpen) merged[openIndex].start else null
    val rows = merged.filter { it.end - it.start >= MIN_SESSION_MS }
        .map { s ->
            UsageRow(
                phoneSessionId(s.pkg, s.start), DEVICE_PHONE, s.pkg, null, s.start, s.end,
                localDateOf(Instant.fromEpochMilliseconds(s.start)),
            )
        }
    return PhoneSessions(rows, openStartAt)
}
