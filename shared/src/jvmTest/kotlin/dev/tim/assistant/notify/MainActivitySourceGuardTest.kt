package dev.tim.assistant.notify

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** MainActivity.kt is only built by the Android compile, so a stray escape in a comment or a lost App() call is caught here. */
class MainActivitySourceGuardTest {
    private fun source(): String {
        var root: File? = File("").absoluteFile
        while (root != null && !File(root, "settings.gradle.kts").exists()) root = root.parentFile
        assertTrue(root != null, "project root not found")
        return File(root, "androidApp/src/main/kotlin/dev/tim/assistant/MainActivity.kt").readText()
    }

    @Test
    fun no_comment_holds_a_literal_backslash_n() {
        val bad = source().lines().filter { line ->
            val t = line.trim()
            (t.startsWith("//") || t.startsWith("*") || t.startsWith("/*")) && t.contains("\\n")
        }
        assertTrue(bad.isEmpty(), "literal \\n in comments: $bad")
    }

    @Test
    fun set_content_still_calls_app_and_the_launch_target_is_handled() {
        val text = source()
        val setContent = text.substringAfter("setContent {")
        assertTrue("App(services" in setContent, "setContent must call App")
        assertTrue("override fun onNewIntent" in text && "postLaunchTarget" in text)
        assertFalse("exported" in text)
    }
}
