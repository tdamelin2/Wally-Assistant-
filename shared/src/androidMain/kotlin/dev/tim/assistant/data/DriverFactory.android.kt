package dev.tim.assistant.data

import android.content.Context
import androidx.sqlite.db.SupportSQLiteDatabase
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import dev.tim.assistant.db.AssistantDb

actual class DriverFactory(private val context: Context) {
    actual fun createDriver(): SqlDriver {
        val schema = AssistantDb.Schema
        return AndroidSqliteDriver(
            schema,
            context,
            "assistant.db",
            callback = object : AndroidSqliteDriver.Callback(schema) {
                override fun onConfigure(db: SupportSQLiteDatabase) {
                    db.enableWriteAheadLogging()
                    db.query("PRAGMA busy_timeout = 5000").use { it.moveToFirst() }
                }
            },
        )
    }
}
