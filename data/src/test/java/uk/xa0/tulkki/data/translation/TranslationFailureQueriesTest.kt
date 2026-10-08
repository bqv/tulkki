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
import uk.xa0.tulkki.data.schema.Schema78
import uk.xa0.tulkki.data.schema.Schema79
import uk.xa0.tulkki.data.schema.Schema80

/**
 * S5-6's failures slice: the two projections behind the list, moved into `:data` and executed over a
 * schema-78 fixture reached the way a device reaches it.
 *
 * <p><strong>What this executes, and what it cannot.</strong> `TranslationFailureStore` reads through
 * the connection `HistoryDatabase.get` hands out, so its methods need Room's connection machinery and
 * cannot be called on the host. What is executed is the package's own <em>SQL</em>, which is where the
 * projections' meaning lives: which rows are a failure at all, that a deleted conversation is still
 * listed with no address, and that the message's own text is in the result set.
 *
 * <p>**The column pins moved here with the statements** (from `TranslationFailuresTest`, S5-6): the
 * reversal that the screen shows the message text, and the details each projection needs. The deciding
 * - order, size, reasons, times - stays `:translation`'s and is exercised by its own test.
 */
class TranslationFailureQueriesTest {

    private companion object {

        private const val STORE =
            "translation/src/main/java/uk/xa0/tulkki/translation/TranslationStore.kt"

        private val CONVERSATION = Schema75Fixture.CONVERSATION_PLAIN

        private const val JID = "matti@example.test"

        private const val FAILED_SEND = uk.xa0.tulkki.data.model.Message.TRANSLATION_FAILED

        private const val SETTLED_SEND = uk.xa0.tulkki.data.model.Message.TRANSLATION_DONE

        private const val RECEIVED = uk.xa0.tulkki.data.model.Message.STATUS_RECEIVED

        private fun upgraded(): Connection {
            val connection = Schema75Fixture.openWithRows()
            Schema75Fixture.takeTheSchema75Step(connection)
            Schema76.applyUpgrade(JdbcSchemaExec(connection), 4242L)
            Schema77.applySchema(JdbcSchemaExec(connection))
            Schema78.applyUpgrade(JdbcSchemaExec(connection))
            Schema79.applyUpgrade(JdbcSchemaExec(connection))
            Schema80.applyUpgrade(JdbcSchemaExec(connection))
            return connection
        }

        /** The fixture's own conversation, given the address the join resolves. */
        private fun seedConversation(connection: Connection) {
            DaoSql.exec(
                connection,
                "UPDATE conversations SET contactJid = ? WHERE uuid = ?",
                JID,
                CONVERSATION)
        }

        private fun queue(
            connection: Connection, uuid: String, attempts: Int, state: Int, body: String) {
            DaoSql.exec(
                connection,
                "INSERT OR REPLACE INTO " +
                    TranslationTables.QUEUE_TABLE +
                    " (" +
                    TranslationTables.QUEUE_MESSAGE_UUID +
                    ", " +
                    TranslationTables.QUEUE_CONVERSATION_UUID +
                    ", " +
                    TranslationTables.QUEUE_BODY +
                    ", " +
                    TranslationTables.QUEUE_CACHE_KEY +
                    ", " +
                    TranslationTables.QUEUE_STATE +
                    ", " +
                    TranslationTables.QUEUE_ATTEMPTS +
                    ", " +
                    TranslationTables.QUEUE_NEXT_ATTEMPT_AT +
                    ", " +
                    TranslationTables.QUEUE_CREATED_AT +
                    ") VALUES (?,?,?,?,?,?,1,1)",
                uuid,
                CONVERSATION,
                body,
                "key",
                state,
                attempts)
            // The fixture's own row for this uuid has no conversation that resolves to an address unless
            // the conversation names one, which `seedConversation` does when the cell wants it.
        }

        private fun message(
            connection: Connection, uuid: String, state: Int, status: Int, deleted: Int) {
            DaoSql.exec(
                connection,
                "INSERT OR REPLACE INTO messages (" +
                    uk.xa0.tulkki.data.model.Message.UUID +
                    ", " +
                    uk.xa0.tulkki.data.model.Message.CONVERSATION +
                    ", " +
                    uk.xa0.tulkki.data.model.Message.BODY +
                    ", " +
                    uk.xa0.tulkki.data.model.Message.TIME_SENT +
                    ", " +
                    uk.xa0.tulkki.data.model.Message.STATUS +
                    ", " +
                    uk.xa0.tulkki.data.model.Message.TRANSLATION_STATE +
                    ", " +
                    uk.xa0.tulkki.data.model.Message.DELETED +
                    ") VALUES (?,?,?,?,?,?,?)",
                uuid,
                CONVERSATION,
                "the draft",
                5L,
                status,
                state,
                deleted)
        }

        /** `uuid|state|attempts|body|address`, address spelled `null` when the join found none. */
        private fun queueRows(connection: Connection): List<String> {
            return rows(
                connection,
                TranslationFailureQueries.QUEUE_FAILURES,
                arrayOfNulls<Any?>(0),
                TranslationTables.QUEUE_MESSAGE_UUID,
                TranslationTables.QUEUE_STATE,
                TranslationTables.QUEUE_ATTEMPTS,
                TranslationTables.QUEUE_BODY,
                TranslationFailureQueries.CONVERSATION_JID)
        }

        /** `uuid|body|timeSent|address` for the send projection. */
        private fun sendRows(connection: Connection): List<String> {
            return rows(
                connection,
                TranslationFailureQueries.SEND_FAILURES,
                arrayOf<Any?>(FAILED_SEND, RECEIVED),
                uk.xa0.tulkki.data.model.Message.UUID,
                uk.xa0.tulkki.data.model.Message.BODY,
                uk.xa0.tulkki.data.model.Message.TIME_SENT,
                TranslationFailureQueries.CONVERSATION_JID)
        }

        private fun rows(
            connection: Connection, sql: String, args: Array<Any?>, vararg columns: String): List<String> {
            val out = mutableListOf<String>()
            try {
                connection.prepareStatement(sql).use { statement ->
                    for (i in args.indices) {
                        statement.setObject(i + 1, args[i])
                    }
                    statement.executeQuery().use { results ->
                        while (results.next()) {
                            val row = StringBuilder()
                            for (i in columns.indices) {
                                if (i > 0) {
                                    row.append('|')
                                }
                                val value = results.getString(columns[i])
                                row.append(if (value == null) "null" else value)
                            }
                            out.add(row.toString())
                        }
                    }
                }
            } catch (e: SQLException) {
                throw IllegalStateException("could not run: " + sql, e)
            }
            return out
        }
    }

    /**
     * The reversal, pinned: the owner decided the failures screen shows the message a failure is
     * about, so both projections select the body column. The cache key, which is derived from the
     * text, still has no business in either read.
     */
    @Test
    fun bothProjectionsCarryTheMessageText() {
        Assert.assertTrue(
            "the queue read must select the text the screen shows: " +
                TranslationFailureQueries.QUEUE_FAILURES,
            TranslationFailureQueries.QUEUE_FAILURES.contains(TranslationTables.QUEUE_BODY))
        Assert.assertTrue(
            "and so must the send read: " + TranslationFailureQueries.SEND_FAILURES,
            TranslationFailureQueries.SEND_FAILURES.contains(
                uk.xa0.tulkki.data.model.Message.BODY))
        Assert.assertFalse(
            "the cache key is derived from the text; it has no business here",
            TranslationFailureQueries.QUEUE_FAILURES.contains(
                TranslationTables.QUEUE_CACHE_KEY))
    }

    /** Every column a received row needs, and the join that gives it an address. */
    @Test
    fun theQueueProjectionAsksForTheFactsARowNeeds() {
        val sql = TranslationFailureQueries.QUEUE_FAILURES
        for (column in
            listOf(
                TranslationTables.QUEUE_MESSAGE_UUID,
                TranslationTables.QUEUE_CONVERSATION_UUID,
                TranslationTables.QUEUE_BODY,
                TranslationTables.QUEUE_STATE,
                TranslationTables.QUEUE_ATTEMPTS,
                TranslationTables.QUEUE_CREATED_AT,
                TranslationTables.QUEUE_FAILED_AT,
                TranslationTables.QUEUE_LAST_ERROR,
                uk.xa0.tulkki.data.model.Conversation.CONTACTJID)) {
            Assert.assertTrue("the row needs " + column + ": " + sql, sql.contains(column))
        }
        Assert.assertTrue(
            "the read is still the queue's own rows: " + sql,
            sql.contains(TranslationTables.QUEUE_TABLE))
    }

    /**
     * The send projection's shape: the three facts that are decisions rather than columns are filtered
     * - the state, the status boundary and the deletion - so only a send that failed and never went is
     * listed.
     */
    @Test
    fun theSendProjectionSelectsOnlyWhatARowMayShow() {
        val sql = TranslationFailureQueries.SEND_FAILURES
        for (column in
            listOf(
                uk.xa0.tulkki.data.model.Message.UUID,
                uk.xa0.tulkki.data.model.Message.CONVERSATION,
                uk.xa0.tulkki.data.model.Message.BODY,
                uk.xa0.tulkki.data.model.Message.TIME_SENT,
                uk.xa0.tulkki.data.model.Conversation.CONTACTJID)) {
            Assert.assertTrue("the row needs " + column + ": " + sql, sql.contains(column))
        }
        Assert.assertTrue(
            "a settled send must not be listed: " + sql,
            sql.contains(uk.xa0.tulkki.data.model.Message.TRANSLATION_STATE + "=?"))
        Assert.assertTrue(
            "a received message is the queue's business, not this read's: " + sql,
            sql.contains(uk.xa0.tulkki.data.model.Message.STATUS + ">?"))
        Assert.assertTrue(
            "nor is a message the owner deleted: " + sql,
            sql.contains(uk.xa0.tulkki.data.model.Message.DELETED + "=0"))
    }

    /** Only a row that has attempted something is a failure, and it is listed with its own words. */
    @Test
    fun theQueueProjectionListsOnlyRowsThatHaveFailed() {
        val connection = upgraded()
        seedConversation(connection)
        queue(connection, "m-plain", 2, 2, "the message that failed")
        queue(connection, "m-finnish", 0, 2, "never attempted")

        Assert.assertEquals(
            "the attempted row is the list, with its text and the conversation's address",
            listOf("m-plain|2|2|the message that failed|" + JID),
            queueRows(connection))
    }

    /** A failure whose conversation has been deleted is still a failure, with no address. */
    @Test
    fun aDeletedConversationIsStillListedWithNoAddress() {
        val connection = upgraded()
        // The conversation really goes: foreign keys are off on the host fixture, so the message and
        // its queue row stay behind - which is exactly the orphan the screen has to list.
        DaoSql.exec(connection, "DELETE FROM conversations WHERE uuid = ?", CONVERSATION)
        queue(connection, "m-plain", 2, 1, "orphan text")

        Assert.assertEquals(
            "the row is listed and the address is absent, not invented",
            listOf("m-plain|1|2|orphan text|null"),
            queueRows(connection))
    }

    /**
     * The send read: a failed, undeleted outgoing row is a failure; a settled one, a received one and a
     * deleted one are not.
     */
    @Test
    fun theSendProjectionListsAFailedSendAndNothingElse() {
        val connection = upgraded()
        seedConversation(connection)
        message(connection, "send-failed", FAILED_SEND, 5, 0)
        message(connection, "send-settled", SETTLED_SEND, 5, 0)
        message(connection, "received-failed", FAILED_SEND, RECEIVED, 0)
        message(connection, "send-deleted", FAILED_SEND, 5, 1)

        Assert.assertEquals(
            "one row, the one that could not be translated and has not gone",
            listOf("send-failed|the draft|5|" + JID),
            sendRows(connection))
    }

    /** The port's pin: the store walks no cursor and reaches the projections through the surface. */
    @Test
    fun theStoreComposesNoFailureSqlAndReachesItThroughTheSurface() {
        val store = RepoFiles.read(STORE)
        Assert.assertFalse(
            "the store must not spell a projection any more",
            store.contains("SELECT q.") || store.contains("SELECT m."))
        Assert.assertTrue(
            "and it must reach them through the surface",
            store.contains("TranslationFailureStore.get(context)"))
    }
}
