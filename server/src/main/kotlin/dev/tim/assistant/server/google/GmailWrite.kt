package dev.tim.assistant.server.google

import dev.tim.assistant.db.Mail_action
import dev.tim.assistant.db.Mail_draft
import dev.tim.assistant.model.toSyncRow
import dev.tim.assistant.server.ServerDb
import dev.tim.assistant.server.SyncStore
import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.contentType
import java.net.URLEncoder
import java.util.Base64
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.slf4j.LoggerFactory

const val PERMISSION_MISSING = "Google permission missing - run google-auth again"
const val GMAIL_CALL_TIMEOUT_MS = 30_000L
private const val MAX_ERROR_TEXT = 200
private val RATE_LIMIT_REASONS = setOf("rateLimitExceeded", "userRateLimitExceeded", "dailyLimitExceeded")
private val gmailJson = Json { ignoreUnknownKeys = true }
private val ADDRESS = Regex("""[^\s@<>(),;:\\"]+@[^\s@<>(),;:\\"]+\.[^\s@<>(),;:\\"]+""")

/** What happened to one send. Possibly-sent outcomes are [Unknown]; the mail is never retried. */
sealed interface SendOutcome {
    data class Sent(val threadId: String?) : SendOutcome
    data class Failed(val error: String) : SendOutcome
    data class Unknown(val error: String) : SendOutcome
}

/** The facts about an original message needed to answer it. */
data class OriginalMail(val threadId: String?, val messageId: String?, val references: String?, val subject: String)

/** True when [to] looks like one plain address (no name part, no list). */
fun isValidAddress(to: String): Boolean = ADDRESS.matches(to.trim())

/** The subject with every leading "Re:" removed, for the same-conversation test. */
fun stripReplyPrefix(subject: String): String {
    var s = subject.trim()
    while (s.length >= 3 && s.startsWith("re:", ignoreCase = true)) s = s.substring(3).trim()
    return s
}

/** A reply keeps the conversation only when the subject (minus "Re:") is the original's subject. */
fun staysInConversation(draftSubject: String, originalSubject: String): Boolean =
    stripReplyPrefix(draftSubject) == stripReplyPrefix(originalSubject)

/** RFC 2047: plain ASCII subjects pass through; others become `=?UTF-8?B?...?=` words of at most 75 characters. */
fun encodeSubject(subject: String): String {
    if (subject.all { it.code in 0x20..0x7E }) return subject
    val words = ArrayList<String>()
    val current = java.io.ByteArrayOutputStream()
    fun flush() {
        if (current.size() > 0) {
            words += "=?UTF-8?B?" + Base64.getEncoder().encodeToString(current.toByteArray()) + "?="
            current.reset()
        }
    }
    var i = 0
    while (i < subject.length) {
        val cp = subject.codePointAt(i)
        val bytes = String(Character.toChars(cp)).toByteArray(Charsets.UTF_8)
        if (current.size() + bytes.size > 45) flush()
        current.write(bytes)
        i += Character.charCount(cp)
    }
    flush()
    return words.joinToString("\r\n ")
}

/** True when a header value could smuggle in another header. */
fun hasLineBreak(value: String): Boolean = value.contains('\r') || value.contains('\n')

/** The RFC 2822 message (CRLF lines, base64 UTF-8 body), or null when a header value has a line break. */
fun buildMime(to: String, subject: String, body: String, inReplyTo: String?, references: String?): String? {
    if (listOfNotNull(to, subject, inReplyTo, references).any(::hasLineBreak)) return null
    val lines = ArrayList<String>()
    lines += "To: $to"
    lines += "Subject: ${encodeSubject(subject)}"
    lines += "MIME-Version: 1.0"
    lines += "Content-Type: text/plain; charset=UTF-8"
    lines += "Content-Transfer-Encoding: base64"
    if (inReplyTo != null) lines += "In-Reply-To: $inReplyTo"
    if (references != null) lines += "References: $references"
    val encodedBody = Base64.getMimeEncoder(76, "\r\n".toByteArray()).encodeToString(body.toByteArray(Charsets.UTF_8))
    return lines.joinToString("\r\n") + "\r\n\r\n" + encodedBody + "\r\n"
}

/**
 * The only code that writes to Gmail: sends APPROVED drafts and runs APPROVED label/archive actions,
 * one at a time. Every status change is a short locked transaction (claim -> unlock -> call -> lock ->
 * finish); the lock is never held during HTTP. [signal] wakes [run]; the push response never waits.
 */
class GmailWrite(
    private val sdb: ServerDb,
    private val store: SyncStore,
    private val client: HttpClient,
    private val auth: GoogleAuth,
    private val clock: () -> Long,
    private val callTimeoutMs: Long = GMAIL_CALL_TIMEOUT_MS,
    private val baseUrl: String = GMAIL_BASE,
) {
    private val log = LoggerFactory.getLogger(GmailWrite::class.java)
    private val db get() = sdb.db
    private val wake = Channel<Unit>(Channel.CONFLATED)
    private val sweepLock = Mutex()

    /** Conflated wake-up; safe to call from anywhere, never blocks. */
    fun signal() {
        wake.trySend(Unit)
    }

    /** Start-up recovery, one sweep, then one sweep per wake-up, until cancelled. */
    suspend fun run() {
        try {
            recover()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn("gmail write recovery failed: {}", e::class.simpleName)
        }
        sweepSafely()
        for (ignored in wake) sweepSafely()
    }

    private suspend fun sweepSafely() {
        try {
            sweep()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn("gmail write sweep failed: {}", e::class.simpleName)
        }
    }

    /** A draft left SENDING by a crash may or may not have been sent: it becomes UNKNOWN. */
    suspend fun recover() {
        sdb.writeLock.withLock {
            db.transaction {
                for (d in db.mail_draftQueries.selectAll().executeAsList()) {
                    if (d.status != "SENDING") continue
                    store.forceWrite(
                        toSyncRow(d.copy(status = "UNKNOWN", error = RESTART_NOTE, updatedAt = maxOf(clock(), d.updatedAt + 1))),
                    )
                }
            }
        }
    }

    /** One pass over every APPROVED draft, then every APPROVED action. */
    suspend fun sweep() = sweepLock.withLock {
        for (id in db.mail_draftQueries.selectByStatus("APPROVED").executeAsList().map { it.id }) sendDraft(id)
        for (id in db.mail_actionQueries.selectByStatus("APPROVED").executeAsList().map { it.id }) runAction(id)
    }

    // ---- drafts ---------------------------------------------------------------------------

    private suspend fun sendDraft(id: String) {
        // claim: only an APPROVED, not deleted draft becomes SENDING; the content is read here, under the lock
        val claimed: Mail_draft = sdb.writeLock.withLock {
            db.transactionWithResult {
                val d = db.mail_draftQueries.selectById(id).executeAsOneOrNull()
                if (d == null || d.deleted != 0L || d.status != "APPROVED") {
                    null
                } else {
                    store.forceWrite(toSyncRow(d.copy(status = "SENDING", error = null, updatedAt = maxOf(clock(), d.updatedAt + 1))))
                    d
                }
            }
        } ?: return
        val outcome = deliver(claimed)
        sdb.writeLock.withLock {
            db.transaction {
                val d = db.mail_draftQueries.selectById(id).executeAsOneOrNull()
                if (d != null && d.status == "SENDING") {
                    val stamp = maxOf(clock(), d.updatedAt + 1)
                    val finished = when (outcome) {
                        is SendOutcome.Sent -> d.copy(
                            status = "SENT", sentAt = clock(), error = null,
                            threadId = outcome.threadId ?: d.threadId, updatedAt = stamp,
                        )
                        is SendOutcome.Failed -> d.copy(status = "FAILED", error = outcome.error, updatedAt = stamp)
                        is SendOutcome.Unknown -> d.copy(status = "UNKNOWN", error = outcome.error, updatedAt = stamp)
                    }
                    store.forceWrite(toSyncRow(finished))
                }
            }
        }
        log.info("gmail draft finished: {}", outcome::class.simpleName)
    }

    private suspend fun deliver(d: Mail_draft): SendOutcome {
        val to = d.toAddr.trim()
        if (!isValidAddress(to)) return SendOutcome.Failed("The To address is not valid")
        var original: OriginalMail? = null
        val replyTo = d.replyToGmailId
        if (replyTo != null) {
            when (val fetched = fetchOriginal(replyTo)) {
                is Fetched.Ok -> original = fetched.mail
                is Fetched.Error -> return SendOutcome.Failed(fetched.message)
            }
        }
        val threaded = original != null && staysInConversation(d.subject, original.subject)
        val inReplyTo = if (threaded) original?.messageId else null
        val references = if (threaded) listOfNotNull(original?.references, original?.messageId).joinToString(" ").ifBlank { null } else null
        val mime = buildMime(to, d.subject, d.body, inReplyTo, references)
            ?: return SendOutcome.Failed("A header contains a line break")
        val threadId = if (threaded) original?.threadId else null
        val payload = JsonObject(
            buildMap {
                put("raw", JsonPrimitive(Base64.getUrlEncoder().withoutPadding().encodeToString(mime.toByteArray(Charsets.UTF_8))))
                if (threadId != null) put("threadId", JsonPrimitive(threadId))
            },
        ).toString()
        return when (val net = call(HttpMethod.Post, "$baseUrl/messages/send", payload)) {
            Net.AuthMissing -> SendOutcome.Failed(PERMISSION_MISSING)
            Net.NotSignedIn -> SendOutcome.Failed("Could not sign in to Google - try again")
            is Net.Lost -> SendOutcome.Unknown(
                if (net.timedOut) "Timed out waiting for Gmail - check Gmail Sent" else "Connection lost - check Gmail Sent",
            )
            is Net.Ok -> {
                val raw = net.raw
                when {
                    raw.status in 200..299 -> {
                        val json = parseObject(raw.text)
                        if (json?.str("id") == null) {
                            SendOutcome.Unknown("Gmail answer was unreadable - check Gmail Sent")
                        } else {
                            SendOutcome.Sent(json.str("threadId") ?: threadId)
                        }
                    }
                    raw.status in 400..499 -> SendOutcome.Failed(refusal(raw, "mail"))
                    else -> SendOutcome.Unknown("Gmail error (http ${raw.status}) - check Gmail Sent")
                }
            }
        }
    }

    private sealed interface Fetched {
        data class Ok(val mail: OriginalMail) : Fetched
        data class Error(val message: String) : Fetched
    }

    private suspend fun fetchOriginal(gmailId: String): Fetched {
        val params = listOf(
            "format" to "metadata", "metadataHeaders" to "Message-ID", "metadataHeaders" to "References",
            "metadataHeaders" to "Subject",
        )
        return when (val net = call(HttpMethod.Get, "$baseUrl/messages/${pathEncode(gmailId)}", null, params)) {
            Net.AuthMissing -> Fetched.Error(PERMISSION_MISSING)
            Net.NotSignedIn -> Fetched.Error("Could not sign in to Google - try again")
            is Net.Lost -> Fetched.Error("Could not reach Gmail to read the original mail")
            is Net.Ok -> when {
                net.raw.status == 404 -> Fetched.Error("The original mail was not found")
                net.raw.status !in 200..299 -> Fetched.Error(refusal(net.raw, "original mail read"))
                else -> {
                    val json = parseObject(net.raw.text)
                    val headers = (json?.get("payload") as? JsonObject)?.get("headers") as? JsonArray
                    fun header(name: String): String? = headers?.firstNotNullOfOrNull { el ->
                        val o = el as? JsonObject
                        if (o?.str("name")?.equals(name, ignoreCase = true) == true) o.str("value") else null
                    }
                    if (json == null) {
                        Fetched.Error("The original mail could not be read")
                    } else {
                        val messageId = header("Message-ID")?.trim()?.takeIf { it.isNotEmpty() && !hasLineBreak(it) }
                        val references = header("References")?.trim()?.replace(Regex("\\s+"), " ")?.takeIf { it.isNotEmpty() && !hasLineBreak(it) }
                        Fetched.Ok(OriginalMail(json.str("threadId"), messageId, references, decodeMimeWords(header("Subject") ?: "")))
                    }
                }
            }
        }
    }

    // ---- actions --------------------------------------------------------------------------

    private suspend fun runAction(id: String) {
        val a: Mail_action = db.mail_actionQueries.selectById(id).executeAsOneOrNull() ?: return
        if (a.deleted != 0L || a.status != "APPROVED") return
        val error: String? = performAction(a)
        sdb.writeLock.withLock {
            db.transaction {
                val stored = db.mail_actionQueries.selectById(id).executeAsOneOrNull()
                if (stored != null && stored.status == "APPROVED") {
                    val stamp = maxOf(clock(), stored.updatedAt + 1)
                    store.forceWrite(toSyncRow(stored.copy(status = if (error == null) "DONE" else "FAILED", updatedAt = stamp)))
                    if (error == null && stored.action == "ARCHIVE") {
                        val item = db.mail_itemQueries.selectById("mail:${stored.gmailId}").executeAsOneOrNull()
                        if (item != null && item.deleted == 0L) {
                            store.forceWrite(toSyncRow(item.copy(deleted = 1L, updatedAt = maxOf(clock(), item.updatedAt + 1))))
                        }
                    }
                }
            }
        }
        log.info("gmail action finished: {}", if (error == null) "DONE" else "FAILED")
    }

    /** Null on success, else the error text for the FAILED row. Both Gmail calls are idempotent. */
    private suspend fun performAction(a: Mail_action): String? {
        val body = when (a.action) {
            "LABEL" -> {
                val labelId = a.labelId?.takeIf { it.isNotBlank() } ?: return "No label was chosen"
                """{"addLabelIds":[${JsonPrimitive(labelId)}]}"""
            }
            "ARCHIVE" -> """{"removeLabelIds":["INBOX"]}"""
            else -> return "Unknown action"
        }
        return when (val net = call(HttpMethod.Post, "$baseUrl/messages/${pathEncode(a.gmailId)}/modify", body)) {
            Net.AuthMissing -> PERMISSION_MISSING
            Net.NotSignedIn -> "Could not sign in to Google - try again"
            is Net.Lost -> "Could not reach Gmail"
            is Net.Ok -> when {
                net.raw.status in 200..299 -> null
                net.raw.status == 404 -> "message not found"
                else -> refusal(net.raw, "change")
            }
        }
    }

    // ---- HTTP -----------------------------------------------------------------------------

    private class Raw(val status: Int, val text: String)

    private sealed interface Net {
        class Ok(val raw: Raw) : Net
        data object AuthMissing : Net
        data object NotSignedIn : Net
        class Lost(val timedOut: Boolean) : Net
    }

    /** One authorised call. Sign-in problems happen before anything is sent; [Net.Lost] happens after. */
    private suspend fun call(method: HttpMethod, url: String, body: String?, params: List<Pair<String, String>> = emptyList()): Net {
        val token = try {
            auth.accessToken()
        } catch (e: GoogleAuthException) {
            return Net.AuthMissing
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return Net.NotSignedIn
        }
        return try {
            Net.Ok(
                withTimeout(callTimeoutMs) {
                    val response = client.request(url) {
                        this.method = method
                        header(HttpHeaders.Authorization, "Bearer $token")
                        url { params.forEach { (k, v) -> parameters.append(k, v) } }
                        if (body != null) {
                            contentType(ContentType.Application.Json)
                            setBody(body)
                        }
                    }
                    Raw(response.status.value, response.bodyAsText())
                },
            )
        } catch (e: TimeoutCancellationException) {
            Net.Lost(true)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Net.Lost(false)
        }
    }

    /** Error text for a 4xx answer; a missing-permission answer also drops the cached access token. */
    private fun refusal(raw: Raw, what: String): String {
        val reason = errorReason(raw.text)
        if (raw.status == 401 || (raw.status == 403 && reason !in RATE_LIMIT_REASONS)) {
            auth.invalidate()
            return PERMISSION_MISSING
        }
        if (raw.status !in 400..499) return "Gmail error (http ${raw.status})"
        val message = errorMessage(raw.text)?.let { ": $it" }.orEmpty()
        return "Gmail refused the $what (http ${raw.status})$message"
    }

    private fun errorObject(text: String): JsonObject? = (parseObject(text)?.get("error") as? JsonObject)

    private fun errorReason(text: String): String? {
        val error = errorObject(text) ?: return null
        val first = (error["errors"] as? JsonArray)?.firstOrNull() as? JsonObject
        return first?.str("reason") ?: error.str("status")
    }

    private fun errorMessage(text: String): String? = errorObject(text)?.str("message")?.take(MAX_ERROR_TEXT)

    private fun parseObject(text: String): JsonObject? = try {
        gmailJson.parseToJsonElement(text) as? JsonObject
    } catch (e: Exception) {
        null
    }

    private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

    private fun pathEncode(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    private companion object {
        const val RESTART_NOTE = "The server restarted while sending - check Gmail Sent"
    }
}
