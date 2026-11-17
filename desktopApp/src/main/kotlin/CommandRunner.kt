import dev.tim.assistant.hardware.CommandResult
import dev.tim.assistant.hardware.CommandRunner
import java.io.InputStream
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext

/**
 * Runs one fixed program with a fixed argument list through ProcessBuilder (no shell). stdin is closed at once, stdout and
 * stderr are drained at the same time and kept up to [maxBytes] each (the rest is read and dropped so the program never
 * blocks), the process is killed after [timeoutMs], and stdout and stderr are decoded as UTF-8.
 */
class ProcessCommandRunner(
    private val timeoutMs: Long = 30_000,
    private val maxBytes: Int = 1_000_000,
) : CommandRunner {
    override suspend fun run(executable: String, args: List<String>): CommandResult = withContext(Dispatchers.IO) {
        val process = ProcessBuilder(listOf(executable) + args).start()
        try {
            process.outputStream.close()
            coroutineScope {
                val out = async { readBounded(process.inputStream) }
                val err = async { readBounded(process.errorStream) }
                val finished = runInterruptible { process.waitFor(timeoutMs, TimeUnit.MILLISECONDS) }
                if (!finished) process.destroyForcibly()
                CommandResult(
                    exitCode = if (finished) process.exitValue() else -1,
                    stdout = out.await(),
                    stderr = err.await(),
                    timedOut = !finished,
                )
            }
        } finally {
            if (process.isAlive) process.destroyForcibly()
        }
    }

    private fun readBounded(stream: InputStream): String {
        val kept = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(8 * 1024)
        stream.use { input ->
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                val room = maxBytes - kept.size()
                if (room > 0) kept.write(buffer, 0, minOf(n, room))
            }
        }
        return kept.toString(Charsets.UTF_8)
    }
}
