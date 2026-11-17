package dev.tim.assistant.server.chat

import dev.tim.assistant.db.AssistantDb
import dev.tim.assistant.model.SyncRow
import dev.tim.assistant.server.ServerDb
import dev.tim.assistant.server.SyncStore
import kotlinx.coroutines.sync.withLock

/** A synced write that did not land (the stored row was not older); the whole transaction rolls back. */
class WriteFailedException(message: String) : Exception(message)

/**
 * The one entry for chat-side writes: one writeLock and one SQL transaction. Inside it, synced rows go
 * through [Tx.write] (SyncStore.writeIfNewer, so clients get them at the next pull) and private chat
 * tables are changed through [Tx.db] in the same transaction. Never take writeLock inside the block.
 */
class ServerWriter(
    private val sdb: ServerDb,
    private val store: SyncStore,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    class Tx internal constructor(val db: AssistantDb, private val store: SyncStore, val now: Long) {
        /**
         * Writes [row] with updatedAt = max(now, stored + 1), so the write always wins over what is stored
         * and the stamp never goes backwards. The caller's own updatedAt is ignored. A false writeIfNewer
         * throws [WriteFailedException] (it is a failure, never a silent success).
         */
        fun write(row: SyncRow) {
            val stored = store.storedUpdatedAt(row.table, row.id)
            val stamp = if (stored == null) now else maxOf(now, stored + 1)
            if (!store.writeIfNewer(row.copy(updatedAt = stamp))) {
                throw WriteFailedException("row ${row.table}/${row.id} was not written")
            }
        }
    }

    /** Runs [block] under writeLock in one transaction; an exception rolls everything back. */
    suspend fun <T> transaction(block: (Tx) -> T): T = sdb.writeLock.withLock {
        sdb.db.transactionWithResult { block(Tx(sdb.db, store, clock())) }
    }

    /** Writes one synced row in its own transaction. */
    suspend fun write(row: SyncRow) {
        transaction { it.write(row) }
    }
}
