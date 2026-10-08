package uk.xa0.tulkki.data

import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.SQLException
import uk.xa0.tulkki.data.schema.SchemaExec

/**
 * {@link SchemaExec} over a plain JDBC SQLite connection.
 *
 * <p>This is what lets the schema-76 work actually be <em>executed</em> in a unit test.
 * `docs/MIGRATION.md`, "Design: the data layer" §4.4: SQLCipher ships Android ABIs only, so no
 * JVM test can open the owner's file, and Room's Android entry point cannot run on the host either.
 * What can run is the SQL, and the design's stated fallback is exactly this: a schema-75 fixture
 * built with a plain-JVM SQLite, the shared statements applied, and the result compared as text.
 *
 * <p>`org.xerial:sqlite-jdbc` is a `testImplementation` only. It never reaches the app.
 */
class JdbcSchemaExec(private val connection: Connection) : SchemaExec {

    override fun exec(sql: String) {
        try {
            connection.createStatement().use { statement ->
                statement.execute(sql)
            }
        } catch (e: SQLException) {
            throw IllegalStateException("failed to execute: " + sql, e)
        }
    }

    override fun exec(sql: String, args: Array<Any?>) {
        try {
            connection.prepareStatement(sql).use { statement ->
                bind(statement, args)
                statement.execute()
            }
        } catch (e: SQLException) {
            throw IllegalStateException("failed to execute: " + sql, e)
        }
    }

    override fun hasColumn(table: String, column: String): Boolean {
        for (row in rows("PRAGMA table_info(`" + table + "`)", arrayOf())) {
            if (row.size > 1 && column.equals(row[1], ignoreCase = true)) {
                return true
            }
        }
        return false
    }

    override fun hasTable(table: String): Boolean {
        return rows(
                        "SELECT 1 FROM sqlite_master WHERE type IN ('table','view') AND name=?",
                        arrayOf(table))
                .isNotEmpty()
    }

    override fun rows(sql: String, args: Array<String>): List<Array<String>> {
        val out = mutableListOf<Array<String>>()
        try {
            connection.prepareStatement(sql).use { statement ->
                bind(statement, args)
                statement.executeQuery().use { results ->
                    val columns = results.metaData.columnCount
                    while (results.next()) {
                        val row = arrayOfNulls<String>(columns)
                        for (i in 1..columns) {
                            row[i - 1] = results.getString(i)
                        }
                        @Suppress("UNCHECKED_CAST")
                        out.add(row as Array<String>)
                    }
                }
            }
        } catch (e: SQLException) {
            throw IllegalStateException("failed to query: " + sql, e)
        }
        return out
    }

    private fun bind(statement: PreparedStatement, args: Array<out Any?>) {
        for (i in args.indices) {
            statement.setObject(i + 1, args[i])
        }
    }
}
