package dev.tim.assistant.server.chat

import dev.tim.assistant.model.SyncRow
import dev.tim.assistant.server.SyncStore
import dev.tim.assistant.server.obj
import dev.tim.assistant.server.openDb
import dev.tim.assistant.server.tempDbPath
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

class ServerWriterTest {
    private val sdb = openDb(tempDbPath())
    private val store = SyncStore(sdb)
    private var now = 5_000L
    private val writer = ServerWriter(sdb, store) { now }

    private fun approval(id: String) = SyncRow(
        "approval", id, 1, false,
        obj("tool" to "create_task", "argsJson" to "{}", "summary" to "Add task", "status" to "PENDING", "createdAt" to 1, "resultText" to null),
    )

    private fun task(id: String, title: String = "Buy milk") =
        SyncRow("task", id, 1, false, obj("title" to title, "dueDate" to null, "done" to false, "doneAt" to null))

    @Test
    fun approvalAndTaskRowsAppearInAPull() = runBlocking {
        writer.write(approval("approval:a1"))
        writer.write(task("task:t1"))
        val rows = store.pull(0).rows
        assertTrue(rows.any { it.table == "approval" && it.id == "approval:a1" })
        assertTrue(rows.any { it.table == "task" && it.id == "task:t1" })
        assertTrue(rows.all { it.updatedAt == 5_000L })
    }

    @Test
    fun updatedAtNeverGoesBackwards() = runBlocking {
        writer.write(task("task:t1", "one"))
        writer.write(task("task:t1", "two")) // same clock tick: stored + 1
        now = 4_000L // clock moved back
        writer.write(task("task:t1", "three"))
        val row = store.pull(0).rows.single { it.id == "task:t1" }
        assertEquals(5_002L, row.updatedAt)
        assertEquals("three", (row.data["title"] as kotlinx.serialization.json.JsonPrimitive).content)
    }

    @Test
    fun aFailureRollsBackSyncedAndPrivateWrites() = runBlocking {
        val seqBefore = sdb.db.kvQueries.get("seqCounter").executeAsOneOrNull()
        assertFailsWith<IllegalStateException> {
            writer.transaction { tx ->
                tx.write(task("task:t1"))
                tx.db.kvQueries.put("chat.private", "x")
                error("boom")
            }
        }
        assertTrue(store.pull(0).rows.isEmpty())
        assertNull(sdb.db.kvQueries.get("chat.private").executeAsOneOrNull())
        assertEquals(seqBefore, sdb.db.kvQueries.get("seqCounter").executeAsOneOrNull())
    }

    @Test
    fun syncedAndPrivateWritesShareOneTransaction() = runBlocking {
        writer.transaction { tx ->
            tx.write(task("task:t1"))
            tx.write(approval("approval:a1"))
            tx.db.kvQueries.put("chat.private", "x")
        }
        assertEquals(2, store.pull(0).rows.size)
        assertEquals("x", sdb.db.kvQueries.get("chat.private").executeAsOneOrNull())
    }
}
