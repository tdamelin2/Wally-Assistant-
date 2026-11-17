import kotlin.test.Test
import kotlin.test.assertFalse
import java.io.File

/**
 * Retired in Phase 17: the P13-A-28 cases drove the old client ChatController/ChatAnswerer (chat state on the device).
 * Chat now runs on the HP; the controller's queueing/replay is covered by Phase17AcceptanceTest and ChatControllerTest.
 * Only a gone-check remains here.
 */
class Phase13Step3dAcceptanceTest {
    @Test
    fun test_AC_P13A28_old_client_chat_history_file_is_gone() {
        var d: File? = File("").absoluteFile
        while (d != null && !File(d, "settings.gradle.kts").exists()) d = d.parentFile
        assertFalse(File(d!!, "shared/src/commonMain/kotlin/dev/tim/assistant/ai/ChatHistory.kt").exists())
    }
}