package dev.tim.assistant.server.google

/** Thread-safe snapshot of the last Google poll result; /v1/health reads it. Starts as unknown (null, null). */
class GoogleStatus {
    private val lock = Any()
    private var ok: Boolean? = null
    private var lastFetchAt: Long? = null

    fun snapshot(): Pair<Boolean?, Long?> = synchronized(lock) { ok to lastFetchAt }

    fun update(ok: Boolean?, lastFetchAt: Long?) = synchronized(lock) {
        this.ok = ok
        this.lastFetchAt = lastFetchAt
    }
}
