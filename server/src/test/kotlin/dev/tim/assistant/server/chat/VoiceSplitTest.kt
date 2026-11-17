package dev.tim.assistant.server.chat

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The voice split: the laptop prompt text stays byte-for-byte what Phase 17 sent (the full OneVoiceTest comes with the wiring). */
class VoiceSplitTest {
    private fun reference(): String =
        VoiceSplitTest::class.java.getResourceAsStream("/wally-persona-phase17.md")!!.bufferedReader(Charsets.UTF_8).use { it.readText() }.trim()

    @Test
    fun assembledLaptopPersonaEqualsThePhase17File() {
        assertEquals(reference(), laptopPersona())
    }

    @Test
    fun voiceIsTheFirstSevenLinesAndBrainFileDoesNotRepeatIt() {
        val voice = voiceText()
        assertEquals(reference().lines().take(7).joinToString("\n"), voice)
        assertTrue(voice.startsWith("You are Wally"))
        assertTrue(voice.endsWith("Talk about things by their names."))
        assertFalse(resourceText("wally-persona.md").contains("How you talk"))
        assertTrue(laptopPersona().startsWith(voice + "\n\n"))
    }
}
