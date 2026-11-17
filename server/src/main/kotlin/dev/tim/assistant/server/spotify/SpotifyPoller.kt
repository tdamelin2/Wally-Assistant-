package dev.tim.assistant.server.spotify

import dev.tim.assistant.db.Listen
import dev.tim.assistant.model.toSyncRow
import dev.tim.assistant.server.ServerDb
import dev.tim.assistant.server.SyncStore
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.sync.withLock
import org.slf4j.LoggerFactory

const val SPOTIFY_INTERVAL_MS = 30L * 60 * 1000
const val SPOTIFY_CURSOR_KEY = "spotify:after"

/**
 * One Spotify round: all HTTP first (outside the write lock), then one locked transaction that writes
 * only plays that are new or changed (a replay never bumps serverSeq) together with the cursor. Any
 * 429, 5xx, network or database error commits nothing and leaves the cursor and status as they were.
 * Logs carry counts and error classes only. [client] is null when the config has no spotify block.
 */
class SpotifyPoller(
    private val sdb: ServerDb,
    private val store: SyncStore,
    private val client: SpotifyClient?,
    private val auth: SpotifyAuth?,
    private val status: SpotifyStatus,
    private val clock: () -> Long,
) {
    private val log = LoggerFactory.getLogger(SpotifyPoller::class.java)
    private val db get() = sdb.db

    suspend fun run() {
        if (client == null || auth == null || !auth.hasToken()) {
            status.set(null)
            return
        }
        // a token written by spotify-auth is picked up without a restart: start from the file again
        if (status.get() != true) auth.invalidate()
        val now = clock()
        val cursor = db.kvQueries.get(SPOTIFY_CURSOR_KEY).executeAsOneOrNull()?.toLongOrNull()
        val result = try {
            client.recentlyPlayed(cursor)
        } catch (e: CancellationException) {
            throw e
        } catch (e: SpotifyAuthException) {
            status.set(false)
            log.warn("spotify poll auth failure: {}", e.message)
            return
        } catch (e: Exception) {
            log.warn("spotify poll skipped, retrying next round: {}", e::class.simpleName)
            return
        }

        var written = 0
        try {
            sdb.writeLock.withLock {
                db.transaction {
                    var maxPlayedAt = cursor ?: Long.MIN_VALUE
                    for (play in result.plays.sortedBy { it.playedAt }) {
                        val stored = db.listenQueries.selectById(play.id).executeAsOneOrNull()
                        val row = toSyncRow(
                            Listen(
                                id = play.id, updatedAt = 0, deleted = 0, serverSeq = 0, syncState = "SYNCED",
                                trackName = play.trackName, artist = play.artist, durationMs = play.durationMs,
                                playedAt = play.playedAt, localDate = play.localDate,
                            ),
                        )
                        if (stored != null && stored.deleted == 0L && toSyncRow(stored).data == row.data) continue
                        val stamp = if (stored == null) now else maxOf(now, stored.updatedAt + 1)
                        if (store.writeIfNewer(row.copy(updatedAt = stamp))) {
                            written++
                            if (play.playedAt > maxPlayedAt) maxPlayedAt = play.playedAt
                        }
                    }
                    if (written > 0 && maxPlayedAt != cursor) {
                        db.kvQueries.put(SPOTIFY_CURSOR_KEY, maxPlayedAt.toString())
                    }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn("spotify poll could not store plays, retrying next round: {}", e::class.simpleName)
            return
        }
        status.set(true)
        log.info("spotify poll ok: plays={} written={} malformed={}", result.plays.size, written, result.malformed)
    }
}
