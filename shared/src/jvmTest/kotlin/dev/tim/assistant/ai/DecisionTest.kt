package dev.tim.assistant.ai

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DecisionTest {
    private fun actionEnum(s: JsonObject): List<String> =
        s["properties"]!!.jsonObject["action"]!!.jsonObject["enum"]!!.jsonArray.map { (it as JsonPrimitive).content }

    @Test
    fun actionListHasFifteenNames() {
        assertEquals(15, DECISION_ACTIONS.size)
        assertEquals(DECISION_ACTIONS.size, DECISION_ACTIONS.toSet().size)
    }

    @Test
    fun schemaWithoutDreamsOmitsAddDream() {
        val s = decisionSchema(false)
        assertEquals(14, actionEnum(s).size)
        assertTrue("add_dream" !in actionEnum(s))
        assertEquals(JsonArray(listOf(JsonPrimitive("kind"))), s["required"])
        val kinds = s["properties"]!!.jsonObject["kind"]!!.jsonObject["enum"]!!.jsonArray.map { (it as JsonPrimitive).content }
        assertEquals(listOf("answer", "action", "needs_laptop"), kinds)
        val args = s["properties"]!!.jsonObject["args"]!!.jsonObject
        assertEquals("object", (args["type"] as JsonPrimitive).content)
        assertEquals("string", (args["additionalProperties"]!!.jsonObject["type"] as JsonPrimitive).content)
    }

    @Test
    fun schemaWithDreamsHasAddDream() {
        val s = decisionSchema(true)
        assertEquals(15, actionEnum(s).size)
        assertTrue("add_dream" in actionEnum(s))
        assertEquals(DECISION_ACTIONS.toSet(), actionEnum(s).toSet())
    }

    @Test
    fun parsesValidAction() {
        val d = parseDecision("""{"kind":"action","action":"add_spending","target":"","args":{"amount":"35","category":"Food"}}""")
        assertNotNull(d)
        assertEquals("action", d.kind)
        assertEquals("add_spending", d.action)
        assertEquals(mapOf("amount" to "35", "category" to "Food"), d.args)
    }

    @Test
    fun parsesAnswerAndTolerantOfWhitespace() {
        val d = parseDecision("  {\"kind\":\"answer\",\"reply\":\"Hi\"}\n")
        assertEquals(ModelDecision("answer", reply = "Hi"), d)
    }

    @Test
    fun extraFieldsAreTolerated() {
        val d = parseDecision("""{"kind":"needs_laptop","thinking":"x","extra":{"a":1}}""")
        assertEquals("needs_laptop", d?.kind)
    }

    @Test
    fun missingOrUnknownKindIsNull() {
        assertNull(parseDecision("""{"reply":"hi"}"""))
        assertNull(parseDecision("""{"kind":"chat","reply":"hi"}"""))
        assertNull(parseDecision("""{"kind":5}"""))
        assertNull(parseDecision("""{"kind":null}"""))
    }

    @Test
    fun nonJsonAndNonObjectAreNull() {
        assertNull(parseDecision("hello there"))
        assertNull(parseDecision(""))
        assertNull(parseDecision("""["answer"]"""))
        assertNull(parseDecision("\"answer\""))
        assertNull(parseDecision("""{"kind":"answer""""))
    }

    @Test
    fun numberAndBooleanArgsBecomeText() {
        val d = parseDecision("""{"kind":"action","action":"add_checkin","args":{"mood":4,"done":true,"amount":12.5}}""")
        assertEquals(mapOf("mood" to "4", "done" to "true", "amount" to "12.5"), d?.args)
    }

    @Test
    fun objectArrayOrNullArgValuesGiveNull() {
        assertNull(parseDecision("""{"kind":"action","args":{"a":{"b":"c"}}}"""))
        assertNull(parseDecision("""{"kind":"action","args":{"a":["x"]}}"""))
        assertNull(parseDecision("""{"kind":"action","args":{"a":null}}"""))
        assertNull(parseDecision("""{"kind":"action","args":["x"]}"""))
        assertNull(parseDecision("""{"kind":"action","args":"x"}"""))
    }

    @Test
    fun nonTextReplyActionOrTargetGiveNull() {
        assertNull(parseDecision("""{"kind":"answer","reply":{"a":1}}"""))
        assertNull(parseDecision("""{"kind":"action","action":3}"""))
        assertNull(parseDecision("""{"kind":"action","target":["x"]}"""))
    }
}
