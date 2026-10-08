package uk.xa0.tulkki.data.backup

import android.content.Context
import net.zetetic.database.sqlcipher.SQLiteDatabase
import uk.xa0.tulkki.data.HistoryDatabase
import uk.xa0.tulkki.data.messages.ConversationQueries
import uk.xa0.tulkki.data.messages.MessagesQueries
import uk.xa0.tulkki.data.model.Account
import uk.xa0.tulkki.data.model.PinnedMessage
import uk.xa0.tulkki.data.schema.RawTables

/**
 * The export's read model (S5-6): the one door from the file to a backup's rows.
 *
 * <p>**What this replaces.** `ExportBackupWorker` held `DatabaseBackend.getReadableDatabase()` and
 * composed every statement itself - a table name and a column name from the caller, `select *` on
 * both - so the schema was `:app`'s to know and a typo was a backup that silently missed a table.
 * `docs/MIGRATION.md`, "Design: the data layer" §6 step 6 names this worker among the four direct
 * `getWritableDatabase()` consumers to port, and §2.7 puts the read model on the boundary: the
 * worker now names one of the sets below, gets [BackupRow]s back, and never sees a `Cursor`, a
 * `SQLiteDatabase` or a statement.
 *
 * <p>**Why these methods and not one generic read.** The previous writer's finding stands: a
 * raw-table dump is not DAO-shaped, and exposing `read(table, where, args)` would have moved the
 * worker's schema knowledge into `:data` without giving the worker a vocabulary. So the surface is
 * the export's own sets - the account, its rooms, its messages, its WebXDC state, its pins, its
 * OMEMO material, its files - each named once, each reading through [BackupQueries], and a caller
 * cannot ask for a table the export does not carry.
 *
 * <p>**Fidelity is checked at both ends, and neither end is a comparison of a copy.**
 * `BackupExportTest` runs every statement in [BackupQueries] over a schema-77 fixture reached the
 * way a device reaches it, compares each read's columns with the table's own `PRAGMA table_info`
 * (so a column the export cannot see is red), and asserts the export's table set is exactly the
 * importer's allow-list - the two halves of the round trip cannot drift apart. The round trip itself
 * is `ExportBackupRoundTripTest`'s.
 */
class BackupExport private constructor(private val db: SQLiteDatabase) {

    /** The account's own row, by key. One row on a file that is not corrupt, and none otherwise. */
    fun account(uuid: String): List<BackupRow> =
        rows(Account.TABLENAME, BackupQueries.ACCOUNT, uuid)

    /** The account's rooms. */
    fun conversationRows(uuid: String): List<BackupRow> =
        rows(ConversationQueries.TABLE, BackupQueries.CONVERSATION_ROWS, uuid)

    /** The account's messages, every column of them. */
    fun messageRows(uuid: String): List<BackupRow> =
        rows(MessagesQueries.TABLE, BackupQueries.MESSAGE_ROWS, uuid)

    /** The account's WebXDC state. */
    fun webxdcRows(uuid: String): List<BackupRow> =
        rows(RawTables.WEBXDC_TABLE, BackupQueries.WEBXDC_ROWS, uuid)

    /** The account's pins. */
    fun pinnedRows(uuid: String): List<BackupRow> =
        rows(PinnedMessage.TABLENAME, BackupQueries.PINNED_ROWS, uuid)

    /**
     * The room mutes, unfiltered - see [BackupQueries.MUTE_ROWS] for why that is the behaviour this
     * port must not change.
     */
    fun muteRows(): List<BackupRow> =
        rows(RawTables.MUTED_TABLE, BackupQueries.MUTE_ROWS)

    /**
     * The account's OMEMO material: `prekeys`, `signed_prekeys`, `sessions` and `identities`, in
     * that order, each row carrying its own table's name. One method rather than four because the
     * export writes all four as one section and no caller has ever wanted one of them alone.
     */
    fun omemoRows(uuid: String): List<BackupRow> {
        val out = ArrayList<BackupRow>()
        for (set in BackupQueries.OMEMO_ROWS) {
            out.addAll(rows(set.first, set.second, uuid))
        }
        return out
    }

    /** The account's attachment paths, one per message that names one. */
    fun attachmentPaths(uuid: String): List<String> =
        strings(BackupQueries.ATTACHMENT_PATHS, uuid)

    /** The account's avatar's file name, then its contacts' - the files the export carries. */
    fun avatarNames(uuid: String): List<String> =
        strings(BackupQueries.ACCOUNT_AVATAR, uuid) + strings(BackupQueries.CONTACT_AVATAR, uuid)

    private fun rows(table: String, statement: String, vararg args: Any): List<BackupRow> {
        val out = ArrayList<BackupRow>()
        db.query(statement, arrayOf(*args)).use { cursor ->
            while (cursor.moveToNext()) {
                val columns = ArrayList<BackupColumn>(cursor.columnCount)
                for (index in 0 until cursor.columnCount) {
                    columns.add(BackupColumn(cursor.getColumnName(index), cursor.getString(index)))
                }
                out.add(BackupRow(table, columns))
            }
        }
        return out
    }

    private fun strings(statement: String, vararg args: Any): List<String> {
        val out = ArrayList<String>()
        db.query(statement, arrayOf(*args)).use { cursor ->
            while (cursor.moveToNext()) {
                val value = cursor.getString(0)
                if (value != null) {
                    out.add(value)
                }
            }
        }
        return out
    }

    companion object {

        /**
         * Over the one open database, as the read models are: the caller names the context, not the
         * file. This is also what *opens* it - `HistoryDatabase.get` runs Room's version check and
         * the registered migrations - so a worker that reaches the file this way reaches the same
         * file the app has open, not a second connection of its own.
         */
        @JvmStatic fun get(context: Context): BackupExport = BackupExport(HistoryDatabase.get(context))
    }
}
