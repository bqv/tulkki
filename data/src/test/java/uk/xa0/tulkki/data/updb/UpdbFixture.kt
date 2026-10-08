package uk.xa0.tulkki.data.updb

import java.sql.Connection
import java.sql.DriverManager
import java.sql.SQLException
import org.junit.Assert
import uk.xa0.tulkki.data.JdbcSchemaExec

/**
 * The `push` file as the owner's phone actually holds it, built with plain JDBC.
 *
 * <p>The DDL is the legacy `UnifiedPushDatabase.onCreate` statement, character for character:
 * `expiration NUMBER DEFAULT 0` and the inline `UNIQUE(instance)` are the two things the
 * whole preflight exists for, so a fixture that "tidied" either would prove the wrong thing. It is
 * one copy for both test classes in this package, because a second spelling of it could drift from
 * the first and still look green.
 */
internal object UpdbFixture {

    /** The statement the legacy helper ran, and the file's version beside it. */
    const val LEGACY_CREATE =
            "CREATE TABLE if not exists push (account TEXT, transport TEXT, application TEXT NOT NULL, " +
                    "instance TEXT NOT NULL UNIQUE, endpoint TEXT, expiration NUMBER DEFAULT 0)"

    /** The legacy file's own version: Room's `@Database` is 1 as well, so nothing upgrades it. */
    const val LEGACY_VERSION = 1

    const val ACCOUNT_ONE = "acct-1"
    const val TRANSPORT_ONE = "tr-1"
    const val APPLICATION_ONE = "app-1"
    const val INSTANCE_ONE = "inst-1"
    const val ENDPOINT_ONE = "https://push.example/1"

    const val ACCOUNT_TWO = "acct-2"
    const val TRANSPORT_TWO = "tr-2"
    const val APPLICATION_TWO = "app-2"
    const val INSTANCE_TWO = "inst-2"

    /** A row whose every nullable column is null, so a rebuild that widened a `NOT NULL` throws. */
    const val INSTANCE_THREE = "inst-3"

    /** The columns the rows are compared over: the legacy set, without the surrogate key. */
    val ROW_COLUMNS = arrayOf(
        "account", "transport", "application", "instance", "endpoint", "expiration"
    )

    /** An empty legacy file: the right version, and no `push` table at all. */
    fun empty(): Connection {
        val connection = memory()
        exec(connection, "PRAGMA user_version = " + LEGACY_VERSION)
        return connection
    }

    /** A legacy file with the table and its three rows, one of them entirely nullable. */
    fun legacyWithRows(): Connection {
        val connection = memory()
        exec(connection, LEGACY_CREATE)
        exec(connection, "PRAGMA user_version = " + LEGACY_VERSION)
        row(
                connection,
                ACCOUNT_ONE,
                TRANSPORT_ONE,
                APPLICATION_ONE,
                INSTANCE_ONE,
                ENDPOINT_ONE,
                1000L)
        row(connection, ACCOUNT_TWO, TRANSPORT_TWO, APPLICATION_ONE, INSTANCE_TWO, null, 0L)
        row(connection, null, null, APPLICATION_TWO, INSTANCE_THREE, null, null)
        return connection
    }

    /** The legacy file after the preflight: what Room is handed. */
    fun adopted(): Connection {
        val connection = legacyWithRows()
        Assert.assertTrue(
                "the preflight must rebuild a legacy file",
                LegacyPreflight.rebuildIfLegacy(JdbcSchemaExec(connection)))
        return connection
    }

    fun memory(): Connection {
        try {
            return DriverManager.getConnection("jdbc:sqlite::memory:")
        } catch (e: SQLException) {
            throw IllegalStateException("no in-memory SQLite", e)
        }
    }

    fun exec(connection: Connection, sql: String) {
        try {
            connection.prepareStatement(sql).use { statement ->
                statement.execute()
            }
        } catch (e: SQLException) {
            throw IllegalStateException("failed to execute: " + sql, e)
        }
    }

    fun row(
            connection: Connection,
            account: String?,
            transport: String?,
            application: String?,
            instance: String?,
            endpoint: String?,
            expiration: Long?) {
        val sql =
                "INSERT INTO push (account, transport, application, instance, endpoint, expiration) " +
                        "VALUES (?, ?, ?, ?, ?, ?)"
        try {
            connection.prepareStatement(sql).use { statement ->
                statement.setObject(1, account)
                statement.setObject(2, transport)
                statement.setObject(3, application)
                statement.setObject(4, instance)
                statement.setObject(5, endpoint)
                statement.setObject(6, expiration)
                statement.execute()
            }
        } catch (e: SQLException) {
            throw IllegalStateException("failed to insert " + instance, e)
        }
    }
}
