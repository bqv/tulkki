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
 * S5-6's cache slice: the answers already paid for, executed over a schema-78 fixture reached the way
 * a device reaches it.
 *
 * <p><strong>What this executes, and what it cannot.</strong> `TranslationCacheStore` reads and writes
 * through the connection `HistoryDatabase.get` hands out, so its methods need Room's connection
 * machinery and cannot be called on the host - the gap `BlockingDaoExecutionTest` names once. What is
 * executed is the package's own <em>SQL</em>: the lookup by key, and the `REPLACE` that makes a
 * re-bought answer one row rather than two. The cursor-to-[TranslationCacheRow] mapping is the store's
 * and is a device look, named rather than implied.
 */
class TranslationCacheQueriesTest {

    private companion object {

        private const val STORE =
            "translation/src/main/java/uk/xa0/tulkki/translation/TranslationStore.kt"

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

        /**
         * The production lookup's row, as `language|body|tokens` - the three columns the store maps, read
         * through `TranslationCacheQueries.BY_KEY` rather than through a query spelled for the test.
         */
        private fun row(connection: Connection, key: String): String? {
            try {
                return connection.prepareStatement(TranslationCacheQueries.BY_KEY).use { statement ->
                    statement.setObject(1, key)
                    statement.executeQuery().use { results ->
                        if (!results.next()) {
                            null
                        } else {
                            results.getString(TranslationTables.CACHE_DETECTED_LANGUAGE) +
                                "|" +
                                results.getString(TranslationTables.CACHE_TRANSLATED_BODY) +
                                "|" +
                                results.getInt(TranslationTables.CACHE_TOTAL_TOKENS)
                        }
                    }
                }
            } catch (e: SQLException) {
                throw IllegalStateException("could not read the cache row for " + key, e)
            }
        }

        /**
         * One answer, written the way `TranslationCacheStore.put` writes it: every column named, `REPLACE`
         * on the key.
         */
        private fun put(
            connection: Connection, key: String, language: String, body: String, tokens: Int) {
            DaoSql.exec(
                connection,
                "INSERT OR REPLACE INTO " +
                    TranslationTables.CACHE_TABLE +
                    " (" +
                    TranslationTables.CACHE_KEY +
                    ", " +
                    TranslationTables.CACHE_DETECTED_LANGUAGE +
                    ", " +
                    TranslationTables.CACHE_TRANSLATED_BODY +
                    ", " +
                    TranslationTables.CACHE_TOTAL_TOKENS +
                    ", " +
                    TranslationTables.CACHE_CREATED_AT +
                    ") VALUES (?,?,?,?,1)",
                key,
                language,
                body,
                tokens)
        }
    }

    /** The lookup answers the row the key names, and nothing else's. */
    @Test
    fun theLookupAnswersTheKeyItWasGiven() {
        val connection = upgraded()
        put(connection, "key-one", "fi", "ensimmäinen", 12)
        put(connection, "key-two", "sv", "andra", 34)

        Assert.assertEquals(
            "the row is the one the key names", "fi|ensimmäinen|12", row(connection, "key-one"))
        Assert.assertNull(
            "and an answer that was never bought has no row", row(connection, "key-never"))
    }

    /**
     * A key is written once, however often it is bought: the store's `REPLACE` makes the second answer
     * the row rather than a second row, which is what stops the same text being found twice.
     */
    @Test
    fun rebuyingAKeyReplacesTheRowRatherThanAddingOne() {
        val connection = upgraded()
        put(connection, "key-one", "fi", "ensimmäinen", 12)
        put(connection, "key-one", "fi", "toinen", 99)

        Assert.assertEquals(
            "one row for one key",
            "1",
            DaoSql.scalar(
                connection,
                "SELECT COUNT(*) FROM " +
                    TranslationTables.CACHE_TABLE +
                    " WHERE " +
                    TranslationTables.CACHE_KEY +
                    " = 'key-one'"))
        Assert.assertEquals(
            "and it holds the answer that was bought last",
            "fi|toinen|99",
            row(connection, "key-one"))
    }

    /** The port's pin: the store composes no cache SQL and reaches the table through the surface. */
    @Test
    fun theStoreComposesNoCacheSqlAndReachesItThroughTheSurface() {
        val store = RepoFiles.read(STORE)
        for (gone in
            arrayOf(
                "TranslationTables.CACHE_TABLE", "TranslationTables.CACHE_TRANSLATED_BODY"
            )) {
            Assert.assertFalse(
                "the store must not spell a cache column any more: " + gone,
                store.contains(gone))
        }
        Assert.assertTrue(
            "and it must reach the cache through the surface",
            store.contains("TranslationCacheStore.get(context)"))
    }
}
