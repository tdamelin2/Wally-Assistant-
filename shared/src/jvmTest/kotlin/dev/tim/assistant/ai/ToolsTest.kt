package dev.tim.assistant.ai

import dev.tim.assistant.ai.tools.MAX_TOOL_CALLS
import dev.tim.assistant.ai.tools.RESEARCH_REASK_TEXT
import dev.tim.assistant.ai.tools.needsResearchTool
import dev.tim.assistant.ai.tools.TOOL_BUDGET_REFUSAL
import dev.tim.assistant.ai.tools.TOOL_LIMIT_REFUSAL
import dev.tim.assistant.ai.tools.ToolChatLoop
import dev.tim.assistant.ai.tools.ToolChatReply
import dev.tim.assistant.ai.tools.ToolCheck
import dev.tim.assistant.ai.tools.Tools
import dev.tim.assistant.ai.tools.WAITING_FOR_APPROVAL
import dev.tim.assistant.ai.tools.eventTimes
import dev.tim.assistant.data.APPROVAL_PENDING
import dev.tim.assistant.db.Cal_event
import dev.tim.assistant.db.Listen
import dev.tim.assistant.db.Mail_item
import dev.tim.assistant.usage.UsageRow
import io.ktor.http.HttpStatusCode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/** The tool registry's executors and the tool chat loop (Phase 13). */
class ToolsTest {
    private fun ToolRig.seedLots() = runBlocking {
        val long = "x".repeat(100)
        for (i in 1..400) repo.saveTask(null, "task $i $long", "2026-11-${(i % 28 + 1).toString().padStart(2, '0')}", false)
        repo.saveTask(null, "finished", null, true)
        for (i in 1..80) repo.saveGoal(null, "goal $i $long", "why ".repeat(60), "2026-12-01")
        for (i in 1..60) repo.saveDoc(null, null, "doc $i needle", "needle ".repeat(5_000))
        repo.saveDoc("bigdoc", null, "Big", "y".repeat(30_000))
        for (i in 1..40) repo.saveExam(null, null, "exam $i $long", "2026-12-${(i % 28 + 1).toString().padStart(2, '0')}", "09:00")
        for (i in 1..120) {
            db.mail_itemQueries.upsert(Mail_item("mail:$i", 1, 0, 0, "SYNCED", "g$i", "sender$i@example.com", "subject $i $long", "snippet ".repeat(40), NOW - i))
        }
        for (i in 1..200) {
            db.listenQueries.upsert(Listen("listen:$i", 1, 0, 0, "SYNCED", "track $i", "artist ${i % 30}", 1000, NOW - i * 1000L, TODAY))
        }
        for (i in 1..60) repo.saveHwCheck(NOW - i, "{}", "check $i " + "ok ".repeat(200))
        for (i in 1..40) db.seedResearch("NUTRITION", "calories in thing $i", "answer needle ".repeat(100), "{}", "[]", NOW, null)
        for (i in 1..80) {
            db.cal_eventQueries.upsert(Cal_event("cal:c:$i", 1, 0, 0, "SYNCED", "c", "e$i", "event $i $long", NOW + i * 60_000L, NOW + i * 60_000L + 1000, 0, "room"))
        }
        for (i in 1..60) repo.saveSpending("sp$i", 1000L + i, "Food", "note $i $long", TODAY)
        repo.saveCheckin(3, 3, 3, 3, "ok")
        repo.saveJournal(null, "journal ".repeat(100))
        for (i in 1..60) repo.saveMeal(null, "Lunch", "meal $i $long", NOW - i)
        repo.saveUsageRows(
            (1..150).map { UsageRow("use:laptop:$it", "LAPTOP", "app$it.exe", "SECRET-WINDOW-TITLE-$it", NOW - it * 120_000L, NOW - it * 120_000L + 60_000, TODAY) },
        )
        repo.saveRule("EXE", "app1.exe", dev.tim.assistant.usage.Label.PRODUCTIVE)
    }

    private fun readArgs(name: String) = when (name) {
        "search_docs", "get_research" -> args("query" to "needle")
        "read_doc" -> args("id" to "bigdoc")
        "get_log" -> args("kind" to "meals", "days" to 30)
        "get_screentime", "get_listening", "get_events" -> args("days" to 30)
        "find" -> args("kind" to "task", "query" to "task")
        else -> args()
    }

    @Test
    fun each_read_tool_answers_within_the_cap() = runBlocking<Unit> {
        val t = ToolRig()
        t.seedLots()
        assertEquals(14, Tools.read.size)
        for (spec in Tools.read) {
            val result = t.box.call(ToolCall(spec.name, readArgs(spec.name)))
            assertTrue(result.text.isNotBlank(), spec.name)
            assertTrue(result.text.length <= Tools.TOOL_OUTPUT_MAX_CHARS, "${spec.name} gave ${result.text.length} chars")
            assertEquals(spec.label, result.looked, spec.name)
            assertFalse(result.text.startsWith("Error"), "${spec.name}: ${result.text.take(100)}")
        }
        assertTrue("more not shown" in t.box.call(ToolCall("get_tasks", args())).text)
    }

    @Test
    fun every_log_kind_works_and_stays_within_the_cap() = runBlocking<Unit> {
        val t = ToolRig()
        t.seedLots()
        for (kind in Tools.LOG_KINDS) {
            val r = t.box.call(ToolCall("get_log", args("kind" to kind, "days" to 30)))
            assertTrue(r.text.length <= Tools.TOOL_OUTPUT_MAX_CHARS, kind)
            assertFalse(r.text.startsWith("Error"), kind)
        }
    }

    @Test
    fun screen_time_gives_per_app_and_label_totals_but_never_window_titles() = runBlocking<Unit> {
        val t = ToolRig()
        t.seedLots()
        val text = t.box.call(ToolCall("get_screentime", args("days" to 7))).text
        assertTrue("app1.exe" in text)
        assertTrue("productive" in text)
        assertFalse("SECRET-WINDOW-TITLE" in text)
    }

    @Test
    fun read_tools_say_so_when_there_is_nothing() = runBlocking<Unit> {
        val t = ToolRig()
        for (spec in Tools.read) {
            val r = t.box.call(ToolCall(spec.name, readArgs(spec.name).let { if (spec.name == "read_doc") args("id" to "nope") else it }))
            assertTrue(r.text.isNotBlank() && r.text.length < 300, spec.name)
        }
    }

    @Test
    fun bad_read_arguments_give_an_error_text() = runBlocking<Unit> {
        val t = ToolRig()
        assertTrue(t.box.call(ToolCall("get_screentime", args("days" to 99))).text.startsWith("Error"))
        assertTrue(t.box.call(ToolCall("get_log", args("kind" to "dreams"))).text.startsWith("Error"))
        assertTrue(t.box.call(ToolCall("search_docs", args())).text.startsWith("Error"))
        assertTrue(t.box.call(ToolCall("get_dreams", args())).text.startsWith("Error"))
    }

    // ---- write tools ----------------------------------------------------------------------------------

    private val validWrites = listOf(
        "create_task" to args("title" to "Buy milk", "dueDate" to "2026-11-12"),
        "create_doc" to args("title" to "Note", "body" to "Hello"),
        "create_goal" to args("title" to "Run", "deadline" to "2026-12-31"),
        "add_exam" to args("title" to "Final", "localDate" to "2026-12-20", "time" to "09:00"),
        "queue_job" to args("kind" to "APP_LABELS", "input" to args()),
        "draft_email" to args("to" to "dana@example.com", "subject" to "Hi", "body" to "Hello"),
        "suggest_mail_action" to args("gmailId" to "g1", "action" to "ARCHIVE", "reason" to "newsletter"),
        "set_app_rule" to args("matchType" to "PACKAGE", "pattern" to "com.Example.App", "label" to "PRODUCTIVE"),
        "create_event" to args("title" to "Gym", "start" to "2026-11-12T07:00", "end" to "2026-11-12T08:00", "repeatRule" to "WEEKLY"),
        "add_meal" to args("type" to "MEAL", "text" to "pasta"),
        "add_spending" to args("amount" to "12.50", "category" to "Food"),
        "add_checkin" to args("mood" to 4, "energy" to 3, "sleepQuality" to 5, "stress" to 2),
        "add_journal" to args("text" to "A good day"),
    )

    private fun updateRig(): ToolRig = ToolRig().also { t ->
        runBlocking {
            t.repo.saveTask("t1", "Old task", null, false); t.repo.saveGoal("g1", "Old goal", null, null)
            t.repo.saveDoc("d1", null, "Old doc", "old body"); t.repo.saveMeal("m1", "MEAL", "rice", 1_000_000L)
            t.repo.saveSpending("s1", 1000, "Food", null, "2026-11-01"); t.repo.saveJournal("j1", "old journal")
            t.repo.saveExam("e1", null, "Old exam", "2026-12-01", null); t.repo.saveCheckin(3, 3, 3, 3, null)
        }
    }

    private val validUpdates = listOf(
        "update_task" to args("id" to "t1", "title" to "New task"),
        "update_goal" to args("id" to "g1", "status" to "DONE"),
        "update_doc" to args("id" to "d1", "body" to "more", "bodyMode" to "APPEND"),
        "update_meal" to args("id" to "m1", "text" to "pizza"),
        "update_spending" to args("id" to "s1", "amount" to "20"),
        "update_journal" to args("id" to "j1", "text" to "new journal"),
        "update_exam" to args("id" to "e1", "time" to "10:30"),
    )

    @Test
    fun update_tools_refuse_a_missing_id_and_an_empty_change() = runBlocking<Unit> {
        val t = updateRig()
        for ((name, a) in listOf(
            "update_goal" to args("id" to "g1"),
            "update_meal" to args("title" to "x"),
            "update_doc" to args("id" to "d1", "body" to "x".repeat(15_001)),
            "update_checkin" to args("id" to "c", "mood" to 6),
        )) {
            assertTrue(Tools.validate(name, a) is ToolCheck.Bad, "$name $a")
        }
        assertEquals(0, t.repo.approvals().size)
        assertEquals("Error: Missing what to change - ask Tim", t.box.call(ToolCall("update_goal", args("id" to "g1"))).text)
        assertEquals("Error: Missing id - ask Tim", t.box.call(ToolCall("update_meal", args("text" to "x"))).text)
    }

    @Test
    fun missing_required_arguments_ask_tim_instead_of_defaulting() = runBlocking<Unit> {
        val t = ToolRig()
        for ((name, a, field) in listOf(
            Triple("add_meal", args("text" to "x"), "type"),
            Triple("add_spending", args("category" to "Food"), "amount"),
            Triple("add_spending", args("amount" to "5"), "category"),
            Triple("add_checkin", args("mood" to 3, "energy" to 3, "stress" to 3), "sleepQuality"),
            Triple("add_journal", args(), "text"),
            Triple("create_task", args(), "title"),
            Triple("add_exam", args("title" to "x"), "localDate"),
            Triple("create_event", args("title" to "x"), "start"),
        )) {
            assertEquals("Error: Missing $field - ask Tim", t.box.call(ToolCall(name, a)).text, name)
        }
        assertEquals(0, t.repo.approvals().size)
    }

    @Test
    fun a_timed_event_needs_an_end_or_a_duration_and_an_all_day_event_only_dates() {
        val noLength = Tools.validate("create_event", args("title" to "x", "start" to "2026-11-12T09:00")) as ToolCheck.Bad
        assertTrue("Missing end or durationMinutes - ask Tim" in noLength.reason)
        val dur = Tools.validate("create_event", args("title" to "x", "start" to "2026-11-12T23:30", "durationMinutes" to 90)) as ToolCheck.Ok
        assertEquals("2026-11-13T01:00", dur.args["end"]!!.toString().trim('"'))
        val day = Tools.validate("create_event", args("title" to "x", "start" to "2026-12-03", "allDay" to true)) as ToolCheck.Ok
        assertEquals("2026-12-03", day.args["end"]!!.toString().trim('"'))
        assertTrue(Tools.validate("create_event", args("title" to "x", "start" to "2026-11-12T09:00", "durationMinutes" to 0)) is ToolCheck.Bad)
    }

    @Test
    fun the_new_add_tools_follow_the_form_rules() = runBlocking<Unit> {
        val t = ToolRig()
        for ((name, a) in listOf(
            "add_meal" to args("type" to "LUNCH", "text" to "x"),
            "add_meal" to args("type" to "MEAL", "text" to "x", "date" to "2026-11-12"),
            "add_meal" to args("type" to "MEAL", "text" to "x".repeat(1_001)),
            "add_spending" to args("amount" to "0", "category" to "Food"),
            "add_spending" to args("amount" to "abc", "category" to "Food"),
            "add_spending" to args("amount" to "5", "category" to "Gold"),
            "add_checkin" to args("mood" to 0, "energy" to 3, "sleepQuality" to 3, "stress" to 3),
            "add_journal" to args("text" to "x".repeat(10_001)),
        )) assertTrue(t.box.call(ToolCall(name, a)).text.startsWith("Error"), "$name $a")
        assertEquals(0, t.repo.approvals().size)
    }


    @Test
    fun every_write_tool_accepts_its_valid_arguments() {
        // add_dream is an AUTO tool the HP server runs; the rest are registered write tools
        val all = validWrites + validUpdates + ("update_checkin" to args("id" to "c1", "mood" to 5))
        assertEquals(Tools.write.map { it.name }.toSet() - "add_dream", all.map { it.first }.toSet())
        for ((name, a) in all) assertTrue(Tools.validate(name, a) is ToolCheck.Ok, name)
    }

    @Test
    fun invalid_write_arguments_give_an_error_text_and_no_row() = runBlocking<Unit> {
        val t = ToolRig()
        val bad = listOf(
            "create_task" to args(),
            "create_task" to args("title" to "x", "dueDate" to "tomorrow"),
            "add_exam" to args("title" to "x", "localDate" to "2026-13-45"),
            "draft_email" to args("to" to "not-an-address", "subject" to "s", "body" to "b"),
            "suggest_mail_action" to args("gmailId" to "g", "action" to "LABEL", "reason" to "r"),
            "suggest_mail_action" to args("gmailId" to "g", "action" to "DELETE", "reason" to "r"),
            "set_app_rule" to args("matchType" to "REGEX", "pattern" to "x", "label" to "NEUTRAL"),
            "set_app_rule" to args("matchType" to "EXE", "pattern" to "x", "label" to "UNSORTED"),
            "create_event" to args("title" to "x", "start" to "2026-11-12T09:00", "end" to "2026-11-12T08:00"),
            "create_event" to args("title" to "x", "start" to "2026-11-12", "end" to "2026-11-12"),
            "create_event" to args("title" to "x", "start" to "2026-11-12T09:00", "end" to "2026-11-12T10:00", "repeatUntil" to "2026-11-20"),
            "create_event" to args("title" to "x", "start" to "2026-11-12T09:00", "end" to "2026-11-12T10:00", "repeatRule" to "DAILY", "repeatUntil" to "2026-11-01"),
            "nonsense" to args(),
        )
        val before = t.counts()
        for ((name, a) in bad) {
            val r = t.box.call(ToolCall(name, a))
            assertTrue(r.text.startsWith("Error"), "$name $a -> ${r.text}")
            assertEquals(null, r.approvalId)
        }
        assertEquals(0, t.repo.approvals().size)
        assertEquals(before, t.counts())
    }

    @Test
    fun queue_job_allow_list_denies_dream_and_research_kinds() = runBlocking<Unit> {
        val t = ToolRig()
        for (kind in listOf("DREAM_INTERPRET", "DREAM_PATTERNS", "RESEARCH", "dream_patterns", "NOPE")) {
            val r = t.box.call(ToolCall("queue_job", args("kind" to kind, "input" to args())))
            assertTrue(r.text.startsWith("Error"), kind)
        }
        assertEquals(0, t.repo.approvals().size)
        for (kind in Tools.JOB_KINDS) {
            val input = when (kind) {
                "GOAL_STEPS" -> args("goalId" to "g")
                "STUDY_SET" -> args("fileId" to "f")
                "STUDY_GUIDE" -> args("courseId" to "c")
                "QUIZ" -> args("courseId" to "c")
                "HW_EXPLAIN" -> args("checkId" to "c", "problem" to "p")
                "MEAL_LOOKUP" -> args("mealId" to "m")
                "MAIL_DRAFT" -> args("instruction" to "say hi")
                else -> args()
            }
            assertTrue(Tools.validate("queue_job", args("kind" to kind, "input" to input)) is ToolCheck.Ok, kind)
        }
    }

    // ---- create_event argument mapping ---------------------------------------------------------------

    @Test
    fun event_times_are_jerusalem_local_converted_to_epoch_ms() {
        // November is UTC+2
        val (s, e) = eventTimes("2026-11-12T09:00", "2026-11-12T10:30", false)
        assertEquals(kotlin.time.Instant.parse("2026-11-12T07:00:00Z").toEpochMilliseconds(), s)
        assertEquals(kotlin.time.Instant.parse("2026-11-12T08:30:00Z").toEpochMilliseconds(), e)
        // summer is UTC+3
        assertEquals(kotlin.time.Instant.parse("2026-07-01T06:00:00Z").toEpochMilliseconds(), eventTimes("2026-07-01T09:00", "2026-07-01T09:00", false).first)
        // all day: midnight of the start date to midnight after the end date
        val (ds, de) = eventTimes("2026-11-12", "2026-11-13", true)
        assertEquals(kotlin.time.Instant.parse("2026-11-11T22:00:00Z").toEpochMilliseconds(), ds)
        assertEquals(kotlin.time.Instant.parse("2026-11-13T22:00:00Z").toEpochMilliseconds(), de)
    }

    @Test
    fun a_valid_event_has_defaults_filled_in() {
        val ok = Tools.validate("create_event", args("title" to " Gym ", "start" to "2026-11-12", "end" to "2026-11-12", "allDay" to true)) as dev.tim.assistant.ai.tools.ToolCheck.Ok
        assertEquals("NONE", ok.args["repeatRule"]!!.toString().trim('"'))
        assertEquals("Gym", ok.args["title"]!!.toString().trim('"'))
    }

    // ---- the chat loop -------------------------------------------------------------------------------

    private fun loop(t: ToolRig, ollama: ScriptedOllama) = ToolChatLoop(ollama.client, t.box)

    private suspend fun ToolChatLoop.ask(question: String = "q", context: String = "ctx") =
        ask("http://xps:11434", context, emptyList(), question)

    @Test
    fun event_length_is_recognised_in_english_and_hebrew() {
        for (s in listOf(
            "meeting tomorrow at 15:00 for 45 minutes", "gym for 2 hours", "call for half an hour", "lunch for an hour", "for 1.5h",
            "until 16:00", "from 3pm to 4pm", "15:00-16:00", "ends 17:30", "פגישה מחר ב-15:00 לשעה", "לשעתיים", "חצי שעה", "20 דקות", "מ-15:00 עד 16:00",
        )) assertTrue(dev.tim.assistant.ai.tools.statesEventLength(s), s)
        for (s in listOf("meeting at 15:00 tomorrow", "add dentist on Thursday at 9", "פגישה מחר ב-15:00", "add an event tomorrow")) {
            assertFalse(dev.tim.assistant.ai.tools.statesEventLength(s), s)
        }
    }

    private val timedEvent = args("title" to "Meet", "start" to "2026-11-12T15:00", "end" to "2026-11-12T16:00")

    @Test
    fun a_timed_event_without_a_stated_length_is_refused_and_creates_no_approval() = runBlocking<Unit> {
        val o = ScriptedOllama(listOf(callsBody("create_event" to timedEvent), answerBody("How long is it?")))
        val t = ToolRig()
        val r = loop(t, o).ask("add a meeting at 15:00 tomorrow")
        assertEquals(emptyList(), r.approvalIds)
        assertEquals(emptyList(), t.repo.approvals())
        assertTrue(dev.tim.assistant.ai.tools.EVENT_LENGTH_MISSING_TEXT in o.bodies[1])
    }

    @Test
    fun a_stated_length_or_an_earlier_follow_up_answer_or_an_all_day_event_is_accepted() = runBlocking<Unit> {
        val t1 = ToolRig()
        val r1 = loop(t1, ScriptedOllama(listOf(callsBody("create_event" to timedEvent), answerBody("ok")))).ask("meeting at 15:00 tomorrow for an hour")
        assertEquals(1, r1.approvalIds.size)

        val t2 = ToolRig()
        val h = listOf(
            dev.tim.assistant.ai.ChatMessage("user", "add a meeting at 15:00 tomorrow"),
            dev.tim.assistant.ai.ChatMessage("assistant", "How long is it?"),
        )
        val r2 = loop(t2, ScriptedOllama(listOf(callsBody("create_event" to timedEvent), answerBody("ok"))))
            .ask("http://xps:11434", "ctx", h, "an hour")
        assertEquals(1, r2.approvalIds.size)

        val t3 = ToolRig()
        val day = args("title" to "Trip", "start" to "2026-12-03", "allDay" to true)
        val r3 = loop(t3, ScriptedOllama(listOf(callsBody("create_event" to day), answerBody("ok")))).ask("trip on Dec 3")
        assertEquals(1, r3.approvalIds.size)
    }

    @Test
    fun a_plain_answer_needs_one_turn_and_sends_the_tools() = runBlocking<Unit> {
        val o = ScriptedOllama(listOf(answerBody("Hello")))
        val r = loop(ToolRig(), o).ask()
        assertEquals(ToolChatReply.Answer("Hello"), r.reply)
        assertEquals(1, o.bodies.size)
        assertTrue(o.hadTools(0))
        assertEquals(emptyList(), r.looked)
    }

    @Test
    fun a_given_temperature_reaches_every_turn_and_is_absent_by_default() = runBlocking<Unit> {
        val script = { listOf(callsBody("get_tasks" to args()), answerBody("ok")) }
        val warm = ScriptedOllama(script())
        loop(ToolRig(), warm).ask("http://xps:11434", "ctx", emptyList(), "q", temperature = 0.0)
        assertEquals(2, warm.bodies.size)
        assertTrue(warm.bodies.all { "\"temperature\":0.0" in it })
        val plain = ScriptedOllama(script())
        loop(ToolRig(), plain).ask()
        assertTrue(plain.bodies.none { "temperature" in it.substringAfter("\"options\"").substringBefore("\"messages\"") })
    }

    @Test
    fun results_are_fed_back_and_looked_at_and_approval_cards_are_reported()= runBlocking<Unit> {
        val o = ScriptedOllama(
            listOf(
                callsBody("get_tasks" to args(), "create_task" to args("title" to "Buy milk")),
                answerBody("Done proposing"),
            ),
        )
        val t = ToolRig()
        val r = loop(t, o).ask()
        assertEquals(ToolChatReply.Answer("Done proposing"), r.reply)
        assertEquals(listOf("tasks"), r.looked)
        assertEquals(1, r.approvalIds.size)
        assertTrue("\"tool_name\":\"get_tasks\"" in o.bodies[1])
        assertTrue(WAITING_FOR_APPROVAL in o.bodies[1])
        assertTrue("\"tool_calls\"" in o.bodies[1])
    }

    @Test
    fun a_general_nutrition_question_answered_without_research_is_asked_again_once() = runBlocking<Unit> {
        val o = ScriptedOllama(
            listOf(
                answerBody("About 250 kcal"),
                callsBody("research" to args("category" to "NUTRITION", "item" to "hamburger")),
                answerBody("Looking it up"),
            ),
        )
        val r = loop(ToolRig(), o).ask("how many calories are in a hamburger")
        assertEquals(ToolChatReply.Answer("Looking it up"), r.reply)
        assertEquals(3, o.bodies.size)
        assertTrue(RESEARCH_REASK_TEXT in o.bodies[1])
        assertTrue(o.hadTools(1))
    }

    @Test
    fun the_reask_happens_only_once_and_not_when_research_was_called_or_for_other_questions() = runBlocking<Unit> {
        val stubborn = ScriptedOllama(listOf(answerBody("250"), answerBody("still 250")))
        assertEquals(ToolChatReply.Answer("still 250"), loop(ToolRig(), stubborn).ask("calories in an apple").reply)
        assertEquals(2, stubborn.bodies.size)

        val direct = ScriptedOllama(listOf(answerBody("Rayleigh scattering")))
        assertEquals(ToolChatReply.Answer("Rayleigh scattering"), loop(ToolRig(), direct).ask("why is the sky black at night").reply)
        assertEquals(1, direct.bodies.size)

        val own = ScriptedOllama(listOf(answerBody("1800 kcal")))
        loop(ToolRig(), own).ask("how many calories did I eat today")
        assertEquals(1, own.bodies.size)

        val called = ScriptedOllama(listOf(callsBody("research" to args("category" to "DEFINITION", "item" to "ephemeral")), answerBody("queued")))
        assertEquals(ToolChatReply.Answer("queued"), loop(ToolRig(), called).ask("what does ephemeral mean").reply)
        assertEquals(2, called.bodies.size)
    }

    @Test
    fun the_routing_rule_matches_general_food_and_word_questions_only() {
        for (q in listOf("how many calories are in a hamburger", "protein in 100g chicken", "define ephemeral", "what does serendipity mean", "כמה קלוריות יש בחומוס")) {
            assertTrue(needsResearchTool(q), q)
        }
        for (q in listOf("why is the sky black at night", "how many calories did I eat today", "what is on my calendar", "my protein this week")) {
            assertFalse(needsResearchTool(q), q)
        }
    }

    @Test
    fun the_sixth_call_is_refused_and_the_final_turn_has_no_tools() = runBlocking<Unit> {
        val one = callsBody("get_tasks" to args())
        val o = ScriptedOllama(listOf(one, one, one, one, one, one, answerBody("final")))
        val r = loop(ToolRig(), o).ask()
        assertEquals(ToolChatReply.Answer("final"), r.reply)
        assertEquals(6, r.callsRequested)
        assertEquals(7, o.bodies.size)
        for (i in 0..5) assertTrue(o.hadTools(i), "turn $i")
        assertFalse(o.hadTools(6))
        assertTrue(TOOL_LIMIT_REFUSAL in o.bodies[6])
        assertEquals(1, o.bodies[6].split(TOOL_LIMIT_REFUSAL).size - 1)
    }

    @Test
    fun six_calls_in_one_reply_run_five_and_refuse_the_sixth() = runBlocking<Unit> {
        val six = callsBody(*Array(6) { "create_task" to args("title" to "t$it") })
        val o = ScriptedOllama(listOf(six, answerBody("final")))
        val t = ToolRig()
        val r = loop(t, o).ask()
        assertEquals(ToolChatReply.Answer("final"), r.reply)
        assertEquals(MAX_TOOL_CALLS, r.approvalIds.size)
        assertEquals(6, r.callsRequested)
        assertTrue(TOOL_LIMIT_REFUSAL in o.bodies[1])
        assertFalse(o.hadTools(1))
    }

    @Test
    fun invalid_and_unknown_calls_count_toward_the_limit() = runBlocking<Unit> {
        val six = callsBody("nope" to args(), "get_log" to args(), "get_tasks" to args("days" to "x"), "get_dreams" to args(), "create_task" to args(), "get_tasks" to args())
        val o = ScriptedOllama(listOf(six, answerBody("final")))
        val r = loop(ToolRig(), o).ask()
        assertEquals(6, r.callsRequested)
        assertTrue(TOOL_LIMIT_REFUSAL in o.bodies[1])
    }

    @Test
    fun the_twelve_thousand_char_budget_refuses_a_result_that_does_not_fit() = runBlocking<Unit> {
        val t = ToolRig()
        runBlocking { t.repo.saveDoc("d", null, "T", "z".repeat(3_800)) }
        val read = callsBody(*Array(4) { "read_doc" to args("id" to "d") })
        val o = ScriptedOllama(listOf(read, answerBody("final")))
        val r = loop(t, o).ask(context = "ctx")
        assertEquals(ToolChatReply.Answer("final"), r.reply)
        val second = o.bodies[1]
        assertTrue(TOOL_BUDGET_REFUSAL in second)
        assertFalse(o.hadTools(1))
        // context + kept tool results never pass the budget
        val kept = Regex("z{3800}").findAll(second).count()
        assertEquals(3, kept)
    }

    @Test
    fun an_http_500_turn_is_retried_once() = runBlocking<Unit> {
        val err = HttpStatusCode.InternalServerError to """{"error":"expected element type <function> but have <parameter>"}"""
        val o = ScriptedOllama(listOf(err, answerBody("fine")))
        val r = loop(ToolRig(), o).ask()
        assertEquals(ToolChatReply.Answer("fine"), r.reply)
        assertEquals(2, o.bodies.size)
        assertTrue(o.hadTools(0) && o.hadTools(1))
    }

    @Test
    fun a_second_http_500_ends_the_question_with_an_error() = runBlocking<Unit> {
        val err = HttpStatusCode.InternalServerError to """{"error":"boom"}"""
        val o = ScriptedOllama(listOf(err, err, answerBody("never")))
        val r = loop(ToolRig(), o).ask()
        assertTrue(r.reply is ToolChatReply.Failed)
        assertEquals(2, o.bodies.size)
    }

    @Test
    fun other_errors_are_not_retried_and_offline_is_reported() = runBlocking<Unit> {
        val o = ScriptedOllama(listOf(HttpStatusCode.BadGateway to "{}"))
        assertTrue(loop(ToolRig(), o).ask().reply is ToolChatReply.Failed)
        assertEquals(1, o.bodies.size)
        val offline = ToolChatLoop(
            ChatClient(io.ktor.client.engine.mock.MockEngine { throw java.net.ConnectException("refused") }),
            ToolRig().box,
        )
        assertEquals(ToolChatReply.Offline, offline.ask("http://xps:11434", "ctx", emptyList(), "q").reply)
    }

    @Test
    fun research_tools_are_reported_unavailable_without_a_handler() = runBlocking<Unit> {
        val t = ToolRig()
        val r = t.box.call(ToolCall("research", args("category" to "NUTRITION", "item" to "hummus")))
        assertTrue(r.text.startsWith("Error"))
        assertEquals(0, t.repo.approvals().size)
    }
}
