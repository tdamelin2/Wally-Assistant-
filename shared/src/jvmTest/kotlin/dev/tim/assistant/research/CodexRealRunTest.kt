package dev.tim.assistant.research

import java.io.File
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/**
 * One real NUTRITION run through the production classes (low effort, isolation flags, stdin). Runs only when
 * WALLY_CODEX_PROBE=1; needs Codex installed and logged in on this laptop. Writes shared/build/codex-probe.txt.
 */
class CodexRealRunTest {
    @Test
    fun oneRealNutritionRun() = runBlocking<Unit> {
        if (System.getenv("WALLY_CODEX_PROBE") != "1") return@runBlocking
        val exe = findCodexExecutable()
        assertNotNull(exe, "codex executable not found")
        val question = templateQuestion(RESEARCH_NUTRITION, "hummus")
        val start = System.nanoTime()
        val row = CodexResearch(ProcessCodexProcess()).ask(RESEARCH_NUTRITION, question)
        val seconds = (System.nanoTime() - start) / 1_000_000_000.0
        File("build").mkdirs()
        File("build/codex-probe.txt").writeText("exe=${exe.path}\nseconds=$seconds\nanswer=${row.answerText}\njson=${row.answerJson}\nsources=${row.sources}\n")
        assertTrue(row.answerText.startsWith("Per 100 g"))
    }
}
