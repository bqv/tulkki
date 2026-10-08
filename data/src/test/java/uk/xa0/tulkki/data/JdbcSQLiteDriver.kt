package uk.xa0.tulkki.data

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteDriver
import androidx.sqlite.SQLiteStatement
import java.sql.Connection
import java.sql.DriverManager
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.SQLException

/**
 * Room's KMP connection API over the host's JDBC SQLite.
 *
 * <p>It exists for one purpose: to let a **generated** DAO implementation run on the JVM. Room's
 * generated DAO code obtains a connection from its `RoomDatabase` and calls
 * `prepare`/`bind`/`step`/`getText` on it, so a driver is the smallest thing
 * that can stand between the generated code and the fixture - and
 * `RoomDatabase.Builder.setDriver` on the android artifact accepts exactly this interface. The
 * alternative, Room's own runtime, needs an Android `Context` this module cannot produce (see
 * `BlockingDaoExecutionTest`).
 *
 * <p>Index conventions are the platform's, and they differ: SQLite binds **1-based** and reads
 * **0-based**, which is what Room's generated code assumes (`bindText(1, …)`, `getText(0)`). JDBC is
 * 1-based both ways, so bindings pass through and reads add one.
 *
 * <p><strong>It pools, and that was measured to matter.</strong> `hasConnectionPool()` answers true
 * and every `open` hands out its own JDBC connection over one shared-cache in-memory database: with a
 * single shared connection, Room's machinery fails before a generated statement can run, and pooled,
 * a generated **INSERT reaches SQLite** (where it failed on a foreign key, which is how we learned
 * Room enables them on the driver path). **What it does not model yet is transactions** - Room's pool
 * rolls back a connection it believes is in one and gets "cannot rollback - no transaction is
 * active", surfaced as an opaque `android.database.SQLException`; that is the remaining piece, and it
 * is written down rather than hidden.
 */
class JdbcSQLiteDriver(private val databaseName: String = DEFAULT_DATABASE) : SQLiteDriver {

    /**
     * The same, under its own name. The names are what keep two tests apart: a shared-cache
     * in-memory database outlives the driver that opened it for as long as any connection to it is
     * open, so a test that wants an empty file must not reuse another test's name.
     */
    private val url: String = "jdbc:sqlite:file:" + databaseName + "?mode=memory&cache=shared"

    private val keeper: Connection = try {
        DriverManager.getConnection(url)
    } catch (e: SQLException) {
        throw IllegalStateException("could not open the host SQLite", e)
    }

    /** A connection to the same database, for a test that wants to seed or to inspect a table. */
    fun connection(): Connection {
        return keeper
    }

    override val hasConnectionPool: Boolean
        get() {
            // True: this driver hands out a connection per `open` rather than one for everything,
            // which is what Room means by a driver that pools.
            return true
        }

    override fun open(fileName: String): SQLiteConnection {
        try {
            return JdbcSQLiteConnection(DriverManager.getConnection(url))
        } catch (e: SQLException) {
            throw IllegalStateException("could not open a host connection", e)
        }
    }

    private class JdbcSQLiteConnection(private val connection: Connection) : SQLiteConnection {

        override fun prepare(sql: String): SQLiteStatement {
            try {
                return JdbcSQLiteDriver.JdbcSQLiteStatement(connection.prepareStatement(sql))
            } catch (e: SQLException) {
                throw IllegalStateException("could not prepare: " + sql, e)
            }
        }

        override fun close() {
            try {
                connection.close()
            } catch (e: SQLException) {
                throw IllegalStateException("could not close the connection", e)
            }
        }
    }

    private class JdbcSQLiteStatement(private val statement: PreparedStatement) : SQLiteStatement {

        // SQLite's own column-type codes: 1 INTEGER, 2 FLOAT, 3 TEXT, 4 BLOB, 5 NULL. The interface
        // declares no constant holder at 2.6.2, and nothing on the generated DAO's path calls this
        // method - it is here because the interface requires it, not because a query needs it.
        private companion object {
            const val TYPE_INTEGER = 1
            const val TYPE_FLOAT = 2
            const val TYPE_TEXT = 3
            const val TYPE_BLOB = 4
            const val TYPE_NULL = 5
        }

        private var results: ResultSet? = null

        override fun bindBlob(index: Int, value: ByteArray) {
            set(index, value)
        }

        override fun bindDouble(index: Int, value: Double) {
            set(index, value)
        }

        override fun bindLong(index: Int, value: Long) {
            set(index, value)
        }

        override fun bindText(index: Int, value: String) {
            set(index, value)
        }

        override fun bindNull(index: Int) {
            set(index, null)
        }

        override fun getBlob(index: Int): ByteArray {
            return get(index, ResultSet::getBytes)
        }

        override fun getDouble(index: Int): Double {
            return get(index, ResultSet::getDouble)
        }

        override fun getLong(index: Int): Long {
            return get(index, ResultSet::getLong)
        }

        override fun getText(index: Int): String {
            return get(index, ResultSet::getString)
        }

        override fun isNull(index: Int): Boolean {
            try {
                return results == null || results!!.getObject(index + 1) == null
            } catch (e: SQLException) {
                throw IllegalStateException("could not read column " + index, e)
            }
        }

        override fun getColumnCount(): Int {
            try {
                return if (results == null) 0 else results!!.getMetaData().getColumnCount()
            } catch (e: SQLException) {
                throw IllegalStateException("could not count columns", e)
            }
        }

        override fun getColumnName(index: Int): String {
            try {
                return results!!.getMetaData().getColumnName(index + 1)
            } catch (e: SQLException) {
                throw IllegalStateException("could not name column " + index, e)
            }
        }

        override fun getColumnType(index: Int): Int {
            try {
                if (results!!.getObject(index + 1) == null) {
                    return TYPE_NULL
                }
                val jdbc = results!!.getMetaData().getColumnType(index + 1)
                when (jdbc) {
                    java.sql.Types.INTEGER,
                    java.sql.Types.BIGINT,
                    java.sql.Types.SMALLINT,
                    java.sql.Types.TINYINT,
                    java.sql.Types.BOOLEAN -> return TYPE_INTEGER

                    java.sql.Types.REAL,
                    java.sql.Types.FLOAT,
                    java.sql.Types.DOUBLE,
                    java.sql.Types.NUMERIC,
                    java.sql.Types.DECIMAL -> return TYPE_FLOAT

                    java.sql.Types.BLOB,
                    java.sql.Types.BINARY,
                    java.sql.Types.VARBINARY -> return TYPE_BLOB

                    else -> return TYPE_TEXT
                }
            } catch (e: SQLException) {
                throw IllegalStateException("could not type column " + index, e)
            }
        }

        override fun step(): Boolean {
            try {
                if (results == null) {
                    val hasRows = statement.execute()
                    results = statement.getResultSet()
                    if (!hasRows || results == null) {
                        return false
                    }
                }
                return results!!.next()
            } catch (e: SQLException) {
                throw IllegalStateException("could not step", e)
            }
        }

        override fun clearBindings() {
            try {
                statement.clearParameters()
            } catch (e: SQLException) {
                throw IllegalStateException("could not clear bindings", e)
            }
        }

        override fun reset() {
            try {
                if (results != null) {
                    results!!.close()
                    results = null
                }
                statement.clearParameters()
            } catch (e: SQLException) {
                throw IllegalStateException("could not reset", e)
            }
        }

        override fun close() {
            try {
                if (results != null) {
                    results!!.close()
                    results = null
                }
                statement.close()
            } catch (e: SQLException) {
                throw IllegalStateException("could not close the statement", e)
            }
        }

        private fun set(index: Int, value: Any?) {
            try {
                statement.setObject(index, value)
            } catch (e: SQLException) {
                throw IllegalStateException("could not bind parameter " + index, e)
            }
        }

        private fun <T> get(index: Int, reader: Reader<T>): T {
            try {
                return reader.read(results!!, index + 1)
            } catch (e: SQLException) {
                throw IllegalStateException("could not read column " + index, e)
            }
        }

        private fun interface Reader<T> {
            fun read(results: ResultSet, jdbcIndex: Int): T
        }
    }

    private companion object {
        /**
         * One named in-memory database, shared by every connection this driver opens
         * (`cache=shared`). Room's own drivers pool connections: a reader and a writer must not be
         * the same one, and a single connection is what made a generated DAO call fail inside Room's
         * coroutine boundary with a cause it swallows. `keeper` holds the database alive, because a
         * shared-cache in-memory database is dropped when its last connection closes.
         */
        const val DEFAULT_DATABASE = "tulkki-host"
    }
}
