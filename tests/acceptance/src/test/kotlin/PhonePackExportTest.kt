import dev.tim.assistant.ai.decisionSchema
import dev.tim.assistant.db.Cal_event
import dev.tim.assistant.db.Exam
import dev.tim.assistant.db.Meal
import dev.tim.assistant.db.Spending
import dev.tim.assistant.db.Task
import dev.tim.assistant.server.chat.ChatStore
import dev.tim.assistant.server.chat.ServerWriter
import dev.tim.assistant.server.chat.ladder.DecisionPrompt
import dev.tim.assistant.server.chat.ladder.PromptBrain
import dev.tim.assistant.server.chat.ladder.PromptBudget
import dev.tim.assistant.server.openDb
import dev.tim.assistant.server.SyncStore
import java.io.File
import java.nio.file.Files
import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * PLAN 19.7 steps 1-2: the 20 blind phone eval messages are turned into prompt packs with the server's real PHONE builder over a
 * made-up fixture, and written to tests/acceptance/build/phone-eval.json for the on-phone eval (AC-9).
 */
class PhonePackExportTest {
    private fun root(): File {
        var d: File? = File("").absoluteFile
        while (d != null && !File(d, "settings.gradle.kts").exists()) d = d.parentFile
        return checkNotNull(d)
    }

    private fun at(hhmm: String, day: String): Long =
        LocalDateTime.parse("${day}T$hhmm").atZone(ZoneId.of("Asia/Jerusalem")).toInstant().toEpochMilli()

    private val now = HOLDOUT_NOW // Thursday 2026-11-12, 10:00 in Jerusalem

    private fun fixture(q: dev.tim.assistant.db.AssistantDb) {
        fun task(id: String, title: String, due: String? = null) = q.taskQueries.upsert(Task(id, 1000, 0, 0, "SYNCED", title, due, 0, null, null))
        task("task:lab", "finish physics lab report", "2026-11-14")
        task("task:dentist", "call the dentist", "2026-11-13")
        task("task:noa", "buy a birthday present for Noa", "2026-11-15")
        task("task:form", "submit scholarship form", "2026-11-18")
        task("task:clean", "clean the apartment")
        task("task:slides", "print the lecture slides")
        task("task:internet", "pay the internet bill", "2026-11-16")
        fun exam(id: String, title: String, date: String, time: String?) = q.examQueries.upsert(Exam(id, 1000, 0, 0, "SYNCED", null, title, date, time))
        exam("exam:stats", "Statistics", "2026-11-17", "09:00")
        exam("exam:chem", "Organic Chemistry", "2026-11-18", "13:00")
        exam("exam:discrete", "Discrete Math", "2026-11-19", null)
        fun ev(id: String, title: String, day: String, from: String, to: String) =
            q.cal_eventQueries.upsert(Cal_event("cal:primary:$id", 1000, 0, 0, "SYNCED", "primary", id, title, at(from, day), at(to, day), 0, null))
        ev("e1", "Chem lab", "2026-11-12", "11:00", "13:00")
        ev("e2", "Dentist appointment", "2026-11-13", "16:30", "17:15")
        ev("e3", "Dinner at Savta's", "2026-11-15", "19:00", "21:00")
        ev("e4", "Algorithms lecture", "2026-11-16", "10:00", "12:00")
        ev("e5", "Study group", "2026-11-17", "14:00", "16:00")
        fun meal(id: String, type: String, text: String, hhmm: String) =
            q.mealQueries.upsert(Meal("meal:$id", at(hhmm, "2026-11-12"), 0, 0, "SYNCED", type, text, at(hhmm, "2026-11-12"), "2026-11-12", null, null, null, null, null))
        meal("m1", "MEAL", "shakshuka", "08:00")
        meal("m2", "SNACK", "banana", "09:30")
        fun spend(id: String, agorot: Long, cat: String, note: String, date: String) =
            q.spendingQueries.upsert(Spending("spending:$id", 1000, 0, 0, "SYNCED", agorot, cat, note, date))
        spend("s1", 4500, "Food", "falafel", "2026-11-08")
        spend("s2", 12000, "Transport", "train pass", "2026-11-09")
        spend("s3", 3300, "Food", "burger", "2026-11-10")
        spend("s4", 2800, "Coffee", "cafe", "2026-11-11")
        spend("s5", 1800, "Coffee", "cafe", "2026-11-12")
        spend("s6", 6200, "Groceries", "supermarket", "2026-11-12")
    }

    @Test
    fun test_AC9_export_twenty_phone_packs_with_the_real_phone_builder(): Unit = kotlinx.coroutines.runBlocking {
        val raw = checkNotNull(PhonePackExportTest::class.java.getResourceAsStream("/phone-eval-messages.jsonl")).bufferedReader(Charsets.UTF_8).readText()
        val lines = raw.lines().filter { it.isNotBlank() }.map { Json.parseToJsonElement(it).jsonObject }
        assertEquals(20, lines.size)

        val sdb = openDb(Files.createTempDirectory("phonepack").resolve("t.db").toString())
        try {
            fixture(sdb.db)
            val store = SyncStore(sdb)
            val writer = ServerWriter(sdb, store) { now }
            val chats = ChatStore(sdb, writer) { now }
            val voice = checkNotNull(PhonePackExportTest::class.java.getResourceAsStream("/wally-voice.md")).bufferedReader(Charsets.UTF_8).readText().trim()
            val schema: JsonObject = decisionSchema(false)
            val packs = lines.map { o ->
                val id = o.getValue("id").jsonPrimitive.content
                val text = o.getValue("text").jsonPrimitive.content
                val chatId = "chat-$id"
                chats.create(chatId)
                chats.addUserMessage(chatId, "um-$id", text, now)
                val p = DecisionPrompt.build(PromptBudget.PHONE, sdb.db, chats, chatId, "um-$id", "r:um-$id", text, null, false, now, PromptBrain.PHONE)
                assertTrue(p.system.startsWith(voice), "$id: the system text starts with the Wally voice")
                assertEquals(DecisionPrompt.system(PromptBrain.PHONE), p.system, "$id: PHONE system text")
                assertTrue(p.user.endsWith("Tim's message: $text"), "$id: user part ends with the message")
                assertTrue(p.user.length <= 3_000 + 1_600 + 3 * 200 + 600, "$id: user part ${p.user.length} chars fits the PHONE budget")
                buildJsonObject {
                    put("id", id)
                    put("system", p.system)
                    put("user", p.user)
                    put("schema", schema)
                    put("expect", o.getValue("expect"))
                }
            }
            val out = File(root(), "tests/acceptance/build/phone-eval.json")
            out.parentFile.mkdirs()
            out.writeText(Json { prettyPrint = true }.encodeToString(kotlinx.serialization.json.JsonArray.serializer(), buildJsonArray { packs.forEach { add(it) } }), Charsets.UTF_8)
            val back = Json.parseToJsonElement(out.readText(Charsets.UTF_8)) as kotlinx.serialization.json.JsonArray
            assertEquals(20, back.size)
        } finally {
            sdb.close()
        }
    }

    @Test
    fun test_AC9_phone_eval_message_mix_is_6_adds_5_edits_6_data_3_conversation() {
        val raw = checkNotNull(PhonePackExportTest::class.java.getResourceAsStream("/phone-eval-messages.jsonl")).bufferedReader(Charsets.UTF_8).readText()
        val e = raw.lines().filter { it.isNotBlank() }.map { Json.parseToJsonElement(it).jsonObject.getValue("expect").jsonObject }
        fun act(o: JsonObject) = (o["action"] as? JsonPrimitive)?.content.orEmpty()
        assertEquals(6, e.count { act(it).startsWith("add_") || act(it) == "create_task" })
        assertEquals(5, e.count { act(it).startsWith("update_") })
        assertEquals(6, e.count { it["dataValue"] != null })
        assertEquals(3, e.count { act(it).isEmpty() && it["dataValue"] == null })
    }
}
