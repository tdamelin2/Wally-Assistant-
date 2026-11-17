package dev.tim.assistant.server.study

import dev.tim.assistant.server.Config
import dev.tim.assistant.server.MoodleConfig
import dev.tim.assistant.server.ServerDb
import dev.tim.assistant.server.SyncStore
import dev.tim.assistant.server.openDb
import dev.tim.assistant.server.tempDbPath
import dev.tim.assistant.server.testConfig
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.forms.FormDataContent
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import java.io.File
import java.nio.file.Files
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

const val MOODLE_BASE = "https://moodle.example.test/2027"
const val GOOD_TOKEN = "tok-abc-123"

/** A signed-looking link like the real one: `<scheme>://token=<base64 of signature:::token:::private>`. */
fun moodleLink(token: String = GOOD_TOKEN, scheme: String = "moodlemobile"): String =
    "$scheme://token=" + Base64.getEncoder().encodeToString("sig-not-a-url:::$token:::private-part".toByteArray())

fun resourceBytes(name: String): ByteArray =
    StudyTestSupportMarker::class.java.getResourceAsStream("/study/$name")!!.use { it.readBytes() }

private object StudyTestSupportMarker

data class FakeFile(val name: String, val size: Long, val bytes: ByteArray?, val moduleType: String = "resource") {
    val url = "$MOODLE_BASE/webservice/pluginfile.php/1/mod_$moduleType/content/1/" + name.replace(" ", "%20")
}

data class FakeCourse(val id: Long, val name: String, val visible: Int = 1, val files: List<FakeFile> = emptyList())

/** A scripted Moodle site behind a MockEngine. */
class FakeMoodle {
    @Volatile var validToken: String = GOOD_TOKEN
    @Volatile var courses: List<FakeCourse> = emptyList()
    @Volatile var siteInfoGate: CompletableDeferred<Unit>? = null
    @Volatile var downloadFails: Boolean = false
    val siteInfoCalls = AtomicInteger()
    val contentsCalls = AtomicInteger()
    val downloadCalls = AtomicInteger()
    val seenTokens = ConcurrentHashMap.newKeySet<String>()

    private fun errorJson() = """{"exception":"moodle_exception","errorcode":"invalidtoken","message":"Invalid token"}"""

    fun engine() = MockEngine { request ->
        val json = headersOf(HttpHeaders.ContentType, "application/json")
        if (request.method == HttpMethod.Post && request.url.encodedPath.endsWith("/webservice/rest/server.php")) {
            val form = (request.body as FormDataContent).formData
            val token = form["wstoken"].orEmpty()
            seenTokens += token
            if (token != validToken) return@MockEngine respond(errorJson(), HttpStatusCode.OK, json)
            when (form["wsfunction"]) {
                "core_webservice_get_site_info" -> {
                    siteInfoCalls.incrementAndGet()
                    siteInfoGate?.await()
                    respond("""{"sitename":"Moodle","userid":42,"fullname":"[x]"}""", HttpStatusCode.OK, json)
                }
                "core_enrol_get_users_courses" -> {
                    val arr = buildJsonArray {
                        for (c in courses) add(
                            buildJsonObject {
                                put("id", c.id); put("fullname", c.name); put("shortname", "s${c.id}")
                                put("visible", c.visible); put("enddate", 0); put("startdate", 0)
                            },
                        )
                    }
                    respond(arr.toString(), HttpStatusCode.OK, json)
                }
                "core_course_get_contents" -> {
                    contentsCalls.incrementAndGet()
                    val course = courses.firstOrNull { it.id == form["courseid"]?.toLongOrNull() }
                    val arr: JsonArray = buildJsonArray {
                        add(
                            buildJsonObject {
                                put("id", 1); put("name", "Week 1")
                                put(
                                    "modules",
                                    buildJsonArray {
                                        for (f in course?.files.orEmpty()) add(
                                            buildJsonObject {
                                                put("modname", f.moduleType)
                                                put(
                                                    "contents",
                                                    buildJsonArray {
                                                        add(
                                                            buildJsonObject {
                                                                put("type", "file"); put("filename", f.name)
                                                                put("fileurl", f.url); put("filesize", f.size)
                                                                put("timemodified", 1); put("mimetype", "application/octet-stream")
                                                            },
                                                        )
                                                    },
                                                )
                                            },
                                        )
                                    },
                                )
                            },
                        )
                    }
                    respond(arr.toString(), HttpStatusCode.OK, json)
                }
                else -> respond("""{"exception":"x","errorcode":"unknownfunction"}""", HttpStatusCode.OK, json)
            }
        } else {
            downloadCalls.incrementAndGet()
            val token = request.url.parameters["token"].orEmpty()
            seenTokens += token
            if (token != validToken) return@MockEngine respond(errorJson(), HttpStatusCode.OK, json)
            if (downloadFails) return@MockEngine respond("", HttpStatusCode.InternalServerError)
            val path = request.url.encodedPath
            val file = courses.flatMap { it.files }.firstOrNull { path.endsWith(it.url.substringAfter("/2027")) }
            if (file?.bytes == null) {
                respond("", HttpStatusCode.NotFound)
            } else {
                respond(file.bytes, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/octet-stream"))
            }
        }
    }
}

/** A complete server-side study stack for tests. [clock] is injectable. */
class StudyRig(
    val moodle: FakeMoodle = FakeMoodle(),
    withToken: Boolean = false,
    moodleConfigured: Boolean = true,
    val clock: () -> Long = System::currentTimeMillis,
    extract: (File, String) -> Extraction = TextExtractor::extract,
) {
    val dir: File = Files.createTempDirectory("study-test").toFile()
    val sdb: ServerDb = openDb(tempDbPath())
    val store = SyncStore(sdb)
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val tokenStore = MoodleTokenStore(File(dir, "moodle-token.json"))
    val status = MoodleStatus()
    val http = HttpClient(moodle.engine())
    val files = StudyFiles(File(dir, "study"))
    val study = StudyService(
        sdb, store, files,
        if (moodleConfigured) MoodleClient(MOODLE_BASE, http) else null,
        tokenStore, status, scope, clock, extract,
    )
    val config: Config = testConfig("unused").copy(moodle = if (moodleConfigured) MoodleConfig("$MOODLE_BASE/") else null)

    init {
        if (withToken) tokenStore.write(GOOD_TOKEN, 1L)
    }

    fun fileRows() = sdb.db.study_fileQueries.selectAll().executeAsList()

    fun courseRows() = sdb.db.courseQueries.selectAll().executeAsList()

    fun close() {
        scope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
        http.close()
        sdb.close()
    }
}
