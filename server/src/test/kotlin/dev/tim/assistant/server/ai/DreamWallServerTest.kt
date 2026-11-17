package dev.tim.assistant.server.ai

import dev.tim.assistant.db.Dream
import dev.tim.assistant.db.Dream_pattern
import dev.tim.assistant.db.Usage_session
import dev.tim.assistant.model.toSyncRow
import dev.tim.assistant.research.RESEARCH_NUTRITION
import dev.tim.assistant.research.researchId
import dev.tim.assistant.research.templateQuestion
import dev.tim.assistant.server.chat.CHAT_T0
import dev.tim.assistant.server.chat.ChatRig
import dev.tim.assistant.server.chat.NOTHING_TO_REMEMBER
import dev.tim.assistant.server.chat.PINNED_CHAT_ID
import dev.tim.assistant.server.chat.TurnResult
import dev.tim.assistant.server.chat.decisionJson
import dev.tim.assistant.server.codex.CodexRig
import dev.tim.assistant.server.notify.NotifyScheduler
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Dream wall, server side: nothing from a dream row reaches the morning briefing, the server chat prompt (HP last resort and XPS),
 * the chat summaries, the weekly report, the model ideas or a Codex input, unless Tim's own current chat message is about dreams.
 */
class DreamWallServerTest {
    private val marker = "ZZDREAMMARKER"

    @Test
    fun the_briefing_data_and_the_prompt_sent_to_the_model_contain_no_dream_text() = runBlocking<Unit> {
        val rig = AiRig()
        val db = rig.sdb.db
        // a dream on every day the briefing looks at, plus an analysis and a pattern carrying the marker
        for (date in listOf(TODAY, YESTERDAY)) {
            db.dreamQueries.upsert(
                Dream(
                    "dream-$date", 10, 0, 1, "SYNCED", date, "$marker dream text $date", 3, 1, "tag$marker",
                    "$marker analysis", "model$marker",
                ),
            )
        }
        db.dream_patternQueries.upsert(Dream_pattern("dreampattern:$TODAY", 10, 0, 2, "SYNCED", TODAY, "$marker pattern", "m"))

        assertFalse(marker in rig.briefing.gather(TODAY), "gather() leaked a dream")

        // the full generation path: what is actually sent to Ollama
        rig.briefing.regenerateNow()
        assertTrue(rig.fake.bodies.isNotEmpty())
        for (body in rig.fake.bodies) assertFalse(marker in body, "the Ollama request leaked a dream")
        assertFalse(marker in (rig.briefingRow()?.text ?: ""))
    }

    // ---- server chat ---------------------------------------------------------------------------------------

    private suspend fun ChatRig.seedDream() = seed(
        toSyncRow(Dream("dream:1", 1000, 0, 0, "SYNCED", "2026-11-11", "$marker flying over the sea", 4, 0, "", null, null)),
    )

    private suspend fun ChatRig.turn(chatId: String, id: String, text: String): TurnResult {
        chats.addUserMessage(chatId, id, text, now)
        val result = runner.run(chatId, "r:$id", id, text, "Today", null)
        if (result is TurnResult.Reply) chats.finishReply(chatId, "r:$id", result.text) { it }
        return result
    }

    private fun ChatRig.request(index: Int): String = fake.requestJson(index).toString()

    private fun ChatRig.hpRequest(index: Int): String = hpFake.requestJson(index).toString()

    @Test
    fun the_hp_last_resort_prompt_holds_dream_text_only_when_the_message_mentions_dreams() = runBlocking<Unit> {
        val rig = ChatRig()
        rig.seedDream()
        rig.chats.create("c1")
        rig.scriptHp(decisionJson("answer", "ok"), decisionJson("answer", "ok"))
        rig.turn("c1", "m1", "how was my week")
        rig.turn("c1", "m2", "tell me about my last dream")
        assertFalse(marker in rig.hpRequest(0), "HP prompt without a dream word")
        assertFalse("add_dream" in rig.hpFake.requestJson(0)["format"].toString())
        assertTrue(marker in rig.hpRequest(1), "HP prompt with a dream word")
        assertTrue("add_dream" in rig.hpFake.requestJson(1)["format"].toString())
        assertTrue(rig.fake.bodies.isEmpty())
        rig.close()
    }

    @Test
    fun the_xps_chat_prompt_holds_dream_text_only_when_the_message_mentions_dreams() = runBlocking<Unit> {
        val rig = ChatRig(xps = true)
        rig.seedDream()
        rig.chats.create("c1")
        rig.script(chatBody("ok"), chatBody("ok"))
        rig.turn("c1", "m1", "how was my week")
        rig.turn("c1", "m2", "I had a nightmare, what could it mean")
        assertFalse(marker in rig.request(0), "full prompt without a dream word")
        assertTrue(marker in rig.request(1), "full prompt with a dream word")
        rig.close()
    }

    @Test
    fun earlier_dream_turns_stay_out_of_later_prompts_on_both_models_until_dreams_come_up_again() = runBlocking<Unit> {
        for (xps in listOf(false, true)) {
            val rig = ChatRig(xps = xps)
            rig.chats.create("c1")
            val answers = listOf("It sounded like $marker flying.", "Maybe pasta.", "A third answer.", "The dream may mean freedom.")
            if (xps) rig.script(*answers.map { chatBody(it) }.toTypedArray())
            else rig.scriptHp(*answers.map { decisionJson("answer", it) }.toTypedArray())
            fun request(index: Int) = if (xps) rig.request(index) else rig.hpRequest(index)
            rig.turn("c1", "m1", "I had a dream about $marker")
            rig.turn("c1", "m2", "give me a cooking idea")
            assertFalse(marker in request(1), "turn 2 (xps=$xps)")
            // on the full model the taint holds for the next non-dream turn too. The HP request keeps only the last 4 messages,
            // so a third turn would already have pushed the dream out of its window.
            var next = 2
            if (xps) {
                rig.turn("c1", "m3", "and a dessert idea")
                assertFalse(marker in request(2), "turn 3 (xps=$xps)")
                next = 3
            }
            rig.turn("c1", "m4", "back to my dream, what did it mean")
            assertTrue(marker in request(next), "a dream message brings the earlier dream turns back (xps=$xps)")
            rig.close()
        }
    }
    @Test
    fun a_chat_summary_never_sends_dream_turns_to_the_model_and_a_dream_only_chat_gets_a_plain_note() = runBlocking<Unit> {
        val rig = ChatRig()
        rig.chats.create("c1")
        rig.chats.addUserMessage("c1", "m1", "I had a dream about $marker", rig.now)
        rig.sdb.db.chatQueries.insertMessage("a1", "c1", "assistant", "Sounds vivid: $marker.", null, null, rig.now + 1)
        rig.chats.addUserMessage("c1", "m2", "what is on my calendar tomorrow", rig.now + 2)
        rig.sdb.db.chatQueries.insertMessage("a2", "c1", "assistant", "Nothing tomorrow.", null, null, rig.now + 3)
        rig.chats.delete("c1")
        rig.summaryFake.reply = { 200 to chatBody("Tim asked about the calendar.") }
        rig.summarizer.tick()
        val sent = rig.summaryFake.bodies.single()
        assertTrue("calendar" in sent)
        assertFalse(marker in sent)
        assertFalse("dream" in sent.lowercase())

        rig.chats.create("c2")
        rig.chats.addUserMessage("c2", "m3", "I dreamt about $marker", rig.now + 10)
        rig.sdb.db.chatQueries.insertMessage("a3", "c2", "assistant", "Tell me more.", null, null, rig.now + 11)
        rig.chats.delete("c2")
        val before = rig.summaryFake.bodies.size
        rig.summarizer.tick()
        assertEquals(before, rig.summaryFake.bodies.size, "no model call for a dream-only chat")
        assertEquals(NOTHING_TO_REMEMBER, rig.chats.memory().notes.first { it.sourceChatTitle.contains("dreamt") }.summary)
        rig.close()
    }

    // ---- reminders: weekly report and ideas ---------------------------------------------------------------

    @Test
    fun the_weekly_report_and_the_ideas_are_written_from_dream_free_data() = runBlocking<Unit> {
        val rig = ChatRig()
        val sunday = jerusalemMs("2026-11-15", 17, 30)
        rig.now = sunday
        rig.seed(toSyncRow(Dream("dream:d1", 1000, 0, 0, "SYNCED", "2026-11-15", "$marker harbour dream", 3, 0, "", null, null)))
        rig.seed(toSyncRow(Dream("dream:d2", 1000, 0, 0, "SYNCED", "2026-11-12", "$marker earlier dream", 3, 1, "tag$marker", "$marker analysis", "m")))
        rig.seed(toSyncRow(Usage_session("us1", 1000, 0, 0, "SYNCED", "laptop", "tiktok.exe", null, jerusalemMs("2026-11-15", 9), jerusalemMs("2026-11-15", 10), "2026-11-15")))
        val ollama = OllamaFake()
        ollama.reply = { 200 to chatBody("A line of text.\nSecond line, with more words.\nSuggestion: rest early.") }
        val scheduler = NotifyScheduler(
            rig.sdb, rig.writer, rig.brain, rig.lock, { OllamaClient(it, ollama.engine()) },
        ) { rig.now }
        assertTrue(scheduler.tick() >= 1)
        rig.now = jerusalemMs("2026-11-15", 11, 30)
        scheduler.tick()
        assertTrue(ollama.bodies.isNotEmpty(), "the model was asked for the report or an idea")
        for (body in ollama.bodies) {
            assertFalse(marker in body, "a reminder prompt leaked a dream")
            assertFalse("harbour" in body)
        }
        val report = rig.sdb.db.chatQueries.selectMessagesByChat(PINNED_CHAT_ID).executeAsList()
        for (m in report) assertFalse(marker in m.text)
        for (n in rig.sdb.db.chatQueries.selectNotificationsSince(0, 100).executeAsList()) {
            assertFalse(marker in n.title + n.body)
        }
        rig.close()
    }

    // ---- Codex inputs -------------------------------------------------------------------------------------

    @Test
    fun codex_research_and_meal_lookup_inputs_hold_no_dream_text() = runBlocking<Unit> {
        val rig = CodexRig()
        rig.seed(toSyncRow(Dream("dream:d1", 1000, 0, 0, "SYNCED", "2026-11-11", "$marker flying dream", 3, 0, "", null, null)))
        val id = researchId(RESEARCH_NUTRITION, templateQuestion(RESEARCH_NUTRITION, "rice"))
        rig.seedJob("job-r", "RESEARCH", """{"category":"NUTRITION","item":"rice","researchId":"$id"}""")
        rig.codex.tick()
        rig.seedMeal("meal:m1", "rice")
        rig.seedJob("job-m", "MEAL_LOOKUP", """{"mealId":"meal:m1"}""", createdAt = rig.now + 1)
        rig.splitInto("rice" to 200)
        rig.meals.tick()
        assertTrue(rig.runner.asks.isNotEmpty(), "Codex was asked")
        for ((category, question) in rig.runner.asks) assertFalse(marker in category + question)
        for (body in rig.ollama.bodies) assertFalse(marker in body, "the meal split prompt leaked a dream")
        rig.close()
    }
}
