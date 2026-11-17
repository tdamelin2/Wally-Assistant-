import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class Phase8ItalicAcceptanceTest {
    private val root: File = generateSequence(File("").absoluteFile) { it.parentFile }
        .first { File(it, "settings.gradle.kts").exists() }

    private fun text(rel: String) = File(root, rel).readText()
    private val ui = "shared/src/%s/kotlin/dev/tim/assistant/ui/"

    // AC-2 (R1): desktop output unchanged: jvm actual is FontStyle.Italic
    @Test
    fun test_AC2_jvm_italic_style_is_fontstyle_italic() {
        val t = text(ui.format("jvmMain") + "MarkdownItalic.jvm.kt")
        assertTrue(Regex("""actual val markdownItalicStyle[^=]*=\s*SpanStyle\(\s*fontStyle\s*=\s*FontStyle\.Italic\s*\)""").containsMatchIn(t))
    }

    // AC-2 (R1): Android draws a skew, no fontStyle (no double slant)
    @Test
    fun test_AC2_android_italic_style_is_skew() {
        val t = text(ui.format("androidMain") + "MarkdownItalic.android.kt")
        assertTrue(t.contains("TextGeometricTransform") && t.contains("skewX"))
        assertFalse(t.contains("fontStyle ="))
    }

    // AC-2 (R1): MarkdownView uses the platform style
    @Test
    fun test_AC2_markdown_view_uses_platform_italic() {
        val t = text(ui.format("commonMain") + "MarkdownView.kt")
        assertTrue(t.contains("markdownItalicStyle"))
        assertFalse(t.contains("FontStyle.Italic"))
    }
}
