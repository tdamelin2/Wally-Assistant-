package dev.tim.assistant.ai

import dev.tim.assistant.ai.dream.DreamAnalysis
import dev.tim.assistant.ai.jobs.DREAM_JOB_ERROR
import dev.tim.assistant.ai.jobs.GoalJobs
import dev.tim.assistant.ai.jobs.HardwareJobs
import dev.tim.assistant.ai.jobs.MailJobs
import dev.tim.assistant.ai.jobs.JobRunner
import dev.tim.assistant.ai.jobs.TickResult
import dev.tim.assistant.ai.jobs.UsageJobs
import dev.tim.assistant.ai.tools.ReadTools
import dev.tim.assistant.ai.tools.Tools
import dev.tim.assistant.data.DreamBlock
import dev.tim.assistant.data.DreamWeek
import dev.tim.assistant.data.DreamWeekResult
import dev.tim.assistant.data.ServerConfig
import dev.tim.assistant.data.SyncClient
import dev.tim.assistant.db.Mail_item
import dev.tim.assistant.db.Usage_session
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/** The dream wall: dream text, analyses and patterns never reach chat, briefing-style prompts or non-dream jobs. */
class DreamWallTest {
    private val marker = "ZZDREAMMARKER"
    private val rig = JobRig()
    private val repo = rig.repo
    private val model = FakeModel()
    private val study = StudyRig(rig, model)
    private val mailClient = SyncClient(
        MockEngine { request ->
            val json = headersOf(HttpHeaders.ContentType, "application/json")
            if (request.url.encodedPath.endsWith("/body")) {
                respond("""{"from":"Dana <dana@example.com>","to":"t@x.com","subject":"Lunch","date":1,"text":"Free on Sunday?"}""", HttpStatusCode.OK, json)
            } else {
                respond("""[{"id":"Label_1","name":"Bills"}]""", HttpStatusCode.OK, json)
            }
        },
    )
    private val mail = MailJobs(repo, model, mailClient) { ServerConfig("http://server:8080", "tok") }
    private val hardware = HardwareJobs(repo, model)

    private fun runner() = JobRunner(
        repo, sync = {},
        handlers = DreamAnalysis(repo, model).handlers() + GoalJobs(repo, model, { rig.clock }) { DreamWeekResult.Sent(DreamWeek(0, listOf(DreamBlock("b", "Gym", 1, 540, 600)))) }.handlers() +
            UsageJobs(repo, model) { rig.clock }.handlers() + study.jobs().handlers() + study.guideJob().handlers() + mail.handlers() + hardware.handlers(),
        clock = { rig.clock }, skipKinds = emptyList(),
    )

    private suspend fun seed(): String {
        val id = repo.saveDream(null, TODAY, "$marker dream text", 3, true, "tag$marker")
        repo.saveDreamPattern(TODAY, "$marker pattern", "m$marker")
        return id
    }

    @Test
    fun chat_context_has_no_dream_text_unless_the_message_has_a_dream_word() = runBlocking<Unit> {
        seed()
        repo.saveCheckin(3, 3, 3, 3, "plain note")
        for (message in listOf("what's on today?", "how was my sleep", "daydreaming about lunch", "שלום")) {
            val context = ChatContext.build(rig.db, TODAY, includeDreams = mentionsDreams(message))
            assertFalse(marker in context, message)
            assertFalse(marker in ChatContext.systemPrompt(context), message)
        }
        assertFalse(marker in ChatContext.build(rig.db, TODAY))
    }

    @Test
    fun chat_context_includes_the_dream_text_when_the_message_has_a_dream_word() = runBlocking<Unit> {
        seed()
        repo.saveCheckin(3, 3, 3, 3, "plain note")
        for (message in listOf("Tell me about my dream", "my dreams this week", "I had a nightmare", "ספר לי על חלום", "חלומות")) {
            assertTrue(mentionsDreams(message), message)
            val context = ChatContext.build(rig.db, TODAY, includeDreams = mentionsDreams(message))
            assertTrue("$marker dream text" in context, message)
            // the pattern analysis stays out even then
            assertFalse("$marker pattern" in context, message)
        }
    }

    @Test
    fun every_non_dream_handler_prompt_has_no_dream_text() = runBlocking<Unit> {
        seed()
        val goal = repo.saveGoal(null, "Run 10k", "health", "2026-12-31")
        repo.saveTask(null, "Buy shoes", null, false)
        model.script = { _, _ ->
            Completion.Ok("""{"steps":[{"title":"a","dueDate":null},{"title":"b","dueDate":null},{"title":"c","dueDate":null}]}""")
        }
        rig.clock += 1000
        repo.queueJob("GOAL_STEPS", """{"goalId":"$goal"}""")
        assertTrue(runner().tick() is TickResult.Done)
        model.script = { _, _ -> Completion.Ok("- do a thing") }
        rig.clock += 1000
        repo.queueJob("IDEAL_WEEK", "{}")
        assertTrue(runner().tick() is TickResult.Done)
        rig.db.usage_sessionQueries.upsert(Usage_session("use:t", 1, 0, 0, "SYNCED", "LAPTOP", "new.exe", null, 1000, 61_000, TODAY))
        model.script = { _, _ -> Completion.Ok("""{"labels":[{"app":"new.exe","label":"NEUTRAL"}]}""") }
        rig.clock += 1000
        repo.queueJob("APP_LABELS", "{}")
        assertTrue(runner().tick() is TickResult.Done)
        assertEquals(3, model.prompts.size)
        assertFalse(marker in model.allText())
    }

    @Test
    fun mail_and_hardware_jobs_run_with_populated_data_and_their_prompts_have_no_dream_text() = runBlocking<Unit> {
        seed()
        rig.db.mail_itemQueries.upsert(Mail_item("mail:g1", 1, 0, 0, "SYNCED", "g1", "Dana <dana@example.com>", "Lunch", "snippet", 1000))
        val checkId = repo.saveHwCheck(1000, """{"takenAt":1000,"battery":{"ok":true,"batteries":[]}}""", "battery n/a")
        model.script = { _, _ -> Completion.Ok("Sunday works.") }
        rig.clock += 1000
        val draft = repo.queueJob("MAIL_DRAFT", """{"gmailId":"g1","instruction":"say yes"}""")
        assertEquals(TickResult.Done(draft), runner().tick())
        model.script = { _, _ -> Completion.Ok("""{"actions":[{"gmailId":"g1","action":"LABEL","labelId":"Label_1","reason":"bill"}]}""") }
        rig.clock += 1000
        val triage = repo.queueJob("MAIL_TRIAGE", "{}")
        assertEquals(TickResult.Done(triage), runner().tick())
        model.script = { _, _ -> Completion.Ok("The battery value is missing.") }
        rig.clock += 1000
        val explain = repo.queueJob("HW_EXPLAIN", """{"checkId":"$checkId","problem":"short battery life"}""")
        assertEquals(TickResult.Done(explain), runner().tick())
        assertEquals(3, model.prompts.size)
        assertFalse(marker in model.allText())
        assertEquals("DRAFT", repo.mailDraftById("maildraft:$draft")!!.status)
        assertEquals(1, repo.mailActions().size)
        assertFalse(marker in (rig.job(draft).resultJson ?: "") + (rig.job(triage).resultJson ?: "") + (rig.job(explain).resultJson ?: ""))
        assertTrue(model.prompts.all { it.last().content.length <= 12_000 })
    }

    @Test
    fun study_jobs_chunk_reduce_cards_and_quiz_prompts_have_no_dream_text() = runBlocking<Unit> {
        seed()
        study.seed()
        study.text = paragraphs(30_000)
        model.script = { _, format ->
            if (format != null && format.toString().contains("cards")) Completion.Ok(cardsAnswer(12)) else Completion.Ok("summary text")
        }
        rig.clock += 1000
        assertTrue(runner().tick().let { it is TickResult.Idle })
        val set = repo.queueJob("STUDY_SET", """{"fileId":"sfile:a"}""")
        assertEquals(TickResult.Done(set), runner().tick())
        // several chunk passes, one reduction pass and the cards pass
        assertTrue(model.prompts.size >= 5)
        model.script = { _, _ -> Completion.Ok(quizAnswer()) }
        rig.clock += 1000
        val quiz = repo.queueJob("QUIZ", """{"courseId":"course:1"}""")
        assertEquals(TickResult.Done(quiz), runner().tick())
        assertFalse(marker in model.allText())
        assertTrue(model.prompts.all { it.last().content.length <= 12_000 })
        assertFalse(marker in (rig.job(set).resultJson ?: "") + (rig.job(quiz).resultJson ?: ""))
    }

    @Test
    fun the_study_guide_job_prompts_have_no_dream_text_and_save_the_guide_doc() = runBlocking<Unit> {
        seed()
        study.seed()
        study.text = paragraphs(5_000)
        model.script = { _, _ -> Completion.Ok("guide text") }
        rig.clock += 1000
        val guide = repo.queueJob("STUDY_GUIDE", """{"courseId":"course:1"}""")
        assertEquals(TickResult.Done(guide), runner().tick())
        assertTrue(model.prompts.isNotEmpty())
        assertFalse(marker in model.allText())
        assertFalse(marker in (rig.job(guide).resultJson ?: ""))
        val doc = repo.docById("doc:studyguide:course:1")
        assertTrue(doc != null && marker !in doc.body)
    }

    @Test
    fun dream_jobs_succeed_and_leave_no_marker_in_the_job_row() = runBlocking<Unit> {
        val id = seed()
        model.script = { _, _ -> Completion.Ok("A gentle reading.") }
        rig.clock += 1000
        val a = repo.queueJob("DREAM_INTERPRET", """{"dreamId":"$id"}""")
        assertEquals(TickResult.Done(a), runner().tick())
        val row = rig.job(a)
        assertEquals("DONE", row.status)
        assertFalse(marker in (row.error ?: "") + (row.resultJson ?: ""))
    }

    @Test
    fun an_old_queued_dream_patterns_job_fails_as_an_unknown_kind_without_any_model_call() = runBlocking<Unit> {
        seed()
        model.script = { _, _ -> Completion.Ok("should never be asked") }
        rig.clock += 1000
        val old = repo.queueJob("DREAM_PATTERNS", """{"localDate":"$TODAY"}""")
        assertEquals(TickResult.Failed(old), runner().tick())
        val row = rig.job(old)
        assertEquals("FAILED", row.status)
        assertEquals("unknown job kind", row.error)
        assertTrue(model.prompts.isEmpty())
        assertFalse(marker in (row.error ?: "") + (row.resultJson ?: ""))
    }

    @Test
    fun dream_jobs_offline_keep_no_marker_and_do_not_fail() = runBlocking<Unit> {
        val id = seed()
        model.script = { _, _ -> Completion.Offline }
        rig.clock += 1000
        val a = repo.queueJob("DREAM_INTERPRET", """{"dreamId":"$id"}""")
        assertEquals(TickResult.Offline(a), runner().tick())
        assertEquals("QUEUED", rig.job(a).status)
        assertEquals(0, rig.job(a).attempts)
        assertFalse(marker in (rig.job(a).error ?: ""))
    }

    @Test
    fun a_marker_bearing_exception_never_reaches_the_job_error() = runBlocking<Unit> {
        val id = seed()
        model.script = { _, _ -> throw IllegalStateException("boom $marker", RuntimeException("cause $marker")) }
        rig.clock += 1000
        val a = repo.queueJob("DREAM_INTERPRET", """{"dreamId":"$id"}""")
        assertEquals(TickResult.Retry(a), runner().tick())
        // the job stays QUEUED after a retry, so it runs again; run until it has failed
        var guard = 0
        while (guard++ < 10 && runner().tick() !is TickResult.Idle) rig.clock += 1000
        val row = rig.job(a)
        assertEquals(DREAM_JOB_ERROR, row.error, a)
        assertFalse(marker in (row.error ?: "") + (row.resultJson ?: ""))
    }

    @Test
    fun every_read_tool_with_dream_data_present_returns_no_dream_text() = runBlocking<Unit> {
        seed()
        repo.saveTask(null, "Buy shoes", "2026-11-12", false)
        repo.saveGoal(null, "Run 10k", "health", "2026-12-31")
        repo.saveDoc("needle-doc", null, "needle", "needle body")
        repo.saveExam(null, null, "Physics", "2026-12-01", "09:00")
        repo.saveCheckin(3, 3, 3, 3, "plain note")
        repo.saveJournal(null, "plain journal")
        repo.saveMeal(null, "Lunch", "rice", rig.clock)
        repo.saveSpending("sp1", 1000L, "Food", "lunch", TODAY)
        rig.db.seedResearch("NUTRITION", "calories in rice", "130 kcal needle", "{}", "[]", rig.clock, null)
        rig.db.mail_itemQueries.upsert(Mail_item("mail:g1", 1, 0, 0, "SYNCED", "g1", "Dana <dana@example.com>", "Lunch", "snippet", 1000))
        rig.db.usage_sessionQueries.upsert(Usage_session("use:t", 1, 0, 0, "SYNCED", "LAPTOP", "new.exe", "a title", 1000, 61_000, TODAY))
        val read = ReadTools(repo) { rig.clock }
        val seen = StringBuilder()
        for (spec in Tools.read) {
            val a = when (spec.name) {
                "search_docs", "get_research" -> args("query" to "needle")
                "read_doc" -> args("id" to "needle-doc")
                "get_log" -> args("kind" to "journal", "days" to 30)
                "get_screentime", "get_listening", "get_events" -> args("days" to 30)
                else -> args()
            }
            val text = read.run(spec.name, a)
            assertFalse(text.startsWith("Error"), "${spec.name}: ${text.take(80)}")
            seen.append(text)
        }
        for (kind in listOf("checkins", "spending", "meals", "journal", "health")) seen.append(read.run("get_log", args("kind" to kind, "days" to 30)))
        assertTrue(seen.length > 200)
        assertFalse(marker in seen.toString())
        assertFalse(marker in ChatContext.toolSystemPrompt(ChatContext.build(rig.db, TODAY, maxChars = 3_000)))
    }

    @Test
    fun only_the_allowed_production_files_touch_dream_tables() {
        var root: File? = File("").absoluteFile
        while (root != null && !File(root, "settings.gradle.kts").exists()) root = root.parentFile
        assertTrue(root != null, "project root not found")
        val roots = listOf(
            "shared/src/commonMain/kotlin", "shared/src/androidMain", "shared/src/jvmMain",
            "server/src/main/kotlin", "androidApp/src/main", "desktopApp/src/main",
        )
        for (r in roots.take(1) + roots.drop(3).take(1)) assertTrue(File(root, r).isDirectory, "missing root $r")
        val allowed = setOf(
            "shared/src/commonMain/kotlin/dev/tim/assistant/ai/ChatContext.kt",
            "shared/src/commonMain/kotlin/dev/tim/assistant/ai/dream/DreamAnalysis.kt",
            "shared/src/commonMain/kotlin/dev/tim/assistant/data/Repository.kt",
            "shared/src/commonMain/kotlin/dev/tim/assistant/data/SyncEngine.kt",
            "shared/src/commonMain/kotlin/dev/tim/assistant/model/RowMapping.kt",
            "shared/src/commonMain/kotlin/dev/tim/assistant/ui/dream/DreamScreen.kt",
            "shared/src/commonMain/kotlin/dev/tim/assistant/ui/dream/DreamForm.kt",
            "shared/src/commonMain/kotlin/dev/tim/assistant/ui/dream/DreamDetail.kt",
            "shared/src/commonMain/kotlin/dev/tim/assistant/ui/dream/DreamHistory.kt",
            "server/src/main/kotlin/dev/tim/assistant/server/SyncStore.kt",
            // add_dream (AUTO write) and its server-authorised Undo read/write the dream row
            "server/src/main/kotlin/dev/tim/assistant/server/chat/ServerToolExecutor.kt",
        )
        val pattern = Regex("""dreamQueries|saveDream|saveDreamPattern|dream_patternQueries|dreamById|\.dreams\(|\.dreamsIn\(|dreamPatterns\(|db\.Dream|Dream_pattern|DreamPattern""")
        val found = mutableSetOf<String>()
        for (r in roots) {
            val dir = File(root, r)
            if (!dir.isDirectory) continue
            dir.walkTopDown().filter { it.isFile && it.extension == "kt" }.forEach { f ->
                if (pattern.containsMatchIn(f.readText())) found += f.relativeTo(root!!).path.replace('\\', '/')
            }
        }
        assertEquals(allowed, found)
    }
}
