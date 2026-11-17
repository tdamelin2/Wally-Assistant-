package dev.tim.assistant.server.google

import dev.tim.assistant.model.MailBody
import java.nio.charset.Charset
import java.util.Base64
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

const val MAIL_TEXT_MAX = 20_000

/** The text of a Gmail `format=full` message; returns null when the JSON has no usable id/date. */
fun parseMailBody(message: JsonObject): MailBody? {
    val date = (message["internalDate"] as? JsonPrimitive)?.content?.toLongOrNull() ?: return null
    val payload = message["payload"] as? JsonObject ?: return null
    val headers = payload["headers"] as? JsonArray
    fun header(name: String): String = headers?.firstNotNullOfOrNull { el ->
        val o = el as? JsonObject
        if (o?.string("name")?.equals(name, ignoreCase = true) == true) o.string("value") else null
    }?.let { decodeMimeWords(it) } ?: ""
    val plain = findPart(payload, "text/plain")
    val text = when {
        plain != null -> partText(plain)
        else -> findPart(payload, "text/html")?.let { stripHtml(partText(it)) } ?: ""
    }
    val replyTo = header("Reply-To").trim()
    return MailBody(replyTo.ifBlank { header("From") },header("To"), header("Subject"), date, text.trim().take(MAIL_TEXT_MAX))
}

/** First part (depth first) with [mimeType] that is not an attachment and has data. */
private fun findPart(part: JsonObject, mimeType: String): JsonObject? {
    val isAttachment = !part.string("filename").isNullOrEmpty()
    val data = ((part["body"] as? JsonObject)?.string("data"))
    if (!isAttachment && data != null && part.string("mimeType")?.lowercase()?.substringBefore(';')?.trim() == mimeType) return part
    (part["parts"] as? JsonArray)?.forEach { child ->
        (child as? JsonObject)?.let { c -> findPart(c, mimeType)?.let { return it } }
    }
    return null
}

private fun partText(part: JsonObject): String {
    val data = (part["body"] as? JsonObject)?.string("data") ?: return ""
    val bytes = try {
        Base64.getUrlDecoder().decode(data.trim().trimEnd('='))
    } catch (e: IllegalArgumentException) {
        return ""
    }
    val contentType = ((part["headers"] as? JsonArray)?.firstNotNullOfOrNull { el ->
        val o = el as? JsonObject
        if (o?.string("name")?.equals("Content-Type", ignoreCase = true) == true) o.string("value") else null
    }) ?: ""
    val charsetName = Regex("charset=\"?([A-Za-z0-9_\\-:.]+)\"?", RegexOption.IGNORE_CASE).find(contentType)?.groupValues?.get(1)
    val charset = charsetName?.let { runCatching { Charset.forName(it) }.getOrNull() } ?: Charsets.UTF_8
    return String(bytes, charset)
}

private val SCRIPT_STYLE = Regex("<(script|style)\\b[^>]*>.*?</\\1\\s*>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
private val BREAKS = Regex("<(br|/p|/div|/tr|/li|/h[1-6])\\b[^>]*>", RegexOption.IGNORE_CASE)
private val TAGS = Regex("<[^>]*>")

/** Turns an HTML mail into plain text: scripts and styles dropped, tags removed, common entities decoded. */
fun stripHtml(html: String): String {
    var s = SCRIPT_STYLE.replace(html, " ")
    s = BREAKS.replace(s, "\n")
    s = TAGS.replace(s, "")
    s = s.replace("&nbsp;", " ").replace("&lt;", "<").replace("&gt;", ">")
        .replace("&quot;", "\"").replace("&#39;", "'").replace("&amp;", "&")
    return s.lines().joinToString("\n") { it.trim() }.replace(Regex("\n{3,}"), "\n\n")
}

private val ENCODED_WORD = Regex("=\\?([^?]+)\\?([bBqQ])\\?([^?]*)\\?=")

/** Decodes RFC 2047 encoded words (`=?UTF-8?B?...?=`, `=?UTF-8?Q?...?=`) in a header value. */
fun decodeMimeWords(value: String): String {
    if (!value.contains("=?")) return value
    // whitespace between two encoded words is not part of the text
    val joined = value.replace(Regex("(\\?=)\\s+(=\\?)"), "$1$2")
    return ENCODED_WORD.replace(joined) { m ->
        val charset = runCatching { Charset.forName(m.groupValues[1].substringBefore('*')) }.getOrNull() ?: return@replace m.value
        val bytes = try {
            if (m.groupValues[2].equals("B", ignoreCase = true)) {
                Base64.getMimeDecoder().decode(m.groupValues[3])
            } else {
                decodeQ(m.groupValues[3])
            }
        } catch (e: IllegalArgumentException) {
            return@replace m.value
        }
        String(bytes, charset)
    }
}

private fun decodeQ(text: String): ByteArray {
    val out = java.io.ByteArrayOutputStream()
    var i = 0
    while (i < text.length) {
        val c = text[i]
        when {
            c == '_' -> out.write(' '.code)
            c == '=' && i + 2 < text.length -> {
                out.write(text.substring(i + 1, i + 3).toInt(16))
                i += 2
            }
            else -> out.write(c.code)
        }
        i++
    }
    return out.toByteArray()
}

private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
