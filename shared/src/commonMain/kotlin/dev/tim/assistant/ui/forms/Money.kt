package dev.tim.assistant.ui.forms

/** Parses "12.50" / "12,5" / "12" into integer agorot without floating point; null when invalid or <= 0. */
fun parseAgorot(text: String): Long? {
    val m = Regex("""^(\d{1,9})(?:[.,](\d{1,2}))?$""").matchEntire(text.trim()) ?: return null
    val whole = m.groupValues[1].toLong()
    val frac = m.groupValues[2].padEnd(2, '0').toLong()
    val total = whole * 100 + frac
    return if (total > 0) total else null
}

fun formatAgorot(agorot: Long): String {
    val frac = (agorot % 100).toString().padStart(2, '0')
    return "${agorot / 100}.$frac"
}

private val DATE = Regex("""^\d{4}-\d{2}-\d{2}$""")

fun isValidDate(text: String): Boolean = DATE.matches(text.trim())
