package dev.tim.assistant.server.study

import dev.tim.assistant.server.ErrorResponse
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.withCharset
import io.ktor.http.content.PartData
import io.ktor.http.content.forEachPart
import io.ktor.server.http.content.LocalFileContent
import io.ktor.server.request.receive
import io.ktor.server.request.receiveMultipart
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import io.ktor.utils.io.readAvailable
import java.io.File
import java.io.IOException
import java.net.URLEncoder
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

@Serializable
data class MoodleTokenRequest(val link: String)

@Serializable
data class CoursesResponse(val courses: Int)

@Serializable
data class NewFilesResponse(val newFiles: Int)

@Serializable
data class FileIdResponse(val fileId: String)

@Serializable
data class StudySetRequest(val docId: String)

@Serializable
data class EnabledRequest(val enabled: Boolean)

private const val MULTIPART_SLACK = 64L * 1024
private const val PPTX_TYPE = "application/vnd.openxmlformats-officedocument.presentationml.presentation"

/** A safe display name from an upload name: no folders, no control characters, at most 200 characters. */
fun safeDisplayName(raw: String?): String {
    val last = raw.orEmpty().substringAfterLast('/').substringAfterLast('\\')
    return last.filterNot { it.isISOControl() }.trim().take(200).ifBlank { "file" }
}

private fun contentTypeFor(name: String): ContentType =
    if (name.trim().lowercase().endsWith(".pptx")) ContentType.parse(PPTX_TYPE) else ContentType.Application.Pdf

private fun contentDisposition(name: String): String {
    val ascii = name.map { if (it.code in 32..126 && it != '"' && it != '\\' && it != ';') it else '_' }.joinToString("")
    val encoded = URLEncoder.encode(name, "UTF-8").replace("+", "%20")
    return "attachment; filename=\"$ascii\"; filename*=UTF-8''$encoded"
}

private fun hasMagic(file: File, extension: String): Boolean {
    val head = ByteArray(4)
    val n = file.inputStream().use { it.read(head) }
    return when (extension) {
        "pdf" -> n >= 4 && head[0] == '%'.code.toByte() && head[1] == 'P'.code.toByte() &&
            head[2] == 'D'.code.toByte() && head[3] == 'F'.code.toByte()
        else -> n >= 2 && head[0] == 'P'.code.toByte() && head[1] == 'K'.code.toByte()
    }
}

/** The /v1/study routes (SPEC section 6). Mount inside the authenticated `/v1` route. */
fun Route.studyRoutes(study: StudyService) {
    route("/study") {
        post("/moodle-token") {
            if (!study.moodleConfigured) {
                call.respond(HttpStatusCode.ServiceUnavailable, ErrorResponse("moodle not configured"))
                return@post
            }
            val request = try {
                call.receive<MoodleTokenRequest>()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse("bad link"))
                return@post
            }
            val token = parseMoodleLink(request.link)
            if (token == null) {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse("bad link"))
                return@post
            }
            when (val outcome = study.connect(token)) {
                is ConnectOutcome.Connected -> call.respond(CoursesResponse(outcome.courses))
                ConnectOutcome.Rejected -> call.respond(HttpStatusCode.BadGateway, ErrorResponse("moodle rejected token"))
                ConnectOutcome.Unreachable -> call.respond(HttpStatusCode.BadGateway, ErrorResponse("moodle unreachable"))
            }
        }

        post("/sync-now") {
            if (!study.moodleConfigured) {
                call.respond(HttpStatusCode.ServiceUnavailable, ErrorResponse("moodle not configured"))
                return@post
            }
            when (val outcome = study.check()) {
                is CheckOutcome.Done -> call.respond(NewFilesResponse(outcome.newFiles))
                CheckOutcome.Busy -> call.respond(HttpStatusCode.Conflict, ErrorResponse("check already running"))
                CheckOutcome.NotConnected ->
                    call.respond(HttpStatusCode.ServiceUnavailable, ErrorResponse("moodle not connected"))
                CheckOutcome.Rejected -> call.respond(HttpStatusCode.BadGateway, ErrorResponse("moodle rejected token"))
                CheckOutcome.Failed -> call.respond(HttpStatusCode.BadGateway, ErrorResponse("moodle check failed"))
            }
        }

        put("/courses/{id}/enabled") {
            val id = call.parameters["id"].orEmpty()
            val request = try {
                call.receive<EnabledRequest>()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse("bad body"))
                return@put
            }
            when (study.setCourseEnabled(id, request.enabled)) {
                EnabledOutcome.OK -> call.respond(JsonObject(emptyMap()))
                EnabledOutcome.NOT_FOUND -> call.respond(HttpStatusCode.NotFound, ErrorResponse("unknown course"))
            }
        }

        post("/upload") {
            var courseParam: String? = null
            var temp: File? = null
            var name: String? = null
            var size = 0L
            var tooLarge = false
            var badType = false
            try {
                try {
                    call.receiveMultipart(formFieldLimit = MAX_STUDY_FILE_BYTES + MULTIPART_SLACK).forEachPart { part ->
                        when (part) {
                            is PartData.FormItem -> if (part.name == "courseId") courseParam = part.value
                            is PartData.FileItem -> if (part.name == "file" && temp == null && !badType && !tooLarge) {
                                val shown = safeDisplayName(part.originalFileName)
                                val lower = shown.lowercase()
                                if (!lower.endsWith(".pdf") && !lower.endsWith(".pptx")) {
                                    badType = true
                                } else {
                                    val tmp = study.files.newTemp()
                                    temp = tmp
                                    name = shown
                                    val channel = part.provider()
                                    val buffer = ByteArray(64 * 1024)
                                    withContext(Dispatchers.IO) { tmp.outputStream() }.use { out ->
                                        while (true) {
                                            val n = channel.readAvailable(buffer, 0, buffer.size)
                                            if (n < 0) break
                                            size += n
                                            if (size > MAX_STUDY_FILE_BYTES) {
                                                tooLarge = true
                                                break
                                            }
                                            out.write(buffer, 0, n)
                                        }
                                    }
                                }
                            }
                            else -> {}
                        }
                        part.release()
                    }
                } catch (e: IOException) {
                    // the multipart parser's own size limit: far past the cap, so the same answer
                    if (e.message?.contains("limit") != true) throw e
                    tooLarge = true
                }
                val tmp = temp
                val shown = name
                val course = courseParam
                when {
                    tooLarge -> call.respond(HttpStatusCode.PayloadTooLarge, ErrorResponse("too large"))
                    badType || tmp == null || shown == null ->
                        call.respond(HttpStatusCode.UnsupportedMediaType, ErrorResponse("only pdf or pptx"))
                    !hasMagic(tmp, shown.trim().lowercase().substringAfterLast('.')) ->
                        call.respond(HttpStatusCode.UnsupportedMediaType, ErrorResponse("only pdf or pptx"))
                    course == null -> call.respond(HttpStatusCode.BadRequest, ErrorResponse("courseId missing"))
                    else -> when (val outcome = study.createUpload(tmp, shown, size, course)) {
                        is UploadOutcome.Created -> {
                            temp = null
                            call.respond(HttpStatusCode.Created, FileIdResponse(outcome.fileId))
                        }
                        UploadOutcome.BadCourse -> call.respond(HttpStatusCode.BadRequest, ErrorResponse("bad course"))
                    }
                }
            } finally {
                temp?.delete()
            }
        }

        get("/files/{id}/text") {
            val id = call.parameters["id"].orEmpty()
            val row = study.file(id)
            if (row == null || row.deleted != 0L) {
                call.respond(HttpStatusCode.NotFound, ErrorResponse("unknown file"))
                return@get
            }
            if (row.textStatus != "READY") {
                call.respond(HttpStatusCode.Conflict, ErrorResponse("text not ready"))
                return@get
            }
            val file = study.files.text(id)
            if (!file.isFile) {
                call.respond(HttpStatusCode.NotFound, ErrorResponse("file missing"))
                return@get
            }
            val text = withContext(Dispatchers.IO) { file.readText(Charsets.UTF_8) }
            call.respondText(text, ContentType.Text.Plain.withCharset(Charsets.UTF_8))
        }

        get("/files/{id}/original") {
            val id = call.parameters["id"].orEmpty()
            val row = study.file(id)
            val file = study.files.original(id)
            if (row == null || row.deleted != 0L || !file.isFile) {
                call.respond(HttpStatusCode.NotFound, ErrorResponse("unknown file"))
                return@get
            }
            call.response.header(HttpHeaders.ContentDisposition, contentDisposition(safeDisplayName(row.name)))
            call.respond(LocalFileContent(file, contentTypeFor(row.name)))
        }

        post("/files/{id}/study-set") {
            val id = call.parameters["id"].orEmpty()
            val request = try {
                call.receive<StudySetRequest>()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse("bad body"))
                return@post
            }
            if (request.docId.length !in 1..200) {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse("bad docId"))
                return@post
            }
            when (study.setStudySet(id, request.docId)) {
                StudySetOutcome.OK -> call.respond(JsonObject(emptyMap()))
                StudySetOutcome.NOT_FOUND -> call.respond(HttpStatusCode.NotFound, ErrorResponse("unknown file"))
            }
        }

        delete("/files/{id}") {
            when (study.deleteUpload(call.parameters["id"].orEmpty())) {
                DeleteOutcome.DELETED -> call.respond(JsonObject(emptyMap()))
                DeleteOutcome.NOT_FOUND -> call.respond(HttpStatusCode.NotFound, ErrorResponse("unknown file"))
                DeleteOutcome.MOODLE_FILE ->
                    call.respond(HttpStatusCode.Conflict, ErrorResponse("moodle files cannot be deleted"))
            }
        }
    }
}
