package uk.xa0.tulkki.data.schema

import androidx.sqlite.db.SupportSQLiteDatabase
import net.zetetic.database.sqlcipher.SQLiteDatabase
import uk.xa0.tulkki.data.TranslationTables
import uk.xa0.tulkki.data.messages.ConversationQueries
import uk.xa0.tulkki.data.messages.MessagesQueries
import uk.xa0.tulkki.data.model.Account
import uk.xa0.tulkki.data.model.Conversation
import uk.xa0.tulkki.data.model.Message
import uk.xa0.tulkki.data.model.PinnedMessage
import uk.xa0.tulkki.data.model.Post
import uk.xa0.tulkki.data.model.Story
import uk.xa0.tulkki.xmpp.utils.Resolver

/**
 * The fresh install's schema for everything Room's generated `createAllTables` cannot express.
 *
 * <p>This is the second half of the fresh-install path, and the last home of the legacy DDL. It was
 * `DatabaseBackend.onCreate`'s until S5-5; the class kept only the connection it is handed, and the
 * statements moved here so that "which `CREATE`s exist" has one owning file that is not also a
 * 3,000-line reader. `docs/MIGRATION.md`, "Design: the data layer" §2.1 names it and §1.7 says what
 * it is for: the tables Room declares no entity for, and the FTS machinery, as raw DDL in one place.
 *
 * <p><strong>Why these statements and not the others.</strong> Room's `createAllTables` runs first
 * (measured, not assumed: the class note in {@code HistoryDatabase}), from the entity declarations,
 * so every table an `@Entity` names already exists by the time this runs - and every column of every
 * one of them. What is missing is exactly what no entity declares:
 *
 * <ul>
 *   <li>`messages_index`, the external-content FTS4 table over `messages.translated_body`, and the
 *       three triggers that keep it in step. `messages/` owns the statements
 *       ([MessagesQueries]); this object owns running them on a fresh install.
 *   <li>`resolver_results`, `posts`, `stories`, `webxdc_updates` (with its two indexes),
 *       `muted_participants` and `pinned_messages` (with its two indexes) - the SRV cache, the two
 *       leftover upstream social tables whose rows are kept because the file has them, the webxdc
 *       state, the per-session room mutes and the owner's pins.
 *   <li>Tulkki's own three tables, `translation_queue`, `translation_cache` and
 *       `translation_usage` ([TranslationTables]).
 * </ul>
 *
 * <p><strong>The two account-scoped tables carry S5-12's keys, not the legacy shapes.</strong>
 * `webxdc_updates.conversationUuid` references `conversations(uuid)` and
 * `muted_participants.account_uuid` references `accounts(uuid)`, both `ON DELETE CASCADE`,
 * so `DatabaseBackend.deleteAccount`'s one `DELETE FROM accounts` takes them with it. The
 * legacy spellings ([CREATE_WEBXDC_LEGACY], [CREATE_MUTED_LEGACY]) are data here as well:
 * schema 77's rebuild copies out of them into these shapes, and `OrphanedAccountRowsTest`
 * replays the legacy file. This file is the single home for both tables: the one S5-12 put
 * in `data/raw/` is gone, and `DatabaseBackend` and `Schema77` name this object.
 *
 * <p><strong>What is deliberately not here.</strong> No entity's table, and no guarded column
 * `ALTER`: `messages` has carried `file_deleted`, `subject`, `oobUri`, `fileParams`, `payloads`,
 * `timeReceived`, `notificationDismissed`, `occupant_id`, the three translation columns and
 * `delivery` in `MessageEntity` since S5-2b, and `conversations` has carried
 * `detected_language`/`language_override` since the same commit, so the guards
 * `DatabaseBackend.ensureMessageFileDeletedColumn` and `DatabaseBackend.addTranslationColumns`
 * used to run cannot fire on a fresh install - Room made the columns before this runs. They were
 * deleted with the legacy chain rather than copied here, because a guard that can never fire is a
 * second spelling of the entity and would hide the day one stopped agreeing with it.
 *
 * <p><strong>The upgrade path does not run this, and does not need to.</strong> Every table here
 * predates schema 75 - the newest is `translation_usage`, schema 74 - so a file the owner upgrades
 * already has all of them and all of their columns. The rule "a new table must never be created by
 * `RawTables` alone" (§6 step 3) is about a table this repository adds; there is none here.
 */
object RawTables {

    // -- webxdc_updates ---------------------------------------------------------------------------

    const val WEBXDC_TABLE = "webxdc_updates"

    /** The legacy `AUTOINCREMENT` key; the stream is read in the order it arrived. */
    const val WEBXDC_SERIAL = "serial"

    const val WEBXDC_SENDER = "sender"

    const val WEBXDC_THREAD = "thread"

    const val WEBXDC_THREAD_PARENT = "threadParent"

    const val WEBXDC_INFO = "info"

    const val WEBXDC_DOCUMENT = "document"

    const val WEBXDC_SUMMARY = "summary"

    const val WEBXDC_PAYLOAD = "payload"

    const val WEBXDC_MESSAGE_ID = "message_id"

    /**
     * `webxdc_updates.message_id` added to a file that predates it: SQLite has no
     * `ADD COLUMN IF NOT EXISTS`, so [applySchema] checks the column first - the same guard
     * the legacy chain ran, kept because the JVM fixture drives this over a file that may
     * already have the column.
     */
    private const val ADD_WEBXDC_MESSAGE_ID =
        "ALTER TABLE " + WEBXDC_TABLE + " ADD COLUMN " + WEBXDC_MESSAGE_ID + " TEXT"

    /**
     * The rebuilt table's columns, in `PRAGMA table_info` order - the legacy declaration's own. The
     * new table is a `CREATE` from this list (schema 77 does create-copy-drop-rename), and the
     * legacy `CREATE` below is what the copy reads.
     */
    @JvmField
    val WEBXDC_COLUMNS: List<Pair<String, String>> =
        listOf(
            WEBXDC_SERIAL to "INTEGER NOT NULL",
            Message.CONVERSATION to "TEXT NOT NULL",
            WEBXDC_SENDER to "TEXT NOT NULL",
            WEBXDC_THREAD to "TEXT NOT NULL",
            WEBXDC_THREAD_PARENT to "TEXT",
            WEBXDC_INFO to "TEXT",
            WEBXDC_DOCUMENT to "TEXT",
            WEBXDC_SUMMARY to "TEXT",
            WEBXDC_PAYLOAD to "TEXT",
            WEBXDC_MESSAGE_ID to "TEXT",
        )

    /**
     * The key is the legacy `AUTOINCREMENT` row's own, copied from the old table's `rowid` through
     * [uk.xa0.tulkki.data.schema.Schema76.rebuild]'s `rowid` column; the foreign key is the one this
     * commit adds. `AUTOINCREMENT` is deliberately gone: it only ever existed to keep `serial`
     * monotonic across a delete, and the stream is read in `serial` order anyway. A Room entity
     * cannot declare `AUTOINCREMENT`, and this table is rebuilt once here, so the rebuild settles
     * the shape rather than leaving one no entity could ever match.
     */
    const val WEBXDC_TAIL =
        ", PRIMARY KEY(serial), FOREIGN KEY(" +
            Message.CONVERSATION +
            ") REFERENCES " +
            Conversation.TABLENAME +
            "(" +
            Conversation.UUID +
            ") ON DELETE CASCADE"

    /**
     * The legacy `CREATE`, byte for byte what `DatabaseBackend.onCreate` wrote, kept because the
     * schema-77 rebuild copies out of it. A fresh install never runs it: it runs
     * [WEBXDC_CREATE_STATEMENTS] instead, so the two paths land on the same table.
     */
    const val CREATE_WEBXDC_LEGACY =
        "CREATE TABLE IF NOT EXISTS " +
            WEBXDC_TABLE +
            " (" +
            WEBXDC_SERIAL +
            " INTEGER PRIMARY KEY AUTOINCREMENT, " +
            Message.CONVERSATION +
            " TEXT NOT NULL, " +
            WEBXDC_SENDER +
            " TEXT NOT NULL, " +
            WEBXDC_THREAD +
            " TEXT NOT NULL, " +
            WEBXDC_THREAD_PARENT +
            " TEXT, " +
            WEBXDC_INFO +
            " TEXT, " +
            WEBXDC_DOCUMENT +
            " TEXT, " +
            WEBXDC_SUMMARY +
            " TEXT, " +
            WEBXDC_PAYLOAD +
            " TEXT, " +
            WEBXDC_MESSAGE_ID +
            " TEXT" +
            ")"

    /** The new table's `CREATE`, from [WEBXDC_COLUMNS]; the one a fresh install runs. */
    @JvmField
    val CREATE_WEBXDC: String =
        "CREATE TABLE IF NOT EXISTS " +
            WEBXDC_TABLE +
            " (" +
            WEBXDC_COLUMNS.joinToString(",") { it.first + " " + it.second } +
            WEBXDC_TAIL +
            ")"

    /** The legacy index over the pair the interface reads a thread by. */
    @JvmField
    val CREATE_WEBXDC_INDEX: String =
        "CREATE INDEX IF NOT EXISTS webxdc_index ON " +
            WEBXDC_TABLE +
            " (" +
            Message.CONVERSATION +
            ", thread)"

    /** The legacy unique index over the update's own message id, kept verbatim. */
    @JvmField
    val CREATE_WEBXDC_MESSAGE_ID_INDEX: String =
        "CREATE UNIQUE INDEX IF NOT EXISTS webxdc_message_id_index ON " +
            WEBXDC_TABLE +
            " (" +
            Message.CONVERSATION +
            ", " +
            WEBXDC_MESSAGE_ID +
            ")"

    /** The table, then its two indexes: what a fresh install executes, in order. */
    @JvmField
    val WEBXDC_CREATE_STATEMENTS: List<String> =
        listOf(CREATE_WEBXDC, CREATE_WEBXDC_INDEX, CREATE_WEBXDC_MESSAGE_ID_INDEX)

    /**
     * The indexes alone. A rebuild recreates the table, and `DROP TABLE` takes an index with it, so
     * the migration re-runs exactly these two after the rename.
     */
    @JvmField
    val WEBXDC_INDEX_STATEMENTS: List<String> =
        listOf(CREATE_WEBXDC_INDEX, CREATE_WEBXDC_MESSAGE_ID_INDEX)

    // -- muted_participants ------------------------------------------------------------------------

    const val MUTED_TABLE = "muted_participants"

    /**
     * The account that muted, and the column the `ON DELETE CASCADE` is carried by. The name is the
     * mute table's own (`account_uuid`), not `accounts.uuid`: the foreign key already names the table
     * it points at, and the two are joined by the value, not by a shared column name.
     */
    const val MUTED_ACCOUNT = "account_uuid"

    /** The room as the interface writes it: the MUC's bare JID. */
    const val MUTED_MUC = "muc_jid"

    const val MUTED_OCCUPANT = "occupant_id"

    /** The legacy column dropped on API 34; the rebuild reads whichever columns the file has. */
    const val MUTED_LEGACY_NICK = "nick"

    /**
     * The rebuilt table: the legacy pair as the key, plus the owner.
     *
     * <p>There is deliberately no `_id` surrogate here, unlike `contacts` and the OMEMO store's
     * tables. Those carry one because a Room entity needs a key it can name; this table has no
     * entity, and a `_id INTEGER NOT NULL` no live writer binds is a column that throws on the
     * first mute. The rebuild carries the old `rowid` across as the table's own `rowid` instead.
     */
    @JvmField
    val MUTED_COLUMNS: List<Pair<String, String>> =
        listOf(
            MUTED_ACCOUNT to "TEXT",
            MUTED_MUC to "TEXT",
            MUTED_OCCUPANT to "TEXT",
        )

    /**
     * `account_uuid` is nullable in the file only because a create-copy-drop-rename may not make a
     * row the file already holds illegal - the copy cannot invent an owner for a legacy `muc_jid`
     * that names no conversation. The live writers always bind one: the mute path resolves the
     * account from the conversation the participant belongs to, so no caller passes it and it
     * cannot be got wrong. The room/occupant pair stays the key, exactly as it was; the foreign key
     * is the fix.
     */
    const val MUTED_TAIL =
        ", PRIMARY KEY(" +
            MUTED_MUC +
            ", " +
            MUTED_OCCUPANT +
            "), FOREIGN KEY(" +
            MUTED_ACCOUNT +
            ") REFERENCES " +
            Account.TABLENAME +
            "(" +
            Account.UUID +
            ") ON DELETE CASCADE"

    /**
     * The legacy `CREATE`, in the shape the owner's file is in after the API-34 `nick` drop (the
     * `DROP COLUMN` branch `onCreate` still holds for API 33 and below). Kept for the rebuild, which
     * copies out of it.
     */
    const val CREATE_MUTED_LEGACY =
        "CREATE TABLE IF NOT EXISTS " +
            MUTED_TABLE +
            " (" +
            MUTED_MUC +
            " TEXT NOT NULL, " +
            MUTED_OCCUPANT +
            " TEXT NOT NULL, " +
            "PRIMARY KEY (" +
            MUTED_MUC +
            ", " +
            MUTED_OCCUPANT +
            ")" +
            ")"

    /** The new table's `CREATE`, from [MUTED_COLUMNS]; the one a fresh install runs. */
    @JvmField
    val CREATE_MUTED: String =
        "CREATE TABLE IF NOT EXISTS " +
            MUTED_TABLE +
            " (" +
            MUTED_COLUMNS.joinToString(",") { it.first + " " + it.second } +
            MUTED_TAIL +
            ")"

    /**
     * The account a room JID belongs to, as the file records it: a MUC conversation of some
     * account. Read through the queries package so the table and column names are spelled once.
     */
    @JvmField
    val MUTED_ACCOUNT_BY_JID: String =
        "SELECT " +
            ConversationQueries.ACCOUNT +
            " FROM " +
            ConversationQueries.TABLE +
            " WHERE " +
            ConversationQueries.CONTACT_JID +
            " = ?"

    /**
     * Every mute one account owns, as `muc_jid` + `occupant_id` - the multimap
     * `loadMutedMucUsers()` returned globally and now returns per account. A row with a null
     * `account_uuid` is nobody's and is never returned.
     */
    @JvmField
    val MUTED_FOR_ACCOUNT: String =
        "SELECT " +
            MUTED_MUC +
            ", " +
            MUTED_OCCUPANT +
            " FROM " +
            MUTED_TABLE +
            " WHERE " +
            MUTED_ACCOUNT +
            " = ?"

    /**
     * The resolver's SRV cache. `internal` rather than `private` since the `discovery/` store
     * became its second reader inside `:data`: the name's home is the object that owns the
     * `CREATE`, and the live methods no longer carry a copy.
     */
    internal const val RESOLVER_RESULTS_TABLENAME = "resolver_results"

    private val CREATE_RESOLVER_RESULTS_TABLE =
        "create table if not exists $RESOLVER_RESULTS_TABLENAME(" +
            Resolver.Result.DOMAIN + " TEXT," +
            Resolver.Result.HOSTNAME + " TEXT," +
            Resolver.Result.IP + " BLOB," +
            Resolver.Result.PRIORITY + " NUMBER," +
            Resolver.Result.DIRECT_TLS + " NUMBER," +
            Resolver.Result.AUTHENTICATED + " NUMBER," +
            Resolver.Result.PORT + " NUMBER," +
            "UNIQUE(" + Resolver.Result.DOMAIN + ") ON CONFLICT REPLACE" +
            ");"

    private val CREATE_POSTS_TABLE =
        "CREATE TABLE if not exists ${Post.TABLENAME} (" +
            "${Post.UUID} TEXT PRIMARY KEY," +
            "${Post.ACCOUNT_UUID} TEXT," +
            "${Post.AUTHOR_JID} TEXT," +
            "${Post.TITLE} TEXT," +
            "${Post.CONTENT} TEXT," +
            "${Post.ATTACHMENT_URL} TEXT," +
            "${Post.ATTACHMENT_TYPE} TEXT," +
            "${Post.LINK_URL} TEXT," +
            "${Post.PUBLISHED} NUMBER," +
            "${Post.COMMENTS_NODE} TEXT," +
            "FOREIGN KEY(${Post.ACCOUNT_UUID}) REFERENCES " +
            "${Account.TABLENAME}(${Account.UUID}) ON DELETE CASCADE);"

    private val CREATE_STORIES_TABLE =
        "CREATE TABLE IF NOT EXISTS ${Story.TABLENAME} (" +
            "${Story.UUID} TEXT PRIMARY KEY," +
            "${Story.CONTACT} TEXT," +
            "${Story.URL} TEXT," +
            "${Story.TYPE} TEXT," +
            "${Story.TITLE} TEXT," +
            "${Story.PUBLISHED} NUMBER);"

    private val CREATE_PINNED_MESSAGES =
        "CREATE TABLE IF NOT EXISTS ${PinnedMessage.TABLENAME} (" +
            "${PinnedMessage.MESSAGE_UUID} TEXT PRIMARY KEY, " +
            "${PinnedMessage.CONVERSATION_UUID} TEXT, " +
            "${PinnedMessage.ACCOUNT_UUID} TEXT, " +
            "${PinnedMessage.BODY} TEXT, " +
            "${PinnedMessage.TIMESTAMP} NUMBER, " +
            "${PinnedMessage.CID} TEXT, " +
            "FOREIGN KEY(${PinnedMessage.CONVERSATION_UUID}) REFERENCES " +
            "${Conversation.TABLENAME}(${Conversation.UUID}) ON DELETE CASCADE, " +
            "FOREIGN KEY(${PinnedMessage.ACCOUNT_UUID}) REFERENCES " +
            "${Account.TABLENAME}(${Account.UUID}) ON DELETE CASCADE" +
            ")"

    private val CREATE_PINNED_MESSAGES_INDEX =
        "CREATE INDEX IF NOT EXISTS pinned_messages_index ON " +
            "${PinnedMessage.TABLENAME} (${PinnedMessage.CONVERSATION_UUID})"

    private val CREATE_PINNED_MESSAGES_ACCOUNT_INDEX =
        "CREATE INDEX IF NOT EXISTS pinned_messages_account_index ON " +
            "${PinnedMessage.TABLENAME} (${PinnedMessage.ACCOUNT_UUID})"

    /**
     * The statements, `IF NOT EXISTS` throughout so a second run is a no-op, in an order that
     * satisfies the foreign keys: `pinned_messages` references `conversations` and `accounts`, which
     * Room has already created when this runs.
     *
     * <p>Declared after the statements it names: a Kotlin `object` initialises its properties in
     * declaration order, so a list above them would be built out of nulls.
     */
    @JvmField
    val STATEMENTS: List<String> =
        listOf(
            MessagesQueries.CREATE_INDEX_TABLE,
            MessagesQueries.CREATE_INSERT_TRIGGER,
            MessagesQueries.CREATE_UPDATE_TRIGGER,
            MessagesQueries.CREATE_DELETE_TRIGGER,
            CREATE_RESOLVER_RESULTS_TABLE,
            CREATE_POSTS_TABLE,
            CREATE_STORIES_TABLE,
        ) +
            WEBXDC_CREATE_STATEMENTS +
            listOf(
                CREATE_MUTED,
                CREATE_PINNED_MESSAGES,
                CREATE_PINNED_MESSAGES_INDEX,
                CREATE_PINNED_MESSAGES_ACCOUNT_INDEX,
                TranslationTables.CREATE_QUEUE_TABLE,
                TranslationTables.CREATE_QUEUE_INDEX,
                TranslationTables.CREATE_CACHE_TABLE,
                TranslationTables.CREATE_USAGE_TABLE,
            )

    @JvmStatic fun applySchema(db: SupportSQLiteDatabase) = applySchema(SchemaExecs.of(db))

    @JvmStatic fun applySchema(db: SQLiteDatabase) = applySchema(SchemaExecs.of(db))

    /** The one definition the fresh-install callback runs; the JVM harness drives exactly this. */
    @JvmStatic
    fun applySchema(exec: SchemaExec) {
        for (statement in STATEMENTS) {
            exec.exec(statement)
        }
        if (!exec.hasColumn(WEBXDC_TABLE, WEBXDC_MESSAGE_ID)) {
            exec.exec(ADD_WEBXDC_MESSAGE_ID)
        }
        exec.exec(CREATE_WEBXDC_MESSAGE_ID_INDEX)
    }
}
