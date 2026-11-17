package dev.tim.assistant.ai

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import java.net.ConnectException
import java.net.UnknownHostException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class ChatClientTest {
    private val messages = listOf(ChatMessage("system", "ctx"), ChatMessage("user", "hi"))

    private fun client(status: HttpStatusCode = HttpStatusCode.OK, body: String, seen: MutableList<HttpRequestData>? = null) =
        ChatClient(MockEngine { request ->
            seen?.add(request)
            respond(body, status, headersOf(HttpHeaders.ContentType, "application/x-ndjson"))
        })

    private fun chunk(text: String) = """{"model":"m","message":{"role":"assistant","content":"$text"},"done":false}"""
    private fun last(text: String, tokens: Int) =
        """{"model":"m","message":{"role":"assistant","content":"$text"},"done":true,"prompt_eval_count":$tokens}"""

    @Test
    fun streaming_chunks_are_joined_and_done_carries_prompt_eval_count() = runBlocking<Unit> {
        val body = listOf(chunk("Hel"), chunk("lo "), last("wörld", 1234)).joinToString("\n") + "\n"
        val events = client(body = body).send("http://xps:11434", messages).toList()
        val text = events.filterIsInstance<ChatEvent.Chunk>().joinToString("") { it.text }
        assertEquals("Hello wörld", text)
        assertEquals(ChatEvent.Done(1234), events.last())
        assertEquals(4, events.size)
    }

    @Test
    fun hebrew_chunks_survive() = runBlocking<Unit> {
        val body = listOf(chunk("שלום"), last("!", 5)).joinToString("\n") + "\n"
        val events = client(body = body).send("http://xps:11434", messages).toList()
        assertEquals("שלום!", events.filterIsInstance<ChatEvent.Chunk>().joinToString("") { it.text })
    }

    @Test
    fun the_request_has_the_contract_shape() = runBlocking<Unit> {
        val seen = mutableListOf<HttpRequestData>()
        client(body = last("", 1) + "\n", seen = seen).send("http://xps:11434/", messages).toList()
        val request = seen.single()
        assertEquals("http://xps:11434/api/chat", request.url.toString())
        val json = Json.parseToJsonElement((request.body as TextContent).text).jsonObject
        assertEquals(CHAT_MODEL, json["model"]!!.jsonPrimitive.content)
        assertEquals("true", json["stream"]!!.jsonPrimitive.content)
        assertEquals("false", json["think"]!!.jsonPrimitive.content)
        assertEquals(CHAT_NUM_CTX, json["options"]!!.jsonObject["num_ctx"]!!.jsonPrimitive.int)
        val sent = json["messages"]!!.jsonArray
        assertEquals(2, sent.size)
        assertEquals("system", sent[0].jsonObject["role"]!!.jsonPrimitive.content)
    }

    @Test
    fun connect_failure_gives_xps_offline() = runBlocking<Unit> {
        val client = ChatClient(MockEngine { throw ConnectException("refused") })
        assertEquals(listOf<ChatEvent>(ChatEvent.XpsOffline), client.send("http://xps:11434", messages).toList())
    }

    @Test
    fun unknown_host_gives_xps_offline() = runBlocking<Unit> {
        val client = ChatClient(MockEngine { throw UnknownHostException("xps") })
        assertEquals(listOf<ChatEvent>(ChatEvent.XpsOffline), client.send("http://xps:11434", messages).toList())
    }

    @Test
    fun http_404_gives_chat_error_404() = runBlocking<Unit> {
        val events = client(HttpStatusCode.NotFound, """{"error":"model not found"}""").send("http://xps:11434", messages).toList()
        assertEquals(listOf<ChatEvent>(ChatEvent.ChatError(404)), events)
        assertEquals("Chat error: 404", chatErrorText(404))
    }

    @Test
    fun end_of_stream_before_done_gives_chat_error_0() = runBlocking<Unit> {
        val events = client(body = chunk("par") + "\n" + chunk("tial") + "\n").send("http://xps:11434", messages).toList()
        assertEquals(ChatEvent.ChatError(0), events.last())
        assertTrue(events.dropLast(1).all { it is ChatEvent.Chunk })
    }

    @Test
    fun error_chunk_gives_chat_error_0() = runBlocking<Unit> {
        val events = client(body = chunk("a") + "\n" + """{"error":"boom"}""" + "\n").send("http://xps:11434", messages).toList()
        assertEquals(listOf(ChatEvent.Chunk("a"), ChatEvent.ChatError(0)), events)
    }

    @Test
    fun malformed_line_gives_chat_error_0() = runBlocking<Unit> {
        val events = client(body = chunk("a") + "\nnot json\n").send("http://xps:11434", messages).toList()
        assertEquals(listOf(ChatEvent.Chunk("a"), ChatEvent.ChatError(0)), events)
    }

    @Test
    fun done_without_a_count_is_null() = runBlocking<Unit> {
        val body = """{"message":{"role":"assistant","content":""},"done":true}""" + "\n"
        assertEquals(listOf<ChatEvent>(ChatEvent.Done(null)), client(body = body).send("http://xps:11434", messages).toList())
    }

    private fun full(text: String, done: Boolean = true) =
        """{"model":"m","message":{"role":"assistant","content":"$text"},"done":$done}"""

    @Test
    fun complete_returns_ok_for_a_finished_answer() = runBlocking<Unit> {
        val result = client(body = full("שלום עולם")).complete("http://xps:11434", messages)
        assertEquals(Completion.Ok("שלום עולם"), result)
    }

    @Test
    fun complete_sends_a_non_streaming_request_with_the_format_schema() = runBlocking<Unit> {
        val seen = mutableListOf<HttpRequestData>()
        val schema = Json.parseToJsonElement("""{"type":"object","properties":{"steps":{"type":"array"}}}""").jsonObject
        client(body = full("{}"), seen = seen).complete("http://xps:11434/", messages, schema)
        val request = seen.single()
        assertEquals("http://xps:11434/api/chat", request.url.toString())
        val json = Json.parseToJsonElement((request.body as TextContent).text).jsonObject
        assertEquals(CHAT_MODEL, json["model"]!!.jsonPrimitive.content)
        assertEquals("false", json["stream"]!!.jsonPrimitive.content)
        assertEquals("false", json["think"]!!.jsonPrimitive.content)
        assertEquals(CHAT_NUM_CTX, json["options"]!!.jsonObject["num_ctx"]!!.jsonPrimitive.int)
        assertEquals(schema, json["format"]!!.jsonObject)
        assertEquals(2, json["messages"]!!.jsonArray.size)
    }

    @Test
    fun complete_without_a_format_sends_no_format_key() = runBlocking<Unit> {
        val seen = mutableListOf<HttpRequestData>()
        client(body = full("x"), seen = seen).complete("http://xps:11434", messages)
        assertTrue("format" !in Json.parseToJsonElement((seen.single().body as TextContent).text).jsonObject)
    }

    @Test
    fun complete_is_an_error_when_the_answer_is_not_done() = runBlocking<Unit> {
        val result = client(body = full("par", done = false)).complete("http://xps:11434", messages)
        assertTrue(result is Completion.Error)
    }

    @Test
    fun complete_is_an_error_for_a_partial_stream_of_lines() = runBlocking<Unit> {
        val body = chunk("par") + "\n" + chunk("tial") + "\n"
        assertTrue(client(body = body).complete("http://xps:11434", messages) is Completion.Error)
    }

    @Test
    fun complete_is_offline_when_there_is_no_response() = runBlocking<Unit> {
        val refused = ChatClient(MockEngine { throw ConnectException("refused") })
        assertEquals(Completion.Offline, refused.complete("http://xps:11434", messages))
        val unknown = ChatClient(MockEngine { throw UnknownHostException("xps") })
        assertEquals(Completion.Offline, unknown.complete("http://xps:11434", messages))
    }

    @Test
    fun complete_is_an_error_for_http_500_without_leaking_the_body() = runBlocking<Unit> {
        val result = client(HttpStatusCode.InternalServerError, """{"error":"SECRET-PROMPT-TEXT"}""")
            .complete("http://xps:11434", messages)
        assertEquals(Completion.Error("HTTP 500"), result)
    }

    @Test
    fun complete_is_an_error_for_bad_json_and_for_an_error_object() = runBlocking<Unit> {
        assertTrue(client(body = "not json").complete("http://xps:11434", messages) is Completion.Error)
        assertTrue(client(body = """{"error":"boom"}""").complete("http://xps:11434", messages) is Completion.Error)
        assertTrue(client(body = """{"done":true}""").complete("http://xps:11434", messages) is Completion.Error)
    }

    @Test
    fun streaming_send_is_unchanged_by_complete() = runBlocking<Unit> {
        val events = client(body = last("hi", 3) + "\n").send("http://xps:11434", messages).toList()
        assertEquals(listOf(ChatEvent.Chunk("hi"), ChatEvent.Done(3)), events)
    }

    @Test
    fun the_job_deadline_is_ten_minutes() {
        assertEquals(600_000L, JOB_MODEL_TIMEOUT_MS)
    }

    @Test
    fun history_keeps_the_newest_turns_within_the_cap() {
        val history = listOf(
            ChatMessage("user", "a".repeat(1500)),
            ChatMessage("assistant", "b".repeat(400)),
            ChatMessage("user", "c".repeat(500)),
            ChatMessage("assistant", "d".repeat(500)),
        )
        val kept = trimHistory(history)
        assertEquals(listOf("assistant", "user", "assistant"), kept.map { it.role })
        assertEquals(1400, kept.sumOf { it.content.length })
        assertTrue(kept.sumOf { it.content.length } <= CHAT_HISTORY_MAX_CHARS)
        assertEquals(emptyList(), trimHistory(emptyList()))
    }
}
