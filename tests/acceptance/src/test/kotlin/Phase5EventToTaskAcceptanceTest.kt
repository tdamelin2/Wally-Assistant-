import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Phase 5 "Add as task" (D60) acceptance tests, retired in Phase 11 (Tim change): the feature is gone. */
class Phase5EventToTaskAcceptanceTest {
    private fun root(): File = generateSequence(File("").absoluteFile) { it.parentFile }
        .first { File(it, "settings.gradle.kts").exists() }

    @Test
    fun test_AC4_event_to_task_code_is_retired() {
        val ui = File(root(), "shared/src/commonMain/kotlin/dev/tim/assistant/ui")
        assertFalse(File(ui, "EventToTask.kt").exists())
        assertFalse(File(root(), "shared/src/commonTest/kotlin/dev/tim/assistant/ui/EventToTaskTest.kt").exists())
    }

    @Test
    fun test_AC5_phase4_checklist_says_add_as_task_replaced() {
        val text = File(root(), "ops/checklists/phase4.md").readText()
        assertTrue("Add as task (D60)" in text)
        assertTrue("1.9.0" in text && "no \"Add as task\" on Today" in text)
    }
}