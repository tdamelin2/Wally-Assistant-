package dev.tim.assistant.desktop

import dev.tim.assistant.time.localDateOf
import dev.tim.assistant.usage.MIN_SESSION_MS
import kotlin.time.Instant

/** One reading of the laptop: the program in front, its window title and how long the keyboard/mouse were idle. */
data class Sample(val exe: String, val title: String, val idleMs: Long, val at: Long)

/** A laptop session. [title] is cut to [LAPTOP_TITLE_MAX] characters (null when blank), the form that gets stored. */
data class LaptopSession(
    val id: String,
    val exe: String,
    val title: String?,
    val startAt: Long,
    val endAt: Long,
    val localDate: String,
)

const val LAPTOP_TITLE_MAX = 200
const val LAPTOP_GAP_MS = 30_000L
const val LAPTOP_IDLE_MS = 120_000L

fun laptopSessionId(startAt: Long): String = "use:laptop:$startAt"

/** The reading used when the native calls fail: no program and no title. */
fun Sample.isUnavailable(): Boolean = exe == "unknown" && title.isBlank()

private fun cutTitle(title: String): String? {
    if (title.isBlank()) return null
    if (title.length <= LAPTOP_TITLE_MAX) return title
    val end = if (title[LAPTOP_TITLE_MAX - 1].isHighSurrogate()) LAPTOP_TITLE_MAX - 1 else LAPTOP_TITLE_MAX
    return title.substring(0, end)
}

/**
 * Turns the 5-second samples into sessions. Pure: no clock, no I/O. Not thread-safe; one caller feeds it.
 */
class LaptopTracker {
    private class Open(val exe: String, val title: String, val startAt: Long, var endAt: Long)

    private var current: Open? = null
    private var previousAt: Long? = null

    private fun finish(o: Open, endAt: Long): LaptopSession? {
        val end = maxOf(o.startAt, endAt)
        if (end - o.startAt < MIN_SESSION_MS) return null
        return snapshot(o, end)
    }

    private fun snapshot(o: Open, endAt: Long) = LaptopSession(
        laptopSessionId(o.startAt), o.exe, cutTitle(o.title), o.startAt, endAt,
        localDateOf(Instant.fromEpochMilliseconds(o.startAt)),
    )

    /** Feeds one sample; returns the sessions it closed (those of 5 seconds or more). */
    fun onSample(s: Sample): List<LaptopSession> {
        val out = ArrayList<LaptopSession>(1)
        val prev = previousAt
        previousAt = s.at
        var open = current
        if (open != null && prev != null && s.at - prev > LAPTOP_GAP_MS) {
            finish(open, prev)?.let { out += it }
            current = null
            open = null
        }
        when {
            s.isUnavailable() -> {
                if (open != null) {
                    finish(open, open.endAt)?.let { out += it }
                    current = null
                }
            }
            s.idleMs >= LAPTOP_IDLE_MS -> {
                if (open != null) {
                    finish(open, s.at - s.idleMs)?.let { out += it }
                    current = null
                }
            }
            else -> {
                val exe = s.exe.lowercase()
                if (open != null && open.exe == exe && open.title == s.title) {
                    open.endAt = s.at
                } else {
                    if (open != null) finish(open, s.at)?.let { out += it }
                    current = Open(exe, s.title, s.at, s.at)
                }
            }
        }
        return out
    }

    /** Closes the open session at its last reading (a quit or shutdown); returns it when it is long enough. */
    fun flush(): List<LaptopSession> {
        val open = current ?: return emptyList()
        current = null
        previousAt = null
        return listOfNotNull(finish(open, open.endAt))
    }

    /** The session being recorded, as of its last reading (may be shorter than the minimum). */
    fun open(): LaptopSession? = current?.let { snapshot(it, it.endAt) }
}
