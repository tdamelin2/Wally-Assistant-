package dev.tim.assistant.ai

import dev.tim.assistant.ai.tools.ToolCheck
import dev.tim.assistant.ai.tools.Tools
import java.io.File
import java.net.URI
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.test.Test
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.Json

/**
 * Measures how well qwen3.5:9b on the XPS picks tools and fills arguments with the full registry (Phase 13, Task 12.1).
 * Runs only when WALLY_TOOL_PROBE=1; writes a Markdown table to shared/build/tool-probe/results.md.
 * Sends fixed prompts only (no personal data) and looks at the model's first reply, without running any tool.
 */
class ToolProbeTest {
    private companion object { const val NONE = "(no tool)" }

    private class Case(val prompt: String, val expected: Set<String>, val args: Map<String, String> = emptyMap())

    private val cases = listOf(
        Case("What tasks do I have open?", setOf("get_tasks")),
        Case("How am I doing on my goals?", setOf("get_goals")),
        Case("Find my notes about the physics lab.", setOf("search_docs")),
        Case("Draft an email to dana@example.com telling her I will be ten minutes late to the meeting.", setOf("draft_email")),
        Case("What courses am I studying and how are my quizzes going?", setOf("get_study")),
        Case("When is my next exam?", setOf("get_exams")),
        Case("How much time did I spend on my phone this week?", setOf("get_screentime")),
        Case("What music have I been listening to lately?", setOf("get_listening")),
        Case("Do I have any unread emails?", setOf("get_unread_mail")),
        Case("How did my last laptop hardware check look?", setOf("get_hw_checks")),
        Case("What did I look up earlier about hummus?", setOf("get_research")),
        Case("What did I eat in the last 3 days?", setOf("get_log"), mapOf("kind" to "meals")),
        Case("How much money did I spend this week?", setOf("get_log"), mapOf("kind" to "spending")),
        Case("What is on my calendar this week?", setOf("get_events")),
        Case("Add a task to buy milk tomorrow.", setOf("create_task")),
        Case("Write me a note called Packing list with towels, a charger and a passport.", setOf("create_doc")),
        Case("Set a goal for me: run a 10k by 2027-03-01.", setOf("create_goal")),
        Case("Add my linear algebra exam on 2026-12-15 at 09:00.", setOf("add_exam")),
        Case("Mark the laptop program tiktok.exe as unproductive in my screen time.", setOf("set_app_rule"), mapOf("label" to "UNPRODUCTIVE")),
        Case("How many calories are in hummus?", setOf("research")),
        Case("Schedule a dentist appointment next Tuesday at 15:00 for one hour.", setOf("create_event")),
        Case("Add a weekly event called Football every Sunday from 10:00 to 11:30 starting 2026-11-15, until 2027-01-31.", setOf("create_event")),
        // 23-30: the step 3 prompts (add/update tools, ask-don't-guess, research routing); NONE = answer or question, no tool call
        Case("Log that I had a chicken sandwich for lunch today.", setOf("add_meal")),
        Case("I spent 45 shekels on groceries today.", setOf("add_spending")),
        Case("Mark my task buy milk as done.", setOf("get_tasks", "update_task")),
        Case("Put a dentist appointment in my calendar next Tuesday at 15:00.", setOf(NONE)),
        Case("How many calories are in a hamburger?", setOf("research")),
        Case("Why is the sky black at night?", setOf(NONE)),
        Case("What is the current population of the town of Rosh Pinna?", setOf("research_question")),
        Case("Add a journal entry: today I finally understood eigenvectors.", setOf("add_journal")),
    )

    private fun versionOf(baseUrl: String): String = try {
        val text = URI(baseUrl.trimEnd('/') + "/api/version").toURL().readText()
        Json.parseToJsonElement(text).jsonObject["version"]!!.jsonPrimitive.content
    } catch (e: Exception) {
        "unknown (${e.javaClass.simpleName})"
    }

    @Test
    fun probe_tool_calls_against_the_xps() = runBlocking<Unit> {
        if (System.getenv("WALLY_TOOL_PROBE") != "1") return@runBlocking
        val baseUrl = xpsOllamaUrl(System.getenv("WALLY_XPS_URL"))
        val now = ZonedDateTime.now(ZoneId.of("Asia/Jerusalem"))
        // the real tool system prompt, with only the date/time as context
        val system = ChatContext.toolSystemPrompt(
            "Right now it is " + now.format(DateTimeFormatter.ofPattern("EEEE yyyy-MM-dd HH:mm", Locale.ENGLISH)) + " (Asia/Jerusalem).",
        )
        val client = ChatClient()
        val tools = kotlinx.serialization.json.JsonArray(Tools.ollamaToolList())
        val rows = StringBuilder()
        var okReads = 0
        var okEvents = 0
        var okNew = 0
        val started = System.currentTimeMillis()
        cases.forEachIndexed { index, case ->
            val turn = client.chatTurn(baseUrl, listOf(ToolMessage("system", system), ToolMessage("user", case.prompt)), tools)
            var actual = "-"
            var valid = "-"
            var ok = false
            when (turn) {
                is ToolTurn.Calls -> {
                    val first = turn.calls.first()
                    actual = turn.calls.joinToString(", ") { it.name }
                    val check = Tools.validate(first.name, first.arguments)
                    valid = if (check is ToolCheck.Ok) "yes" else "no: " + (check as ToolCheck.Bad).reason
                    val argsOk = case.args.all { (k, v) -> first.arguments[k]?.jsonPrimitive?.content.equals(v, ignoreCase = true) }
                    ok = first.name in case.expected && check is ToolCheck.Ok && argsOk
                    if (first.name in case.expected && check is ToolCheck.Ok && !argsOk) valid = "yes (wrong arguments: ${first.arguments})"
                }
                is ToolTurn.Answer -> {
                    actual = "$NONE " + turn.text.take(40).replace("|", "/").replace("\n", " ")
                    ok = NONE in case.expected
                    valid = "-"
                }
                is ToolTurn.Offline -> actual = "offline"
                is ToolTurn.Error -> actual = "error: ${turn.message}"
            }
            if (ok) { if (index < 20) okReads++ else if (index < 22) okEvents++ else okNew++ }
            rows.append("| ${index + 1} | ${case.prompt} | ${case.expected.joinToString("/")} | ${actual.replace("|", "/")} | ${valid.replace("|", "/")} | ${if (ok) "yes" else "NO"} |\n")
        }
        val seconds = (System.currentTimeMillis() - started) / 1000
        val dir = File("build/tool-probe").also { it.mkdirs() }
        dir.resolve("tools.json").writeText(tools.toString())
        val out = dir.resolve("results.md")
        out.writeText(
            "Ollama version: ${versionOf(baseUrl)}\nModel: $CHAT_MODEL\nDate: ${now.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)}\n" +
                "Registry: ${Tools.all.size} tools, one turn per prompt, $seconds s total\n" +
                "Score: $okReads/20 (prompts 1-20), $okEvents/2 (event prompts 21-22), $okNew/8 (new prompts 23-30)\n\n" +
                "| # | Prompt | Expected tool | Actual tool | Args valid | OK |\n|---|---|---|---|---|---|\n" + rows,
        )
        client.close()
    }
}
