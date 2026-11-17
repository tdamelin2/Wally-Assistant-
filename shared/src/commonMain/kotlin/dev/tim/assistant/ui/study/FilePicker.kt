package dev.tim.assistant.ui.study

/** A file Tim chose: its display name and at most (limit + 1) bytes (one byte over the limit means "too large"). */
class PickedFile(val name: String, val bytes: ByteArray)

/**
 * Opens the system file chooser. Returns null when Tim cancels (a no-op for the caller). The Android Activity and
 * the desktop entry point each supply their own; the Activity itself is never kept in the app services.
 */
fun interface FilePicker {
    suspend fun pick(): PickedFile?
}
