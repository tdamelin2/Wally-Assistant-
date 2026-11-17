package dev.tim.assistant.data

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import dev.tim.assistant.db.AssistantDb
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SchemaMigrationTest {
    private val dir = Files.createTempDirectory("schema-migration-test").toFile().also { it.deleteOnExit() }

    private fun open(name: String): SqlDriver = JdbcSqliteDriver("jdbc:sqlite:${File(dir, name).absolutePath}")

    private fun rows(d: SqlDriver, sql: String, cols: Int): List<List<String?>> =
        d.executeQuery(
            null, sql,
            { c ->
                val out = mutableListOf<List<String?>>()
                while (c.next().value) out += (0 until cols).map { c.getString(it) }
                QueryResult.Value(out)
            },
            0,
        ).value

    private fun userVersion(d: SqlDriver) = rows(d, "PRAGMA user_version", 1).single().single()!!.toLong()

    private fun tables(d: SqlDriver) =
        rows(d, "SELECT name FROM sqlite_master WHERE type = 'table' AND name NOT LIKE 'sqlite_%' ORDER BY name", 1)
            .map { it[0]!! }

    private fun schemaSql(d: SqlDriver) = rows(d, "SELECT sql FROM sqlite_master ORDER BY name", 1)

    private val newTables = listOf("ai_job", "doc", "doc_folder", "dream", "dream_pattern", "goal")
    private val studyTables = listOf("card", "course", "exam", "quiz", "study_file")
    private val usageTables = listOf("app_rule", "listen", "usage_session")

    private val mailTables = listOf("hw_check", "mail_action", "mail_draft")

    private val phase13Tables = listOf("approval", "research")

    /** Private chat tables added by 7.sqm (never synced). */
    private val chatTables = listOf("chat", "chat_message", "memory_note", "outbox_notification")

    private val everyTable = (
        listOf("briefing", "cal_event", "checkin", "health_day", "journal", "kv", "mail_item", "meal", "spending", "task", "workout") +
            newTables + studyTables + usageTables + mailTables + phase13Tables + chatTables
        ).sorted()

    /** Frozen DDL of the tables as Phase 1 to 3 shipped them. It must never follow the current .sq files. */
    private val frozenBase = listOf(
        """CREATE TABLE checkin (id TEXT NOT NULL PRIMARY KEY, updatedAt INTEGER NOT NULL, deleted INTEGER NOT NULL, serverSeq INTEGER NOT NULL DEFAULT 0, syncState TEXT NOT NULL, localDate TEXT NOT NULL, mood INTEGER NOT NULL, energy INTEGER NOT NULL, sleepQuality INTEGER NOT NULL, stress INTEGER NOT NULL, note TEXT)""",
        """CREATE TABLE spending (id TEXT NOT NULL PRIMARY KEY, updatedAt INTEGER NOT NULL, deleted INTEGER NOT NULL, serverSeq INTEGER NOT NULL DEFAULT 0, syncState TEXT NOT NULL, amountAgorot INTEGER NOT NULL, category TEXT NOT NULL, note TEXT, localDate TEXT NOT NULL)""",
        """CREATE TABLE journal (id TEXT NOT NULL PRIMARY KEY, updatedAt INTEGER NOT NULL, deleted INTEGER NOT NULL, serverSeq INTEGER NOT NULL DEFAULT 0, syncState TEXT NOT NULL, text TEXT NOT NULL, createdAt INTEGER NOT NULL)""",
        """CREATE TABLE task (id TEXT NOT NULL PRIMARY KEY, updatedAt INTEGER NOT NULL, deleted INTEGER NOT NULL, serverSeq INTEGER NOT NULL DEFAULT 0, syncState TEXT NOT NULL, title TEXT NOT NULL, dueDate TEXT, done INTEGER NOT NULL, doneAt INTEGER)""",
        """CREATE TABLE health_day (id TEXT NOT NULL PRIMARY KEY, updatedAt INTEGER NOT NULL, deleted INTEGER NOT NULL, serverSeq INTEGER NOT NULL DEFAULT 0, syncState TEXT NOT NULL, localDate TEXT NOT NULL, sleepMinutes INTEGER, steps INTEGER, restingHr INTEGER)""",
        """CREATE TABLE workout (id TEXT NOT NULL PRIMARY KEY, updatedAt INTEGER NOT NULL, deleted INTEGER NOT NULL, serverSeq INTEGER NOT NULL DEFAULT 0, syncState TEXT NOT NULL, hcRecordId TEXT NOT NULL, type TEXT NOT NULL, startAt INTEGER NOT NULL, endAt INTEGER NOT NULL, distanceMeters INTEGER)""",
        """CREATE TABLE cal_event (id TEXT NOT NULL PRIMARY KEY, updatedAt INTEGER NOT NULL, deleted INTEGER NOT NULL, serverSeq INTEGER NOT NULL DEFAULT 0, syncState TEXT NOT NULL, calendarId TEXT NOT NULL, eventId TEXT NOT NULL, title TEXT NOT NULL, startAt INTEGER NOT NULL, endAt INTEGER NOT NULL, allDay INTEGER NOT NULL, location TEXT)""",
        """CREATE TABLE mail_item (id TEXT NOT NULL PRIMARY KEY, updatedAt INTEGER NOT NULL, deleted INTEGER NOT NULL, serverSeq INTEGER NOT NULL DEFAULT 0, syncState TEXT NOT NULL, gmailId TEXT NOT NULL, sender TEXT NOT NULL, subject TEXT NOT NULL, snippet TEXT NOT NULL, receivedAt INTEGER NOT NULL)""",
        """CREATE TABLE briefing (id TEXT NOT NULL PRIMARY KEY, updatedAt INTEGER NOT NULL, deleted INTEGER NOT NULL, serverSeq INTEGER NOT NULL DEFAULT 0, syncState TEXT NOT NULL, localDate TEXT NOT NULL, text TEXT NOT NULL, model TEXT NOT NULL)""",
        """CREATE TABLE kv (key TEXT NOT NULL PRIMARY KEY, value TEXT NOT NULL)""",
    )

    private val frozenMeal =
        """CREATE TABLE meal (id TEXT NOT NULL PRIMARY KEY, updatedAt INTEGER NOT NULL, deleted INTEGER NOT NULL, serverSeq INTEGER NOT NULL DEFAULT 0, syncState TEXT NOT NULL, type TEXT NOT NULL, text TEXT NOT NULL, eatenAt INTEGER NOT NULL, localDate TEXT NOT NULL, kcal INTEGER, proteinG INTEGER, carbsG INTEGER, fatG INTEGER, estimateModel TEXT)"""

    /** One row per existing table; the key is the table, the value the INSERT. */
    private val frozenRows = linkedMapOf(
        "checkin" to "INSERT INTO checkin VALUES ('checkin:2026-11-01', 10, 0, 3, 'SYNCED', '2026-11-01', 3, 4, 5, 1, 'שלום')",
        "spending" to "INSERT INTO spending VALUES ('s1', 20, 0, 4, 'PENDING', 1250, 'Food', NULL, '2026-11-01')",
        "journal" to "INSERT INTO journal VALUES ('j1', 30, 0, 5, 'SYNCED', 'יומן', 1000)",
        "task" to "INSERT INTO task VALUES ('t1', 40, 0, 6, 'PENDING', 'לקנות חלב', '2026-11-02', 0, NULL)",
        "health_day" to "INSERT INTO health_day VALUES ('health:2026-11-01', 50, 0, 7, 'SYNCED', '2026-11-01', 420, 8000, 55)",
        "workout" to "INSERT INTO workout VALUES ('workout:w1', 60, 0, 8, 'SYNCED', 'w1', 'RUN', 1000, 2000, 5000)",
        "cal_event" to "INSERT INTO cal_event VALUES ('e1', 70, 0, 9, 'SYNCED', 'primary', 'e1', 'Lecture', 1000, 2000, 0, 'Room 5')",
        "mail_item" to "INSERT INTO mail_item VALUES ('m1', 80, 0, 10, 'SYNCED', 'g1', 'a@b.c', 'Hello', 'snip', 3000)",
        "briefing" to "INSERT INTO briefing VALUES ('briefing:2026-11-01', 90, 0, 11, 'SYNCED', '2026-11-01', 'Good morning', 'qwen')",
        "kv" to "INSERT INTO kv VALUES ('cursor', '11')",
    )
    private val mealRow = "INSERT INTO meal VALUES ('meal1', 100, 0, 12, 'PENDING', 'MEAL', 'חומוס', 4000, '2026-11-01', 300, 10, 40, 12, 'qwen')"

    /** Frozen DDL of what 2.sqm added (version 3 era). Task gained goalId. */
    private val frozenV3Tables = listOf(
        """CREATE TABLE doc_folder (id TEXT NOT NULL PRIMARY KEY, updatedAt INTEGER NOT NULL, deleted INTEGER NOT NULL, serverSeq INTEGER NOT NULL DEFAULT 0, syncState TEXT NOT NULL, name TEXT NOT NULL)""",
        """CREATE TABLE doc (id TEXT NOT NULL PRIMARY KEY, updatedAt INTEGER NOT NULL, deleted INTEGER NOT NULL, serverSeq INTEGER NOT NULL DEFAULT 0, syncState TEXT NOT NULL, folderId TEXT, title TEXT NOT NULL, body TEXT NOT NULL, source TEXT NOT NULL)""",
        """CREATE TABLE ai_job (id TEXT NOT NULL PRIMARY KEY, updatedAt INTEGER NOT NULL, deleted INTEGER NOT NULL, serverSeq INTEGER NOT NULL DEFAULT 0, syncState TEXT NOT NULL, kind TEXT NOT NULL, inputJson TEXT NOT NULL, status TEXT NOT NULL, resultJson TEXT, error TEXT, attempts INTEGER NOT NULL, createdAt INTEGER NOT NULL, startedAt INTEGER)""",
        """CREATE TABLE dream (id TEXT NOT NULL PRIMARY KEY, updatedAt INTEGER NOT NULL, deleted INTEGER NOT NULL, serverSeq INTEGER NOT NULL DEFAULT 0, syncState TEXT NOT NULL, localDate TEXT NOT NULL, text TEXT NOT NULL, mood INTEGER NOT NULL, lucid INTEGER NOT NULL, tags TEXT NOT NULL, analysis TEXT, analysisModel TEXT)""",
        """CREATE TABLE dream_pattern (id TEXT NOT NULL PRIMARY KEY, updatedAt INTEGER NOT NULL, deleted INTEGER NOT NULL, serverSeq INTEGER NOT NULL DEFAULT 0, syncState TEXT NOT NULL, localDate TEXT NOT NULL, text TEXT NOT NULL, model TEXT NOT NULL)""",
        """CREATE TABLE goal (id TEXT NOT NULL PRIMARY KEY, updatedAt INTEGER NOT NULL, deleted INTEGER NOT NULL, serverSeq INTEGER NOT NULL DEFAULT 0, syncState TEXT NOT NULL, title TEXT NOT NULL, why TEXT, deadline TEXT, status TEXT NOT NULL)""",
    )
    private val frozenTaskV3 =
        """CREATE TABLE task (id TEXT NOT NULL PRIMARY KEY, updatedAt INTEGER NOT NULL, deleted INTEGER NOT NULL, serverSeq INTEGER NOT NULL DEFAULT 0, syncState TEXT NOT NULL, title TEXT NOT NULL, dueDate TEXT, done INTEGER NOT NULL, doneAt INTEGER, goalId TEXT)"""

    /** One row per version-3 table, plus a task linked to the goal. */
    private val frozenV3Rows = linkedMapOf(
        "task" to "INSERT INTO task VALUES ('t1', 40, 0, 6, 'PENDING', 'לקנות חלב', '2026-11-02', 0, NULL, 'goal1')",
        "doc_folder" to "INSERT INTO doc_folder VALUES ('docfolder:plans', 110, 0, 13, 'SYNCED', 'תוכניות')",
        "doc" to "INSERT INTO doc VALUES ('doc1', 120, 0, 14, 'PENDING', 'docfolder:plans', 'כותרת', 'גוף', 'USER')",
        "ai_job" to "INSERT INTO ai_job VALUES ('job1', 130, 0, 15, 'SYNCED', 'GOAL_STEPS', '{\"goalId\":\"goal1\"}', 'DONE', '{\"steps\":[]}', NULL, 1, 5000, 6000)",
        "dream" to "INSERT INTO dream VALUES ('dream1', 140, 0, 16, 'SYNCED', '2026-11-01', 'חלום', 3, 1, 'a,b', 'analysis', 'qwen')",
        "dream_pattern" to "INSERT INTO dream_pattern VALUES ('dp1', 150, 0, 17, 'SYNCED', '2026-11-01', 'pattern', 'qwen')",
        "goal" to "INSERT INTO goal VALUES ('goal1', 160, 0, 18, 'PENDING', 'יעד', 'why', '2026-12-01', 'ACTIVE')",
    )

    /** Frozen DDL of what 3.sqm added (version 4 era, as Phase 9 shipped it, course.enabled included). */
    private val frozenV4Tables = listOf(
        """CREATE TABLE course (id TEXT NOT NULL PRIMARY KEY, updatedAt INTEGER NOT NULL, deleted INTEGER NOT NULL, serverSeq INTEGER NOT NULL DEFAULT 0, syncState TEXT NOT NULL, moodleId INTEGER, name TEXT NOT NULL, enabled INTEGER NOT NULL DEFAULT 0)""",
        """CREATE TABLE study_file (id TEXT NOT NULL PRIMARY KEY, updatedAt INTEGER NOT NULL, deleted INTEGER NOT NULL, serverSeq INTEGER NOT NULL DEFAULT 0, syncState TEXT NOT NULL, courseId TEXT NOT NULL, name TEXT NOT NULL, origin TEXT NOT NULL, sizeBytes INTEGER NOT NULL, textStatus TEXT NOT NULL, studySetDocId TEXT)""",
        """CREATE TABLE card (id TEXT NOT NULL PRIMARY KEY, updatedAt INTEGER NOT NULL, deleted INTEGER NOT NULL, serverSeq INTEGER NOT NULL DEFAULT 0, syncState TEXT NOT NULL, fileId TEXT NOT NULL, front TEXT NOT NULL, back TEXT NOT NULL, lastResult TEXT, lastReviewedAt INTEGER)""",
        """CREATE TABLE quiz (id TEXT NOT NULL PRIMARY KEY, updatedAt INTEGER NOT NULL, deleted INTEGER NOT NULL, serverSeq INTEGER NOT NULL DEFAULT 0, syncState TEXT NOT NULL, courseId TEXT NOT NULL, questionsJson TEXT NOT NULL, score INTEGER, takenAt INTEGER)""",
        """CREATE TABLE exam (id TEXT NOT NULL PRIMARY KEY, updatedAt INTEGER NOT NULL, deleted INTEGER NOT NULL, serverSeq INTEGER NOT NULL DEFAULT 0, syncState TEXT NOT NULL, courseId TEXT, title TEXT NOT NULL, localDate TEXT NOT NULL, time TEXT)""",
    )

    /** One row per version-4 table; a pending row, a synced row and a tombstone are among them. */
    private val frozenV4Rows = listOf(
        "INSERT INTO course VALUES ('course:7', 170, 0, 19, 'SYNCED', 7, 'Algebra', 1)",
        "INSERT INTO study_file VALUES ('sfile:a', 180, 0, 20, 'SYNCED', 'course:7', 'week1.pdf', 'MOODLE', 1000, 'READY', 'doc:studyset:sfile:a')",
        "INSERT INTO card VALUES ('card:sfile:a:0', 190, 0, 21, 'PENDING', 'sfile:a', 'front', 'back', 'GOOD', 7000)",
        "INSERT INTO quiz VALUES ('quiz:1', 200, 0, 22, 'SYNCED', 'course:7', '[]', 80, 8000)",
        "INSERT INTO exam VALUES ('exam:1', 210, 0, 23, 'SYNCED', 'course:7', 'Final', '2026-12-01', '09:30')",
        "INSERT INTO exam VALUES ('exam:gone', 220, 1, 24, 'PENDING', NULL, 'Old', '2026-01-01', NULL)",
    )

    /** Frozen DDL of what 4.sqm added (version 5 era, as Phase 10 shipped it). */
    private val frozenV5Tables = listOf(
        """CREATE TABLE usage_session (id TEXT NOT NULL PRIMARY KEY, updatedAt INTEGER NOT NULL, deleted INTEGER NOT NULL, serverSeq INTEGER NOT NULL DEFAULT 0, syncState TEXT NOT NULL, device TEXT NOT NULL, app TEXT NOT NULL, title TEXT, startAt INTEGER NOT NULL, endAt INTEGER NOT NULL, localDate TEXT NOT NULL)""",
        """CREATE INDEX usage_session_localDate ON usage_session(localDate)""",
        """CREATE TABLE app_rule (id TEXT NOT NULL PRIMARY KEY, updatedAt INTEGER NOT NULL, deleted INTEGER NOT NULL, serverSeq INTEGER NOT NULL DEFAULT 0, syncState TEXT NOT NULL, matchType TEXT NOT NULL, pattern TEXT NOT NULL, label TEXT NOT NULL)""",
        """CREATE TABLE listen (id TEXT NOT NULL PRIMARY KEY, updatedAt INTEGER NOT NULL, deleted INTEGER NOT NULL, serverSeq INTEGER NOT NULL DEFAULT 0, syncState TEXT NOT NULL, trackName TEXT NOT NULL, artist TEXT NOT NULL, durationMs INTEGER NOT NULL, playedAt INTEGER NOT NULL, localDate TEXT NOT NULL)""",
    )

    /** One row per version-5 table; a pending row, a synced row and a tombstone are among them. */
    private val frozenV5Rows = listOf(
        "INSERT INTO usage_session VALUES ('use:laptop:1', 230, 0, 25, 'PENDING', 'LAPTOP', 'code.exe', 'title', 1000, 9000, '2026-11-01')",
        "INSERT INTO usage_session VALUES ('use:laptop:gone', 240, 1, 26, 'SYNCED', 'LAPTOP', 'old.exe', NULL, 10, 20, '2026-10-01')",
        "INSERT INTO app_rule VALUES ('rule:EXE:code.exe', 250, 0, 27, 'SYNCED', 'EXE', 'code.exe', 'PRODUCTIVE')",
        "INSERT INTO listen VALUES ('listen:1:t', 260, 0, 28, 'SYNCED', 'שיר', 'artist', 180000, 5000, '2026-11-01')",
    )

    /** Frozen DDL of what 5.sqm added (version 6 era, as Phase 12 shipped it). */
    private val frozenV6Tables = listOf(
        """CREATE TABLE mail_draft (id TEXT NOT NULL PRIMARY KEY, updatedAt INTEGER NOT NULL, deleted INTEGER NOT NULL, serverSeq INTEGER NOT NULL DEFAULT 0, syncState TEXT NOT NULL, toAddr TEXT NOT NULL, subject TEXT NOT NULL, body TEXT NOT NULL, replyToGmailId TEXT, threadId TEXT, status TEXT NOT NULL, sentAt INTEGER, error TEXT)""",
        """CREATE TABLE mail_action (id TEXT NOT NULL PRIMARY KEY, updatedAt INTEGER NOT NULL, deleted INTEGER NOT NULL, serverSeq INTEGER NOT NULL DEFAULT 0, syncState TEXT NOT NULL, gmailId TEXT NOT NULL, action TEXT NOT NULL, labelId TEXT, labelName TEXT, reason TEXT NOT NULL, status TEXT NOT NULL)""",
        """CREATE TABLE hw_check (id TEXT NOT NULL PRIMARY KEY, updatedAt INTEGER NOT NULL, deleted INTEGER NOT NULL, serverSeq INTEGER NOT NULL DEFAULT 0, syncState TEXT NOT NULL, takenAt INTEGER NOT NULL, resultJson TEXT NOT NULL, summary TEXT NOT NULL)""",
    )

    /** One or more rows per version-6 table, including the terminal mail states (SENT, FAILED, REJECTED, EXECUTED) and a tombstone. */
    private val frozenV6Rows = listOf(
        "INSERT INTO mail_draft VALUES ('maildraft:j1', 270, 0, 29, 'SYNCED', 'a@b.c', 'נושא', 'גוף', 'g1', 'th1', 'SENT', 9000, NULL)",
        "INSERT INTO mail_draft VALUES ('maildraft:j2', 280, 0, 30, 'PENDING', 'a@b.c', 's', 'b', NULL, NULL, 'FAILED', NULL, 'boom')",
        "INSERT INTO mail_draft VALUES ('maildraft:j3', 290, 1, 31, 'SYNCED', 'a@b.c', 's', 'b', NULL, NULL, 'DRAFT', NULL, NULL)",
        "INSERT INTO mail_action VALUES ('mailaction:j:0', 300, 0, 32, 'SYNCED', 'g1', 'ARCHIVE', NULL, NULL, 'r', 'EXECUTED')",
        "INSERT INTO mail_action VALUES ('mailaction:j:1', 310, 0, 33, 'PENDING', 'g2', 'LABEL', 'L1', 'Work', 'r', 'REJECTED')",
        "INSERT INTO hw_check VALUES ('hw:1', 320, 0, 34, 'SYNCED', 1000, '{}', 'ok')",
    )

    /** Frozen DDL of what 6.sqm added (version 7 era, as Phase 13 shipped it). */
    private val frozenV7Tables = listOf(
        """CREATE TABLE approval (id TEXT NOT NULL PRIMARY KEY, updatedAt INTEGER NOT NULL, deleted INTEGER NOT NULL, serverSeq INTEGER NOT NULL DEFAULT 0, syncState TEXT NOT NULL, tool TEXT NOT NULL, argsJson TEXT NOT NULL, summary TEXT NOT NULL, status TEXT NOT NULL, createdAt INTEGER NOT NULL, resultText TEXT)""",
        """CREATE TABLE research (id TEXT NOT NULL PRIMARY KEY, updatedAt INTEGER NOT NULL, deleted INTEGER NOT NULL, serverSeq INTEGER NOT NULL DEFAULT 0, syncState TEXT NOT NULL, category TEXT NOT NULL, questionKey TEXT NOT NULL, question TEXT NOT NULL, answerText TEXT NOT NULL, answerJson TEXT NOT NULL, sources TEXT NOT NULL, askedAt INTEGER NOT NULL, expiresAt INTEGER)""",
    )

    /** Rows per version-7 table: pending, decided, tombstone approvals and a research answer. */
    private val frozenV7Rows = listOf(
        "INSERT INTO approval VALUES ('approval:a1', 400, 0, 40, 'SYNCED', 'create_task', '{\"title\":\"x\"}', 'Add task x', 'DONE', 3000, 'ok')",
        "INSERT INTO approval VALUES ('approval:a2', 410, 0, 41, 'PENDING', 'create_event', '{}', 'Event', 'PENDING', 3100, NULL)",
        "INSERT INTO approval VALUES ('approval:a3', 420, 1, 42, 'SYNCED', 'x', '{}', 's', 'REJECTED', 3200, NULL)",
        "INSERT INTO research VALUES ('research:r1', 430, 0, 43, 'SYNCED', 'FOOD', 'k', 'q?', 'answer', '{}', '[]', 5000, 9000)",
    )

    private enum class Era(
        val version: Long, val hasMeal: Boolean, val hasDocs: Boolean = false, val hasStudy: Boolean = false, val hasUsage: Boolean = false,
        val hasMail: Boolean = false, val hasPhase13: Boolean = false,
    ) {
        V0(0, false), V1(1, false), V2(2, true), V3(3, true, true), V4(4, true, true, true), V5(5, true, true, true, true),
        V6(6, true, true, true, true, true), V7(7, true, true, true, true, true, true),
    }

    /** A database exactly as the apps shipped it in [era], with one row in every table of that era. */
    private fun frozen(name: String, era: Era): SqlDriver {
        val d = open(name)
        for (ddl in frozenBase) if (!(era.hasDocs && ddl.startsWith("CREATE TABLE task "))) d.execute(null, ddl, 0)
        if (era.hasDocs) {
            d.execute(null, frozenTaskV3, 0)
            for (ddl in frozenV3Tables) d.execute(null, ddl, 0)
        }
        if (era.hasMeal) d.execute(null, frozenMeal, 0)
        if (era.hasStudy) for (ddl in frozenV4Tables) d.execute(null, ddl, 0)
        if (era.hasUsage) for (ddl in frozenV5Tables) d.execute(null, ddl, 0)
        if (era.hasMail) for (ddl in frozenV6Tables) d.execute(null, ddl, 0)
        if (era.hasPhase13) for (ddl in frozenV7Tables) d.execute(null, ddl, 0)
        for ((t, insert) in frozenRows) if (!(era.hasDocs && t == "task")) d.execute(null, insert, 0)
        if (era.hasDocs) for (insert in frozenV3Rows.values) d.execute(null, insert, 0)
        if (era.hasMeal) d.execute(null, mealRow, 0)
        if (era.hasStudy) for (insert in frozenV4Rows) d.execute(null, insert, 0)
        if (era.hasUsage) for (insert in frozenV5Rows) d.execute(null, insert, 0)
        if (era.hasMail) for (insert in frozenV6Rows) d.execute(null, insert, 0)
        if (era.hasPhase13) for (insert in frozenV7Rows) d.execute(null, insert, 0)
        d.execute(null, "PRAGMA user_version = ${era.version}", 0)
        return d
    }

    private fun dataSnapshot(d: SqlDriver): Map<String, List<List<String?>>> {
        val out = linkedMapOf<String, List<List<String?>>>()
        for (t in frozenRows.keys + "meal") {
            if (t !in tables(d)) continue
            val cols = rows(d, "PRAGMA table_info($t)", 6).map { it[1]!! }
            val oldCols = if (t == "task") cols.filter { it != "goalId" } else cols
            out[t] = rows(d, "SELECT ${oldCols.joinToString(", ")} FROM $t ORDER BY 1", oldCols.size)
        }
        return out
    }

    /** Every column of every table that existed in the frozen era (task keeps goalId). */
    private fun fullSnapshot(d: SqlDriver, withStudy: Boolean = false, withUsage: Boolean = false, withMail: Boolean = false, withPhase13: Boolean = false): Map<String, List<List<String?>>> {
        val out = linkedMapOf<String, List<List<String?>>>()
        for (t in frozenRows.keys + "meal" + newTables + (if (withStudy) studyTables else emptyList()) + (if (withUsage) usageTables else emptyList()) + (if (withMail) mailTables else emptyList()) + (if (withPhase13) phase13Tables else emptyList())) {
            val cols = rows(d, "PRAGMA table_info($t)", 6).map { it[1]!! }
            out[t] = rows(d, "SELECT ${cols.joinToString(", ")} FROM $t ORDER BY 1", cols.size)
        }
        return out
    }

    @Test
    fun schema_version_is_seven() {
        assertEquals(8L, AssistantDb.Schema.version)
    }

    @Test
    fun a_new_file_gets_every_table_and_current_version() {
        val d = open("new.db")
        prepareSchema(d)
        assertEquals(everyTable, tables(d))
        assertEquals(AssistantDb.Schema.version, userVersion(d))
        d.close()
    }

    @Test
    fun every_frozen_era_upgrades_to_seven_with_rows_intact_and_goal_ids_kept() {
        for (era in Era.entries) {
            val d = frozen("era-${era.name}.db", era)
            val before = dataSnapshot(d)
            assertEquals(era.hasDocs, "doc" in tables(d) && "goal" in tables(d), era.name)
            assertEquals(era.hasStudy, studyTables.all { it in tables(d) }, era.name)
            assertEquals(era.hasUsage, usageTables.all { it in tables(d) }, era.name)
            assertEquals(era.hasMail, mailTables.all { it in tables(d) }, era.name)
            assertEquals(era.hasPhase13, phase13Tables.all { it in tables(d) }, era.name)
            assertTrue(chatTables.none { it in tables(d) }, era.name)
            prepareSchema(d)
            assertEquals(everyTable, tables(d), era.name)
            assertEquals(34, tables(d).size, era.name)
            assertEquals(8L, userVersion(d), era.name)
            assertEquals(before, dataSnapshot(d).filterKeys { it in before.keys }, era.name)
            val db = AssistantDb(d)
            val task = db.taskQueries.selectById("t1").executeAsOne()
            assertEquals(if (era.hasDocs) "goal1" else null, task.goalId, era.name)
            assertEquals("לקנות חלב", task.title, era.name)
            assertEquals(1, db.checkinQueries.selectAll().executeAsList().size, era.name)
            assertEquals(if (era.hasMeal) 1 else 0, db.mealQueries.selectAll().executeAsList().size, era.name)
            for (t in newTables) {
                val expected = if (era.hasDocs) 1 else 0
                assertEquals(expected, rows(d, "SELECT COUNT(*) FROM $t", 1).single().single()!!.toInt(), "$t in ${era.name}")
            }
            for (t in studyTables) {
                val expected = if (era.hasStudy) (if (t == "exam") 2 else 1) else 0
                assertEquals(expected, rows(d, "SELECT COUNT(*) FROM $t", 1).single().single()!!.toInt(), "$t in ${era.name}")
            }
            for (t in usageTables) {
                val expected = if (era.hasUsage) (if (t == "usage_session") 2 else 1) else 0
                assertEquals(expected, rows(d, "SELECT COUNT(*) FROM $t", 1).single().single()!!.toInt(), "$t in ${era.name}")
            }
            for (t in mailTables) {
                val expected = if (era.hasMail) (if (t == "hw_check") 1 else if (t == "mail_draft") 3 else 2) else 0
                assertEquals(expected, rows(d, "SELECT COUNT(*) FROM $t", 1).single().single()!!.toInt(), "$t in ${era.name}")
            }
            for (t in phase13Tables) {
                val expected = if (era.hasPhase13) (if (t == "approval") 3 else 1) else 0
                assertEquals(expected, rows(d, "SELECT COUNT(*) FROM $t", 1).single().single()!!.toInt(), "$t in ${era.name}")
            }
            for (t in chatTables) assertEquals(0, rows(d, "SELECT COUNT(*) FROM $t", 1).single().single()!!.toInt(), "$t in ${era.name}")
            d.close()
        }
    }

    @Test
    fun a_genuine_version_3_file_keeps_every_column_of_every_row_including_the_linked_task() {
        val d = frozen("v3.db", Era.V3)
        val before = fullSnapshot(d)
        assertEquals(1, before.getValue("goal").size)
        assertEquals("goal1", before.getValue("task").single().last())
        prepareSchema(d)
        assertEquals(before, fullSnapshot(d))
        val db = AssistantDb(d)
        assertEquals("doc1", db.docQueries.selectById("doc1").executeAsOne().id)
        assertEquals("יעד", db.goalQueries.selectById("goal1").executeAsOne().title)
        assertEquals("DONE", db.ai_jobQueries.selectById("job1").executeAsOne().status)
        assertEquals("חלום", db.dreamQueries.selectById("dream1").executeAsOne().text)
        // the new tables are usable and empty
        assertTrue(db.examQueries.selectAllActive().executeAsList().isEmpty())
        assertTrue(db.usage_sessionQueries.selectAll().executeAsList().isEmpty())
        d.close()
    }

    @Test
    fun a_genuine_version_4_file_keeps_every_column_of_all_22_tables_pending_rows_tombstones_and_kv() {
        val d = frozen("v4.db", Era.V4)
        assertEquals(22, tables(d).size)
        val before = fullSnapshot(d, withStudy = true)
        assertEquals(2, before.getValue("exam").size)
        assertEquals("1", before.getValue("course").single().last())
        val kvBefore = rows(d, "SELECT key, value FROM kv ORDER BY key", 2)
        val pendingBefore = rows(d, "SELECT id FROM card WHERE syncState = 'PENDING'", 1)
        prepareSchema(d)
        assertEquals(8L, userVersion(d))
        assertEquals(34, tables(d).size)
        assertEquals(before, fullSnapshot(d, withStudy = true))
        assertEquals(kvBefore, rows(d, "SELECT key, value FROM kv ORDER BY key", 2))
        assertEquals(pendingBefore, rows(d, "SELECT id FROM card WHERE syncState = 'PENDING'", 1))
        val db = AssistantDb(d)
        assertEquals(1L, db.examQueries.selectById("exam:gone").executeAsOne().deleted)
        assertEquals(1L, db.courseQueries.selectById("course:7").executeAsOne().enabled)
        assertEquals(1, db.examQueries.selectAllActive().executeAsList().size)
        d.close()
    }

    @Test
    fun usage_tables_have_the_spec_columns_index_and_queries() {
        val d = open("usage.db")
        prepareSchema(d)
        fun cols(t: String) = rows(d, "PRAGMA table_info($t)", 6).map { it[1]!! }
        val sync = listOf("id", "updatedAt", "deleted", "serverSeq", "syncState")
        assertEquals(sync + listOf("device", "app", "title", "startAt", "endAt", "localDate"), cols("usage_session"))
        assertEquals(sync + listOf("matchType", "pattern", "label"), cols("app_rule"))
        assertEquals(sync + listOf("trackName", "artist", "durationMs", "playedAt", "localDate"), cols("listen"))
        assertTrue(rows(d, "PRAGMA index_list(usage_session)", 3).any { it[1] == "usage_session_localDate" })
        val db = AssistantDb(d)
        fun use(id: String, date: String, start: Long, deleted: Long = 0) =
            db.usage_sessionQueries.upsert(dev.tim.assistant.db.Usage_session(id, 1, deleted, 0, "PENDING", "PHONE", "p", null, start, start + 10_000, date))
        use("b", "2026-11-02", 300)
        use("a", "2026-11-01", 200)
        use("c", "2026-11-03", 100)
        use("gone", "2026-11-02", 50, deleted = 1)
        use("out", "2026-11-09", 10)
        assertEquals(listOf("c", "a", "b"), db.usage_sessionQueries.selectByDateRange("2026-11-01", "2026-11-03").executeAsList().map { it.id })
        db.app_ruleQueries.upsert(dev.tim.assistant.db.App_rule("rule:EXE:b", 1, 0, 0, "PENDING", "EXE", "b", "NEUTRAL"))
        db.app_ruleQueries.upsert(dev.tim.assistant.db.App_rule("rule:EXE:a", 1, 0, 0, "PENDING", "EXE", "a", "NEUTRAL"))
        db.app_ruleQueries.upsert(dev.tim.assistant.db.App_rule("rule:EXE:x", 1, 1, 0, "PENDING", "EXE", "x", "NEUTRAL"))
        assertEquals(listOf("rule:EXE:a", "rule:EXE:b"), db.app_ruleQueries.selectAllActive().executeAsList().map { it.id })
        for (i in 1..3) db.listenQueries.upsert(dev.tim.assistant.db.Listen("listen:$i:t", 1, 0, i.toLong(), "SYNCED", "n$i", "ar", 1000, i * 1000L, "2026-11-01"))
        assertEquals(listOf("listen:3:t", "listen:2:t"), db.listenQueries.selectRecent(2).executeAsList().map { it.id })
        d.close()
    }

    @Test
    fun study_tables_have_the_spec_columns_and_selectAllActive_is_ordered_and_skips_deleted() {
        val d = open("study.db")
        prepareSchema(d)
        fun cols(t: String) = rows(d, "PRAGMA table_info($t)", 6).map { it[1]!! }
        val sync = listOf("id", "updatedAt", "deleted", "serverSeq", "syncState")
        assertEquals(sync + listOf("moodleId", "name", "enabled"), cols("course"))
        assertEquals(sync + listOf("courseId", "name", "origin", "sizeBytes", "textStatus", "studySetDocId"), cols("study_file"))
        assertEquals(sync + listOf("fileId", "front", "back", "lastResult", "lastReviewedAt"), cols("card"))
        assertEquals(sync + listOf("courseId", "questionsJson", "score", "takenAt"), cols("quiz"))
        assertEquals(sync + listOf("courseId", "title", "localDate", "time"), cols("exam"))
        val db = AssistantDb(d)
        fun exam(id: String, date: String, time: String?, deleted: Long = 0) =
            db.examQueries.upsert(dev.tim.assistant.db.Exam(id, 1, deleted, 0, "PENDING", null, id, date, time))
        exam("b", "2026-12-02", "09:00")
        exam("a", "2026-12-02", "09:00")
        exam("c", "2026-12-01", "14:00")
        exam("gone", "2026-11-30", null, deleted = 1)
        assertEquals(listOf("c", "a", "b"), db.examQueries.selectAllActive().executeAsList().map { it.id })
        d.close()
    }

    @Test
    fun migrated_task_and_new_tables_equal_fresh_ones_for_every_era() {
        val fresh = open("fresh.db")
        prepareSchema(fresh)
        for (era in Era.entries) {
            val migrated = frozen("cmp-${era.name}.db", era)
            prepareSchema(migrated)
            for (t in listOf("task", "meal") + newTables + studyTables + usageTables + mailTables + phase13Tables + chatTables) {
                val sql = "PRAGMA table_info($t)"
                assertEquals(rows(fresh, sql, 6), rows(migrated, sql, 6), "$t from ${era.name}")
            }
            assertEquals(rows(fresh, "PRAGMA index_list(usage_session)", 3), rows(migrated, "PRAGMA index_list(usage_session)", 3), era.name)
            assertEquals(10, rows(fresh, "PRAGMA table_info(task)", 6).size)
            assertEquals("goalId", rows(fresh, "PRAGMA table_info(task)", 6).last()[1])
            migrated.close()
        }
        fresh.close()
    }

    @Test
    fun running_prepareSchema_twice_changes_nothing() {
        val d = frozen("twice.db", Era.V1)
        prepareSchema(d)
        val before = schemaSql(d)
        val data = dataSnapshot(d)
        prepareSchema(d)
        assertEquals(before, schemaSql(d))
        assertEquals(data, dataSnapshot(d))
        assertEquals(8L, userVersion(d))
        d.close()
    }

    @Test
    fun a_file_already_at_the_current_version_is_left_unchanged() {
        val d = open("current.db")
        prepareSchema(d)
        val db = AssistantDb(d)
        db.spendingQueries.upsert(dev.tim.assistant.db.Spending("s9", 1, 0, 0, "PENDING", 5, "Fun", "x", "2026-11-02"))
        val before = schemaSql(d)
        prepareSchema(d)
        assertEquals(before, schemaSql(d))
        assertEquals(1, db.spendingQueries.selectAll().executeAsList().size)
        d.close()
    }

    @Test
    fun a_file_from_a_newer_app_is_refused_and_left_untouched() {
        val d = open("future.db")
        prepareSchema(d)
        d.execute(null, "PRAGMA user_version = 99", 0)
        val before = schemaSql(d)
        assertFailsWith<IllegalStateException> { prepareSchema(d) }
        assertEquals(99L, userVersion(d))
        assertEquals(before, schemaSql(d))
        d.close()
    }

    @Test
    fun a_failure_in_the_old_meal_step_rolls_back_everything() {
        val d = frozen("broken-meal.db", Era.V1)
        d.execute(null, "CREATE VIEW meal AS SELECT 1 AS x", 0)
        assertFailsWith<Throwable> { prepareSchema(d) }
        assertEquals(1L, userVersion(d))
        assertTrue("meal" !in tables(d))
        assertTrue("doc" !in tables(d))
        assertEquals(1, AssistantDb(d).checkinQueries.selectAll().executeAsList().size)
        d.close()
    }

    @Test
    fun a_failure_halfway_through_3_sqm_rolls_back_every_study_table_and_a_retry_succeeds() {
        val d = frozen("broken-v4.db", Era.V3)
        // exam is created last, so a leftover view named exam fails the upgrade after four study tables exist.
        d.execute(null, "CREATE VIEW exam AS SELECT 1 AS x", 0)
        val before = fullSnapshot(d)
        assertFailsWith<Throwable> { prepareSchema(d) }
        assertEquals(3L, userVersion(d))
        for (t in studyTables) assertTrue(t !in tables(d), t)
        assertEquals(before, fullSnapshot(d))
        d.execute(null, "DROP VIEW exam", 0)
        prepareSchema(d)
        assertEquals(8L, userVersion(d))
        assertEquals(everyTable, tables(d))
        assertEquals(before, fullSnapshot(d))
        d.close()
    }

    @Test
    fun a_failure_halfway_through_4_sqm_rolls_back_every_usage_table_and_a_retry_succeeds() {
        val d = frozen("broken-v5.db", Era.V4)
        // listen is created last, so a leftover view named listen fails the upgrade after two usage tables exist.
        d.execute(null, "CREATE VIEW listen AS SELECT 1 AS x", 0)
        val before = fullSnapshot(d, withStudy = true)
        assertFailsWith<Throwable> { prepareSchema(d) }
        assertEquals(4L, userVersion(d))
        for (t in usageTables) assertTrue(t !in tables(d), t)
        assertTrue(rows(d, "SELECT name FROM sqlite_master WHERE name = 'usage_session_localDate'", 1).isEmpty())
        assertEquals(before, fullSnapshot(d, withStudy = true))
        d.execute(null, "DROP VIEW listen", 0)
        prepareSchema(d)
        assertEquals(8L, userVersion(d))
        assertEquals(everyTable, tables(d))
        assertEquals(before, fullSnapshot(d, withStudy = true))
        d.close()
    }

    @Test
    fun a_genuine_version_5_file_keeps_every_column_of_all_25_tables_pending_rows_tombstones_and_kv() {
        val d = frozen("v5.db", Era.V5)
        assertEquals(25, tables(d).size)
        val before = fullSnapshot(d, withStudy = true, withUsage = true)
        assertEquals(2, before.getValue("usage_session").size)
        val kvBefore = rows(d, "SELECT key, value FROM kv ORDER BY key", 2)
        val pendingBefore = rows(d, "SELECT id FROM usage_session WHERE syncState = 'PENDING'", 1)
        prepareSchema(d)
        assertEquals(8L, userVersion(d))
        assertEquals(34, tables(d).size)
        assertEquals(before, fullSnapshot(d, withStudy = true, withUsage = true))
        assertEquals(kvBefore, rows(d, "SELECT key, value FROM kv ORDER BY key", 2))
        assertEquals(pendingBefore, rows(d, "SELECT id FROM usage_session WHERE syncState = 'PENDING'", 1))
        assertTrue(rows(d, "PRAGMA index_list(usage_session)", 3).any { it[1] == "usage_session_localDate" })
        val db = AssistantDb(d)
        assertEquals(1L, db.usage_sessionQueries.selectById("use:laptop:gone").executeAsOne().deleted)
        assertEquals("שיר", db.listenQueries.selectById("listen:1:t").executeAsOne().trackName)
        for (t in mailTables) assertEquals(0, rows(d, "SELECT COUNT(*) FROM $t", 1).single().single()!!.toInt(), t)
        d.close()
    }

    @Test
    fun a_failure_halfway_through_5_sqm_rolls_back_every_mail_and_hardware_table_and_a_retry_succeeds() {
        val d = frozen("broken-v6.db", Era.V5)
        // hw_check is created last, so a leftover view named hw_check fails the upgrade after two tables exist.
        d.execute(null, "CREATE VIEW hw_check AS SELECT 1 AS x", 0)
        val before = fullSnapshot(d, withStudy = true, withUsage = true)
        assertFailsWith<Throwable> { prepareSchema(d) }
        assertEquals(5L, userVersion(d))
        assertTrue("mail_draft" !in tables(d))
        assertTrue("mail_action" !in tables(d))
        assertEquals(before, fullSnapshot(d, withStudy = true, withUsage = true))
        d.execute(null, "DROP VIEW hw_check", 0)
        prepareSchema(d)
        assertEquals(8L, userVersion(d))
        assertEquals(everyTable, tables(d))
        assertEquals(before, fullSnapshot(d, withStudy = true, withUsage = true))
        d.close()
    }

    @Test
    fun mail_and_hardware_tables_have_the_contract_columns_and_queries() {
        val d = open("mail.db")
        prepareSchema(d)
        fun cols(t: String) = rows(d, "PRAGMA table_info($t)", 6).map { it[1]!! }
        val sync = listOf("id", "updatedAt", "deleted", "serverSeq", "syncState")
        assertEquals(sync + listOf("toAddr", "subject", "body", "replyToGmailId", "threadId", "status", "sentAt", "error"), cols("mail_draft"))
        assertEquals(sync + listOf("gmailId", "action", "labelId", "labelName", "reason", "status"), cols("mail_action"))
        assertEquals(sync + listOf("takenAt", "resultJson", "summary"), cols("hw_check"))
        val db = AssistantDb(d)
        fun draft(id: String, status: String, deleted: Long = 0) =
            db.mail_draftQueries.upsert(dev.tim.assistant.db.Mail_draft(id, 1, deleted, 0, "PENDING", "a@b.c", "s", "b", null, null, status, null, null))
        draft("d1", "DRAFT")
        draft("d2", "SENT")
        draft("d3", "DRAFT", deleted = 1)
        assertEquals(listOf("d1"), db.mail_draftQueries.selectByStatus("DRAFT").executeAsList().map { it.id })
        db.mail_actionQueries.upsert(dev.tim.assistant.db.Mail_action("mailaction:j:0", 1, 0, 0, "PENDING", "g1", "ARCHIVE", null, null, "r", "SUGGESTED"))
        assertEquals(1, db.mail_actionQueries.selectByStatus("SUGGESTED").executeAsList().size)
        assertTrue(db.mail_actionQueries.selectByStatus("APPROVED").executeAsList().isEmpty())
        db.hw_checkQueries.upsert(dev.tim.assistant.db.Hw_check("hw:1", 1, 0, 7, "SYNCED", 1, "{}", "ok"))
        assertEquals(listOf("hw:1"), db.hw_checkQueries.selectChangedSince(0).executeAsList().map { it.id })
        d.close()
    }

    /**
     * Migration-on-copy of the installed laptop database. Runs only when ASSISTANT_COPY_TEST is set: to a file path,
     * or to "1" for the installed database (%LOCALAPPDATA%\Assistant\assistant.db). The original is never opened for writing.
     */
    @Test
    fun the_installed_laptop_database_copy_upgrades_with_every_row_kept() {
        val flag = System.getenv("ASSISTANT_COPY_TEST")?.takeIf { it.isNotBlank() } ?: return
        val source = if (flag == "1") DriverFactory().databaseFile() else File(flag)
        if (!source.exists()) return
        val target = File(dir, "installed-copy.db")
        source.copyTo(target, overwrite = true)
        for (suffix in listOf("-wal", "-shm")) File(source.path + suffix).takeIf { it.exists() }?.copyTo(File(target.path + suffix), overwrite = true)
        val d = open("installed-copy.db")
        val before = tables(d).associateWith { rows(d, "SELECT COUNT(*) FROM $it", 1).single().single() }
        prepareSchema(d)
        assertEquals(8L, userVersion(d))
        assertEquals(everyTable, tables(d))
        for ((t, count) in before) assertEquals(count, rows(d, "SELECT COUNT(*) FROM $t", 1).single().single(), t)
        for (t in chatTables) {
            assertTrue(t in tables(d), t)
            assertEquals(before[t] ?: "0", rows(d, "SELECT COUNT(*) FROM $t", 1).single().single(), t)
        }
        d.close()
    }

    @Test
    fun reopening_a_version_7_file_keeps_study_and_usage_rows() {
        val first = open("reopen.db")
        prepareSchema(first)
        AssistantDb(first).examQueries.upsert(dev.tim.assistant.db.Exam("exam:1", 5, 0, 2, "SYNCED", "course:1", "Final", "2026-12-01", "09:30"))
        AssistantDb(first).usage_sessionQueries.upsert(dev.tim.assistant.db.Usage_session("use:laptop:1", 6, 0, 3, "SYNCED", "LAPTOP", "code.exe", "t", 1, 9000, "2026-11-01"))
        first.close()
        val second = open("reopen.db")
        prepareSchema(second)
        assertEquals(8L, userVersion(second))
        assertEquals(3L, AssistantDb(second).usage_sessionQueries.selectById("use:laptop:1").executeAsOne().serverSeq)
        val exam = AssistantDb(second).examQueries.selectById("exam:1").executeAsOne()
        assertEquals("09:30", exam.time)
        assertEquals(2L, exam.serverSeq)
        second.close()
    }

    @Test
    fun a_genuine_version_6_file_keeps_every_column_of_all_28_tables_mail_states_tombstones_and_kv() {
        val d = frozen("v6.db", Era.V6)
        assertEquals(28, tables(d).size)
        val before = fullSnapshot(d, withStudy = true, withUsage = true, withMail = true)
        assertEquals(3, before.getValue("mail_draft").size)
        val kvBefore = rows(d, "SELECT key, value FROM kv ORDER BY key", 2)
        val pendingBefore = rows(d, "SELECT id FROM mail_action WHERE syncState = 'PENDING'", 1)
        prepareSchema(d)
        assertEquals(8L, userVersion(d))
        assertEquals(34, tables(d).size)
        assertEquals(everyTable, tables(d))
        assertEquals(before, fullSnapshot(d, withStudy = true, withUsage = true, withMail = true))
        assertEquals(kvBefore, rows(d, "SELECT key, value FROM kv ORDER BY key", 2))
        assertEquals(pendingBefore, rows(d, "SELECT id FROM mail_action WHERE syncState = 'PENDING'", 1))
        val db = AssistantDb(d)
        assertEquals("SENT", db.mail_draftQueries.selectById("maildraft:j1").executeAsOne().status)
        assertEquals("boom", db.mail_draftQueries.selectById("maildraft:j2").executeAsOne().error)
        assertEquals(1L, db.mail_draftQueries.selectById("maildraft:j3").executeAsOne().deleted)
        assertEquals("EXECUTED", db.mail_actionQueries.selectById("mailaction:j:0").executeAsOne().status)
        for (t in phase13Tables) assertEquals(0, rows(d, "SELECT COUNT(*) FROM $t", 1).single().single()!!.toInt(), t)
        d.close()
    }

    @Test
    fun a_failure_halfway_through_6_sqm_rolls_back_both_tables_and_a_retry_succeeds() {
        val d = frozen("broken-v7.db", Era.V6)
        // research is created last, so a leftover view named research fails the upgrade after approval exists.
        d.execute(null, "CREATE VIEW research AS SELECT 1 AS x", 0)
        val before = fullSnapshot(d, withStudy = true, withUsage = true, withMail = true)
        assertFailsWith<Throwable> { prepareSchema(d) }
        assertEquals(6L, userVersion(d))
        assertTrue("approval" !in tables(d))
        assertEquals(before, fullSnapshot(d, withStudy = true, withUsage = true, withMail = true))
        d.execute(null, "DROP VIEW research", 0)
        prepareSchema(d)
        assertEquals(8L, userVersion(d))
        assertEquals(everyTable, tables(d))
        assertEquals(before, fullSnapshot(d, withStudy = true, withUsage = true, withMail = true))
        d.close()
    }

    @Test
    fun approval_and_research_tables_have_the_contract_columns_and_queries() {
        val d = open("p13.db")
        prepareSchema(d)
        fun cols(t: String) = rows(d, "PRAGMA table_info($t)", 6).map { it[1]!! }
        val sync = listOf("id", "updatedAt", "deleted", "serverSeq", "syncState")
        assertEquals(sync + listOf("tool", "argsJson", "summary", "status", "createdAt", "resultText"), cols("approval"))
        assertEquals(
            sync + listOf("category", "questionKey", "question", "answerText", "answerJson", "sources", "askedAt", "expiresAt"),
            cols("research"),
        )
        val db = AssistantDb(d)
        fun appr(id: String, status: String, deleted: Long = 0) =
            db.approvalQueries.upsert(dev.tim.assistant.db.Approval(id, 1, deleted, 0, "PENDING", "create_task", "{}", "s", status, 5, null))
        appr("a1", "PENDING")
        appr("a2", "DONE")
        appr("a3", "PENDING", deleted = 1)
        assertEquals(listOf("a1"), db.approvalQueries.selectByStatus("PENDING").executeAsList().map { it.id })
        fun res(id: String, q: String, answer: String, expiresAt: Long?, deleted: Long = 0) =
            db.researchQueries.upsert(
                dev.tim.assistant.db.Research(id, 1, deleted, 0, "PENDING", "OTHER", q.lowercase(), q, answer, "{}", "[]", 10, expiresAt),
            )
        res("r1", "Hummus kcal", "about 170", null)
        res("r2", "Rice", "about 130", 1000)
        res("r3", "Gone", "x", null, deleted = 1)
        assertEquals("r1", db.researchQueries.selectValid("r1", 999_999).executeAsOne().id)
        assertEquals("r2", db.researchQueries.selectValid("r2", 999).executeAsOne().id)
        assertNull(db.researchQueries.selectValid("r2", 1000).executeAsOneOrNull())
        assertNull(db.researchQueries.selectValid("r3", 0).executeAsOneOrNull())
        assertEquals(listOf("r1"), db.researchQueries.search("hummus").executeAsList().map { it.id })
        assertEquals(listOf("r2"), db.researchQueries.search("130").executeAsList().map { it.id })
        d.close()
    }

    @Test
    fun a_failure_halfway_through_2_sqm_rolls_back_every_new_table_and_goal_id() {
        val d = frozen("broken-v3.db", Era.V2)
        // doc_folder is created before doc, so a leftover view named doc fails the upgrade halfway through.
        d.execute(null, "CREATE VIEW doc AS SELECT 1 AS x", 0)
        val before = dataSnapshot(d)
        assertFailsWith<Throwable> { prepareSchema(d) }
        assertEquals(2L, userVersion(d))
        for (t in newTables.filter { it != "doc" }) assertTrue(t !in tables(d), t)
        assertEquals(9, rows(d, "PRAGMA table_info(task)", 6).size)
        assertEquals(before, dataSnapshot(d))
        d.close()
    }
    @Test
    fun a_genuine_version_7_file_keeps_every_column_of_all_30_tables_and_gets_the_four_chat_tables() {
        val d = frozen("v7.db", Era.V7)
        assertEquals(30, tables(d).size)
        val before = fullSnapshot(d, withStudy = true, withUsage = true, withMail = true, withPhase13 = true)
        assertEquals(3, before.getValue("approval").size)
        val kvBefore = rows(d, "SELECT key, value FROM kv ORDER BY key", 2)
        val pendingBefore = rows(d, "SELECT id FROM approval WHERE syncState = 'PENDING'", 1)
        prepareSchema(d)
        assertEquals(8L, userVersion(d))
        assertEquals(34, tables(d).size)
        assertEquals(everyTable, tables(d))
        assertEquals(before, fullSnapshot(d, withStudy = true, withUsage = true, withMail = true, withPhase13 = true))
        assertEquals(kvBefore, rows(d, "SELECT key, value FROM kv ORDER BY key", 2))
        assertEquals(pendingBefore, rows(d, "SELECT id FROM approval WHERE syncState = 'PENDING'", 1))
        val db = AssistantDb(d)
        assertEquals("DONE", db.approvalQueries.selectById("approval:a1").executeAsOne().status)
        assertEquals("answer", db.researchQueries.selectById("research:r1").executeAsOne().answerText)
        for (t in chatTables) assertEquals(0, rows(d, "SELECT COUNT(*) FROM $t", 1).single().single()!!.toInt(), t)
        // the chat tables are usable and private (no sync columns)
        db.chatQueries.insertChat("c1", "Hello", 1, 1, 0)
        db.chatQueries.insertChat("c1", "Again", 2, 2, 0)
        assertEquals("Hello", db.chatQueries.selectChatById("c1").executeAsOne().title)
        db.chatQueries.insertNotification("IDEA", "t", "b", null, 5, 5, "k1")
        db.chatQueries.insertNotification("IDEA", "t2", "b2", null, 6, 6, "k1")
        assertEquals(1, db.chatQueries.selectNotificationsSince(0, 10).executeAsList().size)
        for (t in chatTables) assertTrue(rows(d, "PRAGMA table_info($t)", 6).none { it[1] == "serverSeq" || it[1] == "syncState" }, t)
        d.close()
    }}
