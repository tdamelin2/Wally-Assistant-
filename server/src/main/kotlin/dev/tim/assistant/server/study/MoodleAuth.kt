package dev.tim.assistant.server.study

import java.io.File
import java.net.URI
import java.nio.charset.StandardCharsets
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermissions
import java.util.Base64
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

const val DEFAULT_MOODLE_TOKEN_PATH = "/var/lib/assistant/moodle-token.json"
private const val MAX_LINK_CHARS = 8192

/** ASSISTANT_MOODLE_TOKEN overrides the default token path (development and tests). */
fun moodleTokenFilePath(env: Map<String, String> = System.getenv()): File =
    File(env["ASSISTANT_MOODLE_TOKEN"]?.takeIf { it.isNotBlank() } ?: DEFAULT_MOODLE_TOKEN_PATH)

@Serializable
data class MoodleTokenFile(val token: String, val obtainedAt: Long) {
    // never print the token, not even by accident
    override fun toString() = "MoodleTokenFile(obtainedAt=$obtainedAt)"
}

private val tokenJson = Json { ignoreUnknownKeys = true }

/** The Moodle token file: `{token, obtainedAt}`, written through a temp file, mode 0600, atomic move. */
class MoodleTokenStore(private val file: File) {
    fun exists(): Boolean = file.isFile

    /** The stored token, or null when the file is missing or unreadable. */
    fun read(): String? = try {
        tokenJson.decodeFromString(MoodleTokenFile.serializer(), file.readText(StandardCharsets.UTF_8)).token
            .takeIf { it.isNotBlank() }
    } catch (e: Exception) {
        null
    }

    fun write(token: String, obtainedAt: Long) {
        val target = file.absoluteFile.toPath()
        val dir = target.parent
        Files.createDirectories(dir)
        val tmp = Files.createTempFile(dir, "moodle-token", ".tmp")
        try {
            val text = tokenJson.encodeToString(MoodleTokenFile.serializer(), MoodleTokenFile(token, obtainedAt))
            Files.write(tmp, text.toByteArray(StandardCharsets.UTF_8))
            if ("posix" in FileSystems.getDefault().supportedFileAttributeViews()) {
                Files.setPosixFilePermissions(tmp, PosixFilePermissions.fromString("rw-------"))
            }
            Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally {
            Files.deleteIfExists(tmp)
        }
    }
}

/** Thread-safe Moodle state for /v1/health: null unknown, true working, false the token was rejected. */
class MoodleStatus {
    private val lock = Any()
    private var ok: Boolean? = null

    fun get(): Boolean? = synchronized(lock) { ok }

    fun set(value: Boolean?) = synchronized(lock) { ok = value }
}

private val LINK = Regex("""^([a-z][a-z0-9+.\-]*)://token=(.+)$""", RegexOption.IGNORE_CASE)

private fun percentDecode(s: String): String {
    if ('%' !in s) return s
    val out = StringBuilder()
    var i = 0
    while (i < s.length) {
        val c = s[i]
        val hex = if (c == '%' && i + 2 < s.length) s.substring(i + 1, i + 3) else null
        val v = hex?.toIntOrNull(16)
        if (v != null) {
            out.append(v.toChar())
            i += 3
        } else {
            out.append(c)
            i++
        }
    }
    return out.toString()
}

/**
 * Reads `<scheme>://token=<base64>` (any scheme, URL-encoding and missing padding tolerated); the base64 text
 * decodes to `<signature>:::<token>[:::<privatetoken>]`. Returns the web-service token (part 2) or null when
 * the text is not a usable link. The result is a secret: never log it.
 */
fun parseMoodleLink(link: String): String? {
    val text = link.trim()
    if (text.isEmpty() || text.length > MAX_LINK_CHARS) return null
    val payload = LINK.matchEntire(text)?.groupValues?.get(2) ?: return null
    val cleaned = percentDecode(payload).filterNot { it.isWhitespace() }
        .replace('-', '+').replace('_', '/').trimEnd('=')
    if (cleaned.isEmpty()) return null
    val bytes = try {
        Base64.getDecoder().decode(cleaned + "=".repeat((4 - cleaned.length % 4) % 4))
    } catch (e: IllegalArgumentException) {
        return null
    }
    val parts = String(bytes, StandardCharsets.UTF_8).split(":::")
    if (parts.size < 2) return null
    val token = parts[1].trim()
    if (token.isEmpty() || token.any { it.isWhitespace() || it.isISOControl() }) return null
    return token
}

/** True when [url] uses the same scheme and host as [baseUrl] (the token is only ever sent there). */
fun sameSite(baseUrl: String, url: String): Boolean = try {
    val a = URI(baseUrl)
    val b = URI(url)
    fun port(u: URI) = if (u.port != -1) u.port else if (u.scheme.equals("https", ignoreCase = true)) 443 else 80
    a.scheme.equals(b.scheme, ignoreCase = true) && a.host != null && a.host.equals(b.host, ignoreCase = true) &&
        port(a) == port(b)
} catch (e: Exception) {
    false
}
