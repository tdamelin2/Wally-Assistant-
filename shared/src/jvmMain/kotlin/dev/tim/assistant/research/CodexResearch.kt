package dev.tim.assistant.research

import dev.tim.assistant.ai.jobs.JobFailedNow
import java.io.File
import java.io.IOException
import java.math.BigDecimal
import java.math.RoundingMode
import java.net.URI
import java.nio.file.Files
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Five minutes for one Codex run. */
const val CODEX_TIMEOUT_MS = 5L * 60 * 1000

/** What one finished Codex process left behind. A process that could not be started throws [IOException] instead. */
data class CodexProcessResult(val exitCode: Int, val stdout: String, val stderr: String, val timedOut: Boolean = false)

/** Starts [executable] with [args] (an argument vector, never a shell line) in [workDir] and feeds [stdin] to it. */
fun interface CodexProcess {
    suspend fun run(executable: String, args: List<String>, workDir: File, stdin: String?, timeoutMs: Long): CodexProcessResult
}

private const val NPM_NATIVE_EXE =
    "node_modules/@openai/codex/node_modules/@openai/codex-win32-x64/vendor/x86_64-pc-windows-msvc/bin/codex.exe"

/**
 * The Codex executable to start, found at run time: next to a `codex.cmd` on PATH the native
 * `codex.exe` the npm package ships, else a `codex.exe` straight on PATH. A `.cmd` shim is never returned.
 */
fun findCodexExecutable(pathEntries: List<File> = pathEntriesFromEnv()): File? {
    for (dir in pathEntries) {
        if (File(dir, "codex.cmd").isFile) {
            val native = File(dir, NPM_NATIVE_EXE)
            if (native.isFile) return native
        }
    }
    return pathEntries.map { File(it, "codex.exe") }.firstOrNull { it.isFile }
}

private fun pathEntriesFromEnv(): List<File> =
    (System.getenv("PATH") ?: "").split(File.pathSeparatorChar).filter { it.isNotBlank() }.map { File(it.trim().trim('"')) }

/** Runs research questions through the Codex CLI. [findExecutable] returns the executable path or null. */
class CodexResearch(
    private val process: CodexProcess,
    private val findExecutable: () -> String? = { findCodexExecutable()?.path },
    private val tempRoot: File? = null,
    private val timeoutMs: Long = CODEX_TIMEOUT_MS,
) : ResearchRunner {

    override suspend fun ask(category: String, question: String): ResearchRow {
        val schema = schemaFor(category)
        val exe = findExecutable() ?: throw JobFailedNow(CODEX_UNAVAILABLE_TEXT)
        val workDir = newTempDir("wally-codex-work-")
        val ioDir = newTempDir("wally-codex-io-")
        try {
            val schemaFile = File(ioDir, "schema.json").also { it.writeText(schema, Charsets.UTF_8) }
            val outFile = File(ioDir, "out.json")
            val result = try {
                process.run(exe, codexArgs(workDir, schemaFile, outFile), workDir, question, timeoutMs)
            } catch (e: CancellationException) {
                throw e
            } catch (e: IOException) {
                throw JobFailedNow(CODEX_UNAVAILABLE_TEXT)
            }
            val output = if (outFile.isFile) outFile.readText(Charsets.UTF_8) else ""
            if (!result.timedOut && result.exitCode == 0) {
                parseCodexOutput(category, question, output)?.let { return it }
            }
            // the run failed or its answer is unusable: now look at the texts for a limit or a login problem
            val texts = listOf(result.stdout, result.stderr, output).joinToString("\n")
            if (isCodexLimitText(texts)) throw JobFailedNow(CODEX_LIMIT_TEXT)
            if (result.timedOut) throw IllegalStateException("Codex timed out")
            if (result.exitCode != 0) {
                val auth = listOf(result.stdout, result.stderr).joinToString("\n").lowercase()
                if (AUTH_WORDS.any { it in auth }) throw JobFailedNow(CODEX_UNAVAILABLE_TEXT)
                throw IllegalStateException("Codex failed (exit ${result.exitCode})")
            }
            throw IllegalStateException("bad Codex output")
        } finally {
            workDir.deleteRecursively()
            ioDir.deleteRecursively()
        }
    }

    private fun newTempDir(prefix: String): File {
        val dir = if (tempRoot == null) Files.createTempDirectory(prefix) else Files.createTempDirectory(tempRoot.toPath(), prefix)
        return dir.toFile()
    }

    private companion object {
        val AUTH_WORDS = listOf("not logged in", "log in", "login", "sign in", "authentication", "unauthorized")
    }
}

/** The argument vector after the executable (the question goes to stdin through the last `-`). */
fun codexArgs(workDir: File, schemaFile: File, outFile: File): List<String> = listOf(
    "exec", "--skip-git-repo-check", "--ephemeral", "-s", "read-only",
    "-c", "web_search=\"live\"", "-c", "model_reasoning_effort=\"low\"",
    "--ignore-user-config", "--ignore-rules",
    "-C", workDir.absolutePath, "--output-schema", schemaFile.absolutePath, "-o", outFile.absolutePath, "-",
)

private const val SOURCES_SCHEMA = """"sources":{"type":"array","items":{"type":"string"}}"""

/** The JSON schema (object, every property required, no extra properties) for [category]. */
fun schemaFor(category: String): String = when (category) {
    RESEARCH_NUTRITION ->
        """{"type":"object","properties":{"kcal":{"type":"number"},"proteinG":{"type":"number"},"carbsG":{"type":"number"},""" +
            """"fatG":{"type":"number"},$SOURCES_SCHEMA},"required":["kcal","proteinG","carbsG","fatG","sources"],"additionalProperties":false}"""
    RESEARCH_DEFINITION ->
        """{"type":"object","properties":{"definition":{"type":"string"},$SOURCES_SCHEMA},"required":["definition","sources"],"additionalProperties":false}"""
    RESEARCH_APP_CATEGORY ->
        """{"type":"object","properties":{"category":{"type":"string"},"summary":{"type":"string"},$SOURCES_SCHEMA},""" +
            """"required":["category","summary","sources"],"additionalProperties":false}"""
    RESEARCH_OTHER ->
        """{"type":"object","properties":{"answer":{"type":"string"},$SOURCES_SCHEMA},"required":["answer","sources"],"additionalProperties":false}"""
    else -> throw JobFailedNow("bad job input")
}

private const val MAX_ANSWER_CHARS = 20_000
private const val MAX_SOURCES = 10
private const val MAX_URL_CHARS = 500
private const val MAX_ANSWER_JSON_CHARS = 50_000

/**
 * Validates Codex's output file text for [category]. Returns null when it is not usable: not a JSON object, a
 * missing or mistyped field, a number that is negative or not finite, or an empty text. Source entries that are
 * not http(s) URLs are dropped; at most 10 are kept.
 */
fun parseCodexOutput(category: String, question: String, text: String): ResearchRow? {
    val obj = try {
        Json.parseToJsonElement(text.trim()) as? JsonObject
    } catch (e: Exception) {
        null
    } ?: return null
    val sources = cleanSources(obj["sources"]) ?: return null
    val sourcesJson = buildJsonArray { sources.forEach { add(JsonPrimitive(it)) } }
    val answerText: String
    val answerJson: JsonObject
    when (category) {
        RESEARCH_NUTRITION -> {
            val kcal = number(obj["kcal"]) ?: return null
            val protein = number(obj["proteinG"]) ?: return null
            val carbs = number(obj["carbsG"]) ?: return null
            val fat = number(obj["fatG"]) ?: return null
            answerText = "Per 100 g: ${fmt(kcal)} kcal, protein ${fmt(protein)} g, carbs ${fmt(carbs)} g, fat ${fmt(fat)} g"
            answerJson = buildJsonObject {
                put("kcal", kcal); put("proteinG", protein); put("carbsG", carbs); put("fatG", fat)
                put("sources", sourcesJson)
            }
        }
        RESEARCH_DEFINITION -> {
            val definition = words(obj["definition"]) ?: return null
            answerText = definition
            answerJson = buildJsonObject { put("definition", definition); put("sources", sourcesJson) }
        }
        RESEARCH_APP_CATEGORY -> {
            val cat = words(obj["category"]) ?: return null
            val summary = words(obj["summary"]) ?: return null
            answerText = "$cat: $summary"
            answerJson = buildJsonObject { put("category", cat); put("summary", summary); put("sources", sourcesJson) }
        }
        RESEARCH_OTHER -> {
            val answer = words(obj["answer"]) ?: return null
            answerText = answer
            answerJson = buildJsonObject { put("answer", answer); put("sources", sourcesJson) }
        }
        else -> return null
    }
    val json = answerJson.toString()
    if (json.length > MAX_ANSWER_JSON_CHARS) return null
    return ResearchRow(category, question, answerText.take(MAX_ANSWER_CHARS), json, sources)
}

/** A finite, non-negative JSON number (a string like "5" is refused), or null. */
private fun number(e: JsonElement?): Double? {
    val p = e as? JsonPrimitive ?: return null
    if (p.isString) return null
    val v = p.content.toDoubleOrNull() ?: return null
    return if (v.isFinite() && v >= 0.0) v else null
}

private fun words(e: JsonElement?): String? {
    val p = e as? JsonPrimitive ?: return null
    if (!p.isString) return null
    return p.content.trim().takeIf { it.isNotEmpty() }?.take(MAX_ANSWER_CHARS)
}

/** The http(s) URLs of a string array (null when it is not an array of strings), deduplicated, at most 10. */
private fun cleanSources(e: JsonElement?): List<String>? {
    val arr = e as? JsonArray ?: return null
    val out = LinkedHashSet<String>()
    for (item in arr) {
        val p = item as? JsonPrimitive ?: return null
        if (!p.isString) return null
        val url = p.content.trim()
        if (url.length > MAX_URL_CHARS) continue
        val uri = try {
            URI(url)
        } catch (ex: Exception) {
            continue
        }
        if ((uri.scheme == "http" || uri.scheme == "https") && !uri.host.isNullOrEmpty()) out += url
        if (out.size >= MAX_SOURCES) break
    }
    return out.toList()
}

private fun fmt(v: Double): String = BigDecimal(v).setScale(2, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()
