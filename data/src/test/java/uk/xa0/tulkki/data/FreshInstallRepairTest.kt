package uk.xa0.tulkki.data

import androidx.sqlite.SQLiteConnection
import java.sql.Connection
import java.sql.SQLException
import org.junit.Assert
import org.junit.Test
import uk.xa0.tulkki.data.schema.RawTables
import uk.xa0.tulkki.data.schema.SchemaExec

/**
 * The fresh-install repair, on the owner's own state: a file Room created with none of the
 * fresh-install schema.
 *
 * <p>Room's callback had no `onCreate`, so `installFreshSchema` never ran on a new file - and a
 * file that exists is never created again, so `adb install -r` kept the hole. That is the owner's
 * file, and it is built here the way Room built it: `HistoryDatabase_Impl.createAllTables`, and
 * deliberately no `installFreshSchema`. The repair is what makes that file work again; the
 * device run is the evidence that its tables are the whole hole, and the cells here are the SQL.
 */
class FreshInstallRepairTest {

    private companion object {

        /**
         * Room's generated schema and nothing else. The callback that would have added `RawTables` is not
         * called, which is exactly what the buggy build did on a new file.
         */
        private fun theOwnersFile(name: String): Connection {
            val driver = JdbcSQLiteDriver(name)
            val connection = driver.connection()
            Schema75Fixture.requireFts4(connection)
            val room: SQLiteConnection = driver.open(name)
            try {
                RoomOnTheHost.openDelegate().createAllTables(room)
            } finally {
                room.close()
            }
            return connection
        }

        /** The complete fresh install: Room's schema, then the one definition the callback runs. */
        private fun aCompleteFreshInstall(name: String): Connection {
            val connection = theOwnersFile(name)
            HistoryDatabase.installFreshSchema(JdbcSchemaExec(connection))
            return connection
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

    @Test
    fun aFileRoomCreatedWithoutTheFreshInstallSchemaNeedsTheRepair() {
        val connection = theOwnersFile("repair-needed")
        val exec: SchemaExec = JdbcSchemaExec(connection)

        Assert.assertTrue(
            "the owner's file: Room's entity tables are there and RawTables' are not",
            exec.hasTable("messages") && !exec.hasTable("stories"))
        Assert.assertFalse(
            "so does it have the FTS machinery the first launch asked for",
            exec.hasTable("messages_index_segdir"))
        Assert.assertTrue(
            "the repair's own decision, and the one the app runs before its first reader",
            DatabaseBackend.needsFreshInstallRepair(exec))
    }

    @Test
    fun theRepairCreatesEveryMissingTableAndBuildsTheIndex() {
        val connection = theOwnersFile("repair-runs")
        val exec: SchemaExec = JdbcSchemaExec(connection)

        DatabaseBackend.repairMissingFreshInstallSchema(exec)

        for (table in
            arrayOf(
                RawTables.MUTED_TABLE,
                "stories",
                "posts",
                "resolver_results",
                "webxdc_updates",
                "pinned_messages",
                "translation_cache",
                "translation_usage",
                "messages_index",
                "messages_index_segdir",
            )) {
            Assert.assertTrue("the repair must create " + table, exec.hasTable(table))
        }
        for (trigger in
            arrayOf("after_message_insert", "after_message_update", "after_message_delete")) {
            Assert.assertEquals(
                "the repair must create the " + trigger + " trigger",
                1L,
                count(
                    connection,
                    "SELECT COUNT(*) FROM sqlite_master WHERE type='trigger' AND name='" +
                        trigger +
                        "'"))
        }
        Assert.assertFalse(
            "and the file no longer needs the repair, so a second launch is a no-op and does " +
                "not re-read every message row",
            DatabaseBackend.needsFreshInstallRepair(exec))
    }

    @Test
    fun aCompleteFreshInstallIsLeftAlone() {
        val connection = aCompleteFreshInstall("repair-not-needed")
        val exec: SchemaExec = JdbcSchemaExec(connection)

        Assert.assertTrue(
            "the fresh install has the probe table, so the repair decides it has nothing to do",
            exec.hasTable(RawTables.MUTED_TABLE) &&
                !DatabaseBackend.needsFreshInstallRepair(exec))
    }
}
