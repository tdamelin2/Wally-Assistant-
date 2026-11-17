package dev.tim.assistant.data

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import dev.tim.assistant.db.AssistantDb

/**
 * Brings the database behind [driver] up to [AssistantDb.Schema].version in one transaction:
 * a file without a `kv` table is created, an older one is migrated (a legacy file with
 * user_version 0 counts as version 1). A file written by a newer app is refused and left untouched.
 * Any failure rolls the whole upgrade back. Must be called on the thread that will use the driver first.
 */
fun prepareSchema(driver: SqlDriver) {
    val schema = AssistantDb.Schema
    val target = schema.version
    // One transaction on the driver's own connection (a raw BEGIN/COMMIT through execute() can land on another connection).
    AssistantDb(driver).transaction {
        val hasKv = driver.executeQuery(
            null,
            "SELECT name FROM sqlite_master WHERE type = 'table' AND name = 'kv'",
            { cursor -> QueryResult.Value(cursor.next().value) },
            0,
        ).value
        if (!hasKv) {
            schema.create(driver).value
        } else {
            val stored = userVersion(driver)
            val current = if (stored == 0L) 1L else stored
            if (current > target) {
                throw IllegalStateException("database version $current is newer than this app supports ($target)")
            }
            if (current < target) schema.migrate(driver, current, target).value
        }
        driver.execute(null, "PRAGMA user_version = $target", 0)
    }
}
private fun userVersion(driver: SqlDriver): Long =
    driver.executeQuery(
        null,
        "PRAGMA user_version",
        { cursor -> QueryResult.Value(if (cursor.next().value) cursor.getLong(0) ?: 0L else 0L) },
        0,
    ).value