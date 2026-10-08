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
 * S5-6's ledger slice: the day's spend split by where the call went, executed over a schema-78
 * fixture reached the way a device reaches it.
 *
 * <p><strong>What this executes, and what it cannot.</strong> `TranslationUsageStore` reads and writes
 * through the connection `HistoryDatabase.get` hands out, so its methods need Room's connection
 * machinery and cannot be called on the host. What is executed is the package's own <em>SQL</em>, in
 * the order the store runs it, which is where the ledger's behaviour lives: the two writes being one
 * call, the `''` key being one bucket and not two, the name being resolved at read time, and - the
 * cell the whole breakdown rests on - **the buckets summing to the day's own figure**.
 *
 * <p>The cursor-to-[UsageCounts] mapping is the store's and is a device look, named rather than
 * implied, as the queue slice's is.
 */
class TranslationUsageQueriesTest {

    private companion object {

        private const val STORE =
            "translation/src/main/java/uk/xa0/tulkki/translation/TranslationStore.kt"

        private const val TRANSLATION_SERVICE =
            "translation/src/main/java/uk/xa0/tulkki/translation/TranslationService.kt"

        private const val DAY = "2026-10-12"

        private const val OTHER_DAY = "2026-10-11"

        private const val ONE_TO_ONE = "conv-contact"

        private const val GROUP = "conv-room"

        private const val GONE = "conv-deleted"

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
         * One one-to-one and one group chat, so the join has a name and a mode to resolve.
         *
         * <p>The name says what it seeds and not the plural of `conversation`: the naming sweep counts a
         * new identifier that spells the banned word, however innocent the helper is.
         */
        private fun seedAOneToOneAndAGroupChat(connection: Connection) {
            DaoSql.exec(
                connection,
                "INSERT INTO conversations (uuid, accountUuid, contactJid, mode) VALUES (?,?,?,0)",
                ONE_TO_ONE,
                Schema75Fixture.ACCOUNT,
                "matti@example.test")
            DaoSql.exec(
                connection,
                "INSERT INTO conversations (uuid, accountUuid, contactJid, mode) VALUES (?,?,?,1)",
                GROUP,
                Schema75Fixture.ACCOUNT,
                "room@conference.example.test")
        }

        /**
         * One call, filed the way `TranslationUsageStore.add` files it: the day's row seeded with
         * `IGNORE`, added to in place, and the day-and-origin row the same. The order is the store's.
         */
        private fun add(
            connection: Connection, day: String, origin: String?, hit: Int, miss: Int, output: Int) {
            val key = if (origin == null) "" else origin
            DaoSql.exec(connection, TranslationUsageQueries.TOUCH_DAY, day)
            DaoSql.exec(
                connection,
                TranslationUsageQueries.ADD_TO_DAY,
                hit,
                miss,
                output,
                0,
                0,
                0,
                day)
            DaoSql.exec(connection, TranslationUsageQueries.TOUCH_ORIGIN, day, key)
            DaoSql.exec(
                connection,
                TranslationUsageQueries.ADD_TO_ORIGIN,
                hit,
                miss,
                output,
                0,
                0,
                0,
                day,
                key)
        }

        private fun sumOfDay(): String {
            return "SELECT SUM(" + sixColumns() + ") FROM " + TranslationTables.USAGE_TABLE + " WHERE " +
                TranslationTables.USAGE_DAY + " = ?"
        }

        private fun sumOfBuckets(): String {
            return "SELECT SUM(" + sixColumns() + ") FROM " + TranslationTables.USAGE_ORIGIN_TABLE +
                " WHERE " + TranslationTables.USAGE_DAY + " = ?"
        }

        /** All six counts added, so a short sum cannot hide a bucket's off-peak tokens. */
        private fun sixColumns(): String {
            return TranslationTables.USAGE_PEAK_CACHE_HIT +
                " + " +
                TranslationTables.USAGE_PEAK_CACHE_MISS +
                " + " +
                TranslationTables.USAGE_PEAK_OUTPUT +
                " + " +
                TranslationTables.USAGE_OFF_PEAK_CACHE_HIT +
                " + " +
                TranslationTables.USAGE_OFF_PEAK_CACHE_MISS +
                " + " +
                TranslationTables.USAGE_OFF_PEAK_OUTPUT
        }

        private fun countOfBuckets(): String {
            return "SELECT COUNT(*) FROM " +
                TranslationTables.USAGE_ORIGIN_TABLE +
                " WHERE " +
                TranslationTables.USAGE_DAY +
                " = ?"
        }

        /** The resolved address a bucket's origin names, read through the production join. */
        private fun addressOf(origin: String): String {
            return "SELECT c." +
                uk.xa0.tulkki.data.model.Conversation.CONTACTJID +
                " FROM " +
                TranslationTables.USAGE_ORIGIN_TABLE +
                " t LEFT JOIN conversations c ON c." +
                uk.xa0.tulkki.data.model.Conversation.UUID +
                " = t." +
                TranslationTables.USAGE_ORIGIN +
                " WHERE t." +
                TranslationTables.USAGE_DAY +
                " = ? AND t." +
                TranslationTables.USAGE_ORIGIN +
                " = '" +
                origin +
                "'"
        }

        private fun modeOf(origin: String): String {
            return "SELECT c." +
                uk.xa0.tulkki.data.model.Conversation.MODE +
                " FROM " +
                TranslationTables.USAGE_ORIGIN_TABLE +
                " t LEFT JOIN conversations c ON c." +
                uk.xa0.tulkki.data.model.Conversation.UUID +
                " = t." +
                TranslationTables.USAGE_ORIGIN +
                " WHERE t." +
                TranslationTables.USAGE_DAY +
                " = ? AND t." +
                TranslationTables.USAGE_ORIGIN +
                " = '" +
                origin +
                "'"
        }

        private fun resolvedOf(origin: String): String {
            return "SELECT CASE WHEN c." +
                uk.xa0.tulkki.data.model.Conversation.UUID +
                " IS NULL THEN 0 ELSE 1 END FROM " +
                TranslationTables.USAGE_ORIGIN_TABLE +
                " t LEFT JOIN conversations c ON c." +
                uk.xa0.tulkki.data.model.Conversation.UUID +
                " = t." +
                TranslationTables.USAGE_ORIGIN +
                " WHERE t." +
                TranslationTables.USAGE_DAY +
                " = ? AND t." +
                TranslationTables.USAGE_ORIGIN +
                " = '" +
                origin +
                "'"
        }

        private fun scalar(connection: Connection, sql: String, day: String): String? {
            return scalar(connection, sql, *arrayOf<Any?>(day))
        }

        private fun scalar(connection: Connection, sql: String, vararg args: Any?): String? {
            val value = scalarObject(connection, sql, *args)
            return if (value == null) null else value.toString()
        }

        private fun scalarObject(connection: Connection, sql: String, vararg args: Any?): Any? {
            try {
                connection.prepareStatement(sql).use { statement ->
                    for (i in args.indices) {
                        statement.setObject(i + 1, args[i])
                    }
                    statement.executeQuery().use { results ->
                        Assert.assertTrue("no row for " + sql, results.next())
                        return results.getObject(1)
                    }
                }
            } catch (e: SQLException) {
                throw IllegalStateException("could not query: " + sql, e)
            }
        }
    }

    /**
     * **The summing cell.** Every bucket that holds tokens is read back and added up, and the sum must
     * be the day's own row - a breakdown that does not add up is worse than no breakdown, which is why
     * this is asserted rather than assumed.
     */
    @Test
    fun theBucketsSumToTheDaysOwnFigure() {
        val connection = upgraded()
        seedAOneToOneAndAGroupChat(connection)
        add(connection, DAY, ONE_TO_ONE, 3, 4, 5)
        add(connection, DAY, GROUP, 1, 1, 1)
        add(connection, DAY, GONE, 2, 0, 7)
        add(connection, DAY, null, 10, 20, 30)
        add(connection, OTHER_DAY, ONE_TO_ONE, 100, 100, 100)

        val buckets = scalar(connection, sumOfBuckets(), DAY)
        val day = scalar(connection, sumOfDay(), DAY)

        Assert.assertEquals(
            "the day's buckets must add up to the day's own row, figure for figure",
            day,
            buckets)
        Assert.assertEquals(
            "and the sum is every call of that day, the other day's excluded",
            "84",
            buckets)
    }

    /**
     * The `''` key is one bucket, not many: SQLite treats every `NULL` in a unique key as distinct,
     * which is why the column is `NOT NULL DEFAULT ''` - and this is the cell that says so, by seeding
     * the same bucket twice and finding one row.
     */
    @Test
    fun theNotTiedBucketIsOneRowHoweverOftenItIsWritten() {
        val connection = upgraded()
        add(connection, DAY, null, 1, 2, 3)
        add(connection, DAY, null, 4, 5, 6)

        Assert.assertEquals(
            "two calls with no conversation are one bucket",
            "1",
            scalar(connection, countOfBuckets(), DAY))
        Assert.assertEquals(
            "and it holds both calls' tokens, not the second one's alone",
            "21",
            scalar(connection, sumOfBuckets(), DAY))
        Assert.assertEquals(
            "with nothing stored as a null key",
            "0",
            scalar(
                connection,
                "SELECT COUNT(*) FROM " +
                    TranslationTables.USAGE_ORIGIN_TABLE +
                    " WHERE " +
                    TranslationTables.USAGE_ORIGIN +
                    " IS NULL",
                *arrayOfNulls<Any?>(0)))
    }

    /** The name is resolved from `conversations` at read time, and never stored in the ledger. */
    @Test
    fun theNameIsResolvedAtReadTimeAndTheKindComesFromTheConversation() {
        val connection = upgraded()
        seedAOneToOneAndAGroupChat(connection)
        add(connection, DAY, ONE_TO_ONE, 1, 1, 1)
        add(connection, DAY, GROUP, 1, 1, 1)

        Assert.assertEquals(
            "the one-to-one's address is the conversation's own, read not stored",
            "matti@example.test",
            scalar(connection, addressOf(ONE_TO_ONE), DAY))
        Assert.assertEquals(
            "and its mode is the conversation's: one-to-one",
            "0",
            scalar(connection, modeOf(ONE_TO_ONE), DAY))
        Assert.assertEquals("a group chat", "1", scalar(connection, modeOf(GROUP), DAY))
        Assert.assertEquals(
            "and nothing about the address is in the ledger table itself",
            "0",
            scalar(
                connection,
                "SELECT COUNT(*) FROM " +
                    TranslationTables.USAGE_ORIGIN_TABLE +
                    " WHERE " +
                    TranslationTables.USAGE_ORIGIN +
                    " LIKE '%@%'",
                *arrayOfNulls<Any?>(0)))
    }

    /**
     * A conversation the owner has deleted: its bucket still appears, unresolved, and still counts
     * towards the day - the two facts the read model's `DELETED_CONVERSATION` kind exists for.
     */
    @Test
    fun anOriginThatNoLongerResolvesIsStillItsOwnBucket() {
        val connection = upgraded()
        add(connection, DAY, GONE, 5, 0, 0)

        Assert.assertEquals(
            "the bucket is there even though nothing names it", "1", scalar(connection, countOfBuckets(), DAY))
        Assert.assertEquals(
            "and it did not resolve: no conversation row answered the join",
            "0",
            scalar(connection, resolvedOf(GONE), DAY))
        Assert.assertNull(
            "and there is no address to show", scalarObject(connection, addressOf(GONE), DAY))
        Assert.assertEquals(
            "its tokens are still the day's, so the sum still holds",
            scalar(connection, sumOfDay(), DAY),
            scalar(connection, sumOfBuckets(), DAY))
    }

    /**
     * A batch: one request covering several rooms. Its marker is a reserved origin, so the bucket is
     * still one row of the same table and still sums - and the read must tell it apart from a deleted
     * conversation, which is the trap: the marker never resolves, because no conversation has that
     * uuid.
     */
    @Test
    fun theSeveralRoomsMarkerIsItsOwnBucketAndNotADeletedConversation() {
        val connection = upgraded()
        add(connection, DAY, UsageOrigin.MULTI_ROOM_ORIGIN, 6, 7, 8)

        Assert.assertEquals(
            "the marker is one bucket", "1", scalar(connection, countOfBuckets(), DAY))
        Assert.assertEquals(
            "and it never resolves: the leading `*` cannot be a uuid",
            "0",
            scalar(connection, resolvedOf(UsageOrigin.MULTI_ROOM_ORIGIN), DAY))
        Assert.assertNull(
            "so it carries no address, rather than a resurrected one",
            scalarObject(connection, addressOf(UsageOrigin.MULTI_ROOM_ORIGIN), DAY))
        Assert.assertEquals(
            "and its tokens are still the day's",
            scalar(connection, sumOfDay(), DAY),
            scalar(connection, sumOfBuckets(), DAY))
        Assert.assertTrue(
            "the marker is a reserved value no uuid can spell: " + UsageOrigin.MULTI_ROOM_ORIGIN,
            UsageOrigin.MULTI_ROOM_ORIGIN.startsWith("*") &&
                !UsageOrigin.MULTI_ROOM_ORIGIN.isEmpty())
    }

    /** And the batch path is where that marker is written, or the kind above is decoration. */
    @Test
    fun theBatchPathFilesTheSeveralRoomsMarker() {
        Assert.assertTrue(
            "TranslationService's batch purchase must file the reserved marker",
            RepoFiles.read(TRANSLATION_SERVICE).contains("UsageOrigin.MULTI_ROOM_ORIGIN"))
    }

    /** The port's pin: the store composes no ledger SQL and reaches the tables through the surface. */
    @Test
    fun theStoreComposesNoLedgerSqlAndReachesItThroughTheSurface() {
        val store = RepoFiles.read(STORE)
        for (gone in arrayOf("TranslationTables.USAGE_TABLE", "USAGE_PEAK_CACHE_HIT")) {
            Assert.assertFalse(
                "the store must not spell a ledger column any more: " + gone,
                store.contains(gone))
        }
        Assert.assertTrue(
            "and it must reach the ledger through the surface",
            store.contains("TranslationUsageStore.get(context)"))
    }
}
