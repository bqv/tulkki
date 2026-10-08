package uk.xa0.tulkki.data.delivery

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
 * S5-3's `reliable-delivery/` package test: the six receipt-bookkeeping columns of
 * `messages` and the held / owed select.
 *
 * <p><strong>What this test executes, and what it does not.</strong> It executes all seven
 * statements the DAO publishes over a schema-77 fixture reached the way a device reaches it, and it
 * asserts that each of the six bookkeeping statements moves <em>only</em> its own column of
 * <em>only</em> the named row - which is the package's whole claim, since every one of them is
 * written by a stanza arriving minutes after the row. It does not execute a call through
 * `DeliveryDao_Impl`: that needs Room's connection machinery and is the gap every package test
 * inherits.
 *
 * <p>There is no DDL here and no entity: the package owns six columns of `messages`, whose
 * `CREATE` `messages/` owns. So there is nothing to compare against
 * `PRAGMA table_info`.
 */
class DeliveryDaoTest {

    private companion object {

        private const val DAO_SOURCE =
            "data/src/main/java/uk/xa0/tulkki/data/delivery/DeliveryDao.kt"

        private const val MESSAGE = "m-plain"

        /** A schema-77 file, reached the way a device reaches it. */
        private fun upgraded(): Connection {
            val connection = Schema75Fixture.openWithRows()
            Schema75Fixture.takeTheSchema75Step(connection)
            Schema76.applyUpgrade(JdbcSchemaExec(connection), 4242L)
            Schema77.applySchema(JdbcSchemaExec(connection))
            return connection
        }

        private fun named(connection: Connection, sql: String, name: String, value: Any?) {
            DaoSql.execNamed(connection, sql, "uuid", MESSAGE, name, value)
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

        /** A row in a state neither UNSEND nor WAITING is not held and not owed. */
        private fun thirdStateIsNotOwed(connection: Connection): List<String> {
            DaoSql.execNamed(connection, DeliveryQueries.SET_STATUS, "uuid", MESSAGE, "status", 0L)
            return DaoSql.queryNamed(
                connection,
                DeliveryQueries.NOT_YET_ON_THE_WIRE,
                "unsend",
                DeliveryQueries.STATUS_UNSEND,
                "waiting",
                DeliveryQueries.STATUS_WAITING)
        }
    }

    /** The DAO's annotations and the package's constants, read as the same list of statements. */
    @Test
    fun theDaosAnnotationsAndThePackagesConstantsAreTheSameStatements() {
        Assert.assertEquals(
            "every statement DeliveryDao publishes, in the order it declares them",
            listOf(
                DeliveryQueries.SET_STATUS,
                DeliveryQueries.SET_SERVER_MSG_ID,
                DeliveryQueries.SET_REMOTE_MSG_ID,
                DeliveryQueries.SET_CARBON,
                DeliveryQueries.SET_EDITED,
                DeliveryQueries.SET_RETRACT_ID,
                DeliveryQueries.NOT_YET_ON_THE_WIRE),
            DaoSql.queriesIn(RepoFiles.read(DAO_SOURCE)))
    }

    /** Six statements, six columns, one row: nothing else in the row may move. */
    @Test
    fun eachBookkeepingStatementMovesOnlyItsOwnColumnOfOnlyItsOwnRow() {
        val connection = upgraded()
        val before = rowOf(connection, MESSAGE)
        val other = rowOf(connection, "m-ordinary")

        named(connection, DeliveryQueries.SET_STATUS, "status", 5L)
        named(connection, DeliveryQueries.SET_SERVER_MSG_ID, "serverMsgId", "srv-1")
        named(connection, DeliveryQueries.SET_REMOTE_MSG_ID, "remoteMsgId", "rem-1")
        named(connection, DeliveryQueries.SET_CARBON, "carbon", 1L)
        named(connection, DeliveryQueries.SET_EDITED, "edited", "edited body")
        named(connection, DeliveryQueries.SET_RETRACT_ID, "retractId", "retract-1")

        Assert.assertEquals(
            "no statement may touch a column it does not own",
            listOf("carbon", "edited", "remoteMsgId", "retractId", "serverMsgId", "status"),
            replacedColumns(before, rowOf(connection, MESSAGE)))
        Assert.assertEquals(
            "and no statement may touch another row",
            other,
            rowOf(connection, "m-ordinary"))
        Assert.assertEquals(
            "the values are the ones the statements were handed",
            "5|srv-1|rem-1|1|edited body|retract-1",
            DaoSql.scalar(
                connection,
                "SELECT status || '|' || serverMsgId || '|' || remoteMsgId || '|' || " +
                    "carbon || '|' || edited || '|' || retractId FROM messages " +
                    "WHERE uuid = ?",
                MESSAGE))
        Assert.assertEquals(
            "and an unknown uuid moves nothing",
            0,
            DaoSql.execNamed(connection, DeliveryQueries.SET_STATUS, "uuid", "m-nope", "status", 5L))
    }

    /** The held / owed select is the two not-yet-on-the-wire states, oldest first. */
    @Test
    fun theHeldAndOwedSelectIsTheTwoUnsentStatesOldestFirst() {
        val connection = upgraded()
        Assert.assertEquals(
            "no fixture row is unsent to begin with",
            emptyList<String>(),
            DaoSql.queryNamed(
                connection,
                DeliveryQueries.NOT_YET_ON_THE_WIRE,
                "unsend",
                DeliveryQueries.STATUS_UNSEND,
                "waiting",
                DeliveryQueries.STATUS_WAITING))
        Assert.assertEquals(
            "and the two states it names are the model's own",
            "1|5",
            DeliveryQueries.STATUS_UNSEND.toString() + "|" + DeliveryQueries.STATUS_WAITING.toString())

        named(connection, DeliveryQueries.SET_STATUS, "status", DeliveryQueries.STATUS_WAITING.toLong())
        named(connection, DeliveryQueries.SET_STATUS, "status", DeliveryQueries.STATUS_UNSEND.toLong())
        Assert.assertEquals(
            "a row in one of the two states is owed",
            listOf(MESSAGE),
            DaoSql.queryNamed(
                connection,
                DeliveryQueries.NOT_YET_ON_THE_WIRE,
                "unsend",
                DeliveryQueries.STATUS_UNSEND,
                "waiting",
                DeliveryQueries.STATUS_WAITING))
        Assert.assertEquals(
            "a third state is not owed",
            emptyList<String>(),
            thirdStateIsNotOwed(connection))
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
                    "data/build/generated/ksp/debug/kotlin/uk/xa0/tulkki/data/delivery/" +
                        "DeliveryDao_Impl.kt")
        Assert.assertTrue(
            "Room must have generated DeliveryDao_Impl, which only the accessor makes happen",
            Files.isRegularFile(generated))
        val text = DaoSql.squeezed(RepoFiles.read(generated))
        Assert.assertTrue(
            "and it must be the generated class, not a hand-written copy",
            text.contains(DaoSql.squeezed("class DeliveryDao_Impl")))
        for (statement in
            arrayOf(
                DeliveryQueries.SET_STATUS,
                DeliveryQueries.SET_SERVER_MSG_ID,
                DeliveryQueries.SET_REMOTE_MSG_ID,
                DeliveryQueries.SET_CARBON,
                DeliveryQueries.SET_EDITED,
                DeliveryQueries.SET_RETRACT_ID,
                DeliveryQueries.NOT_YET_ON_THE_WIRE,
            )) {
            Assert.assertTrue(
                "the generated implementation must carry the DAO's statement: " + statement,
                text.contains(DaoSql.squeezed(DaoSql.bound(statement))))
        }
        Assert.assertFalse(
            "no statement here may create a row: a row's existence is messages/'",
            text.contains(DaoSql.squeezed("INSERT INTO")))
    }
}
