package dev.tim.assistant.ai

/**
 * What the phone says about itself. The phone brain needs [batteryPercent] and [powerSave] (see [PhoneBrainGate]) and the
 * app in the [foreground]. [setForegroundListener] lets the chat react when the app leaves or enters the foreground
 * (leaving stops a generation, entering sends what waits); the default does nothing.
 */
interface DeviceState {
    fun batteryPercent(): Int
    fun powerSave(): Boolean
    fun foreground(): Boolean
    fun setForegroundListener(listener: ((Boolean) -> Unit)?) {}
}

/** No device (desktop, tests): 0% battery and never in the foreground, so the phone brain is never allowed. */
object NoDeviceState : DeviceState {
    override fun batteryPercent(): Int = 0
    override fun powerSave(): Boolean = false
    override fun foreground(): Boolean = false
}
