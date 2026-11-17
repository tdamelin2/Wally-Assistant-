package dev.tim.assistant.server.study

import io.ktor.client.HttpClient
import io.ktor.client.request.forms.submitForm
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.Parameters
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.utils.io.readAvailable
import java.io.File
import java.io.IOException
import java.net.URLEncoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull

const val MAX_STUDY_FILE_BYTES = 52_428_800L

/** Moodle answered with an error object (HTTP 200 + `exception`/`errorcode`) or a failed download. */
class MoodleException(val errorCode: String, message: String = errorCode) : IOException(message) {
    val isInvalidToken: Boolean get() = errorCode == "invalidtoken"
}

/** The download went past the size cap (counted on the actual bytes). */
class FileTooLargeException : IOException("file too large")

data class MoodleCourse(val id: Long, val fullName: String, val visible: Boolean, val endDate: Long)

data class MoodleFile(val fileName: String, val fileUrl: String, val fileSize: Long?, val timeModified: Long?)

private val json = Json { ignoreUnknownKeys = true }

/**
 * Calls the Moodle web-service REST endpoint. [baseUrl] has no trailing slash and may contain a path. The
 * [http] client (and so its engine) is injected; tokens are only ever sent to the configured site.
 */
class MoodleClient(val baseUrl: String, private val http: HttpClient) {
    private suspend fun call(token: String, function: String, vararg fields: Pair<String, String>): JsonElement {
        val response = http.submitForm(
            url = "$baseUrl/webservice/rest/server.php",
            formParameters = Parameters.build {
                append("wstoken", token)
                append("wsfunction", function)
                append("moodlewsrestformat", "json")
                for ((k, v) in fields) append(k, v)
            },
        )
        if (!response.status.isSuccess()) throw MoodleException("http ${response.status.value}")
        val element = try {
            json.parseToJsonElement(response.bodyAsText())
        } catch (e: Exception) {
            throw MoodleException("badresponse")
        }
        if (element is JsonObject && (element.containsKey("exception") || element.containsKey("errorcode"))) {
            throw MoodleException((element["errorcode"] as? JsonPrimitive)?.contentOrNull ?: "error")
        }
        return element
    }

    /** `core_webservice_get_site_info`: the user id of the token's owner. */
    suspend fun siteInfo(token: String): Long {
        val info = call(token, "core_webservice_get_site_info").asObject()
        return (info["userid"] as? JsonPrimitive)?.longOrNull ?: throw MoodleException("badresponse")
    }

    /** `core_enrol_get_users_courses`. A missing `visible` counts as visible, a missing `enddate` as 0. */
    suspend fun courses(token: String, userId: Long): List<MoodleCourse> {
        val list = call(token, "core_enrol_get_users_courses", "userid" to userId.toString()) as? JsonArray
            ?: throw MoodleException("badresponse")
        return list.mapNotNull { e ->
            val o = e as? JsonObject ?: return@mapNotNull null
            val id = (o["id"] as? JsonPrimitive)?.longOrNull ?: return@mapNotNull null
            val name = (o["fullname"] as? JsonPrimitive)?.contentOrNull
                ?: (o["displayname"] as? JsonPrimitive)?.contentOrNull
                ?: (o["shortname"] as? JsonPrimitive)?.contentOrNull ?: "Course $id"
            MoodleCourse(
                id, name,
                visible = ((o["visible"] as? JsonPrimitive)?.longOrNull ?: 1L) != 0L,
                endDate = (o["enddate"] as? JsonPrimitive)?.longOrNull ?: 0L,
            )
        }
    }

    /** `core_course_get_contents`: every `type == "file"` entry of every module of every section. */
    suspend fun courseFiles(token: String, courseId: Long): List<MoodleFile> {
        val sections = call(token, "core_course_get_contents", "courseid" to courseId.toString()) as? JsonArray
            ?: throw MoodleException("badresponse")
        val out = ArrayList<MoodleFile>()
        for (section in sections) {
            val modules = (section as? JsonObject)?.get("modules") as? JsonArray ?: continue
            for (module in modules) {
                val contents = (module as? JsonObject)?.get("contents") as? JsonArray ?: continue
                for (c in contents) {
                    val o = c as? JsonObject ?: continue
                    if ((o["type"] as? JsonPrimitive)?.contentOrNull != "file") continue
                    val name = (o["filename"] as? JsonPrimitive)?.contentOrNull ?: continue
                    val url = (o["fileurl"] as? JsonPrimitive)?.contentOrNull ?: continue
                    out += MoodleFile(
                        name, url,
                        (o["filesize"] as? JsonPrimitive)?.longOrNull,
                        (o["timemodified"] as? JsonPrimitive)?.longOrNull,
                    )
                }
            }
        }
        return out
    }

    /**
     * Streams [fileUrl] (plus the `token` parameter) into [dest]. Refuses another site, a non-success
     * status and JSON error bodies; stops with [FileTooLargeException] once more than [maxBytes] have
     * arrived. Returns the byte count. [dest] is left for the caller to move or delete.
     */
    suspend fun download(token: String, fileUrl: String, dest: File, maxBytes: Long = MAX_STUDY_FILE_BYTES): Long {
        if (!sameSite(baseUrl, fileUrl)) throw MoodleException("foreignurl")
        val separator = if ('?' in fileUrl) '&' else '?'
        val url = fileUrl + separator + "token=" + URLEncoder.encode(token, "UTF-8")
        return http.prepareGet(url).execute { response ->
            if (!response.status.isSuccess()) throw MoodleException("http ${response.status.value}")
            if (response.contentType()?.match(ContentType.Application.Json) == true) {
                val code = try {
                    (json.parseToJsonElement(response.bodyAsText()) as? JsonObject)
                        ?.get("errorcode")?.let { (it as? JsonPrimitive)?.contentOrNull }
                } catch (e: Exception) {
                    null
                }
                throw MoodleException(code ?: "badresponse")
            }
            val channel = response.bodyAsChannel()
            val buffer = ByteArray(64 * 1024)
            var total = 0L
            dest.outputStream().use { out ->
                while (true) {
                    val n = channel.readAvailable(buffer, 0, buffer.size)
                    if (n < 0) break
                    total += n
                    if (total > maxBytes) throw FileTooLargeException()
                    out.write(buffer, 0, n)
                }
            }
            total
        }
    }

    private fun JsonElement.asObject(): JsonObject = this as? JsonObject ?: throw MoodleException("badresponse")
}
