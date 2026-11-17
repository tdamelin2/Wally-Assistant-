package dev.tim.assistant.server.chat

import dev.tim.assistant.ai.CHAT_MODEL
import dev.tim.assistant.server.ai.chatBody
import dev.tim.assistant.server.chat.ladder.DecisionPrompt
import dev.tim.assistant.db.Dream
import dev.tim.assistant.model.toSyncRow
import dev.tim.assistant.server.chat.ladder.PromptBrain
import dev.tim.assistant.server.chat.ladder.PromptBudget
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** B30: one voice for every brain. Each system prompt is the voice, a blank line, then the brain's own file. */
class OneVoiceTest {
    private val rig = ChatRig()

    @AfterTest
    fun close() = rig.close()

    private val voice = voiceText()
    private val firstLine = voice.lineSequence().first()

    private fun reference(): String =
        checkNotNull(OneVoiceTest::class.java.getResourceAsStream("/wally-persona-phase17.md")) { "reference file missing" }
            .bufferedReader(Charsets.UTF_8).use { it.readText() }.trim()

    @Test
    fun theVoiceIsTheOpeningAndTheWholeHowYouTalkBlock() {
        assertTrue(voice.contains("How you talk"))
        assertTrue(voice.endsWith("Talk about things by their names."), voice.takeLast(80))
        assertFalse(voice.contains("How you act"))
    }

    @Test
    fun theLaptopPersonaIsByteForByteTheOldOne() {
        assertEquals(reference(), laptopPersona())
        assertTrue(laptopPersona().startsWith(voice + "\n\n"))
    }

    @Test
    fun neitherBrainFileRepeatsTheVoice() {
        for (file in listOf("wally-persona.md", "hp-last-resort.md")) {
            val text = resourceText(file)
            assertFalse(firstLine in text, "$file repeats the opening")
            assertFalse("How you talk" in text, "$file repeats the voice heading")
        }
        assertTrue("How you act" in resourceText("wally-persona.md"))
    }

    @Test
    fun theRealLaptopRequestStartsWithTheVoice() = runBlocking<Unit> {
        val xpsTags = MockEngine {
            respond("""{"models":[{"name":"$CHAT_MODEL"}]}""", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        }
        val brain = Brain("http://xps.test:11434", "http://hp.test:11434", "hp-model", engine = xpsTags)
        val runner = ChatRunner(rig.sdb, rig.store, rig.writer, rig.chats, brain, rig.lock, rig.chatClient, { rig.now }, hpOllama = rig.hpOllama)
        rig.chats.create("c1")
        rig.chats.addUserMessage("c1", "m1", "tell me something nice", rig.now)
        rig.script(chatBody("ok"))
        runner.run("c1", "r:m1", "m1", "tell me something nice", null, null)
        val system = rig.fake.requestJson(0)["messages"]!!.jsonArray.first().jsonObject["content"]!!.jsonPrimitive.content
        assertTrue(system.startsWith(voice + "\n\n"))
        assertEquals(1, Regex("How you talk").findAll(system).count())
        assertTrue("How you act" in system)
    }

    @Test
    fun theHpLastResortPromptStartsWithTheVoiceAndHoldsItOnce() = runBlocking<Unit> {
        rig.chats.create("c1")
        val prompt = DecisionPrompt.build(PromptBudget.HP, rig.sdb.db, rig.chats, "c1", "m1", "r:m1", "hello", null, false, rig.now)
        assertTrue(prompt.system.startsWith(voice + "\n\n"))
        assertEquals(1, Regex("How you talk").findAll(prompt.system).count())
        assertEquals(assemblePrompt("hp-last-resort.md"), prompt.system)
    }

    @Test
    fun thePhonePromptStartsWithTheVoiceThenTheBlankLineThenPhoneBrainMd() = runBlocking<Unit> {
        rig.chats.create("c1")
        val prompt = DecisionPrompt.build(PromptBudget.PHONE, rig.sdb.db, rig.chats, "c1", "m1", "r:m1", "hello", null, false, rig.now, PromptBrain.PHONE)
        assertEquals(voice + "\n\n" + resourceText("phone-brain.md"), prompt.system)
        assertEquals(1, Regex("How you talk").findAll(prompt.system).count())
        assertFalse(firstLine in resourceText("phone-brain.md"))
        assertTrue(resourceText("phone-brain.md").length <= 1_500, resourceText("phone-brain.md").length.toString())
        assertEquals(prompt.system, DecisionPrompt.system(PromptBrain.PHONE))
        // the HP request is untouched by the phone profile
        val hp = DecisionPrompt.build(PromptBudget.HP, rig.sdb.db, rig.chats, "c1", "m1", "r:m1", "hello", null, false, rig.now)
        assertEquals(assemblePrompt("hp-last-resort.md"), hp.system)
        assertEquals(hp.system, DecisionPrompt.system())
    }

    @Test
    fun phoneDreamTextAppearsOnlyWithADreamWord() = runBlocking<Unit> {
        rig.seed(toSyncRow(Dream("dream:1", 1000, 0, 0, "SYNCED", "2026-11-10", "SECRETDREAMTEXT flying", 4, 0, "", null, null)))
        rig.chats.create("c1")
        fun build(text: String, dreams: Boolean) =
            DecisionPrompt.build(PromptBudget.PHONE, rig.sdb.db, rig.chats, "c1", "m1", "r:m1", text, null, dreams, rig.now, PromptBrain.PHONE)
        assertFalse("SECRETDREAMTEXT" in build("what is on today", false).user)
        assertTrue("SECRETDREAMTEXT" in build("tell me about my last dream", true).user)
    }

    @Test
    fun theFileForTimsRerunIsTheAssembledHpSystemTextByteForByte() {
        var dir: File? = File("").absoluteFile
        while (dir != null && !File(dir, "ops/hp-brain-check-system.txt").exists()) dir = dir.parentFile
        val file = File(checkNotNull(dir) { "ops/hp-brain-check-system.txt not found" }, "ops/hp-brain-check-system.txt")
        assertContentEquals(assemblePrompt("hp-last-resort.md").toByteArray(Charsets.UTF_8), file.readBytes())
    }
}
