package dev.tim.assistant.server.chat

import dev.tim.assistant.ai.tools.ToolMode
import dev.tim.assistant.ai.tools.Tools
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** The exact tool list and modes of PLAN 17.4, what the server may offer, and that nothing outside the offered set runs. */
class ToolPolicyTest {
    private fun modes(mode: ToolMode) = Tools.all.filter { Tools.modeOf(it.name) == mode }.map { it.name }.toSet()

    @Test
    fun theWriteToolsAndTheirModesAreExactlyThePlanTable() {
        assertEquals(setOf("create_task", "add_checkin", "add_meal", "add_spending", "add_journal", "add_dream", "research"), modes(ToolMode.AUTO))
        assertEquals(
            setOf(
                "update_task", "update_checkin", "update_meal", "update_spending", "update_journal", "create_goal", "update_goal",
                "add_exam", "update_exam", "create_doc", "update_doc", "create_event", "set_app_rule", "queue_job", "suggest_mail_action",
                "draft_email", "research_question",
            ),
            modes(ToolMode.CARD),
        )
        assertEquals(
            setOf(
                "get_tasks", "get_goals", "get_exams", "get_events", "get_log", "get_study", "get_screentime", "get_listening",
                "get_research", "get_unread_mail", "get_hw_checks", "search_docs", "read_doc", "find",
            ),
            modes(ToolMode.READ),
        )
    }

    @Test
    fun thereIsNoDeleteSendSettingsOrFileTool() {
        val names = Tools.all.map { it.name }
        assertTrue(names.none { it.contains("delete") || it.contains("remove") || it.startsWith("send") || it.contains("setting") || it.contains("file") })
        assertNull(Tools.modeOf("send_email"))
        assertNull(Tools.modeOf("delete_task"))
    }

    @Test
    fun theDreamToolIsOnlyOfferedWhenTheMessageMentionsDreams() {
        assertTrue(Tools.ollamaToolList(includeDream = true).any { it["function"]!!.jsonObject["name"]!!.jsonPrimitive.content == "add_dream" })
        assertFalse(Tools.ollamaToolList(includeDream = false).any { it["function"]!!.jsonObject["name"]!!.jsonPrimitive.content == "add_dream" })
    }

    @Test
    fun aToolOutsideTheOfferedSetIsRefusedAndNothingIsWritten() = runBlocking<Unit> {
        val rig = ChatRig()
        try {
            val executor = rig.executor()
            val offered = Tools.ollamaToolList(includeDream = false).map { it["function"]!!.jsonObject["name"]!!.jsonPrimitive.content }.toSet()
            val result = rig.call(executor, "add_dream", """{"text":"I flew"}""", allowed = offered)
            assertTrue("not available" in result.text)
            assertTrue(rig.pull().isEmpty())
            // the executor itself also refuses when the box is bypassed
            val direct = executor.handle("add_dream", kotlinx.serialization.json.buildJsonObject { put("text", JsonPrimitiveOf("I flew")) }, offered)
            assertTrue("not available" in direct.text)
            assertTrue(rig.pull().isEmpty())
            // a card tool outside the set is refused too
            assertTrue("not available" in rig.call(executor, "create_goal", """{"title":"Run"}""", allowed = setOf("get_tasks")).text)
            assertTrue(rig.pull().isEmpty())
        } finally {
            rig.close()
        }
    }

    @Test
    fun theServerExecutorRefusesAnAddDreamWhenTheMessageDidNotMentionDreams() = runBlocking<Unit> {
        val rig = ChatRig()
        try {
            val executor = rig.executor() // dreamAllowed stays false
            val result = rig.call(executor, "add_dream", """{"text":"I flew"}""")
            assertTrue("not available" in result.text)
            assertTrue(rig.pull().isEmpty())
            executor.dreamAllowed = true
            val saved = rig.call(executor, "add_dream", """{"text":"I flew","mood":4}""")
            assertTrue(saved.actions.size == 1)
            assertEquals("I flew", rig.str(rig.pull().single { it.table == "dream" }, "text"))
        } finally {
            rig.close()
        }
    }
}

private fun JsonPrimitiveOf(s: String) = kotlinx.serialization.json.JsonPrimitive(s)
