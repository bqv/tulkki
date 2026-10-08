package uk.xa0.tulkki.data

import java.sql.Connection
import java.sql.ResultSet
import java.sql.SQLException
import java.sql.Statement
import java.util.regex.Pattern
import org.junit.Assert
import org.junit.Test
import uk.xa0.tulkki.data.schema.SchemaExec

/**
 * The message index's presence decision and the rebuild that makes its answer actable, over the
 * host's own SQLite.
 *
 * <p><strong>What this is for.</strong> A fresh install had no {@code messages_index} at all - Room's
 * callback never ran the fresh-install schema - and the first reader to touch the FTS machinery,
 * {@code restoreFromDatabase}'s {@code isFtsIndexFragmented}, threw
 * {@code SQLiteException: no such table: messages_index_segdir (code 1)} and took the app down on
 * every launch. The decision now answers "it must be rebuilt" for an absent index, and the rebuild
 * builds the index when there is none. These cells execute that decision over real SQLite, with the
 * {@link JdbcSchemaExec} seam the schema tests already use.
 *
 * <p><strong>What it cannot cover, and what does.</strong> The production entry point takes a
 * {@code SQLiteDatabase}, and SQLCipher ships Android ABIs only, so no JVM test can cast the seam on
 * for real - {@code SchemaExecs.of(SQLiteDatabase)} is three lines of {@code rawQuery}/ {@code execSQL}
 * and is the only part of this path the host cannot run. Neither can a JVM test drive Room's
 * {@code onCreate}, which is what the wiring below is pinned as source for. The evidence that the
 * owner's phone stops crash-looping is the fresh install on the phone itself.
 */
class MessageIndexRebuildTest {

    private companion object {

        /** The shadow table's contents replaced by {@code rows} synthetic segment rows. */
        private fun setSegmentRows(connection: Connection, rows: Int) {
            try {
                connection.createStatement().use { statement ->
                    statement.execute("DELETE FROM messages_index_segdir")
                    for (i in 0 until rows) {
                        statement.execute(
                            "INSERT INTO messages_index_segdir" +
                                " (level, idx, start_block, leaves_end_block, end_block, root)" +
                                " VALUES (0, " +
                                i +
                                ", 0, 0, 0, x'00')")
                    }
                }
            } catch (e: SQLException) {
                throw IllegalStateException("could not fill messages_index_segdir", e)
            }
        }

        private fun count(connection: Connection, sql: String): Long {
            try {
                connection.createStatement().use { statement ->
                    statement.executeQuery(sql).use { results ->
                        Assert.assertTrue("no row for " + sql, results.next())
                        return results.getLong(1)
                    }
                }
            } catch (e: SQLException) {
                throw IllegalStateException("failed to query: " + sql, e)
            }
        }
    }

    /**
     * The owner's state: a file with the message rows and no FTS machinery, because the callback that
     * would have created it never ran. The decision must answer "rebuild it" rather than throwing,
     * and asking must not itself change the file.
     */
    @Test
    fun aFileWithoutTheIndexMustBeRebuiltRatherThanThrow() {
        val connection = Schema75Fixture.openWithRows()
        Schema75Fixture.dropMessageIndex(connection)
        val exec: SchemaExec = JdbcSchemaExec(connection)

        Assert.assertFalse(
            "the probe: the fixture really has no index and no shadow table",
            exec.hasTable("messages_index") || exec.hasTable("messages_index_segdir"))
        Assert.assertTrue(
            "an index that is not there is not 'not fragmented': it must be built, and the " +
                "caller rebuilds on a true answer. This is the call that used to throw " +
                "`no such table: messages_index_segdir` and kill the first launch.",
            DatabaseBackend.indexMustBeRebuilt(exec))
        Assert.assertFalse(
            "the check is a read: deciding that it must be rebuilt does not rebuild it",
            exec.hasTable("messages_index"))
    }

    /** The rebuild, on that same file: it creates the index, its shadow table, and fills it. */
    @Test
    fun theRebuildBuildsAndFillsAnIndexThatWasNeverThere() {
        val connection = Schema75Fixture.openWithRows()
        Schema75Fixture.dropMessageIndex(connection)
        val exec: SchemaExec = JdbcSchemaExec(connection)

        DatabaseBackend.rebuildMessagesIndex(exec)

        Assert.assertTrue("the FTS4 table itself", exec.hasTable("messages_index"))
        Assert.assertTrue(
            "its shadow table, the one the crash named", exec.hasTable("messages_index_segdir"))
        Assert.assertEquals(
            "and it is filled from the rows already in `messages`: the displayed text is " +
                "findable, so the triggers and the deferred re-read both ran",
            1L,
            count(
                connection,
                "SELECT COUNT(*) FROM messages_index WHERE messages_index MATCH '" +
                    Schema75Fixture.TRANSLATED_BODY +
                    "'"))
        Assert.assertFalse(
            "so the caller's next pass does not rebuild again - the absent branch is not a " +
                "rebuild loop",
            DatabaseBackend.indexMustBeRebuilt(exec))
    }

    /**
     * The threshold, which was the whole of the old decision: more than four segments means rebuild.
     * The segment rows are written by hand because no natural small index crosses the boundary.
     */
    @Test
    fun fourSegmentsAreNotFragmentedAndFiveAre() {
        val connection = Schema75Fixture.openWithRows()
        val exec: SchemaExec = JdbcSchemaExec(connection)

        setSegmentRows(connection, 4)
        Assert.assertFalse(
            "four segments are the last healthy count", DatabaseBackend.indexMustBeRebuilt(exec))

        setSegmentRows(connection, 5)
        Assert.assertTrue(
            "five segments are past it, which is the answer the caller rebuilds on",
            DatabaseBackend.indexMustBeRebuilt(exec))
    }

    /**
     * Room's callback, as source.
     *
     * <p>This is a pin and not a behaviour, and it is the only cell that can be one: opening the file
     * needs SQLCipher, so no JVM test can watch Room call {@code onCreate}. It exists because the
     * class comment named {@code HistoryCallbacks.onCreate} while the class overrode only
     * {@code onOpen} - a fresh install therefore got Room's entity tables and none of
     * {@code RawTables}, and every test stayed green because every test calls
     * {@code installFreshSchema} directly. The pin is what fails if the override is dropped again.
     */
    @Test
    fun theFreshInstallCallbackIsWiredToTheOneDefinition() {
        val opener = RepoFiles.read("data/src/main/java/uk/xa0/tulkki/data/HistoryDatabase.kt")

        Assert.assertTrue(
            "Room's callback must override onCreate and run installFreshSchema, or a fresh " +
                "install has no messages_index, no translation cache and no pins: " +
                opener.contains("override fun onCreate("),
            Pattern.compile(
                "override fun onCreate\\(db: SupportSQLiteDatabase\\) \\{\\s*" +
                    "installFreshSchema\\(db\\)")
                .matcher(opener)
                .find())
    }
}
