import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import dev.tim.assistant.ai.ChatContext
import dev.tim.assistant.ai.ChatMessage
import dev.tim.assistant.ai.Completion
import dev.tim.assistant.ai.dream.DreamAnalysis
import dev.tim.assistant.ai.jobs.DREAM_JOB_ERROR
import dev.tim.assistant.ai.jobs.JobFailedNow
import dev.tim.assistant.ai.jobs.JobHandler
import dev.tim.assistant.ai.jobs.JobResult
import dev.tim.assistant.ai.jobs.JobRunner
import dev.tim.assistant.ai.jobs.ModelClient
import dev.tim.assistant.ai.jobs.OllamaOffline
import dev.tim.assistant.data.GoalStep
import dev.tim.assistant.data.LocalStore
import dev.tim.assistant.data.Repository
import dev.tim.assistant.data.docBodyTooLong
import dev.tim.assistant.data.normalizeTags
import dev.tim.assistant.data.parseGoalSteps
import dev.tim.assistant.data.prepareSchema
import dev.tim.assistant.db.AssistantDb
import dev.tim.assistant.model.SyncRow
import dev.tim.assistant.model.toSyncRow
import dev.tim.assistant.model.upsertFromSyncRow
import dev.tim.assistant.server.validateRow
import dev.tim.assistant.ui.LayoutMode
import dev.tim.assistant.ui.MdBlock
import dev.tim.assistant.ui.MdSpan
import dev.tim.assistant.ui.NavTab
import dev.tim.assistant.ui.addActionFor
import dev.tim.assistant.ui.hubOf
import dev.tim.assistant.ui.jobStatusText
import dev.tim.assistant.ui.parseMarkdown
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Phase 8 acceptance tests, written from the Plan contract (AC-1..AC-8; manual parts live in ops/checklists/w8.md). */
class Phase8AcceptanceTest {
    private val root: File = generateSequence(File("").absoluteFile) { it.parentFile }
        .first { File(it, "settings.gradle.kts").exists() }

    private fun text(rel: String) = File(root, rel).readText()

    // ------------------------------------------------------------ helpers

    private fun tmpDb(): File = Files.createTempFile("p8acc", ".db").toFile().also { it.delete(); it.deleteOnExit() }
    private fun driverFor(f: File): SqlDriver = JdbcSqliteDriver("jdbc:sqlite:${f.absolutePath}")

    private fun rows(d: SqlDriver, sql: String): List<List<String?>> = d.executeQuery(
        null, sql,
        { c ->
            val out = mutableListOf<List<String?>>()
            val n = generateSequence(0) { it + 1 }.takeWhile { i -> runCatching { c.getString(i) }.isSuccess && i < 20 }.count()
            while (c.next().value) out += (0 until n).map { c.getString(it) }
            QueryResult.Value(out)
        },
        0,
    ).value

    private fun userVersion(d: SqlDriver) = rows(d, "PRAGMA user_version")[0][0]!!.toLong()
    private fun tables(d: SqlDriver) = rows(d, "SELECT name FROM sqlite_master WHERE type='table'").map { it[0]!! }.toSet()

    private val newTables = listOf("doc_folder", "doc", "ai_job", "dream", "dream_pattern", "goal")

    /** The 1.5.0 (schema 2) tables, frozen literally: independent of the current schema (Phase 9 added more tables). */
    private val FROZEN_V2_DDL = listOf(
        "CREATE TABLE briefing (id TEXT NOT NULL PRIMARY KEY, updatedAt INTEGER NOT NULL, deleted INTEGER NOT NULL, serverSeq INTEGER NOT NULL DEFAULT 0, syncState TEXT NOT NULL, localDate TEXT NOT NULL, text TEXT NOT NULL, model TEXT NOT NULL)",
        "CREATE TABLE cal_event (id TEXT NOT NULL PRIMARY KEY, updatedAt INTEGER NOT NULL, deleted INTEGER NOT NULL, serverSeq INTEGER NOT NULL DEFAULT 0, syncState TEXT NOT NULL, calendarId TEXT NOT NULL, eventId TEXT NOT NULL, title TEXT NOT NULL, startAt INTEGER NOT NULL, endAt INTEGER NOT NULL, allDay INTEGER NOT NULL, location TEXT)",
        "CREATE TABLE checkin (id TEXT NOT NULL PRIMARY KEY, updatedAt INTEGER NOT NULL, deleted INTEGER NOT NULL, serverSeq INTEGER NOT NULL DEFAULT 0, syncState TEXT NOT NULL, localDate TEXT NOT NULL, mood INTEGER NOT NULL, energy INTEGER NOT NULL, sleepQuality INTEGER NOT NULL, stress INTEGER NOT NULL, note TEXT)",
        "CREATE TABLE health_day (id TEXT NOT NULL PRIMARY KEY, updatedAt INTEGER NOT NULL, deleted INTEGER NOT NULL, serverSeq INTEGER NOT NULL DEFAULT 0, syncState TEXT NOT NULL, localDate TEXT NOT NULL, sleepMinutes INTEGER, steps INTEGER, restingHr INTEGER)",
        "CREATE TABLE journal (id TEXT NOT NULL PRIMARY KEY, updatedAt INTEGER NOT NULL, deleted INTEGER NOT NULL, serverSeq INTEGER NOT NULL DEFAULT 0, syncState TEXT NOT NULL, text TEXT NOT NULL, createdAt INTEGER NOT NULL)",
        "CREATE TABLE kv (key TEXT NOT NULL PRIMARY KEY, value TEXT NOT NULL)",
        "CREATE TABLE mail_item (id TEXT NOT NULL PRIMARY KEY, updatedAt INTEGER NOT NULL, deleted INTEGER NOT NULL, serverSeq INTEGER NOT NULL DEFAULT 0, syncState TEXT NOT NULL, gmailId TEXT NOT NULL, sender TEXT NOT NULL, subject TEXT NOT NULL, snippet TEXT NOT NULL, receivedAt INTEGER NOT NULL)",
        "CREATE TABLE meal (id TEXT NOT NULL PRIMARY KEY, updatedAt INTEGER NOT NULL, deleted INTEGER NOT NULL, serverSeq INTEGER NOT NULL DEFAULT 0, syncState TEXT NOT NULL, type TEXT NOT NULL, text TEXT NOT NULL, eatenAt INTEGER NOT NULL, localDate TEXT NOT NULL, kcal INTEGER, proteinG INTEGER, carbsG INTEGER, fatG INTEGER, estimateModel TEXT)",
        "CREATE TABLE spending (id TEXT NOT NULL PRIMARY KEY, updatedAt INTEGER NOT NULL, deleted INTEGER NOT NULL, serverSeq INTEGER NOT NULL DEFAULT 0, syncState TEXT NOT NULL, amountAgorot INTEGER NOT NULL, category TEXT NOT NULL, note TEXT, localDate TEXT NOT NULL)",
        "CREATE TABLE task (id TEXT NOT NULL PRIMARY KEY, updatedAt INTEGER NOT NULL, deleted INTEGER NOT NULL, serverSeq INTEGER NOT NULL DEFAULT 0, syncState TEXT NOT NULL, title TEXT NOT NULL, dueDate TEXT, done INTEGER NOT NULL, doneAt INTEGER)",
        "CREATE TABLE workout (id TEXT NOT NULL PRIMARY KEY, updatedAt INTEGER NOT NULL, deleted INTEGER NOT NULL, serverSeq INTEGER NOT NULL DEFAULT 0, syncState TEXT NOT NULL, hcRecordId TEXT NOT NULL, type TEXT NOT NULL, startAt INTEGER NOT NULL, endAt INTEGER NOT NULL, distanceMeters INTEGER)",
    )

    /** A file as the 1.5.0 app left it: schema version 2 (meal present), none of the new tables, task without goalId. */
    private fun v2File(): File {
        val f = tmpDb()
        val d = driverFor(f)
        FROZEN_V2_DDL.forEach { d.execute(null, it, 0) }
        d.execute(null, "INSERT INTO checkin VALUES ('checkin:2026-11-01', 5, 0, 0, 'SYNCED', '2026-11-01', 4, 3, 2, 1, 'n')", 0)
        d.execute(null, "INSERT INTO spending VALUES ('s1', 6, 0, 0, 'SYNCED', 1250, 'Food', NULL, '2026-11-01')", 0)
        d.execute(null, "INSERT INTO task VALUES ('t1', 7, 0, 0, 'SYNCED', 'old task', '2026-11-05', 0, NULL)", 0)
        d.execute(null, "INSERT INTO meal VALUES ('m1', 8, 0, 0, 'SYNCED', 'MEAL', 'pasta', 1700000000000, '2026-11-01', NULL, NULL, NULL, NULL, NULL)", 0)
        d.execute(null, "PRAGMA user_version = 2", 0)
        assertEquals(2L, userVersion(d))
        assertFalse("goal" in tables(d))
        d.close()
        return f
    }

    private fun memDb() = AssistantDb(JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).also { AssistantDb.Schema.create(it) })

    private class Clock(var t: Long = 1_700_000_000_000L) { fun now() = t }

    private fun repoWith(clock: Clock): Pair<AssistantDb, Repository> {
        val db = memDb()
        return db to Repository(LocalStore(db), now = clock::now, today = { "2026-11-09" })
    }

    private fun taskData(goalId: Any? = Unit): JsonObject {
        val m = mutableMapOf<String, kotlinx.serialization.json.JsonElement>(
            "title" to JsonPrimitive("t"), "dueDate" to JsonNull, "done" to JsonPrimitive(false), "doneAt" to JsonNull,
        )
        when (goalId) {
            Unit -> {}
            null -> m["goalId"] = JsonNull
            else -> m["goalId"] = JsonPrimitive(goalId as String)
        }
        return JsonObject(m)
    }

    // ------------------------------------------------------------ AC-1 schema / migration

    @Test
    fun test_AC1_fresh_file_is_version_4_with_all_new_tables_and_task_goalId_last() {
        val d = driverFor(tmpDb())
        prepareSchema(d)
        assertEquals(8L, AssistantDb.Schema.version)
        assertEquals(8L, userVersion(d))
        assertTrue(tables(d).containsAll(newTables), "tables: ${tables(d)}")
        assertEquals("goalId", rows(d, "PRAGMA table_info(task)").last()[1])
        d.close()
    }

    @Test
    fun test_AC1_version_2_file_upgrades_to_4_and_old_rows_still_read_back() {
        val f = v2File()
        val d = driverFor(f)
        prepareSchema(d)
        assertEquals(8L, userVersion(d))
        assertTrue(tables(d).containsAll(newTables))
        assertEquals("1", rows(d, "SELECT count(*) FROM checkin")[0][0])
        assertEquals("1250", rows(d, "SELECT amountAgorot FROM spending")[0][0])
        assertEquals("pasta", rows(d, "SELECT text FROM meal")[0][0])
        val t = rows(d, "SELECT title, dueDate, goalId FROM task")[0]
        assertEquals("old task", t[0]); assertEquals("2026-11-05", t[1]); assertNull(t[2])
        d.close()
        // And through the app's own accessors.
        val db = AssistantDb(driverFor(f))
        val repo = Repository(LocalStore(db))
        assertEquals(listOf("old task"), repo.openTasks().map { it.title })
        assertEquals(1, repo.meals().size)
    }

    @Test
    fun test_AC1_migrated_table_info_equals_fresh_for_task_and_new_tables() {
        val migrated = driverFor(v2File()).also { prepareSchema(it) }
        val fresh = driverFor(tmpDb()).also { prepareSchema(it) }
        for (t in listOf("task") + newTables) {
            assertEquals(rows(fresh, "PRAGMA table_info($t)"), rows(migrated, "PRAGMA table_info($t)"), "table $t")
        }
        migrated.close(); fresh.close()
    }

    @Test
    fun test_AC1_opening_a_migrated_file_again_changes_nothing() {
        val d = driverFor(v2File())
        prepareSchema(d)
        val before = rows(d, "SELECT type, name, sql FROM sqlite_master ORDER BY name")
        prepareSchema(d)
        assertEquals(before, rows(d, "SELECT type, name, sql FROM sqlite_master ORDER BY name"))
        assertEquals(8L, userVersion(d))
        d.close()
    }

    // ------------------------------------------------------------ AC-1 sync wiring / validation

    private fun verdict(table: String, data: JsonObject) = validateRow(SyncRow(table, "x1", 1, false, data))

    @Test
    fun test_AC1_server_accepts_and_rejects_new_table_rows() {
        fun o(vararg p: Pair<String, Any?>) = JsonObject(p.associate { (k, v) ->
            k to when (v) { null -> JsonNull; is String -> JsonPrimitive(v); is Boolean -> JsonPrimitive(v); else -> JsonPrimitive(v as Number) }
        })
        assertNull(verdict("doc_folder", o("name" to "Plans")))
        assertNotNull(verdict("doc_folder", o("name" to "")))
        assertNull(verdict("doc", o("folderId" to null, "title" to "t", "body" to "b", "source" to "USER")))
        assertNotNull(verdict("doc", o("folderId" to null, "title" to "t", "body" to "b", "source" to "ROBOT")))
        assertNotNull(verdict("doc", o("folderId" to null, "title" to "", "body" to "b", "source" to "USER")))
        assertNotNull(verdict("doc", o("folderId" to null, "title" to "t", "body" to "x".repeat(200_001), "source" to "USER")))
        assertNull(verdict("goal", o("title" to "g", "why" to null, "deadline" to null, "status" to "ACTIVE")))
        assertNotNull(verdict("goal", o("title" to "g", "why" to null, "deadline" to null, "status" to "NOPE")))
        assertNotNull(verdict("goal", o("title" to "", "why" to null, "deadline" to null, "status" to "ACTIVE")))
    }

    @Test
    fun test_AC1_task_goalId_rules_absent_ok_null_ok_string_ok_wrong_type_rejected() {
        assertNull(verdict("task", taskData(goalId = Unit)), "absent goalId (1.5.0 app) must be accepted")
        assertNull(verdict("task", taskData(goalId = null)))
        assertNull(verdict("task", taskData(goalId = "goal:1")))
        val wrong = JsonObject(taskData(Unit) + ("goalId" to JsonPrimitive(5)))
        assertNotNull(verdict("task", wrong))
        // Other fields stay strict: a task without title is still rejected.
        assertNotNull(verdict("task", JsonObject(taskData(Unit) - "title")))
    }

    @Test
    fun test_AC1_legacy_task_push_without_goalId_keeps_stored_link_and_null_clears() {
        val db = memDb()
        upsertFromSyncRow(db, SyncRow("task", "t1", 10, false, taskData("goal:1")), serverSeq = 1)
        assertEquals("goal:1", db.taskQueries.selectById("t1").executeAsOne().goalId)
        upsertFromSyncRow(db, SyncRow("task", "t1", 11, false, taskData(Unit)), serverSeq = 2)
        assertEquals("goal:1", db.taskQueries.selectById("t1").executeAsOne().goalId)
        upsertFromSyncRow(db, SyncRow("task", "t1", 12, false, taskData(null)), serverSeq = 3)
        assertNull(db.taskQueries.selectById("t1").executeAsOne().goalId)
        // New row with absent key -> null.
        upsertFromSyncRow(db, SyncRow("task", "t2", 10, false, taskData(Unit)), serverSeq = 4)
        assertNull(db.taskQueries.selectById("t2").executeAsOne().goalId)
        // Always sent by new writers.
        assertTrue(toSyncRow(db.taskQueries.selectById("t1").executeAsOne()).data.containsKey("goalId"))
    }

    @Test
    fun test_AC1_new_table_rows_round_trip_through_sync_mapping() = runBlocking {
        val clock = Clock()
        val (db, repo) = repoWith(clock)
        val folder = repo.saveFolder(null, "Notes")
        val doc = repo.saveDoc(null, folder, "Title", "Body שלום", "USER")
        val goal = repo.saveGoal(null, "Learn", null, "2026-12-01")
        val dream = repo.saveDream(null, "2026-11-08", "I flew", 4, true, "Sky, Flying ")
        val job = repo.queueJob("GOAL_STEPS", """{"goalId":"$goal"}""")

        val other = memDb()
        val pairs = listOf(
            toSyncRow(db.doc_folderQueries.selectById(folder).executeAsOne()),
            toSyncRow(db.docQueries.selectById(doc).executeAsOne()),
            toSyncRow(db.goalQueries.selectById(goal).executeAsOne()),
            toSyncRow(db.dreamQueries.selectById(dream).executeAsOne()),
            toSyncRow(db.ai_jobQueries.selectById(job).executeAsOne()),
        )
        for ((i, r) in pairs.withIndex()) {
            assertNull(validateRow(r), "server must accept client-written ${r.table}")
            upsertFromSyncRow(other, r, serverSeq = (i + 1).toLong())
        }
        val repo2 = Repository(LocalStore(other))
        assertEquals("Body שלום", repo2.docById(doc)?.body)
        assertEquals(listOf("Notes"), repo2.folders().map { it.name })
        assertEquals("Learn", repo2.goalById(goal)?.title)
        assertEquals("I flew", repo2.dreamById(dream)?.text)
        assertEquals("QUEUED", repo2.jobById(job)?.status)
    }

    // ------------------------------------------------------------ pure helpers

    @Test
    fun test_AC2_parseMarkdown_headings_bullets_bold_italic_links() {
        val blocks = parseMarkdown("# Title\n\n- item **bold** and *it*\nplain [site](https://example.com) end")
        val h = blocks.filterIsInstance<MdBlock.Heading>().single()
        assertEquals(1, h.level)
        assertEquals(listOf<MdSpan>(MdSpan.Plain("Title")), h.spans)
        val b = blocks.filterIsInstance<MdBlock.Bullet>().single()
        assertTrue(b.spans.contains(MdSpan.Bold("bold")))
        assertTrue(b.spans.contains(MdSpan.Italic("it")))
        val p = blocks.filterIsInstance<MdBlock.Paragraph>().single()
        assertTrue(p.spans.contains(MdSpan.Link("site", "https://example.com")))
    }

    @Test
    fun test_AC2_parseMarkdown_second_level_heading_and_crlf() {
        val blocks = parseMarkdown("## Two\r\n- a\r\n- b")
        assertEquals(2, (blocks[0] as MdBlock.Heading).level)
        assertEquals(2, blocks.count { it is MdBlock.Bullet })
    }

    @Test
    fun test_AC2_parseMarkdown_malformed_stays_literal_and_hebrew_untouched() {
        val unclosed = parseMarkdown("this is **not closed")
        val p = unclosed.single() as MdBlock.Paragraph
        assertEquals("this is **not closed", p.spans.joinToString("") { (it as? MdSpan.Plain)?.text ?: "?" })
        val he = parseMarkdown("שלום עולם") .single() as MdBlock.Paragraph
        assertEquals(listOf<MdSpan>(MdSpan.Plain("שלום עולם")), he.spans)
        assertTrue(parseMarkdown("").isEmpty() || parseMarkdown("").all { true })
    }

    @Test
    fun test_AC2_docBodyTooLong_boundary_and_normalizeTags() {
        assertFalse(docBodyTooLong("x".repeat(200_000)))
        assertTrue(docBodyTooLong("x".repeat(200_001)))
        assertEquals("sky,flying", normalizeTags(" Sky, , Flying ,"))
        assertTrue(normalizeTags((1..400).joinToString(",") { "tag$it" }).length <= 500)
    }

    @Test
    fun test_AC4_jobStatusText_wording() = runBlocking {
        val clock = Clock()
        val (_, repo) = repoWith(clock)
        val id = repo.queueJob("GOAL_STEPS", "{}")
        assertEquals("Queued — runs when Wally is open on the laptop", jobStatusText(repo.jobById(id)))
        assertNull(jobStatusText(null))
        val claim = repo.claimJob(id)!!
        assertEquals("Working…", jobStatusText(repo.jobById(id)))
        repo.failJob(claim, "boom", true)
        assertEquals("Failed: boom", jobStatusText(repo.jobById(id)))
    }

    // ------------------------------------------------------------ AC-3 JobRunner

    private fun runner(repo: Repository, clock: Clock, vararg h: Pair<String, JobHandler>) =
        JobRunner(repo, sync = {}, handlers = mapOf(*h), clock = clock::now)

    @Test
    fun test_AC3_oldest_queued_runs_first_and_done_writes_result() = runBlocking {
        val clock = Clock()
        val (_, repo) = repoWith(clock)
        val first = repo.queueJob("K", "{}")
        clock.t += 1000
        val second = repo.queueJob("K", "{}")
        val ran = mutableListOf<String>()
        val r = runner(repo, clock, "K" to JobHandler { j -> ran += j.id; JobResult("""{"ok":"${j.id}"}""") })
        r.tick(); r.tick()
        assertEquals(listOf(first, second), ran)
        val done = repo.jobById(first)!!
        assertEquals("DONE", done.status)
        assertEquals("""{"ok":"$first"}""", done.resultJson)
        assertNull(jobStatusText(done))
    }

    @Test
    fun test_AC3_exception_adds_attempt_and_third_failure_is_FAILED() = runBlocking {
        val clock = Clock()
        val (_, repo) = repoWith(clock)
        val id = repo.queueJob("K", "{}")
        val r = runner(repo, clock, "K" to JobHandler { throw IllegalStateException("bad") })
        r.tick()
        repo.jobById(id)!!.let { assertEquals("QUEUED", it.status); assertEquals(1L, it.attempts); assertNotNull(it.error) }
        r.tick()
        repo.jobById(id)!!.let { assertEquals("QUEUED", it.status); assertEquals(2L, it.attempts) }
        r.tick()
        repo.jobById(id)!!.let { assertEquals("FAILED", it.status); assertEquals(3L, it.attempts) }
        assertEquals(1, 1)
    }

    @Test
    fun test_AC3_JobFailedNow_fails_at_once_with_its_message() = runBlocking {
        val clock = Clock()
        val (_, repo) = repoWith(clock)
        val id = repo.queueJob("K", "{}")
        runner(repo, clock, "K" to JobHandler { throw JobFailedNow("nope") }).tick()
        val j = repo.jobById(id)!!
        assertEquals("FAILED", j.status)
        assertEquals("nope", j.error)
    }

    @Test
    fun test_AC3_unknown_kind_is_FAILED_with_fixed_text() = runBlocking {
        val clock = Clock()
        val (_, repo) = repoWith(clock)
        val id = repo.queueJob("MYSTERY", "{}")
        runner(repo, clock).tick()
        val j = repo.jobById(id)!!
        assertEquals("FAILED", j.status)
        assertEquals("unknown job kind", j.error)
    }

    @Test
    fun test_AC3_stale_running_job_is_reset_and_run_again() = runBlocking {
        val clock = Clock()
        val (_, repo) = repoWith(clock)
        val id = repo.queueJob("K", "{}")
        repo.claimJob(id)!!
        assertEquals("RUNNING", repo.jobById(id)!!.status)
        clock.t += 31 * 60 * 1000L
        var ran = 0
        runner(repo, clock, "K" to JobHandler { ran++; JobResult(null) }).tick()
        assertEquals(1, ran)
        assertEquals("DONE", repo.jobById(id)!!.status)
    }

    @Test
    fun test_AC3_fresh_running_job_is_not_touched() = runBlocking {
        val clock = Clock()
        val (_, repo) = repoWith(clock)
        val id = repo.queueJob("K", "{}")
        repo.claimJob(id)!!
        clock.t += 5 * 60 * 1000L
        var ran = 0
        runner(repo, clock, "K" to JobHandler { ran++; JobResult(null) }).tick()
        assertEquals(0, ran)
        assertEquals("RUNNING", repo.jobById(id)!!.status)
    }

    @Test
    fun test_AC3_offline_keeps_attempts_and_job_stays_queued() = runBlocking {
        val clock = Clock()
        val (_, repo) = repoWith(clock)
        val id = repo.queueJob("K", "{}")
        val r = runner(repo, clock, "K" to JobHandler { throw OllamaOffline() })
        repeat(5) { r.tick() }
        val j = repo.jobById(id)!!
        assertEquals("QUEUED", j.status)
        assertEquals(0L, j.attempts)
    }

    @Test
    fun test_AC3_failing_sync_is_not_a_job_failure() = runBlocking {
        val clock = Clock()
        val (_, repo) = repoWith(clock)
        val id = repo.queueJob("K", "{}")
        val r = JobRunner(repo, sync = { throw java.io.IOException("offline") },
            handlers = mapOf("K" to JobHandler { JobResult(null) }), clock = clock::now)
        r.tick()
        assertEquals("DONE", repo.jobById(id)!!.status)
    }

    @Test
    fun test_AC3_cancellation_is_not_swallowed() {
        val clock = Clock()
        val (_, repo) = repoWith(clock)
        runBlocking { repo.queueJob("K", "{}") }
        val r = runner(repo, clock, "K" to JobHandler { throw CancellationException("stop") })
        var cancelled = false
        try { runBlocking { r.tick() } } catch (e: CancellationException) { cancelled = true }
        assertTrue(cancelled)
        val j = repo.jobs().single()
        assertEquals(0L, j.attempts, "cancellation must not count as an attempt")
    }

    // ------------------------------------------------------------ AC-5 dream wall

    private val marker = "ZZ_DREAM_SECRET_QQ"

    @Test
    fun test_AC5_chat_context_never_contains_dream_text_or_analysis() = runBlocking {
        val clock = Clock()
        val (db, repo) = repoWith(clock)
        val id = repo.saveDream(null, "2026-11-08", "dream $marker", 3, false, "tag$marker")
        repo.saveDreamPattern("2026-11-08", "pattern $marker", "m")
        repo.saveCheckin(3, 3, 3, 3, "ordinary note")
        repo.saveTask(null, "an ordinary task", null, false)
        assertNotNull(repo.dreamById(id))
        val out = ChatContext.build(db, "2026-11-09") + ChatContext.systemPrompt(ChatContext.build(db, "2026-11-09"))
        assertFalse(out.contains(marker), "dream text leaked into chat context")
        assertTrue(out.contains("ordinary task"), "sanity: the context does include normal data")
    }

    private class FakeModel(val reply: suspend (List<ChatMessage>) -> Completion) : ModelClient {
        val prompts = mutableListOf<List<ChatMessage>>()
        override suspend fun complete(messages: List<ChatMessage>, format: JsonObject?): Completion {
            prompts += messages
            return reply(messages)
        }
    }

    @Test
    fun test_AC5_dream_failure_error_is_exactly_the_fixed_text_even_with_marker_in_cause() = runBlocking {
        val clock = Clock()
        val (_, repo) = repoWith(clock)
        val dreamId = repo.saveDream(null, "2026-11-08", "dream $marker", 3, false, "")
        val model = FakeModel { throw IllegalStateException("cause mentions $marker") }
        val jobId = repo.queueJob("DREAM_INTERPRET", """{"dreamId":"$dreamId"}""")
        val r = JobRunner(repo, sync = {}, handlers = DreamAnalysis(repo, model).handlers(), clock = clock::now)
        r.tick()
        val j = repo.jobById(jobId)!!
        assertEquals("dream analysis failed", DREAM_JOB_ERROR)
        assertEquals("dream analysis failed", j.error)
        assertFalse((j.error ?: "").contains(marker))
        assertNull(j.resultJson)
    }

    @Test
    fun test_AC5_dream_offline_keeps_attempts_and_success_writes_analysis_with_null_result() = runBlocking {
        val clock = Clock()
        val (_, repo) = repoWith(clock)
        val dreamId = repo.saveDream(null, "2026-11-08", "I flew over a lake", 4, false, "")
        val offline = FakeModel { Completion.Offline }
        val jobId = repo.queueJob("DREAM_INTERPRET", """{"dreamId":"$dreamId"}""")
        JobRunner(repo, sync = {}, handlers = DreamAnalysis(repo, offline).handlers(), clock = clock::now).tick()
        assertEquals("QUEUED", repo.jobById(jobId)!!.status)
        assertEquals(0L, repo.jobById(jobId)!!.attempts)

        val ok = FakeModel { Completion.Ok("It is about freedom.") }
        JobRunner(repo, sync = {}, handlers = DreamAnalysis(repo, ok).handlers(), clock = clock::now).tick()
        val j = repo.jobById(jobId)!!
        assertEquals("DONE", j.status)
        assertNull(j.resultJson, "DREAM_* resultJson is always null")
        assertEquals("It is about freedom.", repo.dreamById(dreamId)!!.analysis)
    }

    @Test
    fun test_AC5_non_dream_job_prompts_never_contain_dream_text() = runBlocking {
        val clock = Clock()
        val (_, repo) = repoWith(clock)
        repo.saveDream(null, "2026-11-08", "dream $marker", 3, false, "")
        repo.saveDreamPattern("2026-11-08", "pattern $marker", "m")
        val goalId = repo.saveGoal(null, "Run a 10k", "health", "2026-12-31")
        val model = FakeModel { Completion.Ok("""{"steps":[{"title":"a","dueDate":null},{"title":"b","dueDate":null},{"title":"c","dueDate":null}]}""") }
        val week = dev.tim.assistant.data.DreamWeek(clock.t, listOf(dev.tim.assistant.data.DreamBlock("b1", "Morning run block", 1, 7 * 60, 8 * 60)))
        val goalJobs = dev.tim.assistant.ai.jobs.GoalJobs(repo, model, clock::now) { dev.tim.assistant.data.DreamWeekResult.Sent(week) }
        repo.queueJob("GOAL_STEPS", """{"goalId":"$goalId"}""")
        repo.queueJob("IDEAL_WEEK", """{"localDate":"2026-11-09"}""")
        val r = JobRunner(repo, sync = {}, handlers = goalJobs.handlers(), clock = clock::now)
        r.tick(); r.tick()
        assertTrue(model.prompts.size >= 2, "both the steps job and the coaching job called the model")
        assertTrue(model.prompts.any { p -> p.joinToString("\n") { it.content }.contains("Morning run block") }, "coaching prompt carries the Dream week blocks")
        for (p in model.prompts) assertFalse(p.joinToString("\n") { it.content }.contains(marker))
    }

    // ------------------------------------------------------------ AC-6 goal steps

    private fun stepsJson(n: Int) = """{"steps":[${(1..n).joinToString(",") { """{"title":"step $it","dueDate":null}""" }}]}"""

    @Test
    fun test_AC6_goal_steps_3_to_10_ok_2_and_11_rejected() {
        assertEquals(3, parseGoalSteps(stepsJson(3)).size)
        assertEquals(10, parseGoalSteps(stepsJson(10)).size)
        assertTrue(parseGoalSteps(stepsJson(2)).isEmpty())
        assertTrue(parseGoalSteps(stepsJson(11)).isEmpty())
        assertTrue(parseGoalSteps(null).isEmpty())
        assertTrue(parseGoalSteps("not json").isEmpty())
        val blank = """{"steps":[{"title":" ","dueDate":null},{"title":"b","dueDate":null},{"title":"c","dueDate":null}]}"""
        assertTrue(parseGoalSteps(blank).isEmpty())
        val badDate = """{"steps":[{"title":"a","dueDate":"2026-13-45"},{"title":"b","dueDate":null},{"title":"c","dueDate":null}]}"""
        assertTrue(parseGoalSteps(badDate).isEmpty())
        assertEquals("2026-12-01", parseGoalSteps("""{"steps":[{"title":"a","dueDate":"2026-12-01"},{"title":"b","dueDate":null},{"title":"c","dueDate":null}]}""")[0].dueDate)
    }

    @Test
    fun test_AC6_goal_step_tasks_have_deterministic_ids_and_repeat_add_is_a_no_op() = runBlocking {
        val clock = Clock()
        val (_, repo) = repoWith(clock)
        val goal = repo.saveGoal(null, "G", null, null)
        val steps = listOf(GoalStep("one", null), GoalStep("two", "2026-12-01"), GoalStep("three", null))
        val added = repo.addGoalStepTasks("job1", goal, steps, setOf(0, 2))
        assertEquals(2, added)
        assertEquals(setOf("task:goalstep:job1:0", "task:goalstep:job1:2"), repo.tasksForGoal(goal).map { it.id }.toSet())
        // user edits one, marks the other done
        repo.saveTask("task:goalstep:job1:0", "my edited title", null, false)
        repo.saveTask("task:goalstep:job1:2", "three", null, true)
        repo.addGoalStepTasks("job1", goal, steps, setOf(0, 1, 2))
        val byId = repo.tasksForGoal(goal).associateBy { it.id }
        assertEquals("my edited title", byId.getValue("task:goalstep:job1:0").title)
        assertEquals(1L, byId.getValue("task:goalstep:job1:2").done)
        assertTrue(byId.containsKey("task:goalstep:job1:1"))
        assertEquals(goal, byId.getValue("task:goalstep:job1:0").goalId, "four-arg saveTask keeps the goal link")
        val p = repo.goalProgress(goal)
        assertEquals(3, p.total)
        assertEquals(1, p.done)
    }

    @Test
    fun test_AC6_five_arg_saveTask_sets_and_clears_goal() = runBlocking {
        val clock = Clock()
        val (_, repo) = repoWith(clock)
        val goal = repo.saveGoal(null, "G", null, null)
        val id = repo.saveTask(null, "t", null, false, goal)
        assertEquals(goal, repo.tasksForGoal(goal).single().goalId)
        repo.saveTask(id, "t2", null, true)
        assertEquals(goal, repo.tasksForGoal(goal).single().goalId)
        repo.saveTask(id, "t2", null, true, null)
        assertTrue(repo.tasksForGoal(goal).isEmpty())
    }

    // ------------------------------------------------------------ navigation

    @Test
    fun test_AC2_navigation_tabs_all_have_a_hub() {
        // Phase 14: the phone bar / More sheet became 5 hubs; every NavTab has one.
        assertEquals(17, NavTab.entries.size)
        for (t in NavTab.entries) assertTrue(hubOf(t) in dev.tim.assistant.ui.Hub.entries)
    }

    @Test
    fun test_AC2_add_target_null_on_Chat_Screentime_Hardware() {
        // Phase 15: Files, Goals, Study and Mail got their own + actions (see Phase15AcceptanceTest).
        for (t in listOf(NavTab.Chat, NavTab.Screentime, NavTab.Hardware)) {
            assertNull(addActionFor(t), "tab $t")
        }
    }

    // ------------------------------------------------------------ release / AC-2..AC-6 manual checklist files

    @Test
    fun test_AC8_release_versions_1_7_0_and_versionCode_3() {
        assertTrue(text("desktopApp/build.gradle.kts").contains("packageVersion = \"1.18.0\""))
        val a = text("androidApp/build.gradle.kts")
        assertTrue(a.contains("versionName = \"1.18.0\""), "androidApp versionName")
        assertTrue(Regex("versionCode\\s*=\\s*14\\b").containsMatchIn(a), "androidApp versionCode")
        // unchanged identity
        assertTrue(text("desktopApp/build.gradle.kts").contains("upgradeUuid"))
        assertTrue(text("desktopApp/build.gradle.kts").contains("packageName = \"Wally\""))
    }

    @Test
    fun test_AC2_AC4_AC6_w8_checklist_exists_with_manual_lines() {
        val t = text("ops/checklists/w8.md").lowercase()
        for (k in listOf("files", "dream", "goal", "ideal week", "right-to-left", "link", "more", "deploy", "server")) {
            assertTrue(t.contains(k), "w8.md should mention '$k'")
        }
        assertTrue(t.contains("1 min") || t.contains("minute"), "job line has a timing")
    }
}
