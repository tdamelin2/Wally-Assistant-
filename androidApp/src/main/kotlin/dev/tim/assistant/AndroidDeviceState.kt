package dev.tim.assistant

import android.content.Context
import android.os.BatteryManager
import android.os.PowerManager
import dev.tim.assistant.ai.DeviceState

/**
 * Battery and power saving from the system; foreground = at least one activity of this app is started. [WallyApp] feeds the
 * started count from its lifecycle callbacks through [activityStarted] / [activityStopped].
 */
class AndroidDeviceState(context: Context) : DeviceState {
    private val battery = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
    private val power = context.getSystemService(Context.POWER_SERVICE) as PowerManager

    @Volatile private var started = 0
    @Volatile private var listener: ((Boolean) -> Unit)? = null

    override fun batteryPercent(): Int = battery.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)

    override fun powerSave(): Boolean = power.isPowerSaveMode

    override fun foreground(): Boolean = started > 0

    override fun setForegroundListener(listener: ((Boolean) -> Unit)?) {
        this.listener = listener
    }

    @Synchronized
    fun activityStarted() {
        started++
        if (started == 1) listener?.invoke(true)
    }

    @Synchronized
    fun activityStopped() {
        if (started > 0) started--
        if (started == 0) listener?.invoke(false)
    }
}
