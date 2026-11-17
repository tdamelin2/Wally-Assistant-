package dev.tim.assistant.data

actual fun logWarning(message: String) {
    System.err.println("WARN Wally: $message")
}