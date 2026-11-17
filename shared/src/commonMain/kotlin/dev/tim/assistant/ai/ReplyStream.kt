package dev.tim.assistant.ai

/**
 * Pulls the words Tim should see out of a decision JSON that is still being written. [extract] returns the decoded top-level
 * "reply" string, but only once the top-level "kind" is "answer"; before that, for any other kind, or without a reply it
 * returns "". Nested objects/arrays and look-alike text inside other strings never match. An escape that is cut off (a
 * backslash at the end, a \u with fewer than 4 digits, a high surrogate without its pair) is held back until complete.
 */
object ReplyStream {
    private class Str(val text: String, val end: Int, val complete: Boolean)

    fun extract(partialJson: String): String = scan(partialJson, true)

    /** Like [extract] but for a schema that has only a "reply" string: no "kind" is needed before the words show. */
    fun extractReply(partialJson: String): String = scan(partialJson, false)

    private fun scan(partialJson: String, answerOnly: Boolean): String {
        val p = partialJson
        var i = skipWs(p, 0)
        if (i >= p.length || p[i] != '{') return ""
        i++
        var kind: String? = null
        var reply = ""
        while (true) {
            i = skipWs(p, i)
            if (i >= p.length) break
            if (p[i] == ',') { i++; continue }
            if (p[i] != '"') break
            val key = readString(p, i)
            if (!key.complete) break
            i = skipWs(p, key.end)
            if (i >= p.length || p[i] != ':') break
            i = skipWs(p, i + 1)
            if (i >= p.length) break
            if (p[i] == '"') {
                val value = readString(p, i)
                if (key.text == "kind") { if (value.complete) kind = value.text }
                else if (key.text == "reply") reply = value.text
                if (!value.complete) break
                i = value.end
            } else {
                i = skipValue(p, i)
                if (i < 0) break
            }
        }
        return if (!answerOnly || kind == "answer") reply else ""
    }

    private fun skipWs(p: String, from: Int): Int {
        var i = from
        while (i < p.length && p[i].isWhitespace()) i++
        return i
    }

    /** Reads the string whose opening quote is at [start]; [Str.end] is just after the closing quote when complete. */
    private fun readString(p: String, start: Int): Str {
        val sb = StringBuilder()
        var i = start + 1
        while (i < p.length) {
            val c = p[i]
            if (c == '"') return Str(sb.toString(), i + 1, true)
            if (c != '\\') { sb.append(c); i++; continue }
            if (i + 1 >= p.length) return Str(sb.toString(), p.length, false)
            when (val e = p[i + 1]) {
                'n' -> { sb.append('\n'); i += 2 }
                't' -> { sb.append('\t'); i += 2 }
                'r' -> { sb.append('\r'); i += 2 }
                'b' -> { sb.append('\b'); i += 2 }
                'f' -> { sb.append('\u000C'); i += 2 }
                'u' -> {
                    val code = hex4(p, i + 2) ?: return Str(sb.toString(), p.length, false)
                    if (code in 0xD800..0xDBFF) {
                        // A high surrogate waits for its low half so a split emoji is never shown broken.
                        val low = if (p.startsWith("\\u", i + 6)) hex4(p, i + 8) else null
                        if (low == null || low !in 0xDC00..0xDFFF) return Str(sb.toString(), p.length, false)
                        sb.append(code.toChar()).append(low.toChar())
                        i += 12
                    } else {
                        sb.append(code.toChar())
                        i += 6
                    }
                }
                else -> { sb.append(e); i += 2 }
            }
        }
        return Str(sb.toString(), p.length, false)
    }

    private fun hex4(p: String, from: Int): Int? {
        if (from + 4 > p.length) return null
        var v = 0
        for (k in from until from + 4) {
            val d = p[k].digitToIntOrNull(16) ?: return null
            v = v * 16 + d
        }
        return v
    }

    /** Index just after the value starting at [from], or -1 when the value is not finished yet. */
    private fun skipValue(p: String, from: Int): Int {
        val c = p[from]
        if (c == '{' || c == '[') {
            var depth = 0
            var i = from
            while (i < p.length) {
                when (p[i]) {
                    '"' -> {
                        val s = readString(p, i)
                        if (!s.complete) return -1
                        i = s.end
                        continue
                    }
                    '{', '[' -> depth++
                    '}', ']' -> { depth--; if (depth == 0) return i + 1 }
                }
                i++
            }
            return -1
        }
        var i = from
        while (i < p.length && p[i] != ',' && p[i] != '}' && !p[i].isWhitespace()) i++
        return if (i >= p.length) -1 else i
    }
}
