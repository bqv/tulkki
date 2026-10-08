package uk.xa0.tulkki.data.backup

import uk.xa0.tulkki.data.messages.ConversationQueries
import uk.xa0.tulkki.data.messages.MessagesQueries
import uk.xa0.tulkki.data.model.Account
import uk.xa0.tulkki.data.model.Contact
import uk.xa0.tulkki.data.model.Message
import uk.xa0.tulkki.data.model.PinnedMessage
import uk.xa0.tulkki.data.omemo.OmemoQueries
import uk.xa0.tulkki.data.schema.RawTables

/**
 * The backup's own statements: what each of the export's named sets reads.
 *
 * <p>**Why the export needs a home of its own.** `ExportBackupWorker` used to hold the connection
 * and compose its statements - `select * from <table> where <column>=?`, with the table and the
 * column coming from the caller - so `:app` knew the schema and `:data` did not. `docs/MIGRATION.md`,
 * "Design: the data layer" §2.7 puts the read on the boundary and §6 step 6 names this worker among
 * the four that must reach the file through `:data` rather than through a handle. The sets below are
 * therefore the backup's vocabulary, spelled once, here: there is no `select * from <caller's
 * table>` anywhere in the tree, and a caller cannot name a table this object does not already know.
 *
 * <p>**`SELECT *`, deliberately, and it is a fidelity rule rather than a shortcut.** The importer
 * inserts a row's columns by name, so the export is faithful only if it carries every column the
 * table has. Listing the columns here would make a column added to a table a column the backup
 * silently drops, with nothing to notice it; `*` cannot drop one, and `BackupExportTest` holds the
 * other end of the rule by comparing each read's columns with the table's own `PRAGMA table_info`.
 *
 * <p>**No table or column is spelled as a literal.** Every persisted name comes from the package
 * that owns it (`ConversationQueries`, `MessagesQueries`, `OmemoQueries`, `RawTables`, or the model
 * class the schema is named through), so a rename lands in one place and the naming rule's
 * "reached through the constant, never spelled here" holds for this package too.
 */
internal object BackupQueries {

    // -- the account's own row ------------------------------------------------------------------

    /** The account row, by its key. One row, and the export's loop is over accounts. */
    @JvmField
    val ACCOUNT: String =
        "SELECT * FROM " + Account.TABLENAME + " WHERE " + Account.UUID + " = ?"

    // -- the account's rows, scoped by the two joins the export has always used ------------------

    /** The account's rooms, by the account column `conversations` carries. */
    @JvmField
    val CONVERSATION_ROWS: String =
        "SELECT * FROM " +
            ConversationQueries.TABLE +
            " WHERE " +
            ConversationQueries.ACCOUNT +
            " = ?"

    /**
     * The account's messages: `messages` owns no account column, so the scope is the join through
     * the room - the shape the export has used since long before this port.
     */
    @JvmField
    val MESSAGE_ROWS: String =
        "SELECT " +
            MessagesQueries.TABLE +
            ".* FROM " +
            MessagesQueries.TABLE +
            " JOIN " +
            ConversationQueries.TABLE +
            " ON " +
            ConversationQueries.TABLE +
            "." +
            ConversationQueries.UUID +
            " = " +
            MessagesQueries.TABLE +
            "." +
            MessagesQueries.CONVERSATION +
            " WHERE " +
            ConversationQueries.TABLE +
            "." +
            ConversationQueries.ACCOUNT +
            " = ?"

    /** The WebXDC state of the account's rooms, scoped by the same join. */
    @JvmField
    val WEBXDC_ROWS: String =
        "SELECT " +
            RawTables.WEBXDC_TABLE +
            ".* FROM " +
            ConversationQueries.TABLE +
            " JOIN " +
            RawTables.WEBXDC_TABLE +
            " ON " +
            ConversationQueries.TABLE +
            "." +
            ConversationQueries.UUID +
            " = " +
            RawTables.WEBXDC_TABLE +
            "." +
            Message.CONVERSATION +
            " WHERE " +
            ConversationQueries.TABLE +
            "." +
            ConversationQueries.ACCOUNT +
            " = ?"

    /** The owner's pins for the account. */
    @JvmField
    val PINNED_ROWS: String =
        "SELECT * FROM " +
            PinnedMessage.TABLENAME +
            " WHERE " +
            PinnedMessage.ACCOUNT_UUID +
            " = ?"

    /**
     * The room mutes. **The whole table, and that is the behaviour this port must not change.**
     * The export has always written `muted_participants` unfiltered - it is the one set in the file
     * with no account predicate - so an account's backup carries every account's mutes. Narrowing it
     * here would be a quiet change to what a backup contains, which is a decision of its own and not
     * this port's; the quirk is recorded rather than repaired. `OrphanedAccountRowsTest` is the test
     * that owns the table's own account scoping, and it reads `MUTED_FOR_ACCOUNT`, not this.
     */
    @JvmField
    val MUTE_ROWS: String = "SELECT * FROM " + RawTables.MUTED_TABLE

    /** The account's prekeys, signed prekeys, sessions and identities, in that order. */
    @JvmField
    val OMEMO_ROWS: List<Pair<String, String>> =
        listOf(
            OmemoQueries.PREKEYS_TABLE to omemoRows(OmemoQueries.PREKEYS_TABLE),
            OmemoQueries.SIGNED_PREKEYS_TABLE to omemoRows(OmemoQueries.SIGNED_PREKEYS_TABLE),
            OmemoQueries.SESSIONS_TABLE to omemoRows(OmemoQueries.SESSIONS_TABLE),
            OmemoQueries.IDENTITIES_TABLE to omemoRows(OmemoQueries.IDENTITIES_TABLE),
        )

    private fun omemoRows(table: String): String =
        "SELECT * FROM " + table + " WHERE " + OmemoQueries.ACCOUNT + " = ?"

    // -- the files the backup carries, one path at a time ----------------------------------------

    /**
     * The account's attachment paths: every message of the account that names a relative file path.
     * The exporter turns each into a portable path and copies the bytes; the read answers paths.
     */
    @JvmField
    val ATTACHMENT_PATHS: String =
        "SELECT " +
            MessagesQueries.TABLE +
            "." +
            Message.RELATIVE_FILE_PATH +
            " FROM " +
            MessagesQueries.TABLE +
            " JOIN " +
            ConversationQueries.TABLE +
            " ON " +
            ConversationQueries.TABLE +
            "." +
            ConversationQueries.UUID +
            " = " +
            MessagesQueries.TABLE +
            "." +
            MessagesQueries.CONVERSATION +
            " WHERE " +
            ConversationQueries.TABLE +
            "." +
            ConversationQueries.ACCOUNT +
            " = ? AND " +
            MessagesQueries.TABLE +
            "." +
            Message.RELATIVE_FILE_PATH +
            " IS NOT NULL"

    /** The account's own avatar's file name, when it has one. */
    @JvmField
    val ACCOUNT_AVATAR: String =
        "SELECT " +
            Account.AVATAR +
            " FROM " +
            Account.TABLENAME +
            " WHERE " +
            Account.AVATAR +
            " IS NOT NULL AND " +
            Account.UUID +
            " = ?"

    /** The file names of the account's contacts' avatars. */
    @JvmField
    val CONTACT_AVATAR: String =
        "SELECT " +
            Contact.AVATAR +
            " FROM " +
            Contact.TABLENAME +
            " WHERE " +
            Contact.AVATAR +
            " IS NOT NULL AND " +
            Contact.ACCOUNT +
            " = ?"

    // -- what the restore reports ----------------------------------------------------------------

    /**
     * How many messages one account holds after a restore, by the account's own names: the count the
     * importer logs, and the one read the import side owns.
     *
     * <p>Scoped by `username` and `server` rather than by uuid because the restore has just inserted
     * the account row out of the file, and the pair the backup header names is the pair that
     * identifies the restored account.
     */
    @JvmField
    val RESTORED_MESSAGE_COUNT: String =
        "SELECT COUNT(" +
            MessagesQueries.TABLE +
            "." +
            MessagesQueries.UUID +
            ") FROM " +
            MessagesQueries.TABLE +
            " JOIN " +
            ConversationQueries.TABLE +
            " ON " +
            ConversationQueries.TABLE +
            "." +
            ConversationQueries.UUID +
            " = " +
            MessagesQueries.TABLE +
            "." +
            MessagesQueries.CONVERSATION +
            " JOIN " +
            Account.TABLENAME +
            " ON " +
            ConversationQueries.TABLE +
            "." +
            ConversationQueries.ACCOUNT +
            " = " +
            Account.TABLENAME +
            "." +
            Account.UUID +
            " WHERE " +
            Account.TABLENAME +
            "." +
            Account.USERNAME +
            " = ? AND " +
            Account.TABLENAME +
            "." +
            Account.SERVER +
            " = ?"

    /**
     * Every statement this object publishes, in declaration order: the instrument
     * `BackupExportTest` uses to prove each one runs, and the list a reader checks a new set against.
     */
    @JvmField
    val STATEMENTS: List<String> =
        listOf(ACCOUNT, CONVERSATION_ROWS, MESSAGE_ROWS, WEBXDC_ROWS, PINNED_ROWS, MUTE_ROWS) +
            OMEMO_ROWS.map { it.second } +
            listOf(ATTACHMENT_PATHS, ACCOUNT_AVATAR, CONTACT_AVATAR)

    /**
     * Every table the export writes, in the order it writes them. Declared last because a Kotlin
     * `object` initialises its properties in declaration order and this names [OMEMO_ROWS].
     *
     * <p>The list is the export's half of the round trip, and `BackupExportTest` checks it against
     * the importer's own allow-list: a table added here that
     * `ImportBackupWorker.TABLE_ALLOW_LIST` does not name is a backup that cannot be restored.
     */
    /** The OMEMO store's four tables, by their own names: the set the import's `omemo` rule scopes. */
    @JvmField
    val OMEMO_TABLES: List<String> = OMEMO_ROWS.map { it.first }

    @JvmField
    val TABLES: List<String> =
        listOf(
            Account.TABLENAME,
            ConversationQueries.TABLE,
            MessagesQueries.TABLE,
            RawTables.WEBXDC_TABLE,
            PinnedMessage.TABLENAME,
            RawTables.MUTED_TABLE,
        ) + OMEMO_ROWS.map { it.first }
}
