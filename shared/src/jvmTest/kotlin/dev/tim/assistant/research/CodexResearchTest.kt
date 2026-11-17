package dev.tim.assistant.research

import dev.tim.assistant.ai.jobs.JobFailedNow
import java.io.File
import java.io.IOException
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Records the call and lets a script write the `-o` file and return a result, like the real Codex would. */
private class FakeCodex(
    var script: (args: List<String>) -> CodexProcessResult = { CodexProcessResult(0, "", "") },
    var outText: String? = null,
) : CodexProcess {
    var executable: String? = null
    var args: List<String> = emptyList()
    var workDir: File? = null
    var workDirEntriesAtStart: Int = -1
    var workDirExistedAtStart = false
    var stdin: String? = null
    var timeoutMs = 0L
    var schemaAtStart: String? = null
    var calls = 0

    override suspend fun run(executable: String, args: List<String>, workDir: File, stdin: String?, timeoutMs: Long): CodexProcessResult {
        calls++
        this.executable = executable
        this.args = args
        this.workDir = workDir
        workDirExistedAtStart = workDir.isDirectory
        workDirEntriesAtStart = workDir.list()?.size ?: -1
        this.stdin = stdin
        this.timeoutMs = timeoutMs
        schemaAtStart = File(args[args.indexOf("--output-schema") + 1]).readText()
        outText?.let { File(args[args.indexOf("-o") + 1]).writeText(it) }
        return script(args)
    }
}

class CodexResearchTest {
    private val tmp: File = Files.createTempDirectory("codex-research-test").toFile()

    private fun research(fake: CodexProcess, exe: String? = "C:/codex/codex.exe") =
        CodexResearch(fake, findExecutable = { exe }, tempRoot = tmp, timeoutMs = 1234)

    private val nutrition = """{"kcal":166,"proteinG":7.9,"carbsG":14.29,"fatG":9.6,"sources":["https://example.org/h","https://example.org/i"]}"""
    private val question = "Nutrition facts per 100 g of hummus. Return kcal, proteinG, carbsG, fatG and source URLs."

    @Test
    fun theCommandLineIsExact() = runBlocking<Unit> {
        val fake = FakeCodex(outText = nutrition)
        val row = research(fake).ask(RESEARCH_NUTRITION, question)
        assertEquals("C:/codex/codex.exe", fake.executable)
        val a = fake.args
        val workDir = fake.workDir!!.absolutePath
        val schema = a[a.indexOf("--output-schema") + 1]
        val out = a[a.indexOf("-o") + 1]
        assertEquals(
            listOf(
                "exec", "--skip-git-repo-check", "--ephemeral", "-s", "read-only",
                "-c", "web_search=\"live\"", "-c", "model_reasoning_effort=\"low\"",
                "--ignore-user-config", "--ignore-rules",
                "-C", workDir, "--output-schema", schema, "-o", out, "-",
            ),
            a,
        )
        assertTrue(schema.endsWith("schema.json") && out.endsWith("out.json"))
        // the question goes through stdin only, never as an argument
        assertEquals(question, fake.stdin)
        assertTrue(a.none { it.contains("Nutrition facts") })
        assertEquals(1234, fake.timeoutMs)
        assertEquals(RESEARCH_NUTRITION, row.category)
    }

    @Test
    fun theWorkingDirectoryIsNewAndEmptyAndSeparateFromTheSchemaFiles() = runBlocking<Unit> {
        val fake = FakeCodex(outText = nutrition)
        research(fake).ask(RESEARCH_NUTRITION, question)
        assertTrue(fake.workDirExistedAtStart)
        assertEquals(0, fake.workDirEntriesAtStart)
        val schemaFile = File(fake.args[fake.args.indexOf("--output-schema") + 1])
        assertTrue(schemaFile.parentFile.absolutePath != fake.workDir!!.absolutePath)
        assertEquals(schemaFor(RESEARCH_NUTRITION), fake.schemaAtStart)
    }

    @Test
    fun ownedTempDirsAreDeletedAfterSuccessFailureAndCancellation() = runBlocking<Unit> {
        research(FakeCodex(outText = nutrition)).ask(RESEARCH_NUTRITION, question)
        assertEquals(0, tmp.list()!!.size)
        assertFailsWith<IllegalStateException> { research(FakeCodex(script = { CodexProcessResult(1, "", "boom") })).ask(RESEARCH_NUTRITION, question) }
        assertEquals(0, tmp.list()!!.size)
        class Cancelling : CodexProcess {
            override suspend fun run(executable: String, args: List<String>, workDir: File, stdin: String?, timeoutMs: Long): CodexProcessResult =
                throw CancellationException("stop")
        }
        assertFailsWith<CancellationException> { research(Cancelling()).ask(RESEARCH_NUTRITION, question) }
        assertEquals(0, tmp.list()!!.size)
    }

    @Test
    fun everyCategoryHasAStrictObjectSchema() {
        for (c in RESEARCH_CATEGORIES) {
            val s = Json.parseToJsonElement(schemaFor(c)).jsonObject
            assertEquals("object", s["type"]!!.jsonPrimitive.content)
            assertEquals(false, s["additionalProperties"]!!.jsonPrimitive.boolean)
            val props = s["properties"]!!.jsonObject.keys
            assertEquals(props, (s["required"] as JsonArray).map { it.jsonPrimitive.content }.toSet())
            assertTrue("sources" in props)
        }
        assertEquals(setOf("kcal", "proteinG", "carbsG", "fatG", "sources"), Json.parseToJsonElement(schemaFor(RESEARCH_NUTRITION)).jsonObject["properties"]!!.jsonObject.keys)
    }

    @Test
    fun validNutritionOutput() {
        val row = parseCodexOutput(RESEARCH_NUTRITION, question, nutrition)!!
        assertEquals("Per 100 g: 166 kcal, protein 7.9 g, carbs 14.29 g, fat 9.6 g", row.answerText)
        assertEquals(listOf("https://example.org/h", "https://example.org/i"), row.sources)
        val json = Json.parseToJsonElement(row.answerJson).jsonObject
        assertEquals(166.0, json["kcal"]!!.jsonPrimitive.content.toDouble())
        assertEquals(9.6, json["fatG"]!!.jsonPrimitive.content.toDouble())
        assertEquals(2, (json["sources"] as JsonArray).size)
    }

    @Test
    fun invalidNutritionOutputIsRefused() {
        fun bad(text: String) = assertNull(parseCodexOutput(RESEARCH_NUTRITION, question, text), text)
        bad("")
        bad("not json")
        bad("[1,2]")
        bad("""{"kcal":-1,"proteinG":1,"carbsG":1,"fatG":1,"sources":[]}""")
        bad("""{"kcal":1,"proteinG":-0.5,"carbsG":1,"fatG":1,"sources":[]}""")
        bad("""{"kcal":1e999,"proteinG":1,"carbsG":1,"fatG":1,"sources":[]}""")
        bad("""{"kcal":"166","proteinG":1,"carbsG":1,"fatG":1,"sources":[]}""")
        bad("""{"kcal":null,"proteinG":1,"carbsG":1,"fatG":1,"sources":[]}""")
        bad("""{"proteinG":1,"carbsG":1,"fatG":1,"sources":[]}""")
        bad("""{"kcal":1,"proteinG":1,"carbsG":1,"fatG":1}""")
        bad("""{"kcal":1,"proteinG":1,"carbsG":1,"fatG":1,"sources":"https://x.org"}""")
        bad("""{"kcal":1,"proteinG":1,"carbsG":1,"fatG":1,"sources":[5]}""")
        assertNotNull(parseCodexOutput(RESEARCH_NUTRITION, question, """{"kcal":0,"proteinG":0,"carbsG":0,"fatG":100,"sources":[]}"""))
    }

    @Test
    fun theSourceListKeepsOnlyUsableUrls() {
        val many = (1..15).joinToString(",") { "\"https://example.org/$it\"" }
        val text = """{"kcal":1,"proteinG":1,"carbsG":1,"fatG":1,"sources":["javascript:alert(1)","ftp://x.org/a","not a url","https://example.org/1","https://example.org/1","file:///c:/x",$many]}"""
        val row = parseCodexOutput(RESEARCH_NUTRITION, question, text)!!
        assertEquals(10, row.sources.size)
        assertEquals("https://example.org/1", row.sources.first())
        assertTrue(row.sources.all { it.startsWith("https://") })
        assertEquals(row.sources.size, row.sources.toSet().size)
    }

    @Test
    fun otherCategoriesParse() {
        val def = parseCodexOutput(RESEARCH_DEFINITION, "q", """{"definition":" A thing. ","sources":["https://a.org"]}""")!!
        assertEquals("A thing.", def.answerText)
        val app = parseCodexOutput(RESEARCH_APP_CATEGORY, "q", """{"category":"Notes","summary":"Takes notes.","sources":[]}""")!!
        assertEquals("Notes: Takes notes.", app.answerText)
        val other = parseCodexOutput(RESEARCH_OTHER, "q", """{"answer":"France","sources":["https://a.org"]}""")!!
        assertEquals("France", other.answerText)
        assertNull(parseCodexOutput(RESEARCH_DEFINITION, "q", """{"definition":"  ","sources":[]}"""))
        assertNull(parseCodexOutput(RESEARCH_OTHER, "q", """{"answer":5,"sources":[]}"""))
        assertNull(parseCodexOutput("WEATHER", "q", """{"answer":"x","sources":[]}"""))
    }

    @Test
    fun aValidAnswerThatMentionsALimitIsStillAccepted() = runBlocking<Unit> {
        val fake = FakeCodex(outText = """{"definition":"A rate limit caps requests per time.","sources":[]}""", script = { CodexProcessResult(0, "usage limit noise", "") })
        val row = research(fake).ask(RESEARCH_DEFINITION, "Define rate limit in one short paragraph with source URLs.")
        assertTrue("rate limit" in row.answerText)
    }

    @Test
    fun limitTextsFailAtOnceWithTheFixedText() = runBlocking<Unit> {
        for (result in listOf(
            CodexProcessResult(1, "", "ERROR: You've hit your usage limit. Upgrade to Pro"),
            CodexProcessResult(1, "Rate limit reached for requests", ""),
            CodexProcessResult(0, "You have hit the Usage Limit", ""),
        )) {
            val e = assertFailsWith<JobFailedNow> { research(FakeCodex(script = { result })).ask(RESEARCH_NUTRITION, question) }
            assertEquals("Codex limit reached", e.message)
        }
        val inOutput = assertFailsWith<JobFailedNow> {
            research(FakeCodex(outText = "usage limit exceeded", script = { CodexProcessResult(0, "", "") })).ask(RESEARCH_NUTRITION, question)
        }
        assertEquals("Codex limit reached", inOutput.message)
    }

    @Test
    fun unavailableMapsToTheFixedText() = runBlocking<Unit> {
        val missing = assertFailsWith<JobFailedNow> { research(FakeCodex(), exe = null).ask(RESEARCH_NUTRITION, question) }
        assertEquals("Codex is not set up on the server", missing.message)
        class CannotStart : CodexProcess {
            override suspend fun run(executable: String, args: List<String>, workDir: File, stdin: String?, timeoutMs: Long): CodexProcessResult =
                throw IOException("CreateProcess error=2")
        }
        assertEquals("Codex is not set up on the server", assertFailsWith<JobFailedNow> { research(CannotStart()).ask(RESEARCH_NUTRITION, question) }.message)
        val login = assertFailsWith<JobFailedNow> {
            research(FakeCodex(script = { CodexProcessResult(1, "", "Not logged in. Run codex login") })).ask(RESEARCH_NUTRITION, question)
        }
        assertEquals("Codex is not set up on the server", login.message)
        assertEquals(0, tmp.list()!!.size)
    }

    @Test
    fun otherFailuresAreCountedAttempts() = runBlocking<Unit> {
        assertFailsWith<IllegalStateException> { research(FakeCodex(script = { CodexProcessResult(2, "", "boom") })).ask(RESEARCH_NUTRITION, question) }
        assertFailsWith<IllegalStateException> { research(FakeCodex(script = { CodexProcessResult(-1, "", "", timedOut = true) })).ask(RESEARCH_NUTRITION, question) }
        assertFailsWith<IllegalStateException> { research(FakeCodex(outText = """{"kcal":-5,"proteinG":1,"carbsG":1,"fatG":1,"sources":[]}""")).ask(RESEARCH_NUTRITION, question) }
        assertFailsWith<IllegalStateException> { research(FakeCodex()).ask(RESEARCH_NUTRITION, question) }
    }

    @Test
    fun cancellationPropagates() = runBlocking<Unit> {
        class Cancelling : CodexProcess {
            override suspend fun run(executable: String, args: List<String>, workDir: File, stdin: String?, timeoutMs: Long): CodexProcessResult =
                throw CancellationException("stop")
        }
        assertFailsWith<CancellationException> { research(Cancelling()).ask(RESEARCH_NUTRITION, question) }
    }

    @Test
    fun theExecutableIsTheNativeOneNextToTheCmdShim() {
        val npm = Files.createTempDirectory(tmp.toPath(), "npm").toFile()
        val other = Files.createTempDirectory(tmp.toPath(), "other").toFile()
        assertNull(findCodexExecutable(listOf(other, npm)))
        File(npm, "codex.cmd").writeText("@echo off")
        assertNull(findCodexExecutable(listOf(npm)))
        val native = File(npm, "node_modules/@openai/codex/node_modules/@openai/codex-win32-x64/vendor/x86_64-pc-windows-msvc/bin/codex.exe")
        native.parentFile.mkdirs()
        native.writeText("x")
        assertEquals(native, findCodexExecutable(listOf(other, npm)))
        // a codex.exe straight on PATH is used when there is no shim
        val plain = Files.createTempDirectory(tmp.toPath(), "plain").toFile()
        File(plain, "codex.exe").writeText("x")
        assertEquals(File(plain, "codex.exe"), findCodexExecutable(listOf(other, plain)))
    }

    // ---- ProcessCodexProcess with a real, harmless program -------------------------------------------

    private fun javaProgram(source: String): Pair<String, List<String>> {
        val file = File(tmp, "P.java").also { it.writeText(source) }
        val java = File(System.getProperty("java.home"), "bin/" + (if (System.getProperty("os.name").startsWith("Windows")) "java.exe" else "java")).path
        return java to listOf(file.path)
    }

    @Test
    fun theProcessGetsStdinAnEmptyCwdAndItsOutputIsBounded() = runBlocking<Unit> {
        val (java, args) = javaProgram(
            """
            public class P { public static void main(String[] a) throws Exception {
              String in = new String(System.in.readAllBytes(), "UTF-8");
              System.out.println("cwd=" + new java.io.File(".").getCanonicalPath());
              System.out.println("in=" + in.length() + "/" + in.getBytes("UTF-8").length);
              StringBuilder sb = new StringBuilder(); for (int i = 0; i < 3000; i++) sb.append("0123456789");
              System.out.println(sb);
              System.err.println("err-line");
            } }
            """.trimIndent(),
        )
        val work = Files.createTempDirectory(tmp.toPath(), "cwd").toFile()
        val r = ProcessCodexProcess(maxBytes = 2000).run(java, args, work, "héllo ✓", 60_000)
        assertEquals(0, r.exitCode)
        assertEquals(false, r.timedOut)
        assertTrue(r.stdout.contains("cwd=" + work.canonicalPath), r.stdout)
        assertTrue(r.stdout.contains("in=7/10"), r.stdout)
        assertTrue(r.stdout.toByteArray().size <= 2000)
        assertTrue(r.stderr.contains("err-line"))
    }

    @Test
    fun aRunningProcessIsKilledAtTheTimeoutAndAMissingProgramThrows() = runBlocking<Unit> {
        val (java, args) = javaProgram("public class P { public static void main(String[] a) throws Exception { Thread.sleep(120000); } }")
        val start = System.nanoTime()
        val r = ProcessCodexProcess().run(java, args, tmp, null, 1500)
        assertTrue(r.timedOut)
        assertEquals(-1, r.exitCode)
        assertTrue((System.nanoTime() - start) / 1_000_000 < 30_000)
        assertFailsWith<IOException> { ProcessCodexProcess().run(File(tmp, "no-such-program.exe").path, emptyList(), tmp, null, 1000) }
    }
}
