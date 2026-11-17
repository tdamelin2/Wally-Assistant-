import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import dev.tim.assistant.ai.ChatContext
import dev.tim.assistant.ai.ChatMessage
import dev.tim.assistant.ai.Completion
import dev.tim.assistant.ai.dream.DreamAnalysis
import dev.tim.assistant.ai.jobs.JobRunner
import dev.tim.assistant.ai.jobs.ModelClient
import dev.tim.assistant.data.LocalStore
import dev.tim.assistant.data.Repository
import dev.tim.assistant.db.AssistantDb
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject

/** Phase 8 Tim change (P8-A-21-R1): deleting a dream pattern summary. */
class Phase8PatternDeleteAcceptanceTest {
    private class Clock(var t: Long = 1_700_000_000_000L) { fun now() = t }

    private fun repoWith(clock: Clock): Pair<AssistantDb, Repository> {
        val db = AssistantDb(JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).also { AssistantDb.Schema.create(it) })
        return db to Repository(LocalStore(db), now = clock::now, today = { "2026-11-09" })
    }

    private class FakeModel(val text: String) : ModelClient {
        override suspend fun complete(messages: List<ChatMessage>, format: JsonObject?): Completion = Completion.Ok(text)
    }

    private fun runJobs(repo: Repository, clock: Clock, text: String) = runBlocking {
        JobRunner(repo, sync = {}, handlers = DreamAnalysis(repo, FakeModel(text)).handlers(), clock = clock::now).tick()
    }

    private fun patternRow(db: AssistantDb, id: String) = db.dream_patternQueries.selectById(id).executeAsOneOrNull()

    @Test
    fun test_AC_P21_delete_pattern_tombstones_pending_and_hides_it(): Unit = runBlocking {
        val clock = Clock()
        val (db, repo) = repoWith(clock)
        val id = repo.saveDreamPattern("2026-11-08", "pattern A", "m")
        db.dream_patternQueries.upsert(patternRow(db, id)!!.copy(syncState = "SYNCED"))
        assertEquals(1, repo.dreamPatterns().size)
        val before = patternRow(db, id)!!.updatedAt
        clock.t += 5000
        repo.deleteDreamPattern(id)
        val row = patternRow(db, id)!!
        assertEquals(1L, row.deleted)
        assertEquals("PENDING", row.syncState)
        assertTrue(row.updatedAt > before)
        assertTrue(repo.dreamPatterns().isEmpty())
    }

    @Test
    fun test_AC_P21_delete_unknown_pattern_is_a_no_op(): Unit = runBlocking {
        val (_, repo) = repoWith(Clock())
        repo.deleteDreamPattern("dreampattern:nope")
        assertTrue(repo.dreamPatterns().isEmpty())
    }

    @Test
    fun test_AC_P21_deleting_pattern_never_touches_dream_rows(): Unit = runBlocking {
        val clock = Clock()
        val (db, repo) = repoWith(clock)
        val dreamId = repo.saveDream(null, "2026-11-08", "a dream", 3, false, "t")
        val dreamBefore = db.dreamQueries.selectById(dreamId).executeAsOne()
        val id = repo.saveDreamPattern("2026-11-08", "pattern A", "m")
        clock.t += 1000
        repo.deleteDreamPattern(id)
        assertEquals(dreamBefore, db.dreamQueries.selectById(dreamId).executeAsOne())
        assertEquals(1, repo.dreams().size)
    }

    // Phase 15: the DREAM_PATTERNS job handler is retired (Find patterns removed), so the two job-vs-delete tests are gone;
    // an old queued DREAM_PATTERNS job now fails without a model call (covered by the shared DreamWallTest).

    @Test
    fun test_AC_P21_dream_wall_chat_context_has_no_dream_or_pattern_text(): Unit = runBlocking {
        val clock = Clock()
        val (db, repo) = repoWith(clock)
        val m = "ZZ_PATTERN_SECRET_QQ"
        repo.saveDream(null, "2026-11-08", "dream $m", 3, false, "")
        val id = repo.saveDreamPattern("2026-11-08", "pattern $m", "m")
        repo.deleteDreamPattern(id)
        repo.saveDreamPattern("2026-11-09", "pattern2 $m", "m")
        repo.saveTask(null, "an ordinary task", null, false)
        val ctx = ChatContext.build(db, "2026-11-09")
        val out = ctx + ChatContext.systemPrompt(ctx)
        assertFalse(out.contains(m))
        assertTrue(out.contains("ordinary task"))
        assertNotNull(repo.dreamPatterns().firstOrNull())
    }
}
