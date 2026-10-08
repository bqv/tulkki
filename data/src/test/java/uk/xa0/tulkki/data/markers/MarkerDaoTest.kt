package uk.xa0.tulkki.data.markers

import java.nio.file.Files
import java.sql.Connection
import java.sql.SQLException
import org.junit.Assert
import org.junit.Test
import uk.xa0.tulkki.data.DaoSql
import uk.xa0.tulkki.data.JdbcSchemaExec
import uk.xa0.tulkki.data.RepoFiles
import uk.xa0.tulkki.data.Schema75Fixture
import uk.xa0.tulkki.data.schema.Schema76
import uk.xa0.tulkki.data.schema.Schema77

/**
 * S5-3's `markers/` package test: three columns of `messages` and the four statements
 * that move and read them.
 *
 * <p><strong>What this test executes, and what it does not.</strong> It executes every statement the
 * DAO publishes over a schema-77 fixture reached the way a device reaches it, and it asserts that
 * the two flag statements move <em>only</em> their own column - which is the package's whole claim,
 * and the reason these are targeted `UPDATE`s rather than whole-row writes. It does not
 * execute a call through `MarkerDao_Impl`: that needs Room's connection machinery and is the
 * gap every package test inherits.
 *
 * <p>There is no DDL here and no entity: the package owns columns of `messages`, whose
 * `CREATE` and whose eight indexes `messages/` owns, and no table of its own. So there
 * is nothing for this test to compare against `PRAGMA table_info`; what it does instead is
 * execute the statements against the table the entity declares.
 */
class MarkerDaoTest {

    private companion object {

        private const val DAO_SOURCE =
            "data/src/main/java/uk/xa0/tulkki/data/markers/MarkerDao.kt"

        private const val MESSAGE = "m-plain"

        /** A schema-77 file, reached the way a device reaches it. */
        private fun upgraded(): Connection {
            val connection = Schema75Fixture.openWithRows()
            Schema75Fixture.takeTheSchema75Step(connection)
            Schema76.applyUpgrade(JdbcSchemaExec(connection), 4242L)
            Schema77.applySchema(JdbcSchemaExec(connection))
            return connection
        }

        /** Every column of one row, as `name=value|` pairs - the "nothing else moved" instrument. */
        private fun rowOf(connection: Connection, uuid: String): String {
            val columns = Schema75Fixture.columnNames(connection, "messages")
            val out = StringBuilder()
            val sql = "SELECT " + java.lang.String.join(",", columns) + " FROM messages WHERE uuid = ?"
            try {
                connection.prepareStatement(sql).use { statement ->
                    statement.setString(1, uuid)
                    statement.executeQuery().use { results ->
                        Assert.assertTrue("the row must exist: " + uuid, results.next())
                        for (column in columns) {
                            val value = results.getObject(column)
                            out.append(column).append('=').append(if (value == null) "NULL" else value)
                                .append('|')
                        }
                    }
                }
            } catch (e: SQLException) {
                throw IllegalStateException("could not dump the row: " + uuid, e)
            }
            return out.toString()
        }

        /** The column names whose values differ between two `rowOf` dumps. */
        private fun replacedColumns(before: String, after: String): List<String> {
            val out = ArrayList<String>()
            val left = before.split(Regex("\\|"))
            val right = after.split(Regex("\\|"))
            Assert.assertEquals("the two dumps must be of the same row", left.size, right.size)
            for (i in left.indices) {
                if (left[i] != right[i]) {
                    out.add(left[i].substring(0, left[i].indexOf('=')))
                }
            }
            out.sort()
            return out
        }

        private fun readFlag(connection: Connection, uuid: String): Long {
            return DaoSql.scalarLong(connection, "SELECT read FROM messages WHERE uuid = ?", uuid)
        }

        private fun markableFlag(connection: Connection, uuid: String): Long {
            return DaoSql.scalarLong(connection, "SELECT markable FROM messages WHERE uuid = ?", uuid)
        }
    }

    /** The DAO's annotations and the package's constants, read as the same list of statements. */
    @Test
    fun theDaosAnnotationsAndThePackagesConstantsAreTheSameStatements() {
        Assert.assertEquals(
            "every statement MarkerDao publishes, in the order it declares them",
            listOf(
                MarkerQueries.MARK_READ,
                MarkerQueries.MARK_MARKABLE,
                MarkerQueries.READ_BY_MARKERS_OF,
                MarkerQueries.SET_READ_BY_MARKERS),
            DaoSql.queriesIn(RepoFiles.read(DAO_SOURCE)))
    }

    /** The two flags move one column each, and nothing else in the row. */
    @Test
    fun theTwoFlagsMoveOneColumnOfOneRow() {
        val connection = upgraded()
        val before = rowOf(connection, MESSAGE)
        Assert.assertEquals("the fixture row starts out read", 1L, readFlag(connection, MESSAGE))
        Assert.assertEquals("and not markable", 0L, markableFlag(connection, MESSAGE))

        Assert.assertEquals(
            "the read flag moves one row",
            1,
            DaoSql.execNamed(connection, MarkerQueries.MARK_READ, "uuid", MESSAGE, "read", 0L))
        Assert.assertEquals("and the column reads back", 0L, readFlag(connection, MESSAGE))
        Assert.assertEquals(
            "the markable flag moves one row",
            1,
            DaoSql.execNamed(
                connection, MarkerQueries.MARK_MARKABLE, "uuid", MESSAGE, "markable", 1L))
        Assert.assertEquals("and that one reads back too", 1L, markableFlag(connection, MESSAGE))

        Assert.assertEquals(
            "no statement here may touch a column it does not own",
            listOf("markable", "read"),
            replacedColumns(before, rowOf(connection, MESSAGE)))
        Assert.assertEquals(
            "and an unknown uuid moves nothing",
            0,
            DaoSql.execNamed(connection, MarkerQueries.MARK_READ, "uuid", "m-nope", "read", 0L))
    }

    /** The marker list is one JSON value: written whole, read back whole, and absent by default. */
    @Test
    fun theMarkerListIsOneValueWrittenWhole() {
        val connection = upgraded()
        Assert.assertNull(
            "the fixture row has no markers yet",
            DaoSql.scalarNamed(connection, MarkerQueries.READ_BY_MARKERS_OF, "uuid", MESSAGE))

        Assert.assertEquals(
            "the write moves one row",
            1,
            DaoSql.execNamed(
                connection,
                MarkerQueries.SET_READ_BY_MARKERS,
                "uuid",
                MESSAGE,
                "markers",
                "[{\"jid\":\"a@example.org\"}]"))
        Assert.assertEquals(
            "and the read answers the whole value",
            "[{\"jid\":\"a@example.org\"}]",
            DaoSql.scalarNamed(connection, MarkerQueries.READ_BY_MARKERS_OF, "uuid", MESSAGE))

        DaoSql.execNamed(
            connection, MarkerQueries.SET_READ_BY_MARKERS, "uuid", MESSAGE, "markers", "[]")
        Assert.assertEquals(
            "a second write replaces it rather than appending",
            "[]",
            DaoSql.scalar(
                connection, "SELECT readByMarkers FROM messages WHERE uuid = ?", MESSAGE))
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
                    "data/build/generated/ksp/debug/kotlin/uk/xa0/tulkki/data/markers/" +
                        "MarkerDao_Impl.kt")
        Assert.assertTrue(
            "Room must have generated MarkerDao_Impl, which only the accessor makes happen",
            Files.isRegularFile(generated))
        val text = DaoSql.squeezed(RepoFiles.read(generated))
        Assert.assertTrue(
            "and it must be the generated class, not a hand-written copy",
            text.contains(DaoSql.squeezed("class MarkerDao_Impl")))
        for (statement in
            arrayOf(
                MarkerQueries.MARK_READ,
                MarkerQueries.MARK_MARKABLE,
                MarkerQueries.READ_BY_MARKERS_OF,
                MarkerQueries.SET_READ_BY_MARKERS,
            )) {
            Assert.assertTrue(
                "the generated implementation must carry the DAO's statement: " + statement,
                text.contains(DaoSql.squeezed(DaoSql.bound(statement))))
        }
        Assert.assertFalse(
            "the DAO must not publish an insert: it owns three columns of a row another package" +
                " creates",
            text.contains(DaoSql.squeezed("INSERT INTO")))
    }
}
