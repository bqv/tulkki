package uk.xa0.tulkki.data.translation

import java.sql.Connection
import java.sql.SQLException
import org.junit.Assert
import org.junit.Test
import uk.xa0.tulkki.data.DaoSql
import uk.xa0.tulkki.data.JdbcSchemaExec
import uk.xa0.tulkki.data.RepoFiles
import uk.xa0.tulkki.data.Schema75Fixture
import uk.xa0.tulkki.data.TranslationTables
import uk.xa0.tulkki.data.schema.Schema76
import uk.xa0.tulkki.data.schema.Schema77
import uk.xa0.tulkki.data.schema.Schema80

/**
 * S5-6's queue-slice cell: the statements `TranslationStore`'s queue half runs through `:data` now.
 *
 * <p><strong>What this executes, and what it cannot.</strong> `TranslationQueueStore` reads through
 * the connection `HistoryDatabase.get` hands out, so its methods need Room's connection machinery and
 * cannot be called on the host - the gap `BlockingDaoExecutionTest` names once. What is executed here
 * is the package's <em>SQL</em>, over a schema-77 fixture reached the way a device reaches it, which is
 * where the queue's behaviour lives: the due reads' cutoff and order, the revival, the deletion and the
 * membership test. The cursor-to-[TranslationQueueRow] mapping is the store's and is a device look;
 * that is named rather than implied.
 *
 * <p>The statements bind positionally (`?`), which is what `TranslationQueueStore` hands SQLite, so
 * the helper below binds in the statement's own order rather than by a name the text does not carry.
 */
class TranslationQueueQueriesTest {

    private companion object {

        private const val STORE =
            "translation/src/main/java/uk/xa0/tulkki/translation/TranslationStore.kt"

        private const val PENDING = 0

        private const val DONE = 1

        private const val FAILED = 2

        private fun upgraded(): Connection {
            val connection = Schema75Fixture.openWithRows()
            Schema75Fixture.takeTheSchema75Step(connection)
            Schema76.applyUpgrade(JdbcSchemaExec(connection), 4242L)
            Schema77.applySchema(JdbcSchemaExec(connection))
            Schema80.applyUpgrade(JdbcSchemaExec(connection))
            return connection
        }

        private fun queue(
            connection: Connection, messageUuid: String, state: Int, due: Long, createdAt: Long) {
            DaoSql.exec(
                connection,
                "INSERT INTO " +
                    TranslationTables.QUEUE_TABLE +
                    " (" +
                    TranslationTables.QUEUE_MESSAGE_UUID +
                    ", " +
                    TranslationTables.QUEUE_BODY +
                    ", " +
                    TranslationTables.QUEUE_CACHE_KEY +
                    ", " +
                    TranslationTables.QUEUE_TARGET_LANGUAGE +
                    ", " +
                    TranslationTables.QUEUE_STATE +
                    ", " +
                    TranslationTables.QUEUE_ATTEMPTS +
                    ", " +
                    TranslationTables.QUEUE_NEXT_ATTEMPT_AT +
                    ", " +
                    TranslationTables.QUEUE_LAST_ERROR +
                    ", " +
                    TranslationTables.QUEUE_CREATED_AT +
                    ") VALUES (?,?,?,?,?,0,?,?,?)",
                messageUuid,
                "body",
                "key",
                "first",
                state,
                due,
                "first",
                createdAt)
        }

        /** One row as `state|attempts|next|target|cache`, which is what the revival changes. */
        private fun row(connection: Connection, messageUuid: String): String {
            return DaoSql.scalar(
                connection,
                "SELECT " +
                    TranslationTables.QUEUE_STATE +
                    " || '|' || " +
                    TranslationTables.QUEUE_ATTEMPTS +
                    " || '|' || " +
                    TranslationTables.QUEUE_NEXT_ATTEMPT_AT +
                    " || '|' || " +
                    TranslationTables.QUEUE_TARGET_LANGUAGE +
                    " || '|' || " +
                    TranslationTables.QUEUE_CACHE_KEY +
                    " FROM " +
                    TranslationTables.QUEUE_TABLE +
                    " WHERE " +
                    TranslationTables.QUEUE_MESSAGE_UUID +
                    " = ?",
                messageUuid)!!
        }

        /** The first column of every row, bound in the statement's own order. */
        private fun firstColumn(connection: Connection, sql: String, vararg args: Any?): List<String> {
            val out = ArrayList<String>()
            try {
                connection.prepareStatement(sql).use { statement ->
                    for (index in args.indices) {
                        statement.setObject(index + 1, args[index])
                    }
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
    }

    /** The two due reads answer the pending rows that are due, oldest first, and nobody else's. */
    @Test
    fun theDueReadsHonourTheStateTheCutoffAndTheOrder() {
        val connection = upgraded()
        queue(connection, "m-plain", PENDING, 500L, 1L)
        queue(connection, "m-muc", PENDING, 100L, 2L)
        queue(connection, "m-translated", PENDING, 9_000L, 3L)
        queue(connection, "m-finnish", FAILED, 1L, 4L)

        Assert.assertEquals(
            "the next due row is the earliest pending one inside the cutoff",
            listOf("m-muc"),
            firstColumn(connection, TranslationQueueQueries.NEXT_DUE, PENDING, 1_000L))
        Assert.assertEquals(
            "the snapshot is the same read as a list, in the same order, and stops at the cutoff",
            listOf("m-muc", "m-plain"),
            firstColumn(connection, TranslationQueueQueries.due(5), PENDING, 1_000L))
        Assert.assertEquals(
            "a row in another state is never due, whatever its instant",
            "0",
            DaoSql.scalar(connection, TranslationQueueQueries.COUNT_IN_STATE, DONE))
        Assert.assertEquals(
            "and the earliest instant is the pending rows' only",
            "100",
            DaoSql.scalar(connection, TranslationQueueQueries.EARLIEST_IN_STATE, PENDING))
    }

    /** The owner's tap revives one row and leaves a done one alone. */
    @Test
    fun makeDueRevivesTheRowAndLeavesADoneOneAlone() {
        val connection = upgraded()
        queue(connection, "m-plain", FAILED, 5_000L, 1L)
        queue(connection, "m-muc", DONE, 5_000L, 1L)

        DaoSql.exec(
            connection,
            TranslationQueueQueries.MAKE_DUE,
            PENDING,
            77L,
            "language",
            "fi",
            "m-plain",
            DONE)

        Assert.assertEquals(
            "the revived row is pending, due now, and carries the language in force",
            "0|0|77|language|fi",
            row(connection, "m-plain"))
        Assert.assertEquals(
            "and a done row is untouched",
            "1|0|5000|first|key",
            row(connection, "m-muc"))
    }

    /** The clear takes one state and leaves the others, and the membership test sees every state. */
    @Test
    fun theClearTakesOneStateAndTheMembershipTestSeesEveryState() {
        val connection = upgraded()
        queue(connection, "m-plain", PENDING, 1L, 1L)
        queue(connection, "m-finnish", PENDING, 1L, 2L)
        queue(connection, "m-muc", FAILED, 1L, 3L)

        Assert.assertEquals(
            "a queued message is queued whatever state it is in",
            listOf("1"),
            firstColumn(connection, TranslationQueueQueries.HAS_QUEUED, "m-muc"))

        DaoSql.exec(connection, TranslationQueueQueries.DELETE_IN_STATE, PENDING)

        Assert.assertEquals(
            "the pending rows are gone",
            "0",
            DaoSql.scalar(connection, TranslationQueueQueries.COUNT_IN_STATE, PENDING))
        Assert.assertEquals(
            "and the failed one is not",
            "1",
            DaoSql.scalar(connection, TranslationQueueQueries.COUNT_IN_STATE, FAILED))
    }

    /**
     * The port's pin: the store composes no queue SQL and holds no handle for the queue. The cache,
     * the usage ledger and the message write-back are the next slice and still use `db()`.
     */
    @Test
    fun theStoreComposesNoQueueSqlAndReachesTheQueueThroughTheSurface() {
        val store = RepoFiles.read(STORE)
        for (gone in arrayOf("TranslationTables.QUEUE_TABLE")) {
            Assert.assertFalse(
                "the store must not spell a queue column any more: " + gone,
                store.contains(gone))
        }
        Assert.assertTrue(
            "and it must reach the queue through the surface",
            store.contains("TranslationQueueStore.get(context)"))
    }
}
