package dev.tim.assistant.research

import java.io.File
import java.io.InputStream
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext

/**
 * Runs one program through ProcessBuilder as an argument vector (no shell) in an explicit working directory.
 * [stdin] is written as UTF-8 and the stream is closed; stdout and stderr are drained at the same time and kept up
 * to [maxBytes] each (the rest is read and dropped so the program never blocks). After [timeoutMs], and when the
 * caller is cancelled, the process and all its descendants are killed. A program that cannot be started throws
 * java.io.IOException.
 */
open class ProcessCodexProcess(private val maxBytes: Int = 1_000_000) : CodexProcess {
    override suspend fun run(
        executable: String, args: List<String>, workDir: File, stdin: String?, timeoutMs: Long,
    ): CodexProcessResult = withContext(Dispatchers.IO) {
        val builder = ProcessBuilder(listOf(executable) + args).directory(workDir)
        val process = builder.start()
        try {
            coroutineScope {
                val writer = async {
                    try {
                        process.outputStream.use { if (stdin != null) it.write(stdin.toByteArray(Charsets.UTF_8)) }
                    } catch (e: java.io.IOException) {
                        // the program may close its input early; that is not an error here
                    }
                }
                val out = async { readBounded(process.inputStream) }
                val err = async { readBounded(process.errorStream) }
                val finished = try {
                    runInterruptible { process.waitFor(timeoutMs, TimeUnit.MILLISECONDS) }
                } catch (e: Throwable) {
                    // cancelled: the readers only end when the process is gone, so kill it before the scope waits for them
                    killTree(process)
                    throw e
                }
                if (!finished) killTree(process)
                writer.await()
                CodexProcessResult(
                    exitCode = if (finished) process.exitValue() else -1,
                    stdout = out.await(),
                    stderr = err.await(),
                    timedOut = !finished,
                )
            }
        } finally {
            killTree(process)
        }
    }

    private fun killTree(process: Process) {
        process.descendants().forEach { it.destroyForcibly() }
        if (process.isAlive) process.destroyForcibly()
    }

    private fun readBounded(stream: InputStream): String {
        val kept = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(8 * 1024)
        stream.use { input ->
            while (true) {
                val n = try {
                    input.read(buffer)
                } catch (e: java.io.IOException) {
                    -1
                }
                if (n < 0) break
                val room = maxBytes - kept.size()
                if (room > 0) kept.write(buffer, 0, minOf(n, room))
            }
        }
        return kept.toString(Charsets.UTF_8)
    }
}
