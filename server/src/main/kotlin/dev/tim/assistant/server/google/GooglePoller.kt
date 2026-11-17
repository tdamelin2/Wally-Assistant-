package dev.tim.assistant.server.google

import dev.tim.assistant.db.Mail_item
import dev.tim.assistant.model.toSyncRow
import dev.tim.assistant.server.ServerDb
import dev.tim.assistant.server.SyncStore
import kotlinx.coroutines.sync.withLock
import org.slf4j.LoggerFactory

const val DAY_MS = 24L * 60 * 60 * 1000
const val MAIL_WINDOW_DAYS = 30L

/**
 * One Google round: every HTTP call first (outside the write lock), then a single locked
 * transaction that re-reads the stored rows and writes only what changed. Logs carry counts and
 * error classes only.
 */
class GooglePoller(
    private val sdb: ServerDb,
    private val store: SyncStore,
    private val api: GoogleApi,
    private val status: GoogleStatus,
    private val clock: () -> Long,
) {
    private val log = LoggerFactory.getLogger(GooglePoller::class.java)
    private val db get() = sdb.db

    private class Counts {
        var mailWritten = 0
        var mailDeleted = 0
        var expired = 0
    }

    suspend fun run() {
        val now = clock()
        val errors = ArrayList<String>()
        val unread: ApiResult<String>
        val headers = HashMap<String, MailHeaders>()
        try {
            unread = api.unreadMessageIds()
            unread.failure?.let { errors += it }
            for (id in unread.items) {
                if (db.mail_itemQueries.selectById(mailRowId(id)).executeAsOneOrNull() != null) continue
                val h = api.messageHeaders(id)
                h.failure?.let { errors += it }
                h.items.firstOrNull()?.let { headers[id] = it }
            }
        } catch (e: GoogleAuthException) {
            api.invalidateAuth()
            status.update(false, status.snapshot().second)
            log.warn("google poll auth failure: {}", e::class.simpleName)
            return
        }

        val counts = Counts()
        sdb.writeLock.withLock {
            db.transaction {
                reconcileMail(unread, headers, now, counts)
                expire(now, counts)
            }
        }

        val capped = !unread.complete && unread.failure == null
        if (capped) log.warn("google unread mail list reached the {} id cap; no mail removed this round", MAX_UNREAD_IDS)
        val summary = "mailWritten=${counts.mailWritten} " +
            "mailDeleted=${counts.mailDeleted} expired=${counts.expired}"
        if (errors.isEmpty()) {
            status.update(true, now)
            log.info("google poll ok: {}", summary)
        } else {
            log.warn("google poll incomplete: errors={} {}", errors.joinToString(","), summary)
        }
    }

    private fun stamp(now: Long, storedUpdatedAt: Long?): Long =
        if (storedUpdatedAt == null) now else maxOf(now, storedUpdatedAt + 1)

    private fun reconcileMail(unread: ApiResult<String>, headers: Map<String, MailHeaders>, now: Long, counts: Counts) {
        val cutoff = now - MAIL_WINDOW_DAYS * DAY_MS
        for (gmailId in unread.items) {
            val id = mailRowId(gmailId)
            val stored = db.mail_itemQueries.selectById(id).executeAsOneOrNull()
            if (stored == null) {
                val h = headers[gmailId] ?: continue
                val item = Mail_item(
                    id = id, updatedAt = 0, deleted = 0, serverSeq = 0, syncState = "SYNCED",
                    gmailId = gmailId, sender = h.sender, subject = h.subject, snippet = h.snippet,
                    receivedAt = h.internalDate,
                )
                store.writeIfNewer(toSyncRow(item).copy(updatedAt = now))
                counts.mailWritten++
            } else if (stored.deleted != 0L && stored.receivedAt >= cutoff) {
                store.writeIfNewer(toSyncRow(stored).copy(deleted = false, updatedAt = stamp(now, stored.updatedAt)))
                counts.mailWritten++
            }
        }
        if (unread.complete && unread.failure == null) {
            val present = unread.items.toHashSet()
            for (stored in db.mail_itemQueries.selectAll().executeAsList()) {
                if (stored.deleted != 0L || stored.gmailId in present) continue
                store.writeIfNewer(toSyncRow(stored).copy(deleted = true, updatedAt = stamp(now, stored.updatedAt)))
                counts.mailDeleted++
            }
        }
    }

    /** Old rows are soft-deleted (tombstones stay) so clients drop them on their next pull. */
    private fun expire(now: Long, counts: Counts) {
        val cutoff = now - MAIL_WINDOW_DAYS * DAY_MS
        for (stored in db.mail_itemQueries.selectAll().executeAsList()) {
            if (stored.deleted == 0L && stored.receivedAt < cutoff) {
                store.writeIfNewer(toSyncRow(stored).copy(deleted = true, updatedAt = stamp(now, stored.updatedAt)))
                counts.expired++
            }
        }
    }

    private fun mailRowId(gmailId: String) = "mail:$gmailId"
}
