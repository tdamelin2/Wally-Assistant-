package dev.tim.assistant.data

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import java.io.File
import java.util.Properties

actual class DriverFactory {
    actual fun createDriver(): SqlDriver {
        val file = databaseFile()
        file.absoluteFile.parentFile?.mkdirs()
        val props = Properties().apply {
            put("journal_mode", "WAL")
            put("busy_timeout", "5000")
        }
        val driver = JdbcSqliteDriver("jdbc:sqlite:${file.absolutePath}", props)
        try {
            prepareSchema(driver)
        } catch (e: Throwable) {
            driver.close()
            throw e
        }
        return driver
    }

    /** The database file this process uses (the ASSISTANT_DB override or the installed app's file). */
    fun databaseFile(): File {
        val override = System.getenv("ASSISTANT_DB")
        if (!override.isNullOrBlank()) return File(override)
        val base = System.getenv("LOCALAPPDATA")?.takeIf { it.isNotBlank() }
            ?: (System.getProperty("user.home") + File.separator + "AppData" + File.separator + "Local")
        return File(base, "Assistant" + File.separator + "assistant.db")
    }
}
