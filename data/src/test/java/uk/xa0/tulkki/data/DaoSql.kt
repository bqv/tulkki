package uk.xa0.tulkki.data

import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.SQLException
import java.util.regex.Pattern
import org.junit.Assert

/**
 * The one copy of the DAO-test instrument that every S5-3 package test needs: read a Kotlin DAO's
 * `@Query` literals, bind a statement by parameter <em>name</em>, and run it over a JDBC
 * SQLite fixture.
 *
 * <p><strong>Why it is here and not three times over.</strong> `SyncDaoTest` and
 * `UploadDaoTest` each carry their own private copy of exactly these methods - that was the
 * second copy, and the `messages/` package's test would have been the third. This class is the
 * promoted copy; the two older tests' private ones are a pure deletion away from using it and are
 * left in place by the commit that added this class rather than rewritten inside a package's commit.
 *
 * <p><strong>Binding by name is not a convenience.</strong> A Room `@Query` names its
 * parameters, and SQLite numbers `?`s by first appearance in the statement - so
 * `SET x = :a WHERE y = :b` and a `VALUES` list whose order differs from the method's
 * parameters both bind wrongly under positional guessing. The names come from the statement text, in
 * the order SQLite will number them.
 */
object DaoSql {

    private val PARAMETER = Pattern.compile(":([A-Za-z][A-Za-z0-9_]*)")

    /** Names become `?`; the bound SQL is what a `PreparedStatement` runs. */
    fun bound(sql: String): String {
        return sql.replace(Regex(":[A-Za-z][A-Za-z0-9_]*"), "?")
    }

    /**
     * Whitespace, quotes and concatenation signs removed, so an assertion is about the SQL rather
     * than about how Kotlin wrapped the literal the generator wrote.
     */
    fun squeezed(text: String): String {
        return text.replace(Regex("[\\s\"+]"), "")
    }

    /** The names a statement binds, in the order SQLite numbers its `?`s. */
    fun parametersOf(sql: String): List<String> {
        val names = mutableListOf<String>()
        val matcher = PARAMETER.matcher(sql)
        while (matcher.find()) {
            names.add(matcher.group(1))
        }
        return names
    }

    fun execNamed(connection: Connection, sql: String, vararg nameValuePairs: Any?): Int {
        try {
            return connection.prepareStatement(bound(sql)).use { statement ->
                bind(statement, parametersOf(sql), values(*nameValuePairs))
                statement.execute()
                Math.max(statement.updateCount, 0)
            }
        } catch (e: SQLException) {
            throw IllegalStateException("could not execute: " + sql, e)
        }
    }

    fun bind(statement: PreparedStatement, names: List<String>, values: Map<String, Any?>) {
        for (i in names.indices) {
            val name = names[i]
            Assert.assertTrue("no value given for :" + name, values.containsKey(name))
            statement.setObject(i + 1, values[name])
        }
    }

    fun values(vararg nameValuePairs: Any?): Map<String, Any?> {
        Assert.assertEquals("the name/value pairs must come in twos", 0, nameValuePairs.size % 2)
        val out = linkedMapOf<String, Any?>()
        var i = 0
        while (i < nameValuePairs.size) {
            Assert.assertTrue("a parameter name must be a String", nameValuePairs[i] is String)
            out[nameValuePairs[i] as String] = nameValuePairs[i + 1]
            i += 2
        }
        return out
    }

    fun exec(connection: Connection, sql: String, vararg args: Any?): Int {
        try {
            return connection.prepareStatement(sql).use { statement ->
                for (i in args.indices) {
                    statement.setObject(i + 1, args[i])
                }
                statement.execute()
                Math.max(statement.updateCount, 0)
            }
        } catch (e: SQLException) {
            throw IllegalStateException("could not execute: " + sql, e)
        }
    }

    /** Whether a statement throws - the fixture's way of asserting a constraint fired. */
    fun fails(connection: Connection, sql: String, vararg args: Any?): Boolean {
        try {
            exec(connection, sql, *args)
            return false
        } catch (e: IllegalStateException) {
            return true
        }
    }

    /** The first column of the first row, bound by the statement's own parameter names. */
    fun scalarNamed(connection: Connection, sql: String, vararg nameValuePairs: Any?): String? {
        return scalar(connection, bound(sql), *boundValues(sql, nameValuePairs))
    }

    /** A statement's positional values, named in the order SQLite numbers its `?`s. */
    fun boundValues(sql: String, nameValuePairs: Array<out Any?>): Array<Any?> {
        val names = parametersOf(sql)
        val values = values(*nameValuePairs)
        val out = arrayOfNulls<Any?>(names.size)
        for (i in names.indices) {
            Assert.assertTrue("no value given for :" + names[i], values.containsKey(names[i]))
            out[i] = values[names[i]]
        }
        return out
    }

    fun scalar(connection: Connection, sql: String, vararg args: Any?): String? {
        val value = scalarObject(connection, sql, *args)
        return if (value == null) null else value.toString()
    }

    fun scalarLong(connection: Connection, sql: String, vararg args: Any?): Long {
        val value = scalarObject(connection, sql, *args)
        Assert.assertNotNull("the query must answer a value: " + sql, value)
        return (value as Number).toLong()
    }

    fun scalarObject(connection: Connection, sql: String, vararg args: Any?): Any? {
        try {
            return connection.prepareStatement(sql).use { statement ->
                for (i in args.indices) {
                    statement.setObject(i + 1, args[i])
                }
                statement.executeQuery().use { results ->
                    Assert.assertTrue("the query must answer one row: " + sql, results.next())
                    results.getObject(1)
                }
            }
        } catch (e: SQLException) {
            throw IllegalStateException("could not query: " + sql, e)
        }
    }

    /** The first column of every row a statement selected, bound by the statement's own names. */
    fun queryNamed(connection: Connection, sql: String, vararg nameValuePairs: Any?): List<String> {
        val out = mutableListOf<String>()
        try {
            connection.prepareStatement(bound(sql)).use { statement ->
                bind(statement, parametersOf(sql), values(*nameValuePairs))
                statement.executeQuery().use { results ->
                    while (results.next()) {
                        out.add(results.getString(1))
                    }
                }
            }
        } catch (e: SQLException) {
            throw IllegalStateException("could not query: " + sql, e)
        }
        return out
    }

    /**
     * Every `@Query` in a Kotlin DAO, with the `+`-joined literals of a wrapped
     * annotation read as one string. It reads the annotation, not the file: a statement that only
     * appears in a comment is not a statement Room will run.
     */
    fun queriesIn(source: String): List<String> {
        val out = mutableListOf<String>()
        var at = source.indexOf("@Query(")
        while (at >= 0) {
            var index = at + "@Query(".length
            var depth = 1
            var inString = false
            val sql = StringBuilder()
            while (index < source.length) {
                val c = source[index]
                if (inString) {
                    if (c == '\\') {
                        sql.append(source[index + 1])
                        index += 2
                        continue
                    }
                    if (c == '"') {
                        inString = false
                        index++
                        continue
                    }
                    sql.append(c)
                } else if (c == '"') {
                    inString = true
                } else if (c == '(') {
                    depth++
                } else if (c == ')') {
                    depth--
                    if (depth == 0) {
                        break
                    }
                }
                index++
            }
            out.add(sql.toString())
            at = source.indexOf("@Query(", index)
        }
        return out
    }
}
