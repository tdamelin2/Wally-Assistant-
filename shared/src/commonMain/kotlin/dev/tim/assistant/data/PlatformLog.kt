package dev.tim.assistant.data

/** Writes one warning line to the platform log (Logcat on Android, stderr on the desktop). */
expect fun logWarning(message: String)