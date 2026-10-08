package uk.xa0.tulkki.data.backup

import java.sql.Connection
import org.junit.Assert
import org.junit.Test
import uk.xa0.tulkki.data.DaoSql
import uk.xa0.tulkki.data.JdbcSchemaExec
import uk.xa0.tulkki.data.RepoFiles
import uk.xa0.tulkki.data.Schema75Fixture
import uk.xa0.tulkki.data.messages.ConversationQueries
import uk.xa0.tulkki.data.messages.MessagesQueries
import uk.xa0.tulkki.data.model.Account
import uk.xa0.tulkki.data.model.PinnedMessage
import uk.xa0.tulkki.data.omemo.OmemoQueries
import uk.xa0.tulkki.data.schema.RawTables
import uk.xa0.tulkki.data.schema.Schema76
import uk.xa0.tulkki.data.schema.Schema77

/**
 * S5-6's backup-import cell: the write surface's vocabulary, and the two source pins that say the
 * worker reaches the file through it.
 *
 * <p><strong>What this test executes, and what it cannot.</strong> {@link BackupImport} reads
 * through the connection `HistoryDatabase.get` hands out, which needs Room's connection machinery -
 * the gap `BlockingDaoExecutionTest` names once. So the statement is executed over a JDBC fixture
 * and the *rules* (`accepts`, `insert`, the transaction) are pinned as source facts: that the
 * allow-list is `:data`'s own list of the export's tables, that it is the only allow-list, and that
 * the worker no longer holds a database handle. What a device or a real file proves is the insert
 * itself, and the round trip is the export's own cell's job.
 */
class BackupImportTest {

    private companion object {

        private const val IMPORT_WORKER =
            "app/src/main/java/uk/xa0/tulkki/app/worker/ImportBackupWorker.kt"

        private const val IMPORT = "data/src/main/java/uk/xa0/tulkki/data/backup/BackupImport.kt"

        /** The file's carrier pseudo-table: written by the worker, never accepted as a table. */
        private const val FILE_CARRIER = "files"

        /** A schema-77 file, reached the way a device reaches it. */
        private fun upgraded(): Connection {
            val connection = Schema75Fixture.openWithRows()
            Schema75Fixture.takeTheSchema75Step(connection)
            Schema76.applyUpgrade(JdbcSchemaExec(connection), 4242L)
            Schema77.applySchema(JdbcSchemaExec(connection))
            return connection
        }
    }

    /**
     * The two halves are one list. The export's `TABLES` is pinned here a second time - the house
     * instrument - and the importer consumes it rather than keeping a list of its own, so a table
     * added to one half cannot be missing from the other. `files` is deliberately not among them: it
     * is the file format's carrier and the worker writes those rows itself.
     */
    @Test
    fun theImporterAcceptsTheExportsTablesAndNothingElse() {
        Assert.assertEquals(
            "the ten tables, pinned by name so a set added to either half is red",
            listOf(
                Account.TABLENAME,
                ConversationQueries.TABLE,
                MessagesQueries.TABLE,
                RawTables.WEBXDC_TABLE,
                PinnedMessage.TABLENAME,
                RawTables.MUTED_TABLE,
                OmemoQueries.PREKEYS_TABLE,
                OmemoQueries.SIGNED_PREKEYS_TABLE,
                OmemoQueries.SESSIONS_TABLE,
                OmemoQueries.IDENTITIES_TABLE),
            BackupQueries.TABLES)
        Assert.assertFalse(
            "the file carrier is not a table the restore inserts into",
            BackupQueries.TABLES.contains(FILE_CARRIER))

        val importer = RepoFiles.read(IMPORT)
        Assert.assertTrue(
            "the acceptance must be the export's own list and not a second spelling",
            importer.contains("BackupQueries.TABLES.contains(table)"))
        Assert.assertFalse(
            "and no caller may keep an allow-list of its own",
            RepoFiles.read(IMPORT_WORKER).contains("TABLE_ALLOW_LIST"))
    }

    /** The restore's one read runs, and it counts the account the header names and nobody else. */
    @Test
    fun theRestoredCountAnswersTheAccountsOwnMessages() {
        val connection = upgraded()
        DaoSql.exec(
            connection,
            "UPDATE " + Account.TABLENAME + " SET " + Account.SERVER + " = 'example.org'")

        Assert.assertEquals(
            "the account's seven messages, reached by the header's username and server",
            "7",
            DaoSql.scalar(
                connection,
                BackupQueries.RESTORED_MESSAGE_COUNT,
                "owner",
                "example.org"))
        Assert.assertEquals(
            "a server the account is not on answers nothing",
            "0",
            DaoSql.scalar(
                connection,
                BackupQueries.RESTORED_MESSAGE_COUNT,
                "owner",
                "other.example.org"))
        Assert.assertEquals(
            "and so does another username on the same server",
            "0",
            DaoSql.scalar(
                connection,
                BackupQueries.RESTORED_MESSAGE_COUNT,
                "someone",
                "example.org"))
    }

    /**
     * The port's own pin: the restore writes through this surface and holds no handle. The row's gate
     * is "the four direct `getWritableDatabase()` consumers ported", and this is what that means in
     * the source. `Cursor` is deliberately not in the list: the worker still reads one for
     * `OpenableColumns.SIZE`, which is the content resolver's and not the database's.
     */
    @Test
    fun theWorkerWritesThroughTheImportSurfaceAndHoldsNoHandle() {
        val worker = RepoFiles.read(IMPORT_WORKER)
        for (gone in arrayOf(
            "getWritableDatabase",
            "getReadableDatabase",
            "insertWithOnConflict",
            "beginTransaction",
            "setTransactionSuccessful",
            "rawQuery")) {
            Assert.assertFalse(
                "ImportBackupWorker must not hold the database handle any more: " + gone,
                worker.contains(gone))
        }
        Assert.assertTrue(
            "and it must reach the file through the import surface",
            worker.contains("BackupImport.get("))
        Assert.assertTrue(
            "whose acceptance decides the table", worker.contains("importer.accepts("))
        Assert.assertTrue(
            "and whose insert decides the row", worker.contains("importer.insert("))
        Assert.assertTrue(
            "and whose transaction wraps the walk", worker.contains("importer.begin()"))
    }
}
