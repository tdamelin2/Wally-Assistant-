package dev.tim.assistant.server.google

import dev.tim.assistant.model.MailBody
import dev.tim.assistant.model.MailLabel
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import java.net.URLEncoder
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * A page-following Google result. [failure] is a short error class ("http_503", "timeout", ...) and
 * never carries response text. [complete] is false after a failure or when the id cap was reached
 * (a capped list has failure == null).
 */
data class ApiResult<T>(val items: List<T>, val complete: Boolean, val failure: String? = null)

data class MailHeaders(val id: String, val sender: String, val subject: String, val snippet: String, val internalDate: Long)

/** Ordered query parameters (a key such as metadataHeaders may repeat). */
private class Query {
    val pairs = ArrayList<Pair<String, String>>()
    fun put(key: String, value: String) { pairs += key to value }
}

private class ApiFailure(val kind: String) : Exception(kind)

const val MAX_UNREAD_IDS = 200
private const val MAX_PAGES = 100
internal const val GMAIL_BASE = "https://gmail.googleapis.com/gmail/v1/users/me"
private val AUTH_REASONS = setOf("authError", "insufficientPermissions")
private val apiJson = Json { ignoreUnknownKeys = true }

/** Read-only Google access: only GET requests to Google APIs. */
class GoogleApi(private val client: HttpClient, private val auth: GoogleAuth) {

    fun invalidateAuth() = auth.invalidate()

    suspend fun unreadMessageIds(): ApiResult<String> {
        val ids = LinkedHashSet<String>()
        var pageToken: String? = null
        for (page in 0 until MAX_PAGES) {
            val body = try {
                getJson("$GMAIL_BASE/messages") {
                    put("q", "is:unread in:inbox newer_than:30d")
                    put("maxResults", "100")
                    pageToken?.let { put("pageToken", it) }
                }
            } catch (e: ApiFailure) {
                return ApiResult(ids.toList(), false, e.kind)
            }
            (body["messages"] as? JsonArray)?.forEach { el ->
                val id = (el as? JsonObject)?.str("id") ?: return ApiResult(ids.toList(), false, "malformed")
                if (id !in ids) {
                    if (ids.size >= MAX_UNREAD_IDS) return ApiResult(ids.toList(), false)
                    ids += id
                }
            }
            val next = body.str("nextPageToken")?.takeIf { it.isNotEmpty() } ?: return ApiResult(ids.toList(), true)
            if (ids.size >= MAX_UNREAD_IDS) return ApiResult(ids.toList(), false)
            pageToken = next
        }
        return ApiResult(ids.toList(), false, "too_many_pages")
    }

    /** A message deleted since the list was read (404) is skipped: empty and complete. */
    suspend fun messageHeaders(id: String): ApiResult<MailHeaders> {
        val body = try {
            getJson("$GMAIL_BASE/messages/${pathEncode(id)}", missingIsEmpty = true) {
                put("format", "metadata")
                put("metadataHeaders", "From")
                put("metadataHeaders", "Subject")
            }
        } catch (e: ApiFailure) {
            return ApiResult(emptyList(), false, e.kind)
        }
        if (body.isEmpty()) return ApiResult(emptyList(), true)
        val internalDate = body.str("internalDate")?.toLongOrNull() ?: return ApiResult(emptyList(), false, "malformed")
        val headers = (body["payload"] as? JsonObject)?.get("headers") as? JsonArray
        fun header(name: String): String = headers?.firstNotNullOfOrNull { el ->
            val o = el as? JsonObject
            if (o?.str("name")?.equals(name, ignoreCase = true) == true) o.str("value") else null
        } ?: ""
        val item = MailHeaders(id, header("From"), header("Subject"), body.str("snippet") ?: "", internalDate)
        return ApiResult(listOf(item), true)
    }

    /** The readable text of one message (format=full). A message that is gone (404) is empty and complete. */
    suspend fun mailBody(id: String): ApiResult<MailBody> {
        val body = try {
            getJson("$GMAIL_BASE/messages/${pathEncode(id)}", missingIsEmpty = true) { put("format", "full") }
        } catch (e: ApiFailure) {
            return ApiResult(emptyList(), false, e.kind)
        }
        if (body.isEmpty()) return ApiResult(emptyList(), true)
        val parsed = parseMailBody(body) ?: return ApiResult(emptyList(), false, "malformed")
        return ApiResult(listOf(parsed), true)
    }

    /** The user's own labels (Gmail type `user`), not the system ones. */
    suspend fun userLabels(): ApiResult<MailLabel> {
        val body = try {
            getJson("$GMAIL_BASE/labels") { }
        } catch (e: ApiFailure) {
            return ApiResult(emptyList(), false, e.kind)
        }
        val labels = (body["labels"] as? JsonArray)?.mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            val labelId = o.str("id") ?: return@mapNotNull null
            val name = o.str("name") ?: return@mapNotNull null
            if (o.str("type") == "user") MailLabel(labelId, name) else null
        } ?: emptyList()
        return ApiResult(labels, true)
    }

    private fun pathEncode(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    /** GET [url] with [params]; auth errors throw GoogleAuthException, anything else ApiFailure. */
    private suspend fun getJson(
        url: String,
        missingIsEmpty: Boolean = false,
        params: Query.() -> Unit,
    ): JsonObject {
        val query = Query().apply(params).pairs
        val response = try {
            val token = auth.accessToken()
            client.get(url) {
                header(HttpHeaders.Authorization, "Bearer $token")
                url { query.forEach { (k, v) -> parameters.append(k, v) } }
            }
        } catch (e: GoogleAuthException) {
            throw e
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw ApiFailure(failureClass(e))
        }
        val code = response.status.value
        val text = try {
            response.bodyAsText()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw ApiFailure(failureClass(e))
        }
        when {
            code == 401 -> {
                auth.invalidate()
                throw GoogleAuthException("google api http 401")
            }
            code == 403 -> {
                if (errorReason(text) in AUTH_REASONS) {
                    auth.invalidate()
                    throw GoogleAuthException("google api http 403 auth")
                }
                throw ApiFailure("http_403")
            }
            code == 404 && missingIsEmpty -> return JsonObject(emptyMap())
            code !in 200..299 -> throw ApiFailure("http_$code")
        }
        return try {
            apiJson.parseToJsonElement(text) as? JsonObject ?: throw ApiFailure("malformed")
        } catch (e: ApiFailure) {
            throw e
        } catch (e: Exception) {
            throw ApiFailure("malformed")
        }
    }

    private fun failureClass(e: Exception): String = when {
        e::class.simpleName?.contains("Timeout") == true -> "timeout"
        else -> e::class.simpleName ?: "error"
    }

    private fun errorReason(text: String): String? = try {
        val error = (apiJson.parseToJsonElement(text) as? JsonObject)?.get("error") as? JsonObject
        val first = (error?.get("errors") as? JsonArray)?.firstOrNull() as? JsonObject
        first?.str("reason") ?: error?.str("status")
    } catch (e: Exception) {
        null
    }
}

private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
