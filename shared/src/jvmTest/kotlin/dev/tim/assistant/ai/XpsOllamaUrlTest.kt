package dev.tim.assistant.ai

import kotlin.test.Test
import kotlin.test.assertEquals

class XpsOllamaUrlTest {
    @Test
    fun unset_or_blank_uses_default() {
        assertEquals("http://xxxxxxxx:11434", DEFAULT_XPS_OLLAMA_URL)
        assertEquals(DEFAULT_XPS_OLLAMA_URL, xpsOllamaUrl(null))
        assertEquals(DEFAULT_XPS_OLLAMA_URL, xpsOllamaUrl(""))
        assertEquals(DEFAULT_XPS_OLLAMA_URL, xpsOllamaUrl("   "))
    }

    @Test
    fun custom_value_is_kept_and_trimmed() {
        assertEquals("http://100.1.2.3:11434", xpsOllamaUrl("  http://100.1.2.3:11434 "))
    }
}
