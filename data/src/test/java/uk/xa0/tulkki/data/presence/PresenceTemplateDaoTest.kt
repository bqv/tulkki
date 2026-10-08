package uk.xa0.tulkki.data.presence

import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.SQLException
import org.junit.Assert
import org.junit.Test
import uk.xa0.tulkki.data.DaoSql
import uk.xa0.tulkki.data.JdbcSchemaExec
import uk.xa0.tulkki.data.KspSchema
import uk.xa0.tulkki.data.RepoFiles
import uk.xa0.tulkki.data.Schema75Fixture
import uk.xa0.tulkki.data.schema.Schema76
import uk.xa0.tulkki.data.schema.Schema77

/**
 * S5-3's `presence/` package test: the saved status lines, their upsert and the trim, and the
 * schema-77 rebuild the table needed.
 *
 * <p><strong>What this test executes, and what it does not.</strong> It executes every statement the
 * DAO publishes over a schema-77 fixture reached the way a device reaches it; it executes the rebuild
 * over a fixture <em>with rows in it</em> and compares every legacy column, every surrogate `_id`
 * against the rowid the row already had, and every row's presence; and it executes Room's own
 * generated `CREATE` for `presence_templates` and compares `PRAGMA table_info`
 * with the rebuilt file's - which is what Room's `onValidateSchema` compares after the
 * migration. It does not execute a call through `PresenceTemplateDao_Impl`: that needs Room's
 * connection machinery and is the gap every package test inherits.
 */
class PresenceTemplateDaoTest {

    /** The DAO's annotations and the package's constants, read as the same list of statements. */
    @Test
    fun theDaosAnnotationsAndThePackagesConstantsAreTheSameStatements() {
        Assert.assertEquals(
                "every statement PresenceTemplateDao publishes, in the order it declares them",
                listOf(
                        PresenceQueries.ALL,
                        PresenceQueries.BY_MESSAGE_AND_STATUS,
                        PresenceQueries.UPSERT,
                        PresenceQueries.REMOVE_BY_MESSAGE,
                        PresenceQueries.TRIM_TO_NEWEST),
                DaoSql.queriesIn(RepoFiles.read(DAO_SOURCE)))
    }

    /** The read is most-recently-used first, and the pair is the identity. */
    @Test
    fun theReadsAreOrderedAndKeyedByThePairTheUniqueNames() {
        val connection = upgraded()
        write(connection, "u1", 100L, "hello", "online")
        write(connection, "u2", 300L, "bye", "away")
        write(connection, "u3", 200L, "hello", "away")

        Assert.assertEquals(
                "the list is ordered by last_used, newest first",
                listOf("bye", "hello", "hello"),
                columnNamed(connection, PresenceQueries.ALL, "message"))
        Assert.assertEquals(
                "and the newest is the first row",
                listOf("u2"),
                columnNamed(connection, PresenceQueries.ALL, "uuid").subList(0, 1))

        Assert.assertEquals(
                "one line by the pair",
                listOf("u1"),
                columnNamed(
                        connection,
                        PresenceQueries.BY_MESSAGE_AND_STATUS,
                        "uuid",
                        "message",
                        "hello",
                        "status",
                        "online"))
        Assert.assertEquals(
                "and a pair no row carries answers nothing",
                emptyList<String>(),
                columnNamed(
                        connection,
                        PresenceQueries.BY_MESSAGE_AND_STATUS,
                        "uuid",
                        "message",
                        "hello",
                        "status",
                        "dnd"))
    }

    /** The upsert replaces on the pair, and the trim keeps only the newest rows. */
    @Test
    fun theUpsertReplacesOnThePairAndTheTrimKeepsTheNewest() {
        val connection = upgraded()
        // Distinct uuids throughout, because the trim's own predicate is a `NOT IN` over `uuid` and a
        // reused uuid would keep the row too - the live writer generates a fresh one per template.
        write(connection, "first", 100L, "hello", "online")
        write(connection, "second", 200L, "hello", "online")
        Assert.assertEquals(
                "the same pair twice is one row, because the rebuild kept the UNIQUE",
                1L,
                DaoSql.scalarLong(connection, "SELECT COUNT(*) FROM presence_templates"))

        for (i in 0 until 12) {
            write(connection, "t" + i, 300L + i, "msg" + i, "online")
        }
        Assert.assertEquals("thirteen rows before the trim", 13L, count(connection))
        Assert.assertEquals(
                "the trim answers the rows it moved", 10, trim(connection, 3))
        Assert.assertEquals("and leaves three", 3L, count(connection))
        Assert.assertEquals(
                "the three it kept are the newest",
                listOf("msg11", "msg10", "msg9"),
                columnNamed(connection, PresenceQueries.ALL, "message"))

        Assert.assertEquals(
                "removing by message answers the rows it moved",
                1,
                DaoSql.execNamed(
                        connection, PresenceQueries.REMOVE_BY_MESSAGE, "message", "msg11"))
        Assert.assertEquals("and leaves two", 2L, count(connection))
    }

    /**
     * The rebuild carries every row, every column and every rowid across, and Room's own declaration
     * of the table is what it leaves. Room's `onValidateSchema` compares each column's
     * `notNull` flag, its normalised affinity and its key, so a mismatch here is a refused open
     * on the owner's first launch.
     */
    @Test
    fun theRebuildCarriesEveryRowAndRoomAcceptsWhatItLeaves() {
        val connection = Schema75Fixture.open()
        exec(
                connection,
                "INSERT INTO presence_templates (uuid, last_used, message, status) VALUES (?,?,?,?)",
                "u1",
                100L,
                "hello",
                "online")
        val before = Schema75Fixture.dumpRows(connection, "presence_templates", COLUMNS)

        Schema77.applySchema(JdbcSchemaExec(connection))
        Assert.assertEquals(
                "no row's columns may change across the rebuild",
                before,
                Schema75Fixture.dumpRows(connection, "presence_templates", COLUMNS))
        Assert.assertEquals(
                "the surrogate key is the rowid the row already had",
                listOf("1"),
                columnNamed(connection, "SELECT _id, uuid FROM presence_templates ORDER BY _id", "_id"))

        val tables = mutableListOf<String>()
        for (statement in KspSchema.ddlFor("presence_templates")) {
            if (statement.startsWith("CREATE TABLE IF NOT EXISTS `presence_templates`")) {
                tables.add(statement)
            }
        }
        Assert.assertEquals(
                "Room must emit its own CREATE for the table; anything else means the entity is not "
                        + "in the @Database list",
                1,
                tables.size)
        val roomFresh = DriverManager.getConnection("jdbc:sqlite::memory:")
        DaoSql.exec(roomFresh, tables[0])
        Assert.assertEquals(
                "Room's presence_templates and the rebuilt file's must be the same table",
                Schema75Fixture.tableInfo(connection, "presence_templates"),
                Schema75Fixture.tableInfo(roomFresh, "presence_templates"))
    }

    /**
     * The generated implementation, asserted to exist and to carry every statement. An accessor that
     * was dropped would leave a green compile and no `_Impl` at all - silently.
     */
    @Test
    fun roomGeneratedTheImplementationForEveryStatement() {
        val generated =
                RepoFiles.root()
                        .resolve(
                                "data/build/generated/ksp/debug/kotlin/uk/xa0/tulkki/data/presence/"
                                        + "PresenceTemplateDao_Impl.kt")
        Assert.assertTrue(
                "Room must have generated PresenceTemplateDao_Impl, which only the accessor makes"
                        + " happen",
                Files.isRegularFile(generated))
        val text = DaoSql.squeezed(RepoFiles.read(generated))
        Assert.assertTrue(
                "and it must be the generated class, not a hand-written copy",
                text.contains(DaoSql.squeezed("class PresenceTemplateDao_Impl")))
        for (statement in
                arrayOf(
                        PresenceQueries.ALL,
                        PresenceQueries.BY_MESSAGE_AND_STATUS,
                        PresenceQueries.UPSERT,
                        PresenceQueries.REMOVE_BY_MESSAGE,
                        PresenceQueries.TRIM_TO_NEWEST)) {
            Assert.assertTrue(
                    "the generated implementation must carry the DAO's statement: " + statement,
                    text.contains(DaoSql.squeezed(DaoSql.bound(statement))))
        }
    }

    private companion object {

        const val DAO_SOURCE =
                "data/src/main/java/uk/xa0/tulkki/data/presence/PresenceTemplateDao.kt"

        val COLUMNS = listOf("uuid", "last_used", "message", "status")

        /** A schema-77 file, reached the way a device reaches it. */
        fun upgraded(): Connection {
            val connection = Schema75Fixture.openWithRows()
            Schema75Fixture.takeTheSchema75Step(connection)
            Schema76.applyUpgrade(JdbcSchemaExec(connection), 4242L)
            Schema77.applySchema(JdbcSchemaExec(connection))
            return connection
        }

        /** One saved line, through the package's own upsert statement. */
        fun write(
                connection: Connection, uuid: String, lastUsed: Long, message: String, status: String) {
            DaoSql.execNamed(
                    connection,
                    PresenceQueries.UPSERT,
                    "uuid",
                    uuid,
                    "lastUsed",
                    lastUsed,
                    "message",
                    message,
                    "status",
                    status)
        }

        fun trim(connection: Connection, keep: Int): Int {
            return DaoSql.execNamed(connection, PresenceQueries.TRIM_TO_NEWEST, "keep", keep)
        }

        fun count(connection: Connection): Long {
            return DaoSql.scalarLong(connection, "SELECT COUNT(*) FROM presence_templates")
        }

        /** One named column of every row a statement selected, bound by the statement's own names. */
        fun columnNamed(
                connection: Connection, sql: String, column: String, vararg nameValuePairs: Any?): List<String> {
            val out = mutableListOf<String>()
            try {
                connection.prepareStatement(DaoSql.bound(sql)).use { statement ->
                    DaoSql.bind(statement, DaoSql.parametersOf(sql), DaoSql.values(*nameValuePairs))
                    statement.executeQuery().use { results ->
                        while (results.next()) {
                            out.add(results.getString(column))
                        }
                    }
                }
            } catch (e: SQLException) {
                throw IllegalStateException("could not query: " + sql, e)
            }
            return out
        }

        fun exec(connection: Connection, sql: String, vararg args: Any?) {
            DaoSql.exec(connection, sql, *args)
        }
    }
}
