package dev.tim.assistant.ai

/** Whether the phone may write a reply itself. Foreground is a separate check (see DeviceState). */
object PhoneBrainGate {
    const val MIN_BATTERY_PERCENT = 20

    fun allowed(enabled: Boolean, modelPresent: Boolean, batteryPercent: Int, powerSave: Boolean): Boolean =
        enabled && modelPresent && batteryPercent >= MIN_BATTERY_PERCENT && !powerSave
}
