package dev.tim.assistant.ai

import dev.tim.assistant.ai.tools.ToolCheck
import dev.tim.assistant.ai.tools.ToolKind
import dev.tim.assistant.ai.tools.Tools
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** The shape and argument validators of the tool registry (definitions only; executors come later). */
class ToolRegistryTest {
    private fun args(json: String): JsonObject = Json.parseToJsonElement(json).jsonObject
    private fun ok(name: String, json: String) = Tools.validate(name, args(json)) as ToolCheck.Ok
    private fun bad(name: String, json: String) = assertTrue(Tools.validate(name, args(json)) is ToolCheck.Bad, "$name $json")

    @Test
    fun the_registry_has_14_read_22_write_and_2_research_tools_with_unique_names() {
        assertEquals(14, Tools.read.size)
        assertEquals(22, Tools.write.size)
        assertEquals(2, Tools.research.size)
        assertEquals(38, Tools.all.map { it.name }.toSet().size)
        assertTrue(Tools.read.all { it.kind == ToolKind.READ })
        assertEquals(38, Tools.ollamaToolList().size)
        assertEquals(37, Tools.ollamaToolList(includeDream = false).size)
    }

    @Test
    fun no_tool_name_or_schema_mentions_dreams() {
        assertTrue(Tools.all.filter { it.name != "add_dream" }.none { "dream" in it.name.lowercase() || "dream" in it.description.lowercase() })
    }

    @Test
    fun days_default_to_7_and_are_capped_at_30() {
        assertEquals("7", ok("get_screentime", "{}").args["days"]!!.jsonPrimitive.content)
        assertEquals("30", ok("get_listening", """{"days":"30"}""").args["days"]!!.jsonPrimitive.content)
        bad("get_screentime", """{"days":31}""")
        bad("get_events", """{"days":0}""")
        bad("get_log", """{"kind":"meals","days":"x"}""")
    }

    @Test
    fun get_log_accepts_only_the_five_kinds() {
        for (k in listOf("checkins", "spending", "meals", "journal", "health")) ok("get_log", """{"kind":"$k"}""")
        bad("get_log", """{"kind":"dreams"}""")
        bad("get_log", "{}")
    }

    @Test
    fun queue_job_allows_only_the_listed_kinds_with_their_inputs() {
        ok("queue_job", """{"kind":"GOAL_STEPS","input":{"goalId":"g1"}}""")
        ok("queue_job", """{"kind":"MEAL_LOOKUP","input":{"mealId":"m1"}}""")
        ok("queue_job", """{"kind":"MAIL_TRIAGE","input":{}}""")
        bad("queue_job", """{"kind":"GOAL_STEPS","input":{}}""")
        bad("queue_job", """{"kind":"DREAM_INTERPRET","input":{"dreamId":"d"}}""")
        bad("queue_job", """{"kind":"DREAM_PATTERNS","input":{}}""")
        bad("queue_job", """{"kind":"RESEARCH","input":{"researchId":"r"}}""")
    }

    @Test
    fun create_event_checks_dates_order_and_repeat() {
        ok("create_event", """{"title":"Dentist","start":"2026-11-17T15:00","end":"2026-11-17T16:00"}""")
        assertEquals("NONE", ok("create_event", """{"title":"x","start":"2026-11-17T15:00","end":"2026-11-17T15:00"}""").args["repeatRule"]!!.jsonPrimitive.content)
        ok("create_event", """{"title":"Birthday","start":"2026-12-03","end":"2026-12-03","allDay":true}""")
        ok("create_event", """{"title":"F","start":"2026-11-15T10:00","end":"2026-11-15T11:30","repeatRule":"WEEKLY","repeatUntil":"2027-01-31"}""")
        bad("create_event", """{"title":"x","start":"2026-11-17T15:00","end":"2026-11-17T14:00"}""")
        bad("create_event", """{"title":"x","start":"2026-11-17","end":"2026-11-17"}""")
        bad("create_event", """{"title":"x","start":"2026-12-03T10:00","end":"2026-12-03T11:00","allDay":true}""")
        bad("create_event", """{"title":"x","start":"2026-11-15T10:00","end":"2026-11-15T11:00","repeatRule":"WEEKLY","repeatUntil":"2026-11-01"}""")
        bad("create_event", """{"title":"x","start":"2026-02-30T10:00","end":"2026-02-30T11:00"}""")
        bad("create_event", """{"title":"x","start":"2026-11-15T10:00","end":"2026-11-15T11:00","color":"PINK"}""")
        bad("create_event", """{"title":"","start":"2026-11-15T10:00","end":"2026-11-15T11:00"}""")
    }

    @Test
    fun research_items_and_mail_tools_are_checked() {
        ok("research", """{"category":"NUTRITION","item":"hummus"}""")
        ok("research", """{"category":"DEFINITION","item":"חומוס"}""")
        bad("research", """{"category":"NUTRITION","item":"a@b.com"}""")
        bad("research", """{"category":"NUTRITION","item":"call 123456"}""")
        bad("research", """{"category":"NUTRITION","item":"${"x".repeat(61)}"}""")
        bad("research", """{"category":"OTHER","item":"x"}""")
        ok("draft_email", """{"to":"dana@example.com","subject":"Late","body":"Hi"}""")
        bad("draft_email", """{"to":"not-an-address","subject":"Late","body":"Hi"}""")
        bad("suggest_mail_action", """{"gmailId":"g","action":"LABEL","reason":"r"}""")
        ok("suggest_mail_action", """{"gmailId":"g","action":"ARCHIVE","reason":"r"}""")
        bad("suggest_mail_action", """{"gmailId":"g","action":"SEND","reason":"r"}""")
    }

    @Test
    fun set_app_rule_normalises_the_pattern_and_refuses_unsorted() {
        assertEquals("tiktok.exe", ok("set_app_rule", """{"matchType":"EXE","pattern":" TikTok.exe ","label":"UNPRODUCTIVE"}""").args["pattern"]!!.jsonPrimitive.content)
        bad("set_app_rule", """{"matchType":"EXE","pattern":"x","label":"UNSORTED"}""")
        bad("set_app_rule", """{"matchType":"URL","pattern":"x","label":"NEUTRAL"}""")
    }

    @Test
    fun unknown_tools_are_refused() {
        assertTrue(Tools.validate("delete_everything", JsonObject(emptyMap())) is ToolCheck.Bad)
    }
}
