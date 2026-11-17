package dev.tim.assistant.usage

/** Phone-only: the system "usage access" permission. The Activity owns it; the UI only gets this capability. */
interface UsageAccess {
    fun granted(): Boolean
    fun openSettings()
}

/** Desktop-only switches the Settings screen can reach (null on Android). */
interface DesktopControls {
    /** Whether closing the window hides Wally to the tray instead of quitting. */
    fun keepInTray(): Boolean
    fun setKeepInTray(value: Boolean)

    /** False when this run cannot register itself (a dev run has no installed app path). */
    fun autostartAvailable(): Boolean
    fun autostartEnabled(): Boolean
    fun setAutostart(value: Boolean)
}
