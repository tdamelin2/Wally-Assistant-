package dev.tim.assistant.ui

import dev.tim.assistant.ai.PhoneBrainResult
import dev.tim.assistant.ai.PhoneBrainStatus
import dev.tim.assistant.ai.ReplyStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PhoneBrainSectionTest {
    @Test
    fun switch_defaults_on_and_only_false_turns_it_off() {
        assertEquals(true, phoneBrainEnabledFrom(null))
        assertEquals(true, phoneBrainEnabledFrom("true"))
        assertEquals(false, phoneBrainEnabledFrom("false"))
        assertEquals("false", phoneBrainEnabledValue(false))
        assertEquals("true", phoneBrainEnabledValue(true))
    }

    @Test
    fun model_line_shows_one_decimal_gb_or_the_setup_pointer() {
        assertEquals("Model: gemma.litertlm, 3.7 GB", phoneBrainModelLine(PhoneBrainStatus("gemma.litertlm", 3_650_000_000L)))
        assertEquals("No model file. See ops/phone-model.md", phoneBrainModelLine(PhoneBrainStatus(null, null)))
    }

    @Test
    fun test_button_needs_model_battery_and_no_power_saving_but_ignores_the_switch() {
        assertEquals("Needs the model file.", phoneBrainTestBlocker(false, 90, false))
        assertEquals("Battery is below 20%.", phoneBrainTestBlocker(true, 19, false))
        assertEquals("Power saving is on.", phoneBrainTestBlocker(true, 50, true))
        assertNull(phoneBrainTestBlocker(true, 20, false))
    }

    @Test
    fun stats_text_lists_first_word_speed_and_backend() {
        assertEquals(
            "First word: 850 ms | Speed: 12.3 tokens/s | Backend: GPU",
            phoneBrainStatsText(850, PhoneBrainResult("x", 800, 12.34, "GPU")),
        )
        assertEquals("First word: none", phoneBrainStatsText(null, PhoneBrainResult("x", null, null)))
    }

    @Test
    fun reply_comes_from_the_reply_field_without_a_kind() {
        assertEquals("Hello Tim!", phoneBrainTestReply("""{"reply":"Hello Tim!"}"""))
        assertEquals("The phone could not answer.", phoneBrainTestReply(null))
        assertEquals("The phone gave no reply.", phoneBrainTestReply("{}"))
        assertEquals("Hel", ReplyStream.extractReply("""{"reply":"Hel"""))
        assertEquals("", ReplyStream.extract("""{"reply":"Hel"""))
    }
}
