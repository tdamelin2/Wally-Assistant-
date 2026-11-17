package dev.tim.assistant.data

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking

class SyncClientTest {
    private val cfg = ServerConfig("http://server:8080/", "tok")
    private val seen = mutableListOf<HttpRequestData>()
    private var reply: MockRequestHandleScope.(HttpRequestData) -> HttpResponseData = { respond("{}", HttpStatusCode.OK) }
    private var failWith: Exception? = null

    private fun client() = SyncClient(
        MockEngine { request ->
            seen += request
            failWith?.let { throw it }
            reply(request)
        },
    )

    private fun MockRequestHandleScope.json(text: String, status: HttpStatusCode = HttpStatusCode.OK) =
        respond(text, status, headersOf(HttpHeaders.ContentType, "application/json"))

    // ---- Dream week ----

    private val weekBody =
        """{"sentAt":1762600000000,"blocks":[{"id":"b1","title":"Gym","dayOfWeek":1,"startMinute":540,"endMinute":600,"color":"BLUE","extra":1}]}"""

    @Test
    fun dream_week_sent_is_decoded_with_the_bearer_token() = runBlocking<Unit> {
        reply = { json(weekBody) }
        val r = client().dreamWeek(cfg)
        assertIs<DreamWeekResult.Sent>(r)
        assertEquals(1762600000000L, r.week.sentAt)
        assertEquals(DreamBlock("b1", "Gym", 1, 540, 600, "BLUE", null), r.week.blocks.single())
        assertEquals("/v1/calendar/dream-week", seen.single().url.encodedPath)
        assertEquals("Bearer tok", seen.single().headers[HttpHeaders.Authorization])
    }

    @Test
    fun dream_week_with_no_blocks_is_still_sent() = runBlocking<Unit> {
        reply = { json("""{"sentAt":5,"blocks":[]}""") }
        assertEquals(DreamWeekResult.Sent(DreamWeek(5, emptyList())), client().dreamWeek(cfg))
    }

    @Test
    fun dream_week_status_mapping() = runBlocking<Unit> {
        reply = { json("""{"error":"not-sent"}""", HttpStatusCode.NotFound) }
        assertEquals(DreamWeekResult.NotSent, client().dreamWeek(cfg))
        reply = { json("""{"error":"other"}""", HttpStatusCode.NotFound) }
        assertIs<DreamWeekResult.Unavailable>(client().dreamWeek(cfg))
        reply = { json("not json", HttpStatusCode.NotFound) }
        assertIs<DreamWeekResult.Unavailable>(client().dreamWeek(cfg))
        reply = { json("""{"error":"calendar-unreachable"}""", HttpStatusCode.BadGateway) }
        assertIs<DreamWeekResult.Unavailable>(client().dreamWeek(cfg))
        reply = { respond("", HttpStatusCode.Unauthorized) }
        assertEquals(DreamWeekResult.Unauthorized, client().dreamWeek(cfg))
    }

    @Test
    fun dream_week_bad_body_or_network_error_is_unavailable_and_cancellation_is_rethrown() = runBlocking<Unit> {
        reply = { json("""{"nope":1}""") }
        assertIs<DreamWeekResult.Unavailable>(client().dreamWeek(cfg))
        reply = { json("not json") }
        assertIs<DreamWeekResult.Unavailable>(client().dreamWeek(cfg))
        failWith = IOException("down")
        assertIs<DreamWeekResult.Unavailable>(client().dreamWeek(cfg))
        failWith = CancellationException("stop")
        var thrown = false
        try { client().dreamWeek(cfg) } catch (e: CancellationException) { thrown = true }
        assertTrue(thrown)
    }

    @Test
    fun dream_week_line_texts() {
        assertEquals("", dreamWeekLine(null))
        assertEquals("Dream week not sent from Wally Calendar yet", dreamWeekLine(DreamWeekResult.NotSent))
        assertEquals("Wally Calendar not reachable", dreamWeekLine(DreamWeekResult.Unauthorized))
        assertEquals("Wally Calendar not reachable", dreamWeekLine(DreamWeekResult.Unavailable("x")))
        // 2026-11-08 10:00 UTC is 12:00 in Jerusalem
        val sent = DreamWeekResult.Sent(DreamWeek(kotlin.time.Instant.parse("2026-11-08T10:00:00Z").toEpochMilliseconds(), emptyList()))
        assertEquals("Last sent from Wally Calendar: 2026-11-08 12:00", dreamWeekLine(sent))
    }
    @Test
    fun moodle_token_sends_the_link_and_returns_the_course_count() = runBlocking<Unit> {
        reply = { json("""{"courses":11}""") }
        val r = client().moodleToken(cfg, "moodlemobile://token=abc")
        assertEquals(ApiResult.Ok(11), r)
        val req = seen.single()
        assertEquals(HttpMethod.Post, req.method)
        assertEquals("/v1/study/moodle-token", req.url.encodedPath)
        assertEquals("Bearer tok", req.headers[HttpHeaders.Authorization])
        assertTrue((req.body as TextContent).text.contains("moodlemobile://token=abc"))
    }

    @Test
    fun moodle_token_errors_use_fixed_messages() = runBlocking<Unit> {
        val expected = mapOf(
            400 to "bad link", 502 to "Moodle rejected the link", 503 to "Moodle not set up on the server",
        )
        for ((code, message) in expected) {
            reply = { json("""{"error":"x"}""", HttpStatusCode.fromValue(code)) }
            assertEquals(ApiResult.Failure(message), client().moodleToken(cfg, "x"), "status $code")
        }
        reply = { respond("", HttpStatusCode.Unauthorized) }
        assertEquals(ApiResult.Unauthorized, client().moodleToken(cfg, "x"))
    }

    @Test
    fun sync_now_returns_new_files_and_maps_busy_and_not_set_up() = runBlocking<Unit> {
        reply = { json("""{"newFiles":3}""") }
        assertEquals(ApiResult.Ok(3), client().studySyncNow(cfg))
        assertEquals("/v1/study/sync-now", seen.last().url.encodedPath)
        reply = { json("{}", HttpStatusCode.Conflict) }
        assertEquals(ApiResult.Failure("A check is already running"), client().studySyncNow(cfg))
        reply = { json("{}", HttpStatusCode.ServiceUnavailable) }
        assertEquals(ApiResult.Failure("Moodle not set up on the server"), client().studySyncNow(cfg))
    }

    @Test
    fun upload_sends_multipart_and_maps_errors() = runBlocking<Unit> {
        reply = { json("""{"fileId":"sfile:upload:1"}""", HttpStatusCode.Created) }
        assertEquals(ApiResult.Ok("sfile:upload:1"), client().uploadStudyFile(cfg, "course:7", byteArrayOf(1, 2, 3), "week1.pdf"))
        val req = seen.single()
        assertEquals("/v1/study/upload", req.url.encodedPath)
        assertTrue(req.body.contentType.toString().startsWith("multipart/form-data"))
        reply = { json("{}", HttpStatusCode.PayloadTooLarge) }
        assertEquals(ApiResult.Failure("Too large (max 50 MB)"), client().uploadStudyFile(cfg, "c", byteArrayOf(1), "a.pdf"))
        reply = { json("{}", HttpStatusCode.UnsupportedMediaType) }
        assertEquals(ApiResult.Failure("Only PDF or PPTX"), client().uploadStudyFile(cfg, "c", byteArrayOf(1), "a.pdf"))
    }

    @Test
    fun a_broken_upload_connection_says_the_result_is_unknown() = runBlocking<Unit> {
        failWith = IOException("connection reset")
        assertEquals(ApiResult.Failure(UPLOAD_UNKNOWN), client().uploadStudyFile(cfg, "c", byteArrayOf(1), "a.pdf"))
        assertEquals("Upload result unknown - sync and check the list", UPLOAD_UNKNOWN)
        assertEquals(1, seen.size)
    }

    @Test
    fun text_study_set_and_delete_paths_and_statuses() = runBlocking<Unit> {
        reply = { respond("hello text", HttpStatusCode.OK) }
        assertEquals(ApiResult.Ok("hello text"), client().studyFileText(cfg, "sfile:a"))
        assertEquals(HttpMethod.Get, seen.last().method)
        assertEquals("/v1/study/files/sfile:a/text", java.net.URLDecoder.decode(seen.last().url.encodedPath, "UTF-8"))
        reply = { json("{}", HttpStatusCode.Conflict) }
        assertEquals(ApiResult.Failure("HTTP 409"), client().studyFileText(cfg, "sfile:a"))
        reply = { json("{}", HttpStatusCode.NotFound) }
        assertEquals(ApiResult.Failure("HTTP 404"), client().studyFileText(cfg, "sfile:a"))

        reply = { json("{}") }
        assertEquals(ApiResult.Ok(Unit), client().setStudySet(cfg, "sfile:a", "doc:studyset:sfile:a"))
        assertEquals(HttpMethod.Post, seen.last().method)
        assertTrue((seen.last().body as TextContent).text.contains("doc:studyset:sfile:a"))

        assertEquals(ApiResult.Ok(Unit), client().deleteStudyFile(cfg, "sfile:upload:1"))
        assertEquals(HttpMethod.Delete, seen.last().method)
        reply = { json("{}", HttpStatusCode.Conflict) }
        assertEquals(ApiResult.Failure("Moodle files cannot be deleted"), client().deleteStudyFile(cfg, "sfile:a"))
    }

    @Test
    fun set_course_enabled_puts_the_flag() = runBlocking<Unit> {
        reply = { json("{}") }
        assertEquals(ApiResult.Ok(Unit), client().setCourseEnabled(cfg, "course:7", true))
        val req = seen.single()
        assertEquals(HttpMethod.Put, req.method)
        assertEquals("/v1/study/courses/course:7/enabled", java.net.URLDecoder.decode(req.url.encodedPath, "UTF-8"))
        assertEquals("""{"enabled":true}""", (req.body as TextContent).text)
    }

    @Test
    fun a_network_error_is_a_plain_failure_and_cancellation_is_rethrown() = runBlocking<Unit> {
        failWith = IOException("down")
        assertIs<ApiResult.Failure>(client().studySyncNow(cfg))
        failWith = CancellationException("stop")
        var rethrown = false
        try {
            client().studySyncNow(cfg)
        } catch (e: CancellationException) {
            rethrown = true
        }
        assertTrue(rethrown)
    }
}
