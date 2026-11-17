package dev.tim.assistant.server.chat.ladder

import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class RouterDevSetTest {
    private val today = LocalDate.of(2026, 11, 12)

    private class Line(val group: String, val text: String, val intent: String, val slots: Map<String, String>)

    private fun load(): List<Line> {
        val raw = checkNotNull(javaClass.getResourceAsStream("/router-dev.jsonl")) { "router-dev.jsonl missing" }
            .bufferedReader(Charsets.UTF_8).readText()
        return raw.lines().filter { it.isNotBlank() }.map { l ->
            val o: JsonObject = Json.parseToJsonElement(l).jsonObject
            Line(
                o.getValue("group").jsonPrimitive.content,
                o.getValue("text").jsonPrimitive.content,
                o.getValue("intent").jsonPrimitive.content,
                o.getValue("slots").jsonObject.mapValues { it.value.jsonPrimitive.content },
            )
        }
    }

    private fun result(l: Line): RouteResult = IntentRouter.route(l.text, today)

    @Test
    fun devSetHasTheRequiredShape() {
        val lines = load()
        assertEquals(239, lines.size)
        val byGroup = lines.groupBy { it.group }
        assertTrue((byGroup["convo"]?.size ?: 0) >= 20)
        assertTrue((byGroup["blocked"]?.size ?: 0) >= 10)
        assertTrue((byGroup["otherday"]?.size ?: 0) >= 5)
        assertTrue((byGroup["otherkind"]?.size ?: 0) >= 10)
        assertTrue((byGroup["lookalike"]?.size ?: 0) >= 30)
        val acting = byGroup.getValue("act").groupBy { it.intent }
        for (i in listOf("create_task", "add_spending", "add_meal", "add_checkin", "add_journal", "add_dream", "mark_task_done", "move_task", "move_exam", "find", "ask_spending", "ask_events", "ask_tasks", "ask_exam")) {
            assertTrue((acting[i]?.size ?: 0) >= 6, "fewer than 6 lines for $i")
        }
        for (i in listOf("ask_health", "ask_meals", "ask_checkin")) assertTrue((acting[i]?.size ?: 0) >= 3, "fewer than 3 lines for $i")
    }

    @Test
    fun noLineProducesAWrongIntentOrWrongSlots() {
        val wrong = ArrayList<String>()
        for (l in load()) {
            val r = result(l)
            if (r is RouteResult.Match) {
                if (r.intent.wire != l.intent || r.slots != l.slots) wrong += "${l.text}: ${l.intent}->${r.intent.wire} ${r.slots}"
            }
        }
        assertEquals(emptyList(), wrong)
    }

    @Test
    fun nonActingLinesNeverMatchAnything() {
        val bad = load().filter { it.group != "act" }.filter { result(it) is RouteResult.Match }
        assertEquals(0, bad.size, "non-acting lines matched: " + bad.map { it.text })
    }

    @Test
    fun atLeastNinetyPercentOfActingLinesMatch() {
        val acting = load().filter { it.group == "act" }
        val matched = acting.count { l ->
            val r = result(l)
            r is RouteResult.Match && r.intent.wire == l.intent && r.slots == l.slots
        }
        assertTrue(matched * 100 >= acting.size * 90, "matched $matched of ${acting.size}; missed: " + acting.filter { l ->
            val r = result(l)
            !(r is RouteResult.Match && r.intent.wire == l.intent && r.slots == l.slots)
        }.map { it.text })
    }
}
