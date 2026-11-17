package dev.tim.assistant.data

import app.cash.sqldelight.db.SqlDriver

/** Opens the local SQLite database for the current platform. */
expect class DriverFactory {
    fun createDriver(): SqlDriver
}
