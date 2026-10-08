package uk.xa0.tulkki.data

import android.database.Cursor
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteQuery
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.nio.charset.StandardCharsets
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.SQLException

/**
 * The JDK's [InvocationHandler.invoke] contract is that `args` is **null**, not an empty array, for
 * a method that declares no parameters. The Java original indexed `args` directly and never
 * noticed; the Kotlin port (`055660f771`) declared the parameter non-null, so every zero-arg call on
 * either proxy - `Cursor.getCount`/`moveToNext`/`close` on Room's validation path, for one - tripped
 * Kotlin's parameter check and surfaced as the bare
 * `NullPointerException: Parameter specified as non-null is null … parameter args`.
 * Java's null and an empty array mean the same thing to every branch in the two handlers below, so
 * each normalises once, at its entry.
 */
private val NO_ARGUMENTS: Array<out Any?> = emptyArray()

/**
 * A [SupportSQLiteDatabase] over the host's JDBC SQLite, so the <em>real</em> Room migration
 * object can be executed where SQLCipher cannot run.
 *
 * <p>`docs/MIGRATION.md`, "Design: the data layer" §4.4 asks for exactly this to be measured
 * rather than assumed: the JVM `Migration` type's `migrate` signature has to be
 * compatible with the `SupportSQLiteDatabase` a host can produce. Handing
 * [of] to `MIGRATION_75_76.migrate(...)` settles it at the compiler and at
 * run time - if the JVM artifact's signature were `SQLiteConnection` (Room 2.7's KMP split has
 * both), `Schema76SharedTest` would not compile.
 *
 * <p><strong>Two proxies, and why not a real class.</strong> `SupportSQLiteDatabase` and
 * `Cursor` each carry dozens of methods this path never calls, and a class that implemented
 * them would be 400 lines of stubs. A stub that answers a default is worse than no test - the
 * migration could look green because a call was swallowed - so every method that is not part of the
 * schema-76 path <em>throws</em>, by name.
 *
 * <p>Only four database methods are reachable from `Schema76`: `execSQL(String)`,
 * `execSQL(String, Object[])`, `query(String)` and `query(String, Object[])`. What
 * the cursor they answer models is what a reader of a row image calls: `getColumnIndex`,
 * `getColumnIndexOrThrow`, `getColumnCount`, `getCount`, `moveToNext`,
 * `isNull`, `getString`, `getInt`, `getLong`, `getDouble`,
 * `getBlob`, and `close` from Kotlin's `use`. `Conversation.fromCursor`
 * reads real SQL `NULL`s through it, which is the reason the two `getColumnIndexOrThrow`/`getInt`
 * cases exist - a row image whose `NULL` is read as `0` is the one thing such a test is for.
 * Anything else means the production path grew a call this harness does not model, and that must be
 * a failure rather than a surprise on the owner's phone.
 */
object JdbcSupportDatabase {

    /** The database the migration is handed. */
    fun of(connection: Connection): SupportSQLiteDatabase {
        return proxy(SupportSQLiteDatabase::class.java, Database(connection)) as SupportSQLiteDatabase
    }

    private fun proxy(type: Class<*>, handler: InvocationHandler): Any? {
        return Proxy.newProxyInstance(
            JdbcSupportDatabase::class.java.classLoader, arrayOf(type), handler
        )
    }

    private class Database(private val connection: Connection) : InvocationHandler {

        override fun invoke(self: Any?, method: Method, args: Array<out Any?>?): Any? {
            val arguments = args ?: NO_ARGUMENTS
            when (method.name) {
                "execSQL" -> {
                    if (arguments.size == 1) {
                        connection.createStatement().use { statement ->
                            statement.execute(arguments[0] as String)
                        }
                        return null
                    }
                    connection.prepareStatement(arguments[0] as String).use { statement ->
                        JdbcSupportDatabase.bind(statement, arguments[1] as Array<Any?>)
                        statement.execute()
                    }
                    return null
                }

                "query" -> {
                    if (arguments[0] is String) {
                        return JdbcSupportDatabase.cursor(
                            connection,
                            arguments[0] as String,
                            if (arguments.size == 1) {
                                arrayOfNulls<Any?>(0)
                            } else {
                                arguments[1] as Array<Any?>
                            }
                        )
                    }
                    val query = arguments[0] as SupportSQLiteQuery
                    if (query.argCount != 0) {
                        throw UnsupportedOperationException(
                            "the schema-76 path prepares no bound queries through "
                                + "SupportSQLiteQuery; this one has "
                                + query.argCount
                                + " argument(s): "
                                + query.sql
                        )
                    }
                    return JdbcSupportDatabase.cursor(
                        connection, query.sql, arrayOfNulls<Any?>(0))
                }

                "toString" -> return "JdbcSupportDatabase"

                "hashCode" -> return System.identityHashCode(self)

                "equals" -> return self === arguments[0]

                else -> throw UnsupportedOperationException(
                    "the schema-76 path does not call SupportSQLiteDatabase."
                        + method.name
                        + "; if the production code grew this call, this harness must "
                        + "learn it rather than answer a stub"
                )
            }
        }
    }

    private fun cursor(connection: Connection, sql: String, args: Array<Any?>): Cursor {
        val columns = mutableListOf<String>()
        val rows = mutableListOf<Array<Any?>>()
        connection.prepareStatement(sql).use { statement ->
            bind(statement, args)
            statement.executeQuery().use { results ->
                val count = results.getMetaData().getColumnCount()
                for (i in 1..count) {
                    columns.add(results.getMetaData().getColumnLabel(i))
                }
                while (results.next()) {
                    val row = arrayOfNulls<Any?>(count)
                    for (i in 1..count) {
                        row[i - 1] = results.getObject(i)
                    }
                    rows.add(row)
                }
            }
        }
        return proxy(Cursor::class.java, Rows(columns, rows)) as Cursor
    }

    /** Android's cursor contract, over rows JDBC has already read: 0-based, before the first row. */
    private class Rows(private val columns: List<String>, private val rows: List<Array<Any?>>) :
        InvocationHandler {

        private var position: Int = -1

        override fun invoke(self: Any?, method: Method, args: Array<out Any?>?): Any? {
            val arguments = args ?: NO_ARGUMENTS
            when (method.name) {
                "getColumnCount" -> return columns.size

                "getCount" -> return rows.size

                "getColumnIndex" -> return columns.indexOf(arguments[0] as String)

                "getColumnIndexOrThrow" -> {
                    val present = columns.indexOf(arguments[0] as String)
                    if (present < 0) {
                        throw IllegalArgumentException(
                            "no column " + arguments[0] + " in " + columns
                        )
                    }
                    return present
                }

                "getColumnName" -> return columns[arguments[0] as Int]

                "moveToNext" -> {
                    position++
                    return position < rows.size
                }

                "isNull" -> return value(arguments[0] as Int) == null

                "getString" -> {
                    val text = value(arguments[0] as Int)
                    return if (text == null) null else text.toString()
                }

                "getInt" -> {
                    val whole = value(arguments[0] as Int)
                    return if (whole == null) 0 else (whole as Number).toInt()
                }

                "getLong" -> {
                    val number = value(arguments[0] as Int)
                    return if (number == null) 0L else (number as Number).toLong()
                }

                "getDouble" -> {
                    val real = value(arguments[0] as Int)
                    return if (real == null) 0.0 else (real as Number).toDouble()
                }

                "getBlob" -> {
                    val blob = value(arguments[0] as Int)
                    return if (blob == null) {
                        null
                    } else {
                        blob.toString().toByteArray(StandardCharsets.UTF_8)
                    }
                }

                "close" -> {
                    position = rows.size
                    return null
                }

                "toString" -> return "JdbcCursor(" + rows.size + " rows)"

                "hashCode" -> return System.identityHashCode(self)

                "equals" -> return self === arguments[0]

                else -> throw UnsupportedOperationException(
                    "the schema-76 path does not call Cursor."
                        + method.name
                        + "; a stub answer here would hide a real one"
                )
            }
        }

        private fun value(column: Int): Any? {
            if (position < 0 || position >= rows.size) {
                throw IllegalStateException("cursor read before moveToNext()")
            }
            return rows[position][column]
        }
    }

    private fun bind(statement: PreparedStatement, args: Array<Any?>) {
        for (i in 0 until args.size) {
            statement.setObject(i + 1, args[i])
        }
    }
}
