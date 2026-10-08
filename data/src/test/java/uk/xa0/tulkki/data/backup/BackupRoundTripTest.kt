package uk.xa0.tulkki.data.backup

import java.sql.Connection
import java.sql.SQLException
import org.junit.Assert
import org.junit.Test
import uk.xa0.tulkki.data.DaoSql
import uk.xa0.tulkki.data.JdbcSchemaExec
import uk.xa0.tulkki.data.Schema75Fixture
import uk.xa0.tulkki.data.messages.ConversationQueries
import uk.xa0.tulkki.data.messages.MessagesQueries
import uk.xa0.tulkki.data.model.Account
import uk.xa0.tulkki.data.model.Message
import uk.xa0.tulkki.data.model.PinnedMessage
import uk.xa0.tulkki.data.schema.RawTables
import uk.xa0.tulkki.data.schema.Schema76
import uk.xa0.tulkki.data.schema.Schema77

/**
 * S5-6's round trip: what the export answers, put back by the import's own loop, lands as the same
 * rows in another file.
 *
 * <p><strong>What this proves, and what it deliberately does not.</strong> It proves the two halves
 * are *column-compatible*: the export's statements answer every column of their table, the import's
 * insert names exactly those columns, and a target that has the same schema ends up with the same
 * rows. That is the property a backup must have, and it is not a comparison of two spellings - the
 * target is a second, empty file, and the rows are moved by executing the export's statements and
 * inserting their results by name.
 *
 * <p>What it does not exercise: the workers themselves. `ExportBackupWorker` and
 * `ImportBackupWorker` are Android `Worker`s and the file between them is Gson over the format's
 * `{table, values:{…}}` objects, so the JSON encoding, the portable-path transform, the account
 * row's two rules and the crypto are the workers' and no JVM cell runs them. This cell moves rows,
 * not bytes; the byte-level half is a device check and is named as such in the commit.
 */
class BackupRoundTripTest {

    private companion object {

        /** The backup's own transports, run on every row: the file carrier is not a table. */
        private const val CARRIER_SKIP = "files"

        /** The export's sets, each with the table it belongs to and the statement that reads it. */
        private fun theExportsSets(): List<Array<String>> {
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

        /**
         * One set: the export statement's rows, inserted into the target by the column names the
         * statement itself answered. Named columns and `IGNORE` are the import surface's own rule, so a
         * target insert can only fail on the table's constraints - which is the point of running it.
         */
        private fun restore(
            source: Connection, target: Connection, table: String, statement: String): Int {
            val columns = ArrayList<String>()
            val rows = ArrayList<Array<Any?>>()
            try {
                source.prepareStatement(statement).use { read ->
                    if (statement.contains("?")) {
                        read.setString(1, Schema75Fixture.ACCOUNT)
                    }
                    read.executeQuery().use { results ->
                        val meta = results.metaData
                        for (index in 1..meta.columnCount) {
                            columns.add(meta.getColumnLabel(index))
                        }
                        while (results.next()) {
                            val row = arrayOfNulls<Any?>(meta.columnCount)
                            for (index in 1..meta.columnCount) {
                                row[index - 1] = results.getObject(index)
                            }
                            rows.add(row)
                        }
                    }
                }
            } catch (e: SQLException) {
                throw IllegalStateException("could not read " + table + " for the round trip", e)
            }

            val placeholders = StringBuilder()
            for (index in columns.indices) {
                placeholders.append(if (index == 0) "?" else ",?")
            }
            val insert =
                "INSERT OR IGNORE INTO " +
                    table +
                    " (" +
                    java.lang.String.join(",", columns) +
                    ") VALUES (" +
                    placeholders +
                    ")"
            for (row in rows) {
                DaoSql.exec(target, insert, *row)
            }
            return rows.size
        }

        /** One row per set that the shared fixture leaves empty, so the round trip has something to carry. */
        private fun seedTheRowsTheFixtureDoesNotHave(source: Connection) {
            DaoSql.exec(
                source,
                "UPDATE " +
                    MessagesQueries.TABLE +
                    " SET " +
                    Message.RELATIVE_FILE_PATH +
                    " = '/files/one' WHERE uuid = 'm-plain'")
            DaoSql.exec(
                source,
                "INSERT INTO " +
                    RawTables.WEBXDC_TABLE +
                    " (" +
                    Message.CONVERSATION +
                    ", sender, thread, payload) VALUES (?,?,?,?)",
                Schema75Fixture.CONVERSATION_CLEARED,
                "peer",
                "thread",
                "payload")
            DaoSql.exec(
                source,
                "INSERT INTO " +
                    PinnedMessage.TABLENAME +
                    " (" +
                    PinnedMessage.MESSAGE_UUID +
                    ", " +
                    PinnedMessage.CONVERSATION_UUID +
                    ", " +
                    PinnedMessage.ACCOUNT_UUID +
                    ", " +
                    PinnedMessage.BODY +
                    ", " +
                    PinnedMessage.TIMESTAMP +
                    ") VALUES (?,?,?,?,?)",
                "m-plain",
                Schema75Fixture.CONVERSATION_PLAIN,
                Schema75Fixture.ACCOUNT,
                "a pin",
                1)
            DaoSql.exec(
                source,
                "INSERT INTO " +
                    RawTables.MUTED_TABLE +
                    " (" +
                    RawTables.MUTED_ACCOUNT +
                    ", " +
                    RawTables.MUTED_MUC +
                    ", " +
                    RawTables.MUTED_OCCUPANT +
                    ") VALUES (?,?,?)",
                Schema75Fixture.ACCOUNT,
                "room@conference.example.org",
                "occupant-1")
            // The OMEMO store's four: one row each, by the columns the schema-77 rebuild keeps.
            DaoSql.exec(
                source,
                "INSERT INTO prekeys (_id, account, id, key) VALUES (1,?,?,?)",
                Schema75Fixture.ACCOUNT,
                1,
                "prekey")
            DaoSql.exec(
                source,
                "INSERT INTO signed_prekeys (_id, account, id, key) VALUES (1,?,?,?)",
                Schema75Fixture.ACCOUNT,
                1,
                "signed-prekey")
            DaoSql.exec(
                source,
                "INSERT INTO sessions (_id, account, name, device_id, key) VALUES (1,?,?,?,?)",
                Schema75Fixture.ACCOUNT,
                "peer@example.org",
                1,
                "record")
            DaoSql.exec(
                source,
                "INSERT INTO identities (_id, account, name, fingerprint) VALUES (1,?,?,?)",
                Schema75Fixture.ACCOUNT,
                "peer@example.org",
                "fingerprint")
        }

        /** A schema-77 file, reached the way a device reaches it, with or without the fixture's rows. */
        private fun upgraded(withRows: Boolean): Connection {
            val connection = if (withRows) Schema75Fixture.openWithRows() else Schema75Fixture.open()
            Schema75Fixture.takeTheSchema75Step(connection)
            Schema76.applyUpgrade(JdbcSchemaExec(connection), 4242L)
            Schema77.applySchema(JdbcSchemaExec(connection))
            return connection
        }

        /** The dump's lines, sorted: the two files' rowids need not agree, their rows must. */
        private fun sortedLines(dump: String): List<String> {
            val lines = ArrayList<String>()
            for (line in dump.split("\n")) {
                if (!line.isEmpty()) {
                    lines.add(line)
                }
            }
            return lines.stream().sorted().toList()
        }
    }

    /**
     * The export's ten sets, read through the production statements and written into a second file
     * by name. Every table is compared afterwards - row for row, column for column - and a table the
     * export cannot answer with every column it needs is a red insert rather than a green miss.
     */
    @Test
    fun anExportRestoresIntoTheSameRowsInAnotherFile() {
        val source = upgraded(true)
        seedTheRowsTheFixtureDoesNotHave(source)
        val target = upgraded(false)

        for (set in theExportsSets()) {
            if (CARRIER_SKIP == set[0]) {
                continue
            }
            Assert.assertTrue(
                "every one of the export's sets must move at least one row, or this cell is " +
                    "asserting two empty tables against each other: " +
                    set[0],
                restore(source, target, set[0], set[1]) > 0)
        }

        for (table in BackupQueries.TABLES) {
            val columns = Schema75Fixture.columnNames(source, table)
            Assert.assertEquals(
                "the restored " +
                    table +
                    " must be the exported one, row for row: the two files were built by " +
                    "the same schema and hold the same data",
                sortedLines(Schema75Fixture.dumpRows(source, table, columns)),
                sortedLines(Schema75Fixture.dumpRows(target, table, columns)))
        }
    }
}
