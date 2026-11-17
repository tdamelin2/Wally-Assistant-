package dev.tim.assistant.server

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import dev.tim.assistant.data.prepareSchema
import dev.tim.assistant.db.AssistantDb
import java.io.File
import java.util.Properties
import kotlinx.coroutines.sync.Mutex

/**
 * The server database. Every DB write (push, future Google poller, briefing) must run inside
 * `writeLock.withLock { db.transaction { ... } }` so the seqCounter read-then-write never races.
 */
class ServerDb(val driver: SqlDriver, val db: AssistantDb) : AutoCloseable {
    val writeLock = Mutex()

    override fun close() = driver.close()
}

fun openDb(dbPath: String): ServerDb {
    File(dbPath).absoluteFile.parentFile?.mkdirs()
    val props = Properties().apply {
        put("journal_mode", "WAL")
        put("busy_timeout", "5000")
    }
    val driver = JdbcSqliteDriver("jdbc:sqlite:$dbPath", props)
    try {
        prepareSchema(driver)
    } catch (e: Throwable) {
        driver.close()
        throw e
    }
    return ServerDb(driver, AssistantDb(driver))
}
