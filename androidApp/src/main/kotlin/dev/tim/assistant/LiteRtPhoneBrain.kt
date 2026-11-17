package dev.tim.assistant

import android.content.Context
import android.util.Log
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.ExperimentalApi
import com.google.ai.edge.litertlm.ExperimentalFlags
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.MessageCallback
import com.google.ai.edge.litertlm.ResponseFormat
import com.google.ai.edge.litertlm.SpeculativeDecodingConfig
import com.google.ai.edge.litertlm.SpeculativeDecodingMode
import dev.tim.assistant.ai.PhoneBrain
import dev.tim.assistant.ai.PhoneBrainResult
import dev.tim.assistant.ai.PhoneBrainStatus
import java.io.File
import java.util.concurrent.Executors
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject

/**
 * The phone's own language model (LiteRT-LM, the Gemma file Tim pushed to the phone). One warm [Engine] is created on first
 * use (GPU, else CPU); every [generate] runs in a fresh clone of a system-prefilled base [Conversation]. Everything native happens on one thread this class owns,
 * one generation at a time. The engine is released after [IDLE_UNLOAD_MS] of silence and on [unload].
 */
@OptIn(ExperimentalApi::class)
@Suppress("TooManyFunctions")
class LiteRtPhoneBrain(
    private val context: Context,
    private val speculative: Boolean = false,
    private val clone: Boolean = false,
    private val maxNumTokens: Int? = null,
    private val collectStats: Boolean = false,
) : PhoneBrain {
    private val dispatcher = Executors.newSingleThreadExecutor { r -> Thread(r, "phone-brain").apply { isDaemon = true } }
        .asCoroutineDispatcher()
    private val gate = Mutex()
    private val timers = CoroutineScope(Dispatchers.Default)

    // Touched only on [dispatcher] while [gate] is held.
    private var engine: Engine? = null
    private var conversation: Conversation? = null

    // The base holds only the system text (prefilled once); every request runs in a clone of it and nothing a request adds
    // survives the clone (dream wall: no history carried between requests). sendMessage is never called on the base.
    private var base: Conversation? = null
    private var baseSystem: String? = null
    private var cloneBroken = false

    @Volatile private var idleJob: Job? = null
    @Volatile private var unloadWhenFree = false
    @Volatile private var cpuOnly = false

    /** Eval only: benchmark numbers of the last finished request (null until one finished or if the library gave none). */
    @Volatile var lastStats: String? = null
        private set
    private var openMs = 0L
    /** "GPU" or "CPU" once the engine exists (kept after it is released, so the harness can still read it); null before. */
    @Volatile var backend: String? = null
        private set

    private fun modelFile(): File? {
        val dirs = listOfNotNull(context.getExternalFilesDir(null), context.filesDir).map { File(it, "models") }
        for (d in dirs) {
            val f = d.listFiles { x -> x.isFile && x.name.endsWith(".litertlm") }?.minByOrNull { it.name }
            if (f != null) return f
        }
        return null
    }

    override fun status(): PhoneBrainStatus {
        val f = modelFile() ?: return PhoneBrainStatus(null, null)
        return PhoneBrainStatus(f.name, f.length())
    }

    override suspend fun generate(
        system: String,
        user: String,
        schema: JsonObject,
        onPartial: (String) -> Unit,
    ): PhoneBrainResult {
        val file = modelFile() ?: return PhoneBrainResult(null, null, null)
        idleJob?.cancel()
        try {
            return gate.withLock {
                try {
                    withContext(dispatcher) { run(file, system, user, schema, onPartial) }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    Log.w(TAG, "generation failed: ${e.javaClass.simpleName}: ${e.message}")
                    PhoneBrainResult(null, null, null)
                } finally {
                    withContext(NonCancellable + dispatcher) {
                        if (unloadWhenFree) closeAll() else closeConversation()
                    }
                }
            }
        } finally {
            scheduleIdleUnload()
        }
    }

    /** Runs on [dispatcher] with [gate] held. Cancellation stops the native call and waits for it before returning. */
    private suspend fun run(file: File, system: String, user: String, schema: JsonObject, onPartial: (String) -> Unit): PhoneBrainResult {
        val started = System.nanoTime()
        val eng = engine ?: createEngine(file)
        val openStart = System.nanoTime()
        val conv = openConversation(eng, system)
        openMs = (System.nanoTime() - openStart) / 1_000_000
        conversation = conv

        val text = StringBuilder()
        var firstNs = 0L
        var lastNs = 0L
        var chunks = 0
        val finished = CompletableDeferred<Throwable?>()
        val callback = object : MessageCallback {
            override fun onMessage(message: Message) {
                val piece = message.contents.contents.filterIsInstance<Content.Text>().joinToString("") { it.text }
                if (piece.isEmpty()) return
                val now = System.nanoTime()
                if (chunks == 0) firstNs = now
                lastNs = now
                chunks++
                text.append(piece)
                onPartial(text.toString())
            }

            override fun onDone() {
                finished.complete(null)
            }

            override fun onError(throwable: Throwable) {
                finished.complete(throwable)
            }
        }
        try {
            val error = try {
                conv.sendMessageAsync(Message.user(user), callback, responseFormat = ResponseFormat.json(schema.toString()))
                finished.await()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                e
            }
            if (error != null) {
                if (chunks == 0 && backend == "GPU" && isAcceleratorFailure(error)) {
                    // The GPU path failed before any text arrived: rebuild on the CPU once and retry; CPU stays for this process.
                    Log.w(TAG, "GPU generate failed (${error.javaClass.simpleName}: ${error.message}); retrying on CPU")
                    cpuOnly = true
                    closeAll()
                    return run(file, system, user, schema, onPartial)
                }
                throw error
            }
        } catch (e: CancellationException) {
            // Stop the native generation, then wait until it really has stopped before the conversation is closed.
            withContext(NonCancellable) {
                conv.cancelProcess()
                val stopped = withTimeoutOrNull(STOP_WAIT_MS) { finished.await() }
                if (stopped == null && !finished.isCompleted) {
                    Log.w(TAG, "native generation did not stop in time; releasing the engine")
                    closeAll()
                }
            }
            throw e
        }
        lastStats = if (!collectStats) null else runCatching {
            val b = conv.getBenchmarkInfo()
            "prefillTok=${b.lastPrefillTokenCount} prefillTps=${"%.0f".format(b.lastPrefillTokensPerSecond)} " +
                "ttft=${b.timeToFirstTokenInSecond} ctx=${b.maxNumTokens} maxTok=$maxNumTokens openMs=$openMs"
        }.getOrNull()
        val tps = if (chunks >= 2 && lastNs > firstNs) (chunks - 1) / ((lastNs - firstNs) / 1e9) else null
        val firstMs = if (chunks > 0) (firstNs - started) / 1_000_000 else null
        return PhoneBrainResult(text.toString(), firstMs, tps, backend)
    }

    private fun openConversation(eng: Engine, system: String): Conversation {
        if (clone && !cloneBroken) {
            try {
                var b = base
                if (b == null || baseSystem != system) {
                    closeBase()
                    b = eng.createConversation(
                        ConversationConfig(
                            systemInstruction = Contents.of(system),
                            enableResponseFormat = true,
                            prefillPrefaceOnInit = true,
                            maxOutputToken = MAX_OUTPUT_TOKENS,
                        ),
                    )
                    base = b
                    baseSystem = system
                }
                return b.clone()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                Log.w(TAG, "prefilled base/clone failed (${e.javaClass.simpleName}: ${e.message}); using a fresh conversation per request")
                cloneBroken = true
                closeBase()
            }
        }
        return eng.createConversation(ConversationConfig(systemInstruction = Contents.of(system), enableResponseFormat = true, maxOutputToken = MAX_OUTPUT_TOKENS))
    }

    private fun closeBase() {
        val b = base
        base = null
        baseSystem = null
        if (b != null) runCatching { b.close() }
    }

    private fun newEngine(file: File, backend: Backend, cache: String, spec: Boolean): Engine {
        if (collectStats) ExperimentalFlags.enableBenchmark = true
        val config = if (spec) {
            EngineConfig(
                file.path, backend, maxNumTokens = maxNumTokens, cacheDir = cache,
                speculativeDecodingConfig = SpeculativeDecodingConfig(SpeculativeDecodingMode.ON),
            )
        } else {
            EngineConfig(file.path, backend, maxNumTokens = maxNumTokens, cacheDir = cache)
        }
        val e = Engine(config)
        try {
            e.initialize()
        } catch (t: Throwable) {
            runCatching { e.close() }
            throw t
        }
        return e
    }

    /** Builds an engine on [backend]; with the speculative switch on, a failed init is retried once without it. */
    private fun buildEngine(file: File, backend: Backend, name: String, cache: String): Engine {
        val t0 = System.nanoTime()
        var spec = speculative
        val e = try {
            newEngine(file, backend, cache, spec)
        } catch (t: Throwable) {
            if (!spec) throw t
            Log.w(TAG, "speculative decoding failed (${t.javaClass.simpleName}: ${t.message}); retrying without it")
            spec = false
            newEngine(file, backend, cache, false)
        }
        Log.i(TAG, "engine init backend=$name ms=${(System.nanoTime() - t0) / 1_000_000} spec=${if (spec) "on" else "off"}")
        return e
    }

    private fun createEngine(file: File): Engine {
        val cache = File(context.cacheDir, "litertlm").apply { mkdirs() }.path
        if (!cpuOnly) try {
            val gpu = buildEngine(file, Backend.GPU(), "GPU", cache)
            backend = "GPU"
            engine = gpu
            return gpu
        } catch (e: Throwable) {
            Log.w(TAG, "GPU backend failed (${e.javaClass.simpleName}: ${e.message}); using CPU")
        }
        val cpu = buildEngine(file, Backend.CPU(), "CPU", cache)
        backend = "CPU"
        engine = cpu
        return cpu
    }

    private fun closeConversation() {
        val c = conversation ?: return
        conversation = null
        runCatching { c.close() }
    }

    /** Only accelerator or backend errors justify the one-time switch to CPU; e.g. an over-long prompt does not. */
    private fun isAcceleratorFailure(t: Throwable): Boolean {
        val text = "${t.javaClass.simpleName} ${t.message.orEmpty()}".lowercase()
        return listOf("opencl", "gpu", "webgpu", "delegate", "accelerator").any { it in text }
    }

    private fun closeAll() {
        closeConversation()
        closeBase()
        val e = engine ?: return
        engine = null
        runCatching { e.close() }
    }

    private fun scheduleIdleUnload() {
        idleJob?.cancel()
        idleJob = timers.launch {
            delay(IDLE_UNLOAD_MS)
            unloadNow()
        }
    }

    /** Releases the conversation and engine now if nothing is generating; a busy engine is released as soon as it is free. */
    private suspend fun unloadNow() {
        if (!gate.tryLock()) {
            unloadWhenFree = true
            return
        }
        try {
            withContext(dispatcher) { closeAll() }
            unloadWhenFree = false
        } finally {
            gate.unlock()
        }
    }

    /** Waits until the engine is released (or marked for release after the running generation). Used by the eval harness. */
    suspend fun unloadAndWait() {
        idleJob?.cancel()
        gate.withLock {
            withContext(dispatcher) { closeAll() }
            unloadWhenFree = false
        }
    }

    override fun prewarm() {
        val file = modelFile() ?: return
        idleJob?.cancel()
        timers.launch {
            try {
                // The gate makes a generate wait for this init (and this wait for a running generate): never a second init.
                gate.withLock {
                    withContext(dispatcher) { if (engine == null) createEngine(file) }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                Log.w(TAG, "prewarm failed: ${e.javaClass.simpleName}: ${e.message}")
            } finally {
                scheduleIdleUnload()
            }
        }
    }

    override fun unload() {
        idleJob?.cancel()
        timers.launch { unloadNow() }
    }

    private companion object {
        const val TAG = "PhoneBrain"
        const val IDLE_UNLOAD_MS = 180_000L
        const val STOP_WAIT_MS = 15_000L
        const val MAX_OUTPUT_TOKENS = 512
    }
}
