package uk.xa0.tulkki.data.translation

import java.sql.Connection
import java.sql.SQLException
import org.junit.Assert
import org.junit.Test
import uk.xa0.tulkki.data.DaoSql
import uk.xa0.tulkki.data.JdbcSchemaExec
import uk.xa0.tulkki.data.RepoFiles
import uk.xa0.tulkki.data.Schema75Fixture
import uk.xa0.tulkki.data.model.Message
import uk.xa0.tulkki.data.schema.Schema76
import uk.xa0.tulkki.data.schema.Schema77
import uk.xa0.tulkki.data.schema.Schema78
import uk.xa0.tulkki.data.schema.Schema79
import uk.xa0.tulkki.data.schema.Schema80

/**
 * S5-6's write-back slice: the three translation columns of one message, and one conversation's
 * detected language, executed over a schema-78 fixture reached the way a device reaches it.
 *
 * <p><strong>What this executes, and what it cannot.</strong> `MessageTranslationStore` writes through
 * the connection `HistoryDatabase.get` hands out, so its methods need Room's connection machinery and
 * cannot be called on the host. What is executed is the package's own <em>SQL</em>, which is where the
 * safety property lives: **every statement names its row by key and touches only its own columns**, so
 * a write-back that a redraw triggers can never clobber a row it did not read. The fixture's own
 * message rows carry bodies and states, so a statement that reached further than its three columns
 * would be visible here rather than only on a device.
 */
class MessageTranslationQueriesTest {

    private companion object {

        private const val STORE =
            "translation/src/main/java/uk/xa0/tulkki/translation/TranslationStore.kt"

        /** The fixture's own message, whose body must survive a write-back. */
        private const val MESSAGE = "m-plain"

        /** Another of the fixture's conversations, so "by its own key" has a second key to leave alone. */
        private val OTHER_CONVERSATION = Schema75Fixture.CONVERSATION_CLEARED

        /** The fixture's own conversation, whose row must survive the language write-back. */
        private val CONVERSATION = Schema75Fixture.CONVERSATION_PLAIN

        /** A schema-78 file, reached the way a device reaches it. */
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

        /** `translated_body|translation_lang|translation_state`, unreadable nulls spelled out. */
        private fun translationOf(connection: Connection, uuid: String): String {
            return read(
                connection,
                uuid,
                Message.TRANSLATED_BODY,
                Message.TRANSLATION_LANG,
                Message.TRANSLATION_STATE)
        }

        /**
         * The message's own body, which no write-back may move. The translation state is deliberately
         * *not* here: it is one of the three columns the write-back owns, so it is expected to change.
         */
        private fun bodyOf(connection: Connection, uuid: String): String {
            return read(connection, uuid, Message.BODY)
        }

        /** The conversation's detected language by its key, unreadable nulls spelled out. */
        private fun languageOf(connection: Connection, uuid: String): String {
            val value =
                DaoSql.scalarObject(
                    connection,
                    MessageTranslationQueries.READ_CONVERSATION_LANGUAGE,
                    uuid)
            return if (value == null) "null" else value.toString()
        }

        private fun read(connection: Connection, uuid: String, vararg columns: String): String {
            val sql = StringBuilder("SELECT ")
            for (i in columns.indices) {
                if (i > 0) {
                    sql.append(", ")
                }
                sql.append(columns[i])
            }
            sql.append(" FROM ").append(Message.TABLENAME).append(" WHERE ").append(Message.UUID).append(" = ?")
            val out = StringBuilder()
            try {
                connection.prepareStatement(sql.toString()).use { statement ->
                    statement.setObject(1, uuid)
                    statement.executeQuery().use { results ->
                        Assert.assertTrue("the fixture's message must exist: " + uuid, results.next())
                        for (i in 1..columns.size) {
                            if (i > 1) {
                                out.append('|')
                            }
                            val value = results.getString(i)
                            out.append(if (value == null) "null" else value)
                        }
                    }
                }
            } catch (e: SQLException) {
                throw IllegalStateException("could not read the message row", e)
            }
            return out.toString()
        }
    }

    /** The write-back names its row and its three columns, and leaves the rest of the row alone. */
    @Test
    fun theWriteBackTouchesTheThreeTranslationColumnsAndNothingElse() {
        val connection = upgraded()
        val before = bodyOf(connection, MESSAGE)

        DaoSql.exec(
            connection,
            MessageTranslationQueries.WRITE_TRANSLATION,
            "käännös",
            "fi",
            Message.TRANSLATION_DONE,
            MESSAGE)

        Assert.assertEquals(
            "the three columns are the write-back's",
            "käännös|fi|" + Message.TRANSLATION_DONE,
            translationOf(connection, MESSAGE))
        Assert.assertEquals(
            "and the message's own body is exactly what it was - the write-back did not read it " +
                "and must not write it",
            before,
            bodyOf(connection, MESSAGE))
    }

    /**
     * `forget`: the stored pair goes, and the state goes back to none. The body is still the body -
     * this is about the translation, not the message.
     */
    @Test
    fun clearingTheTranslationLeavesTheMessageItselfAlone() {
        val connection = upgraded()
        DaoSql.exec(
            connection,
            MessageTranslationQueries.WRITE_TRANSLATION,
            "käännös",
            "fi",
            Message.TRANSLATION_DONE,
            MESSAGE)
        val before = bodyOf(connection, MESSAGE)

        DaoSql.exec(connection, MessageTranslationQueries.CLEAR_TRANSLATION, MESSAGE)

        Assert.assertEquals(
            "nothing is stored for the message any more",
            "null|null|" + Message.TRANSLATION_NONE,
            translationOf(connection, MESSAGE))
        Assert.assertEquals(
            "and its own text is untouched", before, bodyOf(connection, MESSAGE))
    }

    /** The conversation's language: one column read by key, one column written by key. */
    @Test
    fun theConversationLanguageIsReadAndWrittenByItsOwnKey() {
        val connection = upgraded()
        val untouchedBefore = languageOf(connection, OTHER_CONVERSATION)

        DaoSql.exec(
            connection,
            MessageTranslationQueries.WRITE_CONVERSATION_LANGUAGE,
            "fi",
            CONVERSATION)

        Assert.assertEquals(
            "and it is the language that was written",
            "fi",
            DaoSql.scalar(
                connection,
                MessageTranslationQueries.READ_CONVERSATION_LANGUAGE,
                CONVERSATION))
        Assert.assertEquals(
            "and another conversation's own language is exactly what it was",
            untouchedBefore,
            languageOf(connection, OTHER_CONVERSATION))
    }

    /** The port's pin: the store composes no message or conversation SQL any more. */
    @Test
    fun theStoreComposesNoWriteBackSqlAndReachesItThroughTheSurface() {
        val store = RepoFiles.read(STORE)
        for (gone in arrayOf("Message.TRANSLATED_BODY", "Conversation.DETECTED_LANGUAGE")) {
            Assert.assertFalse(
                "the store must not spell a write-back column any more: " + gone,
                store.contains(gone))
        }
        Assert.assertTrue(
            "and it must reach the write-back through the surface",
            store.contains("MessageTranslationStore.get(context)"))
    }
}
