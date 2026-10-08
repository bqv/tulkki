package uk.xa0.tulkki.data.translation

import java.sql.Connection
import java.sql.SQLException
import org.junit.Assert
import org.junit.Test
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
 * S5-6's last slice: the by-uuid message read, executed over a schema-78 fixture reached the way a
 * device reaches it.
 *
 * <p><strong>What this executes, and what it cannot.</strong> `MessageLookupStore` reads through the
 * connection `HistoryDatabase.get` hands out, so its methods need Room's connection machinery and
 * cannot be called on the host; the cursor-to-[Message] mapping is the store's and is a device look,
 * named as every other slice names it. What is executed is the package's own SQL, which is where the
 * read's meaning lives: a chunk answers exactly the uuids it names, an unknown uuid contributes
 * nothing rather than an empty row, and the statement's placeholder count is the chunk's.
 */
class MessageLookupQueriesTest {

    private companion object {

        private const val STORE =
            "translation/src/main/java/uk/xa0/tulkki/translation/TranslationStore.kt"

        private fun refused(statement: Runnable): Boolean {
            return try {
                statement.run()
                false
            } catch (e: IllegalArgumentException) {
                true
            }
        }

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

        /** The production statement for this chunk, answered as its ordered uuids. */
        private fun uuids(connection: Connection, vararg messageUuids: String): List<String> {
            val out = ArrayList<String>()
            try {
                connection.prepareStatement(
                        MessageLookupQueries.byUuid(messageUuids.size))
                    .use { statement ->
                        for (i in messageUuids.indices) {
                            statement.setObject(i + 1, messageUuids[i])
                        }
                        statement.executeQuery().use { results ->
                            while (results.next()) {
                                out.add(results.getString(Message.UUID))
                            }
                        }
                    }
            } catch (e: SQLException) {
                throw IllegalStateException("could not run the by-uuid read", e)
            }
            return out
        }

        private fun sorted(values: List<String>): List<String> {
            val out = ArrayList(values)
            out.sort()
            return out
        }
    }

    /** The named uuids are the answer, and a uuid nobody has is not. */
    @Test
    fun theChunkAnswersExactlyTheUuidsItNames() {
        val connection = upgraded()
        Assert.assertEquals(
            "the fixture's own messages are answered",
            listOf("m-plain"),
            uuids(connection, "m-plain"))
        Assert.assertEquals(
            "an unknown uuid contributes nothing, not an empty row",
            emptyList<String>(),
            uuids(connection, "no-such-message"))
        Assert.assertEquals(
            "and a chunk of several keeps the ones that exist",
            listOf("m-covered", "m-plain"),
            sorted(uuids(connection, "m-plain", "no-such-message", "m-covered")))
        Assert.assertEquals(
            "a uuid named twice is answered once: the read is a lookup, not a join",
            listOf("m-plain"),
            uuids(connection, "m-plain", "m-plain"))
    }

    /**
     * The statement is built for the chunk, because SQLite bounds a statement's parameters and a gap
     * can name hundreds of messages: one `?` per uuid, and a chunk of one is still a valid statement.
     */
    @Test
    fun theStatementIsBuiltForTheChunksSize() {
        Assert.assertEquals(
            "a chunk of one",
            "SELECT * FROM messages WHERE uuid IN (?)",
            MessageLookupQueries.byUuid(1))
        Assert.assertEquals(
            "a chunk of four has four binds and no trailing comma",
            "SELECT * FROM messages WHERE uuid IN (?,?,?,?)",
            MessageLookupQueries.byUuid(4))
        Assert.assertTrue(
            "and the bound is a bound: " + MessageLookupQueries.CHUNK,
            MessageLookupQueries.CHUNK > 0 && MessageLookupQueries.CHUNK < 999)
        Assert.assertTrue(
            "a chunk of zero is not a statement, and must be refused rather than built",
            refused(Runnable { MessageLookupQueries.byUuid(0) }))
    }

    /** The port's pin: the store no longer spells the read, and holds no handle of its own. */
    @Test
    fun theStoreComposesNoLookupSqlAndReachesItThroughTheSurface() {
        val store = RepoFiles.read(STORE)
        for (gone in
            arrayOf(
                "SELECT * FROM", "rawQuery", "getWritableDatabase", "CANDIDATE_CHUNK"
            )) {
            Assert.assertFalse(
                "the store must not spell the read any more: " + gone, store.contains(gone))
        }
        Assert.assertTrue(
            "and it must reach the read through the surface",
            store.contains("MessageLookupStore.get(context)"))
    }
}
