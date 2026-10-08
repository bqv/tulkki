package uk.xa0.tulkki.data.translation

import java.nio.file.Files
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
 * Schema 78's read model: the queue's counts, as `QueueSummary`.
 *
 * <p><strong>What this executes, and what it cannot.</strong> `TranslationQueueDao.summary` returns a
 * `Flow` through Room's connection machinery, which the host cannot drive (the gap
 * `BlockingDaoExecutionTest` names once). What is executed here is the package's own *SQL* over a
 * schema-78 fixture reached the way a device reaches it - 74 fixture, the legacy 75 step, 76, 77, 78 -
 * which is where the counts' meaning lives: two states, the earliest due instant, and `NULL` for a
 * queue that owes nothing.
 *
 * <p><strong>Two instruments for the declaration.</strong> The entity is a Kotlin type and the table
 * is a DDL string, and the two agreeing is what `RoomValidationTest` and `FreshInstallSchemaTest`
 * prove by running Room's own validator over both the migrated and the fresh file. What this test adds
 * is the half those two cannot say: Room's *generated* `createAllTables` carries a column for every
 * name `TranslationTables` publishes, so the entity's declaration and the DDL constants are the same
 * table rather than two that happen to validate today.
 */
class TranslationQueueSummaryTest {

    private companion object {

        private const val DAO_SOURCE =
            "data/src/main/java/uk/xa0/tulkki/data/translation/TranslationQueueDao.kt"

        private const val GENERATED_DAO =
            "data/build/generated/ksp/debug/kotlin/uk/xa0/tulkki/data/translation/" +
                "TranslationQueueDao_Impl.kt"

        private const val GENERATED_DATABASE =
            "data/build/generated/ksp/debug/kotlin/uk/xa0/tulkki/data/HistoryDatabase_Impl.kt"

        private const val PENDING = 0

        private const val DONE = 1

        private const val FAILED = 2

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

        /** One queue row, on a message the fixture already has. */
        private fun queue(connection: Connection, messageUuid: String, state: Int, due: Long) {
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
                    TranslationTables.QUEUE_STATE +
                    ", " +
                    TranslationTables.QUEUE_NEXT_ATTEMPT_AT +
                    ", " +
                    TranslationTables.QUEUE_CREATED_AT +
                    ") VALUES (?,?,?,?,?,1)",
                messageUuid,
                "body",
                "key",
                state,
                due)
        }

        /**
         * The statement's one row, as text so a null instant is distinguishable from a zero one.
         *
         * <p>Bound **by name**, because the summary names `:pendingState` twice and SQLite numbers `?`s by
         * first appearance - positional binding is how the first draft of this test read a null instant
         * for a queue that plainly had one. `DaoSql` is the instrument, so the binding rule lives once.
         */
        private fun oneRow(
            connection: Connection, sql: String, vararg nameValuePairs: Any?): List<String> {
            val args = DaoSql.boundValues(sql, nameValuePairs)
            val out = ArrayList<String>()
            try {
                connection.prepareStatement(DaoSql.bound(sql)).use { statement ->
                    for (i in args.indices) {
                        statement.setObject(i + 1, args[i])
                    }
                    statement.executeQuery().use { results ->
                        Assert.assertTrue("the summary query must answer one row", results.next())
                        for (i in 1..results.metaData.columnCount) {
                            out.add(results.getString(i))
                        }
                        Assert.assertFalse(
                            "and only one row: three numbers are one fact", results.next())
                    }
                }
            } catch (e: SQLException) {
                throw IllegalStateException("could not run the summary: " + sql, e)
            }
            return out
        }
    }

    /**
     * The DAO's annotation and the package's constant are the same statement, read as source text
     * with `DaoSql.queriesIn`, which reads the annotation rather than the file and joins the
     * `+`-wrapped literal: a `const val` the annotation named would compile to the same SQL but hide
     * the query Room runs, and the test would then execute a string nobody else uses.
     */
    @Test
    fun theDaosAnnotationAndThePackagesConstantAreTheSameStatement() {
        val queries = DaoSql.queriesIn(RepoFiles.read(DAO_SOURCE))
        Assert.assertEquals("the DAO publishes exactly one statement", 1, queries.size)
        Assert.assertEquals(
            "the DAO's annotation and TranslationQueueQueries.SUMMARY must be one statement, or " +
                "the one the test runs is not the one Room would run",
            DaoSql.squeezed(TranslationQueueQueries.SUMMARY),
            DaoSql.squeezed(queries[0]))
    }

    /** The three numbers, and the earliest due instant: two states, and neither of the other two. */
    @Test
    fun theSummaryCountsTheTwoStatesAndTheEarliestDueInstant() {
        val connection = upgraded()
        queue(connection, "m-ordinary", PENDING, 500L)
        queue(connection, "m-plain", PENDING, 100L)
        queue(connection, "m-finnish", FAILED, 50L)
        queue(connection, "m-covered", DONE, 1L)

        val row =
            oneRow(
                connection,
                TranslationQueueQueries.SUMMARY,
                "pendingState",
                PENDING,
                "failedState",
                FAILED)

        Assert.assertEquals("the pending count is the pending rows alone", "2", row[0])
        Assert.assertEquals("and the failed count is the other state", "1", row[1])
        Assert.assertEquals(
            "the next attempt is the earliest *pending* instant, not the failed row's 50",
            "100",
            row[2])
    }

    /**
     * A queue that owes nothing answers zeroes and a null instant - not a zero instant, which would
     * read as 1970 and draw as a date.
     */
    @Test
    fun anEmptyQueueOwesNothingAndSaysSoWithNull() {
        val connection = upgraded()

        val row =
            oneRow(
                connection,
                TranslationQueueQueries.SUMMARY,
                "pendingState",
                PENDING,
                "failedState",
                FAILED)

        Assert.assertEquals("0", row[0])
        Assert.assertEquals("0", row[1])
        Assert.assertNull(
            "an empty MIN is NULL, and 'nothing is due' is what that says", row[2])
    }

    /**
     * The declaration, as Room's own generated DDL: a column for every name the package publishes,
     * the key it names, and the index. `TranslationQueueDao_Impl.kt` is the other half - a DAO without
     * an accessor on the database is inert and silent, so the generated file is what proves the
     * accessor worked.
     */
    @Test
    fun roomGeneratedTheDaosImplementationAndTheTablesCreateCarriesEveryColumn() {
        val generatedDao = RepoFiles.root().resolve(GENERATED_DAO)
        Assert.assertTrue(
            "Room must have generated TranslationQueueDao_Impl: a DAO without an accessor is " +
                "inert and silent, and this is the file that proves the accessor worked",
            Files.isRegularFile(generatedDao))
        val dao = RepoFiles.read(generatedDao)
        Assert.assertTrue(
            "the generated implementation must carry the DAO's statement: " + dao,
            dao.contains(
                TranslationQueueQueries.SUMMARY.replace(":pendingState", "?")
                    .replace(":failedState", "?")))
        Assert.assertTrue("and it must be the generated class", dao.contains("class TranslationQueueDao_Impl"))

        val database = RepoFiles.read(RepoFiles.root().resolve(GENERATED_DATABASE))
        Assert.assertTrue(
            "Room's generated createAllTables must create the queue table",
            database.contains("CREATE TABLE IF NOT EXISTS `" + TranslationTables.QUEUE_TABLE + "`"))
        for (column in TranslationQueueQueries.COLUMNS) {
            Assert.assertTrue(
                "the entity's declaration must carry the column the DDL names: " + column,
                database.contains("`" + column + "`"))
        }
        Assert.assertTrue(
            "and the due index, so a fresh install and a migrated file have the same one",
            database.contains("translation_queue_due"))
    }

    /** The DDL's own index name, so the entity and the table cannot drift about it. */
    @Test
    fun theDueIndexIsTheOneTheDdlDeclares() {
        Assert.assertTrue(
            "TranslationTables.CREATE_QUEUE_INDEX must name the index the entity declares",
            TranslationTables.CREATE_QUEUE_INDEX.contains("translation_queue_due"))
    }
}
