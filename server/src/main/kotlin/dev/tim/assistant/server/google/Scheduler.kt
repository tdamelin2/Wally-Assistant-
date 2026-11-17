package dev.tim.assistant.server.google

import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory

const val POLL_INTERVAL_MS = 15L * 60 * 1000

/**
 * One sequential loop (so rounds never overlap): run at start, then every [intervalMs]. [name] only
 * appears in the failure log line.
 */
class Scheduler(
    private val name: String = "google poll",
    private val intervalMs: Long = POLL_INTERVAL_MS,
    private val action: suspend () -> Unit,
) {
    private val log = LoggerFactory.getLogger(Scheduler::class.java)

    fun start(scope: CoroutineScope): Job = scope.launch {
        while (isActive) {
            try {
                action()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.warn("{} failed, retrying next round: {}", name, e::class.simpleName)
            }
            delay(intervalMs)
        }
    }
}
