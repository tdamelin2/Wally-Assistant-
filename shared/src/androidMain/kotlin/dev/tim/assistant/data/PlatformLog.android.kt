package dev.tim.assistant.data

import android.util.Log

actual fun logWarning(message: String) {
    Log.w("Wally", message)
}