package dev.tim.assistant.ai

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PhoneBrainGateTest {
    @Test
    fun nineteen_percent_is_refused_and_twenty_is_allowed() {
        assertFalse(PhoneBrainGate.allowed(enabled = true, modelPresent = true, batteryPercent = 19, powerSave = false))
        assertTrue(PhoneBrainGate.allowed(enabled = true, modelPresent = true, batteryPercent = 20, powerSave = false))
    }

    @Test
    fun power_saving_disabled_or_no_model_each_refuse() {
        assertFalse(PhoneBrainGate.allowed(enabled = true, modelPresent = true, batteryPercent = 80, powerSave = true))
        assertFalse(PhoneBrainGate.allowed(enabled = false, modelPresent = true, batteryPercent = 80, powerSave = false))
        assertFalse(PhoneBrainGate.allowed(enabled = true, modelPresent = false, batteryPercent = 80, powerSave = false))
    }

    @Test
    fun no_phone_brain_has_no_model_and_fails_to_generate() = kotlinx.coroutines.runBlocking<Unit> {
        assertEquals(PhoneBrainStatus(null, null), NoPhoneBrain.status())
        val r = NoPhoneBrain.generate("s", "u", kotlinx.serialization.json.JsonObject(emptyMap())) {}
        assertEquals(null, r.raw)
    }
}
