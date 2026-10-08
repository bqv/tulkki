package uk.xa0.tulkki.data.translation

import java.sql.Connection
import java.sql.SQLException
import org.junit.Assert
import org.junit.Test
import uk.xa0.tulkki.data.DaoSql
import uk.xa0.tulkki.data.JdbcSchemaExec
import uk.xa0.tulkki.data.Schema75Fixture
import uk.xa0.tulkki.data.TranslationTables
import uk.xa0.tulkki.data.schema.Schema76
import uk.xa0.tulkki.data.schema.Schema77
import uk.xa0.tulkki.data.schema.Schema80

/**
 * Item 17's per-row failure cause (schema 80), the storage half, on the host.
 *
 * <p><strong>Why the column exists at all.</strong> The queue's retry axis - `attempts` and
 * `next_attempt_at` - cannot say *why* a received row is stopped: "no key" and "the cap was reached"
 * are decided before any call is made, so a row stopped that way is never marked failed with a retry,
 * and the local check's refusal is recorded exactly like an API failure. A per-row tag is the only
 * storage that can answer "which received rows are blocked by which cause" for a re-enqueue.
 *
 * <p><strong>What `:data` owns and what it does not.</strong> This side stores the string: `TEXT`,
 * nullable, with `NULL` meaning *no cause recorded* - both a row that was never blocked and one
 * stopped by an ordinary retryable API failure, which is the same reason the column must not
 * overwrite that axis. The vocabulary the strings come from is `:translation`'s enum, so the two
 * values below are representative strings and not a copy of it; a `:data` test that pinned the
 * spelling would be the second answer only a test could keep in step.
 *
 * <p>The cells execute the package's own SQL over a schema-75 fixture walked the way a device walks
 * it, exactly as `TranslationQueueQueriesTest` does - `TranslationQueueStore` needs Room's connection
 * machinery and cannot be called on the host.
 */
class TranslationQueueFailureCauseTest {

    private companion object {

        /** A cause the app can clear. */
        private const val CLEARABLE = "no_key"

        /** The one cause that is not clearable: the check's own refusal. */
        private const val REFUSAL = "check_refused"

        /** The queue-side columns the failures screen's row image is made of, in the projection's own order. */
        private val FAILURE_ROW_IMAGE =
            listOf(
                TranslationTables.QUEUE_MESSAGE_UUID,
                TranslationTables.QUEUE_CONVERSATION_UUID,
                TranslationTables.QUEUE_BODY,
                TranslationTables.QUEUE_STATE,
                TranslationTables.QUEUE_ATTEMPTS,
                TranslationTables.QUEUE_CREATED_AT,
                TranslationTables.QUEUE_FAILED_AT,
                TranslationTables.QUEUE_LAST_ERROR,
                TranslationTables.QUEUE_FAILURE_CAUSE)

        private fun upgraded(): Connection {
            val connection = Schema75Fixture.openWithRows()
            Schema75Fixture.takeTheSchema75Step(connection)
            Schema76.applyUpgrade(JdbcSchemaExec(connection), 4242L)
            Schema77.applySchema(JdbcSchemaExec(connection))
            Schema80.applyUpgrade(JdbcSchemaExec(connection))
            return connection
        }

        private fun queue(connection: Connection, messageUuid: String, cause: String?) {
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
                    TranslationTables.QUEUE_CREATED_AT +
                    ", " +
                    TranslationTables.QUEUE_FAILURE_CAUSE +
                    ") VALUES (?, ?, ?, ?, ?)",
                messageUuid,
                "body",
                "cache-key",
                1L,
                cause)
        }

        private fun causeOf(connection: Connection, messageUuid: String): String? {
            try {
                connection.prepareStatement(
                        "SELECT " +
                            TranslationTables.QUEUE_FAILURE_CAUSE +
                            " FROM " +
                            TranslationTables.QUEUE_TABLE +
                            " WHERE " +
                            TranslationTables.QUEUE_MESSAGE_UUID +
                            " = ?")
                    .use { statement ->
                        statement.setString(1, messageUuid)
                        statement.executeQuery().use { rows ->
                            Assert.assertTrue("the row must be there", rows.next())
                            return rows.getString(1)
                        }
                    }
            } catch (e: SQLException) {
                throw AssertionError(e)
            }
        }

        /** `PRAGMA table_info`'s `notnull` flag for the cause column. */
        private fun notNullFlagOf(connection: Connection): Int {
            try {
                connection.prepareStatement(
                        "PRAGMA table_info(" + TranslationTables.QUEUE_TABLE + ")")
                    .use { statement ->
                        statement.executeQuery().use { columns ->
                            while (columns.next()) {
                                if (TranslationTables.QUEUE_FAILURE_CAUSE.equals(columns.getString("name"))) {
                                    return columns.getInt("notnull")
                                }
                            }
                        }
                    }
            } catch (e: SQLException) {
                throw AssertionError(e)
            }
            throw AssertionError("the file has no " + TranslationTables.QUEUE_FAILURE_CAUSE + " column")
        }
    }

    /** `NULL`, a clearable cause and the refusal all round-trip through the file unchanged. */
    @Test
    fun theColumnRoundTripsNullAClearableCauseAndTheRefusal() {
        val connection = upgraded()
        queue(connection, "m-none", null)
        queue(connection, "m-nokey", CLEARABLE)
        queue(connection, "m-refused", REFUSAL)

        Assert.assertNull("a row with no cause reads back no cause", causeOf(connection, "m-none"))
        Assert.assertEquals(
            "a clearable cause is stored as the string the consumer spelled",
            CLEARABLE,
            causeOf(connection, "m-nokey"))
        Assert.assertEquals(
            "and the refusal is stored the same way, distinct from it",
            REFUSAL,
            causeOf(connection, "m-refused"))
    }

    /**
     * `NULL` means *no cause recorded*, and that is a different answer from the refusal - the whole
     * point of not reusing a sentinel string for it.
     */
    @Test
    fun nullMeansNoCauseRecordedAndNotTheRefusal() {
        val connection = upgraded()
        queue(connection, "m-none", null)

        Assert.assertNull("no cause is recorded for a row that carries none", causeOf(connection, "m-none"))
        Assert.assertNotEquals(REFUSAL, causeOf(connection, "m-none"))
        Assert.assertEquals(
            "the column must be nullable, or `NULL` could not mean anything at all",
            0,
            notNullFlagOf(connection))
    }

    /** A re-enqueue clears the cause with the rest of the retry state, not before it. */
    @Test
    fun makeDueClearsTheCauseWithTheRestOfTheRetryState() {
        val connection = upgraded()
        queue(connection, "m-nokey", CLEARABLE)

        DaoSql.exec(connection, TranslationQueueQueries.MAKE_DUE, 0, 0L, "fi", "k", "m-nokey", 1)

        Assert.assertNull(
            "the revival clears the cause as it clears last_error",
            causeOf(connection, "m-nokey"))
    }

    /**
     * The failures screen's queue projection must name **every** column of its row image. Room does
     * not compare a row's declared columns with the query's, so a column the statement leaves out
     * arrives as the row's own default - which for the cause is `null`, i.e. "no cause recorded", an
     * answer a row stopped for the refusal never gave. This is the `MessagesDaoTest` assertion for
     * this projection, and it is why `failure_cause` was added to `QUEUE_FAILURES` in the same commit
     * as the column: a projection that drops it is silent.
     */
    @Test
    fun theFailuresProjectionNamesEveryColumnOfItsRowImage() {
        for (column in FAILURE_ROW_IMAGE) {
            Assert.assertTrue(
                "the failures projection must name `" +
                    column +
                    "`; one it drops arrives as the row's own default, and for `" +
                    TranslationTables.QUEUE_FAILURE_CAUSE +
                    "` that default is \"no cause recorded\" rather than the cause the row " +
                    "carries: " +
                    TranslationFailureQueries.QUEUE_FAILURES,
                TranslationFailureQueries.QUEUE_FAILURES.contains("q." + column))
        }
    }
}
