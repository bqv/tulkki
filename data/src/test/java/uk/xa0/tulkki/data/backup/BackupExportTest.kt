package uk.xa0.tulkki.data.backup

import java.sql.Connection
import java.sql.SQLException
import org.junit.Assert
import org.junit.Test
import uk.xa0.tulkki.data.DaoSql
import uk.xa0.tulkki.data.JdbcSchemaExec
import uk.xa0.tulkki.data.RepoFiles
import uk.xa0.tulkki.data.Schema75Fixture
import uk.xa0.tulkki.data.messages.ConversationQueries
import uk.xa0.tulkki.data.messages.MessagesQueries
import uk.xa0.tulkki.data.model.Account
import uk.xa0.tulkki.data.model.Contact
import uk.xa0.tulkki.data.model.Message
import uk.xa0.tulkki.data.model.PinnedMessage
import uk.xa0.tulkki.data.schema.RawTables
import uk.xa0.tulkki.data.schema.Schema76
import uk.xa0.tulkki.data.schema.Schema77

/**
 * S5-6's backup-export cell: `BackupQueries`' vocabulary and the seams the export's fidelity
 * rests on, executed over a schema-77 fixture reached the way a device reaches it.
 *
 * <p><strong>What this test executes, and what it does not.</strong> It executes the package's own
 * statements - each one [BackupQueries.STATEMENTS] publishes - over a JDBC fixture, because
 * SQLCipher's file cannot be opened on the host and a test that re-spelled the statements would be
 * testing the test. It does <em>not</em> call {@link BackupExport}'s methods: they read through the
 * connection `HistoryDatabase.get` hands out, which needs Room's connection machinery - the gap
 * `BlockingDaoExecutionTest` names once and every `:data` package test inherits. So what is asserted
 * is the statements' own behaviour and the scoping the export claims; the cursor-to-[BackupRow]
 * mapping itself is exercised by nothing on the host and says so here rather than being papered
 * over.
 *
 * <p><strong>The round trip's seam is `BackupImportTest`'s now (S5-6, consumer 2).</strong> The
 * importer no longer spells a table list of its own - `BackupImport.accepts` answers
 * `BackupQueries.TABLES` - so the two halves are one list by construction rather than by a test
 * comparing two spellings, and that test pins the list and the acceptance together.
 */
class BackupExportTest {

    private companion object {

        private const val EXPORT_WORKER =
            "app/src/main/java/uk/xa0/tulkki/app/worker/ExportBackupWorker.kt"

        /** The other account, which no export's read may return rows for. */
        private const val OTHER_ACCOUNT = "acct-2"

        private const val OTHER_CONVERSATION = "conv-other"

        /** The file carrier, the one name in the importer's list that is not a table. */
        private const val FILE_CARRIER = "files"

        /** Every statement the vocabulary publishes, with what it binds: an account, or nothing. */
        private fun statements(): List<String> {
            val out = ArrayList(BackupQueries.STATEMENTS)
            return out
        }

        /** Every row read the package publishes, as its table and its statement. */
        private fun rowReads(): List<Array<String>> {
            val out = ArrayList<Array<String>>()
            out.add(arrayOf(Account.TABLENAME, BackupQueries.ACCOUNT))
            out.add(arrayOf(ConversationQueries.TABLE, BackupQueries.CONVERSATION_ROWS))
            out.add(arrayOf(MessagesQueries.TABLE, BackupQueries.MESSAGE_ROWS))
            out.add(arrayOf(RawTables.WEBXDC_TABLE, BackupQueries.WEBXDC_ROWS))
            out.add(arrayOf(PinnedMessage.TABLENAME, BackupQueries.PINNED_ROWS))
            out.add(arrayOf(RawTables.MUTED_TABLE, BackupQueries.MUTE_ROWS))
            for (set in BackupQueries.OMEMO_ROWS) {
                out.add(arrayOf(set.first, set.second))
            }
            return out
        }

        /** A schema-77 file, reached the way a device reaches it. */
        private fun upgraded(): Connection {
            val connection = Schema75Fixture.openWithRows()
            Schema75Fixture.takeTheSchema75Step(connection)
            Schema76.applyUpgrade(JdbcSchemaExec(connection), 4242L)
            Schema77.applySchema(JdbcSchemaExec(connection))
            return connection
        }

        private fun insertMute(
            connection: Connection, account: String, muc: String, occupant: String) {
            DaoSql.exec(
                connection,
                "INSERT INTO " +
                    RawTables.MUTED_TABLE +
                    " (" +
                    RawTables.MUTED_ACCOUNT +
                    ", " +
                    RawTables.MUTED_MUC +
                    ", " +
                    RawTables.MUTED_OCCUPANT +
                    ") VALUES (?,?,?)",
                account,
                muc,
                occupant)
        }

        /** One column of every row, read by its label, as the file names it. */
        private fun column(
            connection: Connection, sql: String, label: String, account: String?
        ): List<String> {
            val out = ArrayList<String>()
            try {
                connection.prepareStatement(sql).use { statement ->
                    if (account != null && sql.contains("?")) {
                        statement.setString(1, account)
                    }
                    statement.executeQuery().use { results ->
                        while (results.next()) {
                            out.add(results.getString(label))
                        }
                    }
                }
            } catch (e: SQLException) {
                throw IllegalStateException("could not query: " + sql, e)
            }
            return out
        }

        /** The first column of every row, bound positionally; a null account means the read takes none. */
        private fun firstColumn(
            connection: Connection, sql: String, account: String?
        ): List<String> {
            val out = ArrayList<String>()
            try {
                connection.prepareStatement(sql).use { statement ->
                    if (account != null && sql.contains("?")) {
                        statement.setString(1, account)
                    }
                    statement.executeQuery().use { results ->
                        while (results.next()) {
                            out.add(results.getString(1))
                        }
                    }
                }
            } catch (e: SQLException) {
                throw IllegalStateException("could not query: " + sql, e)
            }
            return out
        }

        /** The result set's own column labels, in the order the file answers them. */
        private fun columnsOf(connection: Connection, sql: String): List<String> {
            val out = ArrayList<String>()
            try {
                connection.prepareStatement(sql).use { statement ->
                    if (sql.contains("?")) {
                        statement.setString(1, Schema75Fixture.ACCOUNT)
                    }
                    statement.executeQuery().use { results ->
                        val meta = results.metaData
                        for (index in 1..meta.columnCount) {
                            out.add(meta.getColumnLabel(index))
                        }
                    }
                }
            } catch (e: SQLException) {
                throw IllegalStateException("could not read the columns of: " + sql, e)
            }
            return out
        }
    }

    /** Every one of the package's statements runs over the fixture rather than throwing. */
    @Test
    fun everyStatementTheVocabularyPublishesRunsOverTheFixture() {
        val connection = upgraded()
        for (statement in statements()) {
            Assert.assertNotNull(
                "every published statement must run against the file the app has: " + statement,
                columnsOf(connection, statement))
        }
        Assert.assertEquals(
            "the vocabulary is the thirteen statements the export writes, and the list has not " +
                "quietly lost one",
            13,
            BackupQueries.STATEMENTS.size)
    }

    /**
     * Each row read answers its own table's columns, in the file's own order. `SELECT *` makes that
     * the file's declaration rather than a list this package chose, which is the point: a read that
     * became a projection is what this reddens on.
     */
    @Test
    fun everyRowReadAnswersItsTablesOwnColumnsInTheFilesOwnOrder() {
        val connection = upgraded()
        for (read in rowReads()) {
            Assert.assertEquals(
                "the read for " + read[0] + " must answer every column the file declares",
                Schema75Fixture.columnNames(connection, read[0]),
                columnsOf(connection, read[1]))
        }
    }

    /**
     * An account's reads answer its own rows and nobody else's. This is the claim a join made by
     * hand gets wrong: `messages` carries no account column, so the scope is the room it belongs to,
     * and a second account's message is exactly what a mistyped join returns.
     */
    @Test
    fun anAccountsReadsAnswerItsOwnRowsAndNoOtherAccounts() {
        val connection = upgraded()
        DaoSql.exec(
            connection,
            "INSERT INTO " +
                Account.TABLENAME +
                " (uuid, username) VALUES ('" +
                OTHER_ACCOUNT +
                "', 'other')")
        DaoSql.exec(
            connection,
            "INSERT INTO " +
                ConversationQueries.TABLE +
                " (uuid, accountUuid, mode) VALUES ('" +
                OTHER_CONVERSATION +
                "', '" +
                OTHER_ACCOUNT +
                "', 0)")
        DaoSql.exec(
            connection,
            "INSERT INTO " +
                MessagesQueries.TABLE +
                " (uuid, conversationUuid, timeSent, status, type, body, encryption) " +
                "VALUES ('m-other', '" +
                OTHER_CONVERSATION +
                "', 10, 0, 0, 'other text', 0)")

        Assert.assertEquals(
            "the account's own three rooms, and not the other account's",
            listOf(
                Schema75Fixture.CONVERSATION_CLEARED,
                Schema75Fixture.CONVERSATION_MUC,
                Schema75Fixture.CONVERSATION_PLAIN),
            firstColumn(connection, BackupQueries.CONVERSATION_ROWS, Schema75Fixture.ACCOUNT)
                .sorted())
        Assert.assertEquals(
            "the other account's read answers its own one room",
            listOf(OTHER_CONVERSATION),
            firstColumn(connection, BackupQueries.CONVERSATION_ROWS, OTHER_ACCOUNT))
        Assert.assertEquals(
            "the account's seven messages",
            7,
            firstColumn(connection, BackupQueries.MESSAGE_ROWS, Schema75Fixture.ACCOUNT).size)
        Assert.assertFalse(
            "the other account's message must never appear in this account's export",
            firstColumn(connection, BackupQueries.MESSAGE_ROWS, Schema75Fixture.ACCOUNT)
                .contains("m-other"))
        Assert.assertEquals(
            "and the other account's message read answers exactly its own",
            listOf("m-other"),
            firstColumn(connection, BackupQueries.MESSAGE_ROWS, OTHER_ACCOUNT))
    }

    /**
     * The mute read is unfiltered, and that is a recorded quirk rather than an oversight:
     * `muted_participants` is the one set the export has always written without an account
     * predicate, so narrowing it here would be a quiet change to what a backup contains. A cell that
     * reddens if someone narrows it is the point - it makes the change deliberate.
     */
    @Test
    fun theMuteReadIsUnscopedAndSaysSo() {
        val connection = upgraded()
        insertMute(connection, Schema75Fixture.ACCOUNT, "room-a@example.org", "occupant-a")
        insertMute(connection, OTHER_ACCOUNT, "room-b@example.org", "occupant-b")

        Assert.assertEquals(
            "both accounts' mutes, because the export has never scoped this table",
            listOf("occupant-a", "occupant-b"),
            column(connection, BackupQueries.MUTE_ROWS, RawTables.MUTED_OCCUPANT, null).sorted())
    }

    /** The file reads: the account's own attachment paths, and its and its contacts' avatars. */
    @Test
    fun theFileReadsAnswerThePathsAndNamesTheOwnerHas() {
        val connection = upgraded()
        DaoSql.exec(
            connection,
            "UPDATE " +
                MessagesQueries.TABLE +
                " SET " +
                Message.RELATIVE_FILE_PATH +
                " = '/files/one' WHERE uuid = 'm-plain'")
        DaoSql.exec(
            connection,
            "UPDATE " +
                Account.TABLENAME +
                " SET " +
                Account.AVATAR +
                " = 'owner.png' WHERE uuid = '" +
                Schema75Fixture.ACCOUNT +
                "'")
        DaoSql.exec(
            connection,
            "INSERT INTO " +
                Contact.TABLENAME +
                " (_id, accountUuid, " +
                Contact.AVATAR +
                ") VALUES (1, '" +
                Schema75Fixture.ACCOUNT +
                "', 'contact.png')")

        Assert.assertEquals(
            "the one message of the account that names a file",
            listOf("/files/one"),
            firstColumn(connection, BackupQueries.ATTACHMENT_PATHS, Schema75Fixture.ACCOUNT))
        Assert.assertEquals(
            "the account's own avatar",
            listOf("owner.png"),
            firstColumn(connection, BackupQueries.ACCOUNT_AVATAR, Schema75Fixture.ACCOUNT))
        Assert.assertEquals(
            "its contacts'",
            listOf("contact.png"),
            firstColumn(connection, BackupQueries.CONTACT_AVATAR, Schema75Fixture.ACCOUNT))
        Assert.assertEquals(
            "and another account's avatar is not this account's file",
            emptyList<String>(),
            firstColumn(connection, BackupQueries.ACCOUNT_AVATAR, OTHER_ACCOUNT))
    }

    /** The account row, by key: one row for the account, none for another. */
    @Test
    fun theAccountRowIsReadByItsOwnKey() {
        val connection = upgraded()
        Assert.assertEquals(
            "the account's own row",
            listOf(Schema75Fixture.ACCOUNT),
            firstColumn(connection, BackupQueries.ACCOUNT, Schema75Fixture.ACCOUNT))
        Assert.assertEquals(
            "an account no row names answers nothing",
            emptyList<String>(),
            firstColumn(connection, BackupQueries.ACCOUNT, OTHER_ACCOUNT))
    }

    /**
     * The port's own pin: the worker reaches the file through this package and holds no handle. The
     * row's gate is "the four direct `getWritableDatabase()` consumers ported", and this is what
     * that means in the source - the database calls that were here are gone, by name.
     */
    @Test
    fun theWorkerReachesTheFileThroughTheReadModelAndHoldsNoHandle() {
        val worker = RepoFiles.read(EXPORT_WORKER)
        for (gone in arrayOf(
            "getReadableDatabase",
            "getWritableDatabase",
            "rawQuery",
            "android.database.Cursor",
        )) {
            Assert.assertFalse(
                "ExportBackupWorker must not hold the database handle any more: " + gone,
                worker.contains(gone))
        }
        Assert.assertTrue(
            "and it must reach the export through the read model",
            worker.contains("BackupExport.get("))
        Assert.assertTrue(
            "whose message set is the package's", worker.contains("backup.messageRows("))
        Assert.assertTrue(
            "and whose account read is the package's", worker.contains("backup.account("))
    }
}
