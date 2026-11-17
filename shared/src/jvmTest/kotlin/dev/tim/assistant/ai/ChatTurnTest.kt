package dev.tim.assistant.ai

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import java.net.ConnectException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** The additive tool-calling transport `ChatClient.chatTurn` (Phase 13). */
class ChatTurnTest {
    private fun client(body: String, status: HttpStatusCode = HttpStatusCode.OK, seen: MutableList<HttpRequestData>? = null) =
        ChatClient(MockEngine { request ->
            seen?.add(request)
            respond(body, status, headersOf(HttpHeaders.ContentType, "application/json"))
        })

    private val user = listOf(ToolMessage("user", "hi"))

    @Test
    fun a_plain_answer_is_returned_as_Answer() = runBlocking<Unit> {
        val body = """{"message":{"role":"assistant","content":"Hello"},"done":true}"""
        assertEquals(ToolTurn.Answer("Hello"), client(body).chatTurn("http://xps:11434", user, null))
    }

    @Test
    fun several_tool_calls_in_one_reply_are_all_parsed_in_order() = runBlocking<Unit> {
        val body = """{"message":{"role":"assistant","content":"","tool_calls":[
            {"function":{"name":"get_tasks","arguments":{}}},
            {"function":{"name":"get_log","arguments":{"kind":"meals","days":7}}},
            {"function":{"name":"read_doc","arguments":"{\"id\":\"d1\"}"}}]},"done":true}"""
        val turn = client(body).chatTurn("http://xps:11434", user, buildJsonArray { })
        val calls = (turn as ToolTurn.Calls).calls
        assertEquals(listOf("get_tasks", "get_log", "read_doc"), calls.map { it.name })
        assertEquals("meals", calls[1].arguments["kind"]!!.jsonPrimitive.content)
        assertEquals("d1", calls[2].arguments["id"]!!.jsonPrimitive.content)
        assertEquals(JsonObject(emptyMap()), calls[0].arguments)
    }

    @Test
    fun temperature_is_sent_in_options_only_when_given() = runBlocking<Unit> {
        val ok = """{"message":{"role":"assistant","content":"ok"},"done":true}"""
        val seen = mutableListOf<HttpRequestData>()
        val c = client(ok, seen = seen)
        c.chatTurn("http://xps:11434", user, null)
        c.chatTurn("http://xps:11434", user, null, temperature = 0.0)
        val (plain, warm) = seen.map {
            Json.parseToJsonElement((it.body as TextContent).text).jsonObject["options"]!!.jsonObject
        }
        assertEquals(CHAT_NUM_CTX, plain["num_ctx"]!!.jsonPrimitive.int)
        assertTrue("temperature" !in plain)
        assertEquals(CHAT_NUM_CTX, warm["num_ctx"]!!.jsonPrimitive.int)
        assertEquals(0.0, warm["temperature"]!!.jsonPrimitive.double)
    }

    @Test
    fun the_request_is_non_streamed_with_tools_echoed_calls_and_tool_name_results() = runBlocking<Unit> {
        val seen = mutableListOf<HttpRequestData>()
        val tools = buildJsonArray { add(buildJsonObject { put("type", "function") }) }
        val call = ToolCall("get_tasks", buildJsonObject { put("x", 1) })
        val messages = listOf(
            ToolMessage("system", "ctx"),
            ToolMessage("user", "tasks?"),
            ToolMessage("assistant", "", toolCalls = listOf(call)),
            ToolMessage("tool", "result text", toolName = "get_tasks"),
        )
        client("""{"message":{"role":"assistant","content":"ok"},"done":true}""", seen = seen)
            .chatTurn("http://xps:11434/", messages, tools)
        val request = seen.single()
        assertEquals("http://xps:11434/api/chat", request.url.toString())
        val json = Json.parseToJsonElement((request.body as TextContent).text).jsonObject
        assertEquals(CHAT_MODEL, json["model"]!!.jsonPrimitive.content)
        assertEquals("false", json["stream"]!!.jsonPrimitive.content)
        assertEquals("false", json["think"]!!.jsonPrimitive.content)
        assertEquals(1, json["tools"]!!.jsonArray.size)
        val sent = json["messages"]!!.jsonArray.map { it.jsonObject }
        assertEquals("get_tasks", sent[2]["tool_calls"]!!.jsonArray[0].jsonObject["function"]!!.jsonObject["name"]!!.jsonPrimitive.content)
        assertEquals("1", sent[2]["tool_calls"]!!.jsonArray[0].jsonObject["function"]!!.jsonObject["arguments"]!!.jsonObject["x"]!!.jsonPrimitive.content)
        assertEquals("tool", sent[3]["role"]!!.jsonPrimitive.content)
        assertEquals("get_tasks", sent[3]["tool_name"]!!.jsonPrimitive.content)
        assertEquals("result text", sent[3]["content"]!!.jsonPrimitive.content)
    }

    @Test
    fun without_tools_no_tools_field_is_sent() = runBlocking<Unit> {
        val seen = mutableListOf<HttpRequestData>()
        client("""{"message":{"role":"assistant","content":"ok"},"done":true}""", seen = seen).chatTurn("http://xps:11434", user, null)
        val json = Json.parseToJsonElement((seen.single().body as TextContent).text).jsonObject
        assertTrue("tools" !in json)
    }

    @Test
    fun connection_failure_is_Offline() = runBlocking<Unit> {
        val c = ChatClient(MockEngine { throw ConnectException("refused") })
        assertEquals(ToolTurn.Offline, c.chatTurn("http://xps:11434", user, null))
    }

    @Test
    fun http_errors_error_fields_and_incomplete_replies_are_Error() = runBlocking<Unit> {
        assertEquals(ToolTurn.Error("HTTP 500"), client("{}", HttpStatusCode.InternalServerError).chatTurn("http://x", user, null))
        assertEquals(ToolTurn.Error("model error"), client("""{"error":"boom"}""").chatTurn("http://x", user, null))
        assertEquals(ToolTurn.Error("incomplete response"), client("""{"message":{"role":"assistant","content":"a"},"done":false}""").chatTurn("http://x", user, null))
        assertEquals(ToolTurn.Error("bad response"), client("not json").chatTurn("http://x", user, null))
    }
}
