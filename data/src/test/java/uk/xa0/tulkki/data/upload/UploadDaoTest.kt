package uk.xa0.tulkki.data.upload

import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.SQLException
import java.util.regex.Pattern
import org.junit.Assert
import org.junit.Test
import uk.xa0.tulkki.data.KspSchema
import uk.xa0.tulkki.data.RepoFiles
import uk.xa0.tulkki.data.Schema75Fixture

/**
 * S5-3's `upload/` package test: the local-file map, the blocked set and the file-state
 * columns of `messages`.
 *
 * <p><strong>What this test executes, and what it does not.</strong> It executes the package's own
 * SQL - every statement the DAO publishes, plus the two `CREATE`s the package now owns - over
 * the schema-75 fixture, because Room's runtime cannot open the <em>encrypted</em> file on the host
 * (`docs/MIGRATION.md`, "Design: the data layer" §4.4) and a test that re-spelled the queries
 * would be testing the test. It also executes Room's own generated `CREATE`s, read from
 * `HistoryDatabase_Impl.kt`, and compares their `PRAGMA table_info` with the files the
 * owner has.
 *
 * <p><strong>Two things it cannot execute, and says so.</strong> A call through `UploadDao_Impl`
 * needs Room's connection machinery (the gap `BlockingDaoExecutionTest` records), and
 * `blocked_media` is not produced by the migration path at all: nothing on a 75 -> 77 walk
 * creates it, because the file has had it since long before 75 and only a fresh install needs the
 * statement ([RawTables]). So both tables come from `Schema75Fixture`, whose
 * `blocked_media` is created from [UploadQueries.CREATE_BLOCKED_MEDIA] - the same string
 * `RawTables` runs on a fresh install - and whose `cids` comes from the schema-75
 * snapshot.
 *
 * <p>The test binds by parameter name: SQLite numbers a statement's `?`s by first appearance,
 * and `SET_FILE_PATH_DELETED`'s `SET` precedes its `WHERE` - the same trap the
 * `sync/` test documents at length.
 */
class UploadDaoTest {

    /**
     * The DAO's annotations and {@link UploadQueries}' constants, read as the same list of statements.
     */
    @Test
    fun theDaosAnnotationsAndThePackagesConstantsAreTheSameStatements() {
        Assert.assertEquals(
                "every statement the DAO publishes, in the order it declares them",
                listOf(
                        UploadQueries.PATH_FOR_CID,
                        UploadQueries.URL_FOR_CID,
                        UploadQueries.CID_FOR_PATH,
                        UploadQueries.SAVE_CID,
                        UploadQueries.BLOCKED_COUNT,
                        UploadQueries.BLOCK_MEDIA,
                        UploadQueries.CLEAR_BLOCKED_MEDIA,
                        UploadQueries.FILE_PATHS_TO_CHECK,
                        UploadQueries.SET_FILE_PATH_DELETED,
                        UploadQueries.EXPIRED_FILES),
                queriesIn(RepoFiles.read(DAO_SOURCE)))
    }

    /** A CID names one file; a second save replaces it rather than adding a second row. */
    @Test
    fun theCidMapIsKeyedByContentAndReachableByPath() {
        val connection: Connection = fixture()

        Assert.assertEquals(
                "the first save writes the row",
                1,
                execNamed(
                        connection,
                        UploadQueries.SAVE_CID,
                        "cid", "cid-aaaa",
                        "path", "files/a.jpg",
                        "url", null))
        Assert.assertEquals(
                "the second save replaces it",
                1,
                execNamed(
                        connection,
                        UploadQueries.SAVE_CID,
                        "cid", "cid-aaaa",
                        "path", "files/moved.jpg",
                        "url", "https://example.org/a.jpg"))
        Assert.assertEquals(
                "one row per content hash", 1L, scalarLong(connection, "SELECT COUNT(*) FROM cids"))

        Assert.assertEquals(
                "the path is the one the newest write stated",
                "files/moved.jpg",
                scalarNamed(connection, UploadQueries.PATH_FOR_CID, "cid", "cid-aaaa"))
        Assert.assertEquals(
                "and so is the URL",
                "https://example.org/a.jpg",
                scalarNamed(connection, UploadQueries.URL_FOR_CID, "cid", "cid-aaaa"))
        Assert.assertEquals(
                "the reverse lookup answers the content hash a path belongs to",
                "cid-aaaa",
                scalarNamed(connection, UploadQueries.CID_FOR_PATH, "path", "files/moved.jpg"))
        Assert.assertEquals(
                "the superseded path no longer maps to it",
                emptyList<String>(),
                queryNamed(connection, UploadQueries.CID_FOR_PATH, "path", "files/a.jpg"))
        Assert.assertEquals(
                "and an unknown content hash answers nothing rather than failing",
                emptyList<String>(),
                queryNamed(connection, UploadQueries.PATH_FOR_CID, "cid", "cid-nope"))
    }

    /** The blocked set is membership and nothing else, and it is emptied wholesale. */
    @Test
    fun theBlockedSetIsMembershipOnlyAndCanBeEmptied() {
        val connection: Connection = fixture()

        Assert.assertEquals(
                "nothing is blocked to begin with",
                0L,
                scalarLong(connection, "SELECT COUNT(*) FROM blocked_media"))
        Assert.assertEquals(
                "the first block writes the row",
                1,
                execNamed(connection, UploadQueries.BLOCK_MEDIA, "cid", "cid-aaaa"))
        Assert.assertEquals(
                "blocking the same content hash twice is a no-op, not a second row",
                1,
                execNamed(connection, UploadQueries.BLOCK_MEDIA, "cid", "cid-aaaa"))
        execNamed(connection, UploadQueries.BLOCK_MEDIA, "cid", SECOND_CID)
        Assert.assertEquals(
                "the membership question answers one for a blocked hash",
                "1",
                scalarNamed(connection, UploadQueries.BLOCKED_COUNT, "cid", "cid-aaaa"))
        Assert.assertEquals(
                "and one for the second content hash, which was blocked by its own row",
                "1",
                scalarNamed(connection, UploadQueries.BLOCKED_COUNT, "cid", SECOND_CID))
        Assert.assertEquals(
                "and zero for one that was never blocked",
                "0",
                scalarNamed(connection, UploadQueries.BLOCKED_COUNT, "cid", "cid-nope"))

        Assert.assertEquals(
                "clearing answers how many were blocked",
                2,
                execNamed(connection, UploadQueries.CLEAR_BLOCKED_MEDIA))
        Assert.assertEquals(
                "and leaves nothing behind",
                0L,
                scalarLong(connection, "SELECT COUNT(*) FROM blocked_media"))
    }

    /** The sweep sees the file-bearing rows that still carry a path, and the marker's own read. */
    @Test
    fun theFileSweepSelectsOnlyFileBearingRowsThatStillCarryAPath() {
        val connection: Connection = fixture()

        Assert.assertEquals(
                "no fixture row is a file-bearing message yet",
                emptyList<String>(),
                queryNamed(connection, UploadQueries.FILE_PATHS_TO_CHECK))
        exec(
                connection,
                "UPDATE messages SET type = 1, relativeFilePath = ? WHERE uuid = ?",
                "files/a.jpg",
                MESSAGE)
        Assert.assertEquals(
                "a type-1 row with a path is a candidate",
                listOf(MESSAGE),
                queryNamed(connection, UploadQueries.FILE_PATHS_TO_CHECK))
        exec(connection, "UPDATE messages SET type = 0 WHERE uuid = ?", MESSAGE)
        Assert.assertEquals(
                "and a row of another type is not, even with a path",
                emptyList<String>(),
                queryNamed(connection, UploadQueries.FILE_PATHS_TO_CHECK))

        exec(connection, "UPDATE messages SET type = 2 WHERE uuid = ?", MESSAGE)
        Assert.assertEquals(
                "the sweep's write moves one row's deleted flag",
                1,
                execNamed(
                        connection,
                        UploadQueries.SET_FILE_PATH_DELETED,
                        "deleted", 1L,
                        "uuid", MESSAGE))
        Assert.assertEquals(
                "and it moved only that row",
                1L,
                scalarLong(connection, "SELECT deleted FROM messages WHERE uuid = ?", MESSAGE))

        Assert.assertEquals(
                "nothing carries the expiry marker yet",
                emptyList<String>(),
                queryNamed(connection, UploadQueries.EXPIRED_FILES))
        exec(connection, "UPDATE messages SET file_deleted = 1 WHERE uuid = ?", MESSAGE)
        Assert.assertEquals(
                "once the marker is written the read finds it",
                listOf(MESSAGE),
                queryNamed(connection, UploadQueries.EXPIRED_FILES))
    }

    /** The DDL the package owns is re-runnable; the one `ALTER` cannot be and is kept out of it. */
    @Test
    fun everySharedStatementIsReRunnableAndTheAlterIsNot() {
        Assert.assertEquals("two tables", 2, UploadQueries.STATEMENTS.size)
        for (statement in UploadQueries.STATEMENTS) {
            Assert.assertTrue(
                    "a shared statement without IF NOT EXISTS is not re-runnable: " + statement,
                    statement.startsWith("CREATE TABLE IF NOT EXISTS "))
        }
        Assert.assertFalse(
                "the `url` ALTER cannot be `IF NOT EXISTS` (SQLite has no such form), so it is " +
                        "deliberately not in the shared list - the legacy chain guards it with a PRAGMA",
                UploadQueries.ADD_CIDS_URL.contains("IF NOT EXISTS"))
        Assert.assertFalse(
                "and the guarded ALTER must not be in the re-runnable list",
                UploadQueries.STATEMENTS.contains(UploadQueries.ADD_CIDS_URL))
    }

    /**
     * Room's own declaration of the two tables is the file the owner has.
     *
     * <p>`cids` is compared against the schema-75 fixture, which is built from the snapshot of
     * the device's file; `blocked_media` against the package's own `CREATE`, because the
     * legacy chain that makes it cannot run on the host (the class comment says so). Room's
     * `onValidateSchema` compares each column's `notNull` flag, so a mismatch here is a
     * refused open on the owner's first launch rather than a warning.
     */
    @Test
    fun roomsOwnDeclarationsOfTheTwoTablesAreTheFilesTheOwnerHas() {
        val ddl: List<String> = KspSchema.ddlFor("cids", "blocked_media")
        Assert.assertEquals(
                "Room must emit its own CREATE for both tables; anything else means an entity is " +
                        "not in the @Database list",
                2,
                ddl.size)

        val roomFresh: Connection = DriverManager.getConnection("jdbc:sqlite::memory:")
        for (statement in ddl) {
            exec(roomFresh, statement)
        }
        val owner: Connection = fixture()
        Assert.assertEquals(
                "Room's cids and the device's must be the same table",
                Schema75Fixture.tableInfo(owner, "cids"),
                Schema75Fixture.tableInfo(roomFresh, "cids"))
        Assert.assertEquals(
                "Room's blocked_media and the package's own CREATE must be the same table",
                Schema75Fixture.tableInfo(owner, "blocked_media"),
                Schema75Fixture.tableInfo(roomFresh, "blocked_media"))
    }

    /**
     * The generated implementation, asserted to exist and to carry exactly the package's statements.
     * An accessor that was dropped would leave a green compile and no `_Impl` at all - silently.
     */
    @Test
    fun roomGeneratedTheImplementationForEveryStatement() {
        val generated: Path =
                RepoFiles.root()
                        .resolve(
                                "data/build/generated/ksp/debug/kotlin/uk/xa0/tulkki/data/upload/" +
                                        "UploadDao_Impl.kt")
        Assert.assertTrue(
                "Room must have generated UploadDao_Impl, which only the accessor makes happen",
                Files.isRegularFile(generated))
        // `Files.readString` is not on the unit-test classpath (the mockable android jar's
        // `java.nio.file` shadows the JDK's), so text is read the way RepoFiles reads it.
        val text = squeezed(RepoFiles.read(generated))
        Assert.assertTrue(
                "and it must be the generated class, not a hand-written copy",
                text.contains(squeezed("class UploadDao_Impl")))
        for (statement in arrayOf(
                UploadQueries.PATH_FOR_CID,
                UploadQueries.URL_FOR_CID,
                UploadQueries.CID_FOR_PATH,
                UploadQueries.SAVE_CID,
                UploadQueries.BLOCKED_COUNT,
                UploadQueries.BLOCK_MEDIA,
                UploadQueries.CLEAR_BLOCKED_MEDIA,
                UploadQueries.FILE_PATHS_TO_CHECK,
                UploadQueries.SET_FILE_PATH_DELETED,
                UploadQueries.EXPIRED_FILES,
        )) {
            Assert.assertTrue(
                    "the generated implementation must carry the DAO's statement: " + statement,
                    text.contains(squeezed(bound(statement))))
        }
    }

    private companion object {

        const val DAO_SOURCE =
                "data/src/main/java/uk/xa0/tulkki/data/upload/UploadDao.kt"

        const val SECOND_CID = "cid-bbbb"

        const val MESSAGE = "m-plain"

        val PARAMETER = Pattern.compile(":([A-Za-z][A-Za-z0-9_]*)")

        /** The schema-75 fixture, which carries `cids` and `blocked_media` as the owner's file does. */
        fun fixture(): Connection {
            return Schema75Fixture.openWithRows()
        }

        fun bound(sql: String): String {
            return sql.replace(Regex(":[A-Za-z][A-Za-z0-9_]*"), "?")
        }

        fun squeezed(text: String): String {
            return text.replace(Regex("[\\s\"+]"), "")
        }

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

        fun scalarNamed(connection: Connection, sql: String, vararg nameValuePairs: Any?): String? {
            return scalar(connection, bound(sql), *boundValues(sql, nameValuePairs))
        }

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

        /** Every `@Query` in the Kotlin DAO, with the `+`-joined literals of a wrapped one read as one. */
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
}
