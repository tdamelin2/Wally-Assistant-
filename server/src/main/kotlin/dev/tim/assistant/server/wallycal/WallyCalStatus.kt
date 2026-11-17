package dev.tim.assistant.server.wallycal

import java.util.concurrent.atomic.AtomicReference

/** Result of the last WallyCalendar poll for /v1/health: null until the first poll finishes. */
class WallyCalStatus {
    private val value = AtomicReference<Boolean?>(null)

    fun get(): Boolean? = value.get()

    fun set(ok: Boolean?) = value.set(ok)
}
