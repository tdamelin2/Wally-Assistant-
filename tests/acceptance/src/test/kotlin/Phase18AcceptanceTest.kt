import dev.tim.assistant.model.CardDisplayDto
import dev.tim.assistant.model.ChatMessageDto
import dev.tim.assistant.model.SendMessageRequest
import dev.tim.assistant.server.chat.SendResult
import dev.tim.assistant.server.chat.CardDisplays
import dev.tim.assistant.server.chat.HP_NUM_CTX
import dev.tim.assistant.server.chat.TurnResult
import dev.tim.assistant.server.chat.parseExtras
import dev.tim.assistant.ui.SLOW_REPLY_HINT
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Phase 18 acceptance tests, written from the Plan's contract and PLAN Task 18.7 (fixture: 6 tasks, 3 exams, 4 events, meals, spending). */
class Phase18AcceptanceTest {
    private val idShapes = Regex("(task|exam|cal|meal|spending|checkin|journal|dream|approval|chat):|[0-9a-f]{8}-[0-9a-f]{4}-")

    private fun root(): File {
        var d: File? = File("").absoluteFile
        while (d != null && !File(d, "settings.gradle.kts").exists()) d = d.parentFile
        return checkNotNull(d)
    }

    private fun lastAssistant(rig: HoldoutRig, chatId: String = "c1") = rig.chats.messages(chatId).last { it.role == "assistant" }

    /** One turn through the chat service (it stores the reply row, with brain and cards); returns the stored reply and the changed rows. */
    private fun viaService(rig: HoldoutRig, text: String, mid: String = "m1"): Pair<ChatMessageDto, Map<String, Any>> = kotlinx.coroutines.runBlocking {
        rig.chats.create("c1")
        val before = rig.snapshot()
        val r = rig.service.send("c1", SendMessageRequest(mid, text, rig.now)) as SendResult.Ok
        r.response.reply to rig.snapshot().filter { (k, v) -> before[k] != v }
    }

    @Test
    fun test_AC5_exam_card_display_only_on_pending_update_exam_card() {
        val line = loadHoldout("holdout-commands.jsonl").first { it.expect == "move_exam" }
        val rig = HoldoutRig()
        try {
            val (_, changed) = rig.send(line.text)
            assertEquals(1, changed.keys.count { it.startsWith("approval:") }, "an exam move gives one pending card")
            val dto = rig.chats.toDto(lastAssistant(rig))
            val card = dto.cards.single()
            assertEquals("update_exam", card.tool)
            val d = assertNotNull(card.display)
            assertEquals("Move exam", d.title)
            assertEquals("Yes, move it", d.yesLabel)
            assertTrue(d.itemName.isNotBlank())
            val blob = listOf(d.title, d.itemName, d.before, d.after, d.yesLabel).joinToString(" ")
            assertTrue(!idShapes.containsMatchIn(blob) && !blob.contains("update_exam"), "no id or tool name in the display: $blob")
            assertTrue(Regex("^[A-Z][a-z]{2}, [A-Z][a-z]{2} \\d{1,2}( \\d{2}:\\d{2})?$").matches(d.before), d.before)
            assertTrue(Regex("^[A-Z][a-z]{2}, [A-Z][a-z]{2} \\d{1,2}( \\d{2}:\\d{2})?$").matches(d.after), d.after)
        } finally {
            rig.close()
        }
    }

    @Test
    fun test_AC5_exam_card_display_exact_for_physics_exam_move() {
        val rig = HoldoutRig()
        try {
            val approval = rig.sdb.db.approvalQueries.selectAll().executeAsList()
            assertTrue(approval.isEmpty())
            val (_, _) = rig.send("move the physics exam to December 22")
            val card = rig.chats.toDto(lastAssistant(rig)).cards.single()
            assertEquals(CardDisplayDto("Move exam", "Physics", "Thu, Dec 10", "Tue, Dec 22", "Yes, move it"), card.display)
        } finally {
            rig.close()
        }
    }

    @Test
    fun test_AC5_non_exam_card_has_no_display() {
        val line = loadHoldout("holdout-commands.jsonl").first { it.expect == "mark_task_done" }
        val rig = HoldoutRig()
        try {
            val (_, changed) = rig.send(line.text)
            if (changed.keys.any { it.startsWith("approval:") }) {
                val cards = rig.chats.toDto(lastAssistant(rig)).cards
                assertTrue(cards.isNotEmpty())
                cards.forEach { assertNull(it.display, "only update_exam cards get a display") }
            }
        } finally {
            rig.close()
        }
    }

    @Test
    fun test_AC5_display_is_null_when_card_is_not_pending() {
        val rig = HoldoutRig()
        try {
            rig.send("move the physics exam to December 22")
            val a = rig.sdb.db.approvalQueries.selectAll().executeAsList().single()
            assertNotNull(CardDisplays.forExamCard(rig.sdb.db, a))
            assertNull(CardDisplays.forExamCard(rig.sdb.db, a.copy(tool = "update_task")))
            assertNull(CardDisplays.forExamCard(rig.sdb.db, a.copy(argsJson = """{"id":"exam:missing","localDate":"2026-12-22"}""")))
            assertNull(CardDisplays.forExamCard(rig.sdb.db, a.copy(argsJson = "not json")))
            rig.sdb.db.approvalQueries.upsert(a.copy(status = "DONE"))
            val card = rig.chats.toDto(lastAssistant(rig)).cards.single()
            assertEquals("DONE", card.status)
            assertNull(card.display, "a finished card is generic")
        } finally {
            rig.close()
        }
    }

    @Test
    fun test_AC5_no_id_in_any_code_made_reply() {
        val rig0 = HoldoutRig()
        rig0.close()
        var codeReplies = 0
        for (line in loadHoldout("holdout-commands.jsonl") + loadHoldout("holdout-guards.jsonl")) {
            val rig = HoldoutRig(scriptedHp = true)
            try {
                val (dto, _) = viaService(rig, line.text)
                val brain = parseExtras(rig.chats.messages("c1").last { it.role == "assistant" }.card_json).brain
                if (brain == "code" || brain == "sorter") {
                    codeReplies++
                    assertTrue(!idShapes.containsMatchIn(dto.text), "${line.id} code reply shows an id")
                    dto.cards.forEach { c ->
                        assertTrue(!idShapes.containsMatchIn(c.summary), "${line.id} card summary shows an id")
                        c.display?.let { d -> assertTrue(!idShapes.containsMatchIn(d.itemName + d.before + d.after), "${line.id} display shows an id") }
                    }
                }
            } finally {
                rig.close()
            }
        }
        assertTrue(codeReplies > 0, "some lines are answered by code")
    }

    @Test
    fun test_AC10_slow_reply_hint_text() {
        assertEquals("Thinking on the home server. This can take about a minute.", SLOW_REPLY_HINT)
    }

    @Test
    fun test_AC5_plain_answer_stores_brain_hp_and_hp_request_uses_num_ctx_8192() {
        val rig = HoldoutRig(scriptedHp = true)
        try {
            rig.hpReplies.add("""{"kind":"answer","reply":"Take it one step at a time.","args":{}}""")
            val (reply, changed) = viaService(rig, "I keep wondering whether it is worth continuing with all of this")
            assertEquals("Take it one step at a time.", reply.text)
            assertTrue(changed.isEmpty(), "a plain answer writes nothing")
            assertEquals("hp", parseExtras(lastAssistant(rig).card_json).brain)
            assertEquals(1, rig.hpBodies.size)
            val options = Json.parseToJsonElement(rig.hpBodies.single()).jsonObject.getValue("options").jsonObject
            assertEquals(8192, options.getValue("num_ctx").jsonPrimitive.int)
            assertEquals(8192, HP_NUM_CTX)
        } finally {
            rig.close()
        }
    }

    @Test
    fun test_AC5_code_command_makes_no_model_call_and_stores_brain_code() {
        val line = loadHoldout("holdout-commands.jsonl").first { it.expect == "create_task" }
        val rig = HoldoutRig(scriptedHp = true)
        try {
            val (_, changed) = viaService(rig, line.text)
            assertTrue(changed.keys.any { it.startsWith("task:") })
            assertTrue(rig.hpBodies.isEmpty(), "a clear command never reaches the model")
            assertTrue(rig.laptopBodies.isEmpty())
            assertEquals("code", parseExtras(lastAssistant(rig).card_json).brain)
        } finally {
            rig.close()
        }
    }

    @Test
    fun test_AC5_spent_35_on_lunch_yesterday_is_a_card_and_not_a_row() {
        val rig = HoldoutRig(scriptedHp = true)
        try {
            val (result, changed) = rig.send("spent 35 on lunch yesterday")
            assertTrue(changed.keys.none { it.startsWith("spending:") }, "code must not save a dated spend it cannot place")
            // Phase 20: a past day at the edge of the message becomes a card; no row exists until Yes.
            assertTrue(result is TurnResult.Reply, result.toString())
            assertTrue(parseExtras(lastAssistant(rig).card_json).cards.isNotEmpty(), "a card exists")
        } finally {
            rig.close()
        }
    }

    @Test
    fun test_AC5_hp_failure_writes_nothing() {
        val rig = HoldoutRig(scriptedHp = true)
        try {
            rig.hpReplies.add("this is not json at all")
            val (_, changed) = rig.send("I keep wondering whether it is worth continuing with all of this")
            assertTrue(changed.isEmpty(), "unparseable model output writes nothing")
        } finally {
            rig.close()
        }
    }

    @Test
    fun test_AC7_lite_removed() {
        assertTrue(!File(root(), "ops/model-eval.py").exists())
        assertTrue(!File(root(), "server/src/main/resources/wally-persona-lite.md").exists())
        val hits = File(root(), "server/src/main").walkTopDown().filter { it.isFile && it.extension == "kt" }
            .filter { Regex("litePersona|LiteContext\\(|wally-persona-lite").containsMatchIn(it.readText()) }.map { it.name }.toList()
        assertTrue(hits.isEmpty(), "lite symbols left in $hits")
    }

    @Test
    fun test_AC11_version_and_schema_pins() {
        val build = File(root(), "androidApp/build.gradle.kts").readText()
        assertTrue(build.contains("1.18.0") && Regex("versionCode\\s*=\\s*14").containsMatchIn(build))
    }
}
