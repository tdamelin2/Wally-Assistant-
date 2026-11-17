import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import dev.tim.assistant.ai.ChatClient
import dev.tim.assistant.ai.ChatMessage
import dev.tim.assistant.ai.Completion
import dev.tim.assistant.ai.ToolCall
import dev.tim.assistant.ai.jobs.JobFailedNow
import dev.tim.assistant.ai.jobs.JobRunner
import dev.tim.assistant.ai.jobs.ModelClient
import dev.tim.assistant.ai.tools.ApprovalAction
import dev.tim.assistant.ai.tools.ApprovalExecutor
import dev.tim.assistant.ai.tools.ReadTools
import dev.tim.assistant.ai.tools.TOOL_LIMIT_REFUSAL
import dev.tim.assistant.ai.tools.ToolBox
import dev.tim.assistant.ai.tools.ToolChatLoop
import dev.tim.assistant.ai.tools.ToolChatReply
import dev.tim.assistant.ai.tools.ToolResult
import dev.tim.assistant.ai.tools.WriteHandler
import dev.tim.assistant.ai.tools.Tools
import dev.tim.assistant.ai.tools.WAITING_FOR_APPROVAL
import dev.tim.assistant.data.CreateEventResult
import dev.tim.assistant.data.LocalStore
import dev.tim.assistant.data.Repository
import dev.tim.assistant.data.ServerConfig
import dev.tim.assistant.data.SettingKeys
import dev.tim.assistant.data.SyncClient
import dev.tim.assistant.data.SyncEngine
import dev.tim.assistant.data.approvalDisplayStatus
import dev.tim.assistant.data.prepareSchema
import dev.tim.assistant.db.AssistantDb
import dev.tim.assistant.db.Usage_session
import dev.tim.assistant.research.CODEX_LIMIT_TEXT
import dev.tim.assistant.research.CODEX_UNAVAILABLE_TEXT
import dev.tim.assistant.research.RESEARCH_APP_CATEGORY
import dev.tim.assistant.research.RESEARCH_DEFINITION
import dev.tim.assistant.research.RESEARCH_NUTRITION
import dev.tim.assistant.research.RESEARCH_REFRESH_MS
import dev.tim.assistant.research.ResearchRequest
import dev.tim.assistant.research.ResearchRow
import dev.tim.assistant.research.ResearchRunner
import dev.tim.assistant.research.ResearchService
import dev.tim.assistant.research.CodexProcess
import dev.tim.assistant.research.CodexProcessResult
import dev.tim.assistant.research.CodexResearch
import dev.tim.assistant.research.codexLimitNotice
import dev.tim.assistant.research.expiresAtFor
import dev.tim.assistant.research.isCodexLimitText
import dev.tim.assistant.research.isValidItem
import dev.tim.assistant.research.questionKey
import dev.tim.assistant.research.researchId
import dev.tim.assistant.research.templateQuestion
import dev.tim.assistant.server.Config
import dev.tim.assistant.server.ServerDb
import dev.tim.assistant.server.SyncStore
import dev.tim.assistant.server.WallyCalConfig
import dev.tim.assistant.server.module
import dev.tim.assistant.server.openDb
import dev.tim.assistant.server.wallycal.WallyCalClient
import dev.tim.assistant.server.wallycal.WallyCalPoller
import dev.tim.assistant.server.wallycal.WallyCalService
import dev.tim.assistant.server.wallycal.WallyCalStatus
import dev.tim.assistant.ui.LayoutMode
import dev.tim.assistant.ui.NavTab
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.request
import io.ktor.client.request.header
import io.ktor.client.request.setBody
import io.ktor.client.statement.readRawBytes
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.content.TextContent
import io.ktor.http.contentType
import io.ktor.http.fullPath
import io.ktor.http.headersOf
import io.ktor.server.testing.testApplication
import java.io.File
import java.nio.file.Files
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

private val P13_T0: Long = Instant.parse("2026-11-10T10:00:00Z").toEpochMilli()
private const val DAY_MS = 24 * 3_600_000L
private const val D7_MS = 7 * DAY_MS

private fun p13Root(): File {
    var d: File? = File("").absoluteFile
    while (d != null && !File(d, "settings.gradle.kts").exists()) d = d.parentFile
    return assertNotNull(d)
}

private fun p13Text(rel: String) = String(Files.readAllBytes(File(p13Root(), rel).toPath()), Charsets.UTF_8)

private fun jo(vararg p: Pair<String, Any?>): JsonObject = JsonObject(
    p.associate { (k, v) ->
        k to when (v) {
            null -> JsonNull
            is JsonElement -> v
            is Boolean -> JsonPrimitive(v)
            is Number -> JsonPrimitive(v)
            else -> JsonPrimitive(v.toString())
        }
    },
)

private class P13Env(start: Long = P13_T0) {
    var clock = start
    val db = AssistantDb(JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).also { AssistantDb.Schema.create(it) })
    val local = LocalStore(db)
    val repo = Repository(local, now = { clock }, today = { "2026-11-10" })
    val read = ReadTools(repo) { clock }
    // Phase 20: the client write tools are gone; writes are the server's. A stub keeps ToolBox constructible for read tools and the loop.
    val write = WriteHandler { _, _, _ -> ToolResult(text = "stub") }
    val box = ToolBox(read, write)

    fun outputCounts(): Map<String, Int> = mapOf(
        "task" to db.taskQueries.selectAll().executeAsList().size,
        "doc" to db.docQueries.selectAll().executeAsList().size,
        "goal" to db.goalQueries.selectAll().executeAsList().size,
        "exam" to db.examQueries.selectAll().executeAsList().size,
        "ai_job" to db.ai_jobQueries.selectAll().executeAsList().size,
        "mail_draft" to db.mail_draftQueries.selectAll().executeAsList().size,
        "mail_action" to db.mail_actionQueries.selectAll().executeAsList().size,
        "app_rule" to db.app_ruleQueries.selectAll().executeAsList().size,
        "cal_event" to db.cal_eventQueries.selectAll().executeAsList().size,
        "research" to db.researchQueries.selectAll().executeAsList().size,
    )
}

private class P13Model(val reply: suspend (List<ChatMessage>) -> String) : ModelClient {
    override suspend fun complete(messages: List<ChatMessage>, format: JsonObject?): Completion = Completion.Ok(reply(messages))
}

private class P13Runner(val answer: (String, String) -> ResearchRow = { c, q -> nutritionRow(c, q, 100.0) }) : ResearchRunner {
    val asked = ArrayList<Pair<String, String>>()
    override suspend fun ask(category: String, question: String): ResearchRow {
        asked += category to question
        return answer(category, question)
    }
}

private fun nutritionRow(category: String, question: String, kcal: Double) = ResearchRow(
    category, question, "Per 100 g: $kcal kcal",
    """{"kcal":$kcal,"proteinG":1,"carbsG":2,"fatG":3,"sources":["https://example.org/a"]}""", listOf("https://example.org/a"),
)

class Phase13AcceptanceTest {

    // ================================================================ AC-1 probe documents

    @Test
    fun test_AC1_ai_tools_md_lists_22_results_and_passes_the_gate() {
        val t = p13Text("ops/ai-tools.md")
        val rows = Regex("(?m)^\\| (\\d+) \\|").findAll(t).map { it.groupValues[1].toInt() }.toList().take(22)
        assertEquals((1..22).toList(), rows)
        val score = Regex("Score: (\\d+)/20 .*?(\\d+)/2").find(t)
        assertNotNull(score, "Score line")
        assertTrue(score.groupValues[1].toInt() >= 16, "tool gate >= 16/20")
        assertContains(t, "Ollama version")
        assertContains(t, "qwen3.5")
    }

    @Test
    fun test_AC1_codex_md_has_flags_five_timings_and_the_data_controls_step() {
        val t = p13Text("ops/codex.md")
        for (k in listOf("--skip-git-repo-check", "--ephemeral", "read-only", "web_search", "--output-schema", "--ignore-user-config", "--ignore-rules", "model_reasoning_effort", "stdin")) {
            assertContains(t, k, message = "codex.md lacks $k")
        }
        for (food in listOf("hummus", "banana", "white rice", "chicken breast", "olive oil")) {
            assertTrue(Regex("(?m)^\\| ${Regex.escape(food)} \\| [\\d.]+ s \\|").containsMatchIn(t), "timing row for $food")
        }
        assertContains(t, "Improve the model for everyone")
        assertContains(t, "Data controls")
    }

    // ================================================================ AC-3 tools

    @Test
    fun test_AC3_registry_has_13_read_21_write_2_research_tools() {
        assertEquals(14, Tools.read.size)
        assertEquals(22, Tools.write.size)
        assertEquals(2, Tools.research.size)
        for (n in listOf("get_tasks", "get_goals", "search_docs", "read_doc", "get_study", "get_exams", "get_screentime", "get_listening", "get_unread_mail", "get_hw_checks", "get_research", "get_log", "get_events")) {
            assertNotNull(Tools.find(n), n)
        }
        for (n in listOf("create_task", "create_doc", "create_goal", "add_exam", "queue_job", "draft_email", "suggest_mail_action", "set_app_rule", "create_event", "research", "research_question")) {
            assertNotNull(Tools.find(n), n)
        }
    }

    private suspend fun P13Env.seed() {
        repo.saveGoal(null, "Run a 10k", "health", "2027-03-01")
        repo.saveDoc(null, null, "Physics lab notes", "centripetal force and friction")
        repo.saveExam(null, null, "Linear algebra", "2026-12-15", "09:00")
        repo.saveMeal(null, "Lunch", "hummus and pita", clock)
        repo.saveSpending(null, 1250, "Food", null, "2026-11-10")
        repo.saveJournal(null, "A calm day")
        repo.saveCheckin(3, 3, 3, 3, null)
        repeat(300) { repo.saveTask(null, "Task number $it ${"long title ".repeat(8)}", null, false) }
        repo.saveDream(null, "2026-11-09", "ZZ_DREAM_MARK a flying dream", 3, false, "")
        repo.saveDreamPattern("2026-11-09", "ZZ_DREAM_MARK pattern", "m")
        db.usage_sessionQueries.upsert(
            Usage_session("us1", clock, 0, 0, "SYNCED", "laptop", "chrome.exe", "SECRET_WINDOW_TITLE", clock - 3_600_000, clock - 1_800_000, "2026-11-10"),
        )
    }

    @Test
    fun test_AC3_every_read_tool_answers_within_4000_chars_without_dream_text_or_window_titles() = runBlocking<Unit> {
        val e = P13Env()
        e.seed()
        val calls = listOf(
            "get_tasks" to jo(), "get_goals" to jo(), "search_docs" to jo("query" to "physics"), "get_study" to jo(),
            "get_exams" to jo(), "get_screentime" to jo("days" to 7), "get_listening" to jo("days" to 7), "get_unread_mail" to jo(),
            "get_hw_checks" to jo(), "get_research" to jo("query" to "hummus"), "get_events" to jo("days" to 7),
        ) + listOf("checkins", "spending", "meals", "journal", "health").map { "get_log" to jo("kind" to it, "days" to 7) }
        for ((name, a) in calls) {
            val r = e.box.call(ToolCall(name, a))
            assertTrue(r.text.length <= 4000, "$name returned ${r.text.length} chars")
            assertFalse(r.text.startsWith("Error"), "$name: ${r.text}")
            assertFalse(r.text.contains("ZZ_DREAM_MARK"), "$name leaks dream text")
            assertFalse(r.text.contains("SECRET_WINDOW_TITLE"), "$name leaks a window title")
            assertNull(r.approvalId, "$name is a read tool")
        }
        val tasks = e.box.call(ToolCall("get_tasks", jo())).text
        assertTrue(tasks.contains("Task number"), "get_tasks returns data")
        assertTrue(e.box.call(ToolCall("get_goals", jo())).text.contains("Run a 10k"))
        assertTrue(e.box.call(ToolCall("get_exams", jo())).text.contains("Linear algebra"))
        assertTrue(e.box.call(ToolCall("search_docs", jo("query" to "physics"))).text.contains("Physics lab"))
        assertTrue(e.box.call(ToolCall("get_log", jo("kind" to "meals", "days" to 7))).text.contains("hummus"))
        // dream data is not reachable by any query either
        assertFalse(e.box.call(ToolCall("search_docs", jo("query" to "flying"))).text.contains("ZZ_DREAM_MARK"))
        assertFalse(e.box.call(ToolCall("get_log", jo("kind" to "dream", "days" to 7))).text.contains("ZZ_DREAM_MARK"))
    }

    @Test
    fun test_AC3_days_above_30_are_not_accepted_as_more_than_30() = runBlocking<Unit> {
        val e = P13Env()
        val r = e.box.call(ToolCall("get_screentime", jo("days" to 365)))
        assertTrue(r.text.startsWith("Error") || r.text.length <= 4000)
    }

    @Test
    fun test_AC3_invalid_write_args_and_denied_job_kinds_create_no_approval() = runBlocking<Unit> {
        val e = P13Env()
        assertTrue(e.box.call(ToolCall("create_task", jo())).text.startsWith("Error"))
        for (kind in listOf("DREAM_WEEK", "DREAM_PATTERN", "RESEARCH", "NOPE")) {
            val r = e.box.call(ToolCall("queue_job", jo("kind" to kind, "input" to jo())))
            assertTrue(r.text.startsWith("Error"), "$kind: ${r.text}")
        }
        assertEquals(0, e.repo.approvals().size)
    }

    private fun toolEngine(first: String, bodies: MutableList<String>) = MockEngine { req ->
        bodies += (req.body as TextContent).text
        val text = if (bodies.size == 1) first else """{"message":{"role":"assistant","content":"done"},"done":true}"""
        respond(text, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
    }

    @Test
    fun test_AC3_sixth_tool_call_is_refused_when_six_arrive_in_one_reply() = runBlocking<Unit> {
        val e = P13Env()
        val calls = buildJsonArray {
            repeat(6) {
                add(buildJsonObject { put("function", buildJsonObject { put("name", "get_tasks"); put("arguments", JsonObject(emptyMap())) }) })
            }
        }
        val first = buildJsonObject {
            put("message", buildJsonObject { put("role", "assistant"); put("content", ""); put("tool_calls", calls) })
            put("done", true)
        }.toString()
        val bodies = ArrayList<String>()
        val loop = ToolChatLoop(ChatClient(toolEngine(first, bodies)), e.box)
        val out = loop.ask("http://xps:11434", "ctx", emptyList(), "what are my tasks?")
        assertEquals(6, out.callsRequested)
        assertIs<ToolChatReply.Answer>(out.reply)
        assertTrue(bodies.size >= 2)
        assertContains(bodies[1], TOOL_LIMIT_REFUSAL)
        assertFalse(bodies.last().contains("\"tools\""), "the final turn is sent without tools")
    }

    @Test
    fun test_AC3_manual_checklist_w12_has_the_chat_and_approval_lines() {
        val c = p13Text("ops/checklists/w12.md")
        assertContains(c, "#23")
        assertContains(c, "#24")
        assertContains(c, "Looked at")
        assertContains(c, "Approve")
        assertContains(c, "Reject")
        assertContains(c, "7 days")
    }

    // ================================================================ AC-4 research

    @Test
    fun test_AC4_item_validation_60_61_at_sign_5_digits_hebrew() {
        assertTrue(isValidItem("a".repeat(60)))
        assertFalse(isValidItem("a".repeat(61)))
        assertFalse(isValidItem(""))
        assertFalse(isValidItem("me@example.com"))
        assertFalse(isValidItem("order 12345"))
        assertTrue(isValidItem("order 1234"))
        assertTrue(isValidItem("חומוס"))
        assertTrue(isValidItem("olive-oil d'or 2.5"))
    }

    @Test
    fun test_AC4_same_question_gives_one_id_with_the_documented_shape() = runBlocking<Unit> {
        val q = templateQuestion(RESEARCH_NUTRITION, "hummus")
        assertContains(q, "hummus")
        val id = researchId(RESEARCH_NUTRITION, q)
        assertTrue(Regex("research:NUTRITION:[0-9a-f]{64}").matches(id), id)
        assertEquals(id, researchId(RESEARCH_NUTRITION, "  " + q.uppercase().replace(" ", "   ") + " "))
        assertEquals(questionKey("A   B"), questionKey(" a b "))
    }

    @Test
    fun test_AC4_expiry_rules_per_category() {
        assertNull(expiresAtFor(RESEARCH_NUTRITION, P13_T0))
        assertNull(expiresAtFor(RESEARCH_DEFINITION, P13_T0))
        assertEquals(P13_T0 + 180 * DAY_MS, expiresAtFor(RESEARCH_APP_CATEGORY, P13_T0))
        assertEquals(180 * DAY_MS, RESEARCH_REFRESH_MS)
    }

    @Test
    fun test_AC4_codex_limit_notice_window_and_detection() {
        val now = P13_T0
        assertNull(codexLimitNotice(null, now))
        assertNotNull(codexLimitNotice(now - DAY_MS, now))
        assertNull(codexLimitNotice(now - DAY_MS - 1, now))
        for (s in listOf("You hit your usage limit", "Rate Limit exceeded", "quota used", "Too many requests")) assertTrue(isCodexLimitText(s), s)
        assertFalse(isCodexLimitText("all fine"))
        assertTrue(codexLimitNotice(now - 1000, now)!!.contains("out of usage"))
    }

    private class FakeCodex(val stdoutOnRun: String = "", val exit: Int = 0, val out: String? = null) : CodexProcess {
        var exe: String? = null
        var args: List<String> = emptyList()
        var stdin: String? = null
        var workDir: File? = null
        var workDirContents: List<String>? = null
        var calls = 0
        override suspend fun run(executable: String, args: List<String>, workDir: File, stdin: String?, timeoutMs: Long): CodexProcessResult {
            calls++
            exe = executable
            this.args = args
            this.stdin = stdin
            this.workDir = workDir
            workDirContents = workDir.list()?.toList()
            val o = args[args.indexOf("-o") + 1]
            if (out != null) File(o).writeText(out)
            return CodexProcessResult(exit, stdoutOnRun, "")
        }
    }

    private val goodNutrition = """{"kcal":166,"proteinG":7.9,"carbsG":14.29,"fatG":9.6,"sources":["https://example.org/a"]}"""

    @Test
    fun test_AC4_exact_codex_command_line_stdin_prompt_and_temp_cleanup() = runBlocking<Unit> {
        val root = Files.createTempDirectory("p13codex").toFile()
        val p = FakeCodex(out = goodNutrition)
        val codex = CodexResearch(p, { "C:\\codex\\codex.exe" }, root)
        val q = templateQuestion(RESEARCH_NUTRITION, "hummus")
        val row = codex.ask(RESEARCH_NUTRITION, q)
        assertEquals("C:\\codex\\codex.exe", p.exe)
        val a = p.args
        assertEquals(
            listOf(
                "exec", "--skip-git-repo-check", "--ephemeral", "-s", "read-only",
                "-c", "web_search=\"live\"", "-c", "model_reasoning_effort=\"low\"",
                "--ignore-user-config", "--ignore-rules", "-C",
            ),
            a.take(12),
        )
        assertEquals(a[12], p.workDir!!.absolutePath)
        assertEquals(emptyList(), p.workDirContents, "Codex starts in an empty directory")
        assertEquals("--output-schema", a[13])
        assertTrue(File(a[14]).name.endsWith(".json"))
        assertEquals("-o", a[15])
        assertEquals("-", a.last(), "prompt comes from stdin")
        assertEquals(18, a.size)
        assertEquals(q, p.stdin)
        assertFalse(a.contains(q), "question is not an argument")
        assertEquals(RESEARCH_NUTRITION, row.category)
        assertEquals(listOf("https://example.org/a"), row.sources)
        assertTrue(row.answerText.contains("166"))
        assertEquals(emptyList(), root.list()!!.toList(), "temp dirs removed after the run")
    }

    @Test
    fun test_AC4_invalid_codex_output_is_a_counted_failure_not_an_instant_fail() = runBlocking<Unit> {
        val bad = listOf(
            """{"kcal":-5,"proteinG":1,"carbsG":1,"fatG":1,"sources":[]}""",
            """{"kcal":"abc","proteinG":1,"carbsG":1,"fatG":1,"sources":[]}""",
            """{"proteinG":1,"carbsG":1,"fatG":1,"sources":[]}""",
            "not json",
        )
        for (b in bad) {
            val root = Files.createTempDirectory("p13codexbad").toFile()
            val ex = assertFailsWith<Exception>(b) { CodexResearch(FakeCodex(out = b), { "codex.exe" }, root).ask(RESEARCH_NUTRITION, "q") }
            assertFalse(ex is JobFailedNow, "invalid output is retried, not failed at once: $b")
            assertEquals(emptyList(), root.list()!!.toList())
        }
    }

    @Test
    fun test_AC4_codex_unavailable_and_limit_map_to_instant_failures() = runBlocking<Unit> {
        val missing = assertFailsWith<JobFailedNow> { CodexResearch(FakeCodex(), { null }).ask(RESEARCH_NUTRITION, "q") }
        assertEquals(CODEX_UNAVAILABLE_TEXT, missing.message)
        assertEquals("Codex is not set up on the server", CODEX_UNAVAILABLE_TEXT)
        val limit = assertFailsWith<JobFailedNow> {
            CodexResearch(FakeCodex(stdoutOnRun = "ERROR: You've hit your usage limit.", exit = 1), { "codex.exe" }).ask(RESEARCH_NUTRITION, "q")
        }
        assertEquals("Codex limit reached", limit.message)
        // other non-zero exit: counted failure
        val other = assertFailsWith<Exception> { CodexResearch(FakeCodex(stdoutOnRun = "boom", exit = 2), { "codex.exe" }).ask(RESEARCH_NUTRITION, "q") }
        assertFalse(other is JobFailedNow)
    }

    @Test
    fun test_AC4_manual_checklist_has_hummus_cache_and_free_question_lines() {
        val c = p13Text("ops/checklists/w12.md")
        assertContains(c, "hummus")
        assertContains(c, "again", ignoreCase = true)
        assertTrue(c.contains("cache", ignoreCase = true) || c.contains("from the Research list", ignoreCase = true))
        assertContains(c, "exact question")
        assertContains(c, "#27")
        assertContains(c, "#28")
    }

    // ================================================================ AC-5 meal look up
    @Test
    fun test_AC5_manual_checklist_has_the_look_up_lines() {
        val c = p13Text("ops/checklists/w12.md")
        assertContains(c, "#29")
        assertContains(c, "Look up")
        assertContains(c, "(research)")
    }

    // ================================================================ AC-6 create_event

    @Test
    fun test_AC6_manual_checklist_w13_has_the_create_event_lines() {
        val c = p13Text("ops/checklists/w13-calendar.md")
        assertContains(c, "#32")
        assertContains(c, "phone", ignoreCase = true)
        assertContains(c, "laptop", ignoreCase = true)
        assertContains(c, "ONE", message = "same card on both devices still one event")
        assertContains(c, "all-day")
        assertContains(c, "calendar-unreachable")
        assertContains(c, "Server not reachable")
    }

    // ================================================================ AC-7 release / schema / data

    @Test
    fun test_AC7_release_1_11_0_versionCode_7_and_same_identity() {
        val g = p13Text("desktopApp/build.gradle.kts")
        assertContains(g, "packageVersion = \"1.18.0\"")
        val a = p13Text("androidApp/build.gradle.kts")
        assertContains(a, "versionName = \"1.18.0\"")
        assertTrue(Regex("versionCode\\s*=\\s*14\\b").containsMatchIn(a))
        assertContains(a, "dev.tim.assistant")
    }

    @Test
    fun test_AC7_checklists_name_1_11_0_and_w8_to_w11_stay_historical() {
        for (f in listOf("phase1-e2e", "phase4", "phase6", "nutrition")) {
            assertContains(p13Text("ops/checklists/$f.md"), "1.18.0", message = "$f.md")
        }
        // w12 and w13-calendar are history lines and stay at 1.12.0.
        for (f in listOf("w12", "w13-calendar")) assertContains(p13Text("ops/checklists/$f.md"), "1.12.0", message = "$f.md")
        assertContains(p13Text("ops/checklists/phase1-e2e.md"), "Wally-1.18.0.msi")
        for (f in listOf("w8", "w9", "w10", "w11")) {
            val p = File(p13Root(), "ops/checklists/$f.md")
            if (p.exists()) assertFalse(p.readText().contains("1.11.0"), "$f.md is history")
        }
    }

    @Test
    fun test_AC7_restore_helper_lists_30_tables_with_approval_and_research() {
        val t = p13Text("ops/assistant-restore-test.sh")
        val tables = Regex("^TABLES=\"([^\"]*)\"", RegexOption.MULTILINE).find(t)!!.groupValues[1].trim().split(Regex("\\s+"))
        assertEquals(34, tables.size, tables.toString())
        assertEquals(34, tables.toSet().size)
        assertTrue("approval" in tables && "research" in tables)
        assertFalse(t.contains('\r'))
    }

    @Test
    fun test_AC7_schema_is_version_7_with_30_tables_and_the_new_columns() {
        val d = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        AssistantDb.Schema.create(d)
        assertEquals(8L, AssistantDb.Schema.version)
        fun cols(t: String): List<String> {
            val out = ArrayList<String>()
            d.executeQuery(null, "SELECT name FROM pragma_table_info('$t') ORDER BY cid", { c ->
                while (c.next().value) out += c.getString(0)!!
                app.cash.sqldelight.db.QueryResult.Unit
            }, 0)
            return out
        }
        val names = ArrayList<String>()
        d.executeQuery(null, "SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%'", { c ->
            while (c.next().value) names += c.getString(0)!!
            app.cash.sqldelight.db.QueryResult.Unit
        }, 0)
        assertEquals(34, names.size, names.toString())
        assertEquals(
            listOf("id", "updatedAt", "deleted", "serverSeq", "syncState", "tool", "argsJson", "summary", "status", "createdAt", "resultText"),
            cols("approval"),
        )
        assertEquals(
            listOf("id", "updatedAt", "deleted", "serverSeq", "syncState", "category", "questionKey", "question", "answerText", "answerJson", "sources", "askedAt", "expiresAt"),
            cols("research"),
        )
    }

    @Test
    fun test_AC7_a_version_6_file_with_old_rows_upgrades_to_7_and_keeps_its_rows() {
        val f = Files.createTempFile("p13v6", ".db").toFile().also { it.deleteOnExit() }
        val d = JdbcSqliteDriver("jdbc:sqlite:${f.absolutePath}")
        // frozen: one table the 6 -> 7 step never touches, plus the version marker
        d.execute(null, "CREATE TABLE kv (k TEXT NOT NULL PRIMARY KEY, v TEXT)", 0)
        d.execute(null, "CREATE TABLE task (id TEXT NOT NULL PRIMARY KEY, updatedAt INTEGER NOT NULL, deleted INTEGER NOT NULL, serverSeq INTEGER NOT NULL DEFAULT 0, syncState TEXT NOT NULL, title TEXT NOT NULL, dueDate TEXT, done INTEGER NOT NULL, goalId TEXT)", 0)
        d.execute(null, "INSERT INTO task VALUES ('t1', 5, 0, 0, 'SYNCED', 'old task', NULL, 0, NULL)", 0)
        d.execute(null, "PRAGMA user_version = 6", 0)
        prepareSchema(d)
        val names = ArrayList<String>()
        d.executeQuery(null, "SELECT name FROM sqlite_master WHERE type='table'", { c ->
            while (c.next().value) names += c.getString(0)!!
            app.cash.sqldelight.db.QueryResult.Unit
        }, 0)
        assertTrue("approval" in names && "research" in names, names.toString())
        var title: String? = null
        d.executeQuery(null, "SELECT title FROM task WHERE id='t1'", { c -> if (c.next().value) title = c.getString(0); app.cash.sqldelight.db.QueryResult.Unit }, 0)
        assertEquals("old task", title)
        var v = -1L
        d.executeQuery(null, "PRAGMA user_version", { c -> if (c.next().value) v = c.getLong(0)!!; app.cash.sqldelight.db.QueryResult.Unit }, 0)
        assertEquals(8L, v) // Phase 17: prepareSchema runs every step, so a v6 file ends at the current version 8
        d.close()
    }

    @Test
    fun test_AC7_approvals_tab_is_gone_17_tabs() {
        // Phase 14 (P14-A-02): the 12 tabs became 17 NavTabs inside 5 hubs; Approvals still has no tab of its own.
        assertEquals(17, NavTab.entries.size)
        assertTrue(NavTab.entries.none { it.label == "Approvals" })
        assertEquals(NavTab.Settings, NavTab.entries.last())
    }

    companion object {
        /** Forwards a SyncClient's requests to the in-process test server. */
        fun bridge(http: io.ktor.client.HttpClient) = MockEngine { req ->
            val bytes = when (val b = req.body) {
                is TextContent -> b.text.toByteArray()
                is OutgoingContent.ByteArrayContent -> b.bytes()
                else -> ByteArray(0)
            }
            val r = http.request(req.url.fullPath) {
                method = req.method
                header(HttpHeaders.Authorization, req.headers[HttpHeaders.Authorization] ?: "")
                req.body.contentType?.let { contentType(it) }
                if (bytes.isNotEmpty()) setBody(bytes)
            }
            respond(r.readRawBytes(), r.status, headersOf(HttpHeaders.ContentType, r.headers[HttpHeaders.ContentType] ?: "application/json"))
        }
    }
}
