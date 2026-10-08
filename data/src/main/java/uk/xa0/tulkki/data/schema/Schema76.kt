package uk.xa0.tulkki.data.schema

import android.database.Cursor
import androidx.sqlite.db.SupportSQLiteDatabase
import net.zetetic.database.sqlcipher.SQLiteDatabase
import org.json.JSONObject
import uk.xa0.tulkki.data.messages.MessagesQueries
import uk.xa0.tulkki.data.model.Account
import uk.xa0.tulkki.data.model.Conversation
import uk.xa0.tulkki.data.model.Message
import uk.xa0.tulkki.data.sync.SyncQueries
import uk.xa0.tulkki.data.sync.SyncQueries.ANCHOR_SOURCE_SEEDED_FROM_STORE
import uk.xa0.tulkki.data.sync.SyncQueries.CONVERSATION_ACCOUNT
import uk.xa0.tulkki.data.sync.SyncQueries.CONVERSATION_ANCHOR_STANZA
import uk.xa0.tulkki.data.sync.SyncQueries.CONVERSATION_ANCHOR_TIME
import uk.xa0.tulkki.data.sync.SyncQueries.CONVERSATION_ARCHIVE_FIRST
import uk.xa0.tulkki.data.sync.SyncQueries.CONVERSATION_SWEPT_THROUGH
import uk.xa0.tulkki.data.sync.SyncQueries.CONVERSATION_TABLE
import uk.xa0.tulkki.data.sync.SyncQueries.CONVERSATION_UPDATED_AT
import uk.xa0.tulkki.data.sync.SyncQueries.CONVERSATION_UUID
import uk.xa0.tulkki.data.sync.SyncQueries.CURSOR_ACCOUNT
import uk.xa0.tulkki.data.sync.SyncQueries.CURSOR_ANCHOR_SOURCE
import uk.xa0.tulkki.data.sync.SyncQueries.CURSOR_ANCHOR_STANZA
import uk.xa0.tulkki.data.sync.SyncQueries.CURSOR_ANCHOR_TIME
import uk.xa0.tulkki.data.sync.SyncQueries.CURSOR_GAP_END
import uk.xa0.tulkki.data.sync.SyncQueries.CURSOR_TABLE
import uk.xa0.tulkki.data.sync.SyncQueries.CURSOR_UPDATED_AT
import uk.xa0.tulkki.xmpp.mam.MamReference

/**
 * Schema 76 (S5-2): the sync cursor's three tables, the `delivery` marker, and the one data repair
 * the search-index decision needs.
 *
 * **The sync tables' DDL no longer lives here.** S5-3 gave the capability packages their own tables,
 * so `uk.xa0.tulkki.data.sync.SyncQueries` owns the three `CREATE`s, their column names and their
 * index, and [STATEMENTS] delegates to it. What stays here is what the *migration* owns: the seed,
 * the guarded `delivery` column, the repair, and the three entity-table rebuilds. The change the
 * move carried is in `SyncQueries`'s comment - two primary keys gained `NOT NULL` because Room
 * compares each column's `notNull` flag after the migration, and SQLite calls a bare
 * `TEXT PRIMARY KEY` nullable.
 *
 * **Everything here is data, and there is one copy of it.** `docs/MIGRATION.md`, "Design: the data
 * layer" §4.2: the Room migration (75 → 76) and the fresh-install callback are both callers of this
 * object, and the design's whole job is to make them unable to disagree. So the statements are a
 * `List<String>` executed by both callers, the seed is one function the migration runs, and neither
 * caller is allowed to spell a sync table's name for itself - `Schema76SharedTest` fails if one of
 * them stops routing through here. A third caller existed for one commit, the legacy
 * `DatabaseBackend.onUpgrade` hook, and S5-5 deleted it with the rest of the legacy chain.
 *
 * **Why this is a Kotlin object and not the migration's body.** The seed cannot be pure SQL. The
 * account anchor is `MamReference.max(getLastMessageReceived(account), getLastClearDate(account))`
 * (`MessageArchiveService.catchup`), and `getLastClearDate` parses each conversation's `attributes`
 * JSON for `last_clear_history` and hands the value to `MamReference.fromAttribute` - a `time:ref`
 * string. Expressing that in SQL would mean hand-rolling `json_extract` (a compile-time option this
 * design will not bet the owner's file on) or `substr`/`instr` surgery on JSON. So the *reads* stay
 * reads, in Kotlin, and the DDL stays data.
 *
 * **What it must not do.** It never writes `PRAGMA user_version`: Room owns the version and sets it
 * to 76 itself once the migration returns. It never drops anything, and it never names the builder's
 * destructive-fallback option - `DestructiveMigrationBanTest` fails on the spelling itself, comments
 * included, which is why this one does not print it. Its failure mode is a refused open, which is
 * the property the whole adoption rests on.
 */
object Schema76 {

    /** The file's version once this schema is in place. `DatabaseBackend.DATABASE_VERSION` names it. */
    const val VERSION = 76

    /** The one schema addition outside the sync tables (`messages.delivery`). */
    const val DELIVERY_COLUMN = "delivery"

    /** 0 LIVE, 1 ARCHIVE, 2 UNKNOWN - and UNKNOWN is every row written before this column existed. */
    const val DELIVERY_UNKNOWN = 2

    /**
     * A name the file already has, read through the model so it is spelled once.
     *
     * `messages_index` is external-content FTS4 over this column; the repair below writes the column
     * and lets the table's own trigger maintain the index, exactly as every live writer does.
     */
    private val TRANSLATED_BODY = Message.TRANSLATED_BODY

    private val TRANSLATION_STATE = Message.TRANSLATION_STATE

    // -- the DDL: the capability owns it, the migration executes it -------------------------------

    /**
     * Everything schema 76 adds at the DDL level: `sync/`'s three tables and their index, from the
     * one definition that package owns (`SyncQueries`). Every statement is `IF NOT EXISTS`, so the
     * list is re-runnable - which is what makes the legacy hook safe to run on a file that has
     * already seen it.
     *
     * <p>The delegation is the whole point of S5-3's "each package owns its DDL": a migration that
     * re-spelled a capability's table could drift from the entity Room validates, and after
     * `RoomConnectionManager.onMigrate` runs `onValidateSchema`, a drift is a refused open on the
     * owner's first launch rather than a warning.
     */
    @JvmField
    val STATEMENTS: List<String> = SyncQueries.STATEMENTS

    /**
     * The one statement that cannot be `IF NOT EXISTS`: SQLite has no `ADD COLUMN IF NOT EXISTS`.
     * The design decides it is added by a guarded helper rather than by a raw statement in the shared
     * list ("Design: the data layer" §4.2), because the legacy hook must be re-runnable and Room's
     * migration does not need to be. The guard is the `PRAGMA` read in [addDeliveryIfAbsent].
     */
    /** The one spelling of the column's declaration: the guarded `ALTER` and the rebuilt table share it. */
    private val DELIVERY_DECLARATION = "INTEGER NOT NULL DEFAULT " + DELIVERY_UNKNOWN

    @JvmField
    val ADD_DELIVERY_COLUMN =
        "ALTER TABLE " +
            Message.TABLENAME +
            " ADD COLUMN " +
            DELIVERY_COLUMN +
            " " +
            DELIVERY_DECLARATION

    /**
     * The search-index repair ("The search index, decided"). `translated_body` means *the body as
     * the interface renders it*, so a row that needed no translation must carry its body there
     * instead of a NULL - otherwise the owner's own Finnish messages are unfindable until re-sent.
     *
     * It is an `UPDATE` of the column rather than an insert into `messages_index`, and that is the
     * point: the column is the durable decision, the FTS table is external-content over it, and the
     * table's own `after_message_update` trigger is what maintains the index for *every* writer. A
     * direct FTS insert would be a second mechanism that could disagree with the live path.
     *
     * Only `TRANSLATION_SAME_LANGUAGE` rows are touched: those are the rows the state field already
     * says needed no translation. A row that is covered (pending, failed) is deliberately left NULL,
     * and no row with a stored translation is touched at all.
     */
    val MIRROR_DISPLAYED_TEXT =
        "UPDATE " +
            Message.TABLENAME +
            " SET " +
            TRANSLATED_BODY +
            " = " +
            Message.BODY +
            " WHERE " +
            TRANSLATION_STATE +
            " = " +
            Message.TRANSLATION_SAME_LANGUAGE +
            " AND " +
            TRANSLATED_BODY +
            " IS NULL AND " +
            Message.BODY +
            " IS NOT NULL"

    /**
     * The belt for a wiped index. Schema 75 recreates `messages_index` empty and a rebuild refills it
     * at startup, so a row whose trigger-`UPDATE` hit nothing is still there - but until that
     * rebuild runs, a row the loop above mirrored would not be *findable*. This inserts exactly the
     * missing ones, so the repair is complete whether the index is populated or waiting to be
     * rebuilt. It is a no-op when the trigger already did the work.
     */
    val INDEX_MISSING_MIRRORED_ROWS =
        "INSERT INTO messages_index(rowid, uuid, " +
            TRANSLATED_BODY +
            ") SELECT rowid, " +
            Message.UUID +
            ", " +
            TRANSLATED_BODY +
            " FROM " +
            Message.TABLENAME +
            " WHERE " +
            TRANSLATION_STATE +
            " = " +
            Message.TRANSLATION_SAME_LANGUAGE +
            " AND " +
            TRANSLATED_BODY +
            " IS NOT NULL AND rowid NOT IN (SELECT rowid FROM messages_index)"

    // -- the anchor reads, moved out of the live path ---------------------------------------------

    /** Line for line `DatabaseBackend.getLastMessageReceived`'s SQL. */
    private val LAST_MESSAGE_RECEIVED =
        "SELECT " +
            "m." +
            Message.TIME_SENT +
            ", m." +
            Message.SERVER_MSG_ID +
            " FROM " +
            Account.TABLENAME +
            " a JOIN " +
            Conversation.TABLENAME +
            " c ON a." +
            Account.UUID +
            " = c." +
            Conversation.ACCOUNT +
            " JOIN " +
            Message.TABLENAME +
            " m ON c." +
            Conversation.UUID +
            " = m." +
            Message.CONVERSATION +
            " WHERE a." +
            Account.UUID +
            " = ?" +
            " AND (m." +
            Message.STATUS +
            " = " +
            Message.STATUS_RECEIVED +
            " OR m." +
            Message.CARBON +
            " = 1 OR m." +
            Message.SERVER_MSG_ID +
            " NOT NULL)" +
            " AND (c." +
            Conversation.MODE +
            " = " +
            Conversation.MODE_SINGLE +
            " OR (m." +
            Message.SERVER_MSG_ID +
            " NOT NULL AND m." +
            Message.TYPE +
            " = " +
            Message.TYPE_PRIVATE +
            ")) ORDER BY m." +
            Message.TIME_SENT +
            " DESC LIMIT 1"

    /**
     * Line for line `Conversation.getLastMessageTransmitted`'s backwards scan: the newest row that
     * is received, a carbon, or carries a server id - skipping private messages, "unsafe to use as
     * an anchor. They could be coming from user archive".
     */
    private val LAST_MESSAGE_TRANSMITTED =
        "SELECT " +
            "m." +
            Message.TIME_SENT +
            ", m." +
            Message.SERVER_MSG_ID +
            " FROM " +
            Message.TABLENAME +
            " m WHERE m." +
            Message.CONVERSATION +
            " = ?" +
            " AND m." +
            Message.TYPE +
            " NOT IN (" +
            Message.TYPE_PRIVATE +
            ", " +
            Message.TYPE_PRIVATE_FILE +
            ")" +
            " AND (m." +
            Message.STATUS +
            " = " +
            Message.STATUS_RECEIVED +
            " OR m." +
            Message.CARBON +
            " = 1 OR m." +
            Message.SERVER_MSG_ID +
            " NOT NULL)" +
            " ORDER BY m." +
            Message.TIME_SENT +
            " DESC LIMIT 1"

    // -- the three tables Room's entities declare: rebuilt, because a declared type is not ALTERable

    /**
     * Schema 76 declares `AccountEntity`, `ConversationEntity` and `MessageEntity`, and Room
     * validates a pre-existing file by comparing every declared column's normalised **affinity**
     * (`TableInfo.Column.equalsCommon`, final term unconditional) plus the not-null flag, the
     * primary key, the foreign keys and the indexes. A column declared `NUMBER` on disk normalises
     * to `UNDEFINED`, which no entity can emit, and no `ALTER` can change a declared type - so the
     * three tables are **rebuilt**: created new, copied, dropped, renamed.
     *
     * <p>They are three and not one because a foreign key is part of the comparison and Room's
     * `@ForeignKey` needs an entity class for its target: `messages` points at `conversations`,
     * `conversations` points at `accounts`, and `accounts` has `options NUMBER` and `port NUMBER`
     * of its own. Declaring `ConversationEntity` without its account foreign key would compare
     * `{}` against the file's `{accounts}`, which is a refused open.
     *
     * <p>**Foreign keys must be off for this, and the rebuild refuses rather than hopes.** SQLite
     * fires `ON DELETE CASCADE` on `DROP TABLE` when they are enabled, and the drops below are of
     * the very parents whose children are being carried across - with them on, `DROP TABLE
     * conversations` would delete the messages just copied. Room's migration and the legacy hook
     * both run inside the opener's version transaction, and the opener turns them on in `onOpen`,
     * *after* that transaction; [requireForeignKeysOff] reads the pragma and throws if that is ever
     * not true.
     *
     * <p>The columns are spelled here rather than read from the model constants because a rebuild is
     * a *name-for-name* copy and the entity declarations beside them are the second spelling;
     * `SyncMigrationTest` proves the two agree by comparing every row's every column before and
     * after, and the host-side Room validation proves the entities agree with the result.
     */
    const val ACCOUNTS_TABLE = "accounts"

    private val ACCOUNTS_COLUMNS: List<Pair<String, String>> =
        listOf(
            "uuid" to "TEXT NOT NULL PRIMARY KEY",
            "username" to "TEXT",
            "server" to "TEXT",
            "password" to "TEXT",
            "display_name" to "TEXT",
            "status" to "TEXT",
            "status_message" to "TEXT",
            "rosterversion" to "TEXT",
            "options" to "INTEGER",
            "avatar" to "TEXT",
            "keys" to "TEXT",
            "hostname" to "TEXT",
            "resource" to "TEXT",
            "pinned_mechanism" to "TEXT",
            "pinned_channel_binding" to "TEXT",
            "fast_mechanism" to "TEXT",
            "fast_token" to "TEXT",
            "ordering" to "INTEGER DEFAULT 0",
            "port" to "INTEGER DEFAULT 5222",
        )

    private val CONVERSATION_COLUMNS: List<Pair<String, String>> =
        listOf(
            "uuid" to "TEXT NOT NULL PRIMARY KEY",
            "name" to "TEXT",
            "contactUuid" to "TEXT",
            "accountUuid" to "TEXT",
            "contactJid" to "TEXT",
            "created" to "INTEGER",
            "status" to "INTEGER",
            "mode" to "INTEGER",
            "attributes" to "TEXT",
            "detected_language" to "TEXT",
            "language_override" to "TEXT",
        )

    private val MESSAGES_COLUMNS: List<Pair<String, String>> =
        listOf(
            "uuid" to "TEXT NOT NULL PRIMARY KEY",
            "conversationUuid" to "TEXT",
            "timeSent" to "INTEGER",
            "counterpart" to "TEXT",
            "trueCounterpart" to "TEXT",
            "body" to "TEXT",
            "encryption" to "INTEGER",
            "status" to "INTEGER",
            "type" to "INTEGER",
            "relativeFilePath" to "TEXT",
            "serverMsgId" to "TEXT",
            "axolotl_fingerprint" to "TEXT",
            "carbon" to "INTEGER",
            "edited" to "TEXT",
            "read" to "INTEGER DEFAULT 1",
            "oob" to "INTEGER",
            "errorMsg" to "TEXT",
            "readByMarkers" to "TEXT",
            "markable" to "INTEGER DEFAULT 0",
            "file_deleted" to "INTEGER DEFAULT 0",
            "deleted" to "INTEGER DEFAULT 0",
            "bodyLanguage" to "TEXT",
            "retractId" to "TEXT",
            "occupantId" to "TEXT",
            "occupant_id" to "TEXT",
            "reactions" to "TEXT",
            "remoteMsgId" to "TEXT",
            "ephemeral_timer" to "INTEGER DEFAULT 0",
            "expire_at" to "INTEGER DEFAULT 0",
            "translated_body" to "TEXT",
            "translation_lang" to "TEXT",
            "translation_state" to "INTEGER NOT NULL DEFAULT 0",
            "subject" to "TEXT",
            "oobUri" to "TEXT",
            "fileParams" to "TEXT",
            "payloads" to "TEXT",
            "timeReceived" to "INTEGER",
            "notificationDismissed" to "INTEGER DEFAULT 0",
            // Added by the guarded ALTER before the rebuild, carried by the rebuild itself, so a
            // second run preserves every LIVE/ARCHIVE marker instead of resetting them to UNKNOWN.
            DELIVERY_COLUMN to DELIVERY_DECLARATION,
        )

    /** The two foreign keys the 75 file already declares, spelled into the rebuilt `CREATE`s. */
    private val CONVERSATION_FOREIGN_KEY =
        ", FOREIGN KEY(" +
            Conversation.ACCOUNT +
            ") REFERENCES " +
            ACCOUNTS_TABLE +
            "(" +
            Account.UUID +
            ") ON DELETE CASCADE"

    private val MESSAGES_FOREIGN_KEY =
        ", FOREIGN KEY(" +
            Message.CONVERSATION +
            ") REFERENCES " +
            Conversation.TABLENAME +
            "(" +
            Conversation.UUID +
            ") ON DELETE CASCADE"

    /**
     * The deferred rebuild of the external-content FTS4 table: not a `CREATE`, and the one statement
     * in [MESSAGE_INDEX_STATEMENTS] that is a data operation. `messages/` owns the table's DDL; this
     * belongs to the migration that drops and re-creates the table under it.
     */
    private val COPY_PREEXISTING_ENTRIES =
        "INSERT INTO messages_index(messages_index) VALUES('rebuild')"

    /**
     * The eight indexes and the search index's own machinery, which go with `messages` because
     * `DROP TABLE messages` takes them down: an index and a trigger belong to their table, and the
     * FTS4 table keeps its `content="messages"` name while the table under it is replaced. The
     * index is external-content, so the rebuild finishes by re-reading it through the triggers'
     * column - the rowids the copy allocated are not the ones the index was built over.
     *
     * <p>**The list is `messages/`'s, not this migration's.** S5-3 moved it to
     * [uk.xa0.tulkki.data.messages.MessagesQueries.INDEX_STATEMENTS], and the fresh install runs
     * the same list through [RawTables] - so the rebuild, a fresh install and the fixture cannot
     * spell three `messages` tables. What stays here is the rebuild itself.
     */
    @JvmField
    val MESSAGE_INDEX_STATEMENTS: List<String> =
        MessagesQueries.INDEX_STATEMENTS + COPY_PREEXISTING_ENTRIES

    // -- the entry points -------------------------------------------------------------------------

    /** The fresh-install half, run by `HistoryDatabase.installFreshSchema`. No rows are written. */
    @JvmStatic
    fun applySchema(db: SupportSQLiteDatabase) {
        applySchema(SchemaExecs.of(db))
    }

    /** The `SQLiteDatabase` half, for a caller already holding the concrete handle. */
    @JvmStatic
    fun applySchema(db: SQLiteDatabase) {
        applySchema(SchemaExecs.of(db))
    }

    /** The upgrade half: the DDL, the guarded column, the repair and the seed. */
    @JvmStatic
    @JvmOverloads
    fun applyUpgrade(db: SupportSQLiteDatabase, now: Long = System.currentTimeMillis()) {
        applyUpgrade(SchemaExecs.of(db), now)
    }

    @JvmStatic
    @JvmOverloads
    fun applyUpgrade(db: SQLiteDatabase, now: Long = System.currentTimeMillis()) {
        applyUpgrade(SchemaExecs.of(db), now)
    }

    /** The one definition both callers run; the JVM harness drives exactly this. */
    @JvmStatic
    fun applySchema(exec: SchemaExec) {
        // The precondition first, not inside the rebuild: a refusal must leave the file untouched,
        // and the rebuild is not the only statement here that the cascade would make destructive.
        requireForeignKeysOff(exec)
        for (statement in STATEMENTS) {
            exec.exec(statement)
        }
        addDeliveryIfAbsent(exec)
        rebuildEntityTables(exec)
    }

    /** The one definition both callers run; the JVM harness drives exactly this. */
    @JvmStatic
    fun applyUpgrade(exec: SchemaExec, now: Long) {
        applySchema(exec)
        mirrorDisplayedText(exec)
        seed(exec, now)
    }

    /**
     * The guarded `delivery` helper. A bare `ALTER` on a file that already has the column throws,
     * and the legacy hook is allowed to run twice.
     */
    private fun addDeliveryIfAbsent(exec: SchemaExec) {
        if (!exec.hasColumn(Message.TABLENAME, DELIVERY_COLUMN)) {
            exec.exec(ADD_DELIVERY_COLUMN)
        }
    }

    /**
     * The three rebuilds, in parent-to-child order, and then everything `DROP TABLE messages` took
     * down with it. Each rebuild is create-copy-drop-rename and copies **every** column plus the
     * rowid, so a mistake here is a lost message rather than a compile error - which is what
     * `SyncMigrationTest`'s before-and-after row comparison exists to make impossible.
     */
    private fun rebuildEntityTables(exec: SchemaExec) {
        rebuild(exec, ACCOUNTS_TABLE, ACCOUNTS_COLUMNS)
        rebuild(exec, Conversation.TABLENAME, CONVERSATION_COLUMNS, CONVERSATION_FOREIGN_KEY)
        rebuild(exec, Message.TABLENAME, MESSAGES_COLUMNS, MESSAGES_FOREIGN_KEY)
        for (statement in MESSAGE_INDEX_STATEMENTS) {
            exec.exec(statement)
        }
    }

    /**
     * The create-copy-drop-rename itself, shared with `Schema77` (which rebuilds `roster/`'s two
     * tables for the same affinity reason, at the version that already exists). It copies **every**
     * column plus the rowid, so a mistake here is a lost row rather than a compile error.
     *
     * <p>`rowidColumn` names the *new* table's column that receives the old table's `rowid`. It is
     * `rowid` for every rebuild whose key is the row's own - SQLite's implicit rowid is available on
     * both sides - and `_id` for `contacts`, whose key is a surrogate and whose old table has no such
     * column; that column is then filled from `rowid` rather than copied by name.
     *
     * <p>`copy` is the one rebuild that cannot copy by name (S5-12's `muted_participants`, which
     * gains an owner the old row does not carry and has to read it out of the file). It replaces
     * only the `INSERT … SELECT`; the drop, the create and the rename are unchanged, so it cannot
     * turn a rebuild into something else. `null` - every other caller - is the by-name copy above.
     */
    internal fun rebuild(
        exec: SchemaExec,
        table: String,
        columns: List<Pair<String, String>>,
        tail: String = "",
        rowidColumn: String = "rowid",
        copy: String? = null,
    ) {
        val names = columns.filter { it.first != rowidColumn }.joinToString(",") { it.first }
        exec.exec("DROP TABLE IF EXISTS " + table + "_new")
        exec.exec(
            "CREATE TABLE " +
                table +
                "_new (" +
                columns.joinToString(",") { it.first + " " + it.second } +
                tail +
                ")",
        )
        exec.exec(
            copy
                ?: ("INSERT INTO " +
                    table +
                    "_new (" +
                    rowidColumn +
                    "," +
                    names +
                    ") SELECT rowid," +
                    names +
                    " FROM " +
                    table),
        )
        exec.exec("DROP TABLE " + table)
        exec.exec("ALTER TABLE " + table + "_new RENAME TO " + table)
    }

    /**
     * The refusal that stands between this migration and a cascade. `DROP TABLE` on a parent whose
     * children are being carried across is an implicit `DELETE FROM` that fires `ON DELETE CASCADE`
     * when foreign keys are on, so the rebuild would empty the very rows it is preserving - in the
     * order below, `DROP TABLE conversations` would take the copied messages with it. The opener
     * enables foreign keys in `onOpen`, after the version transaction that runs this, so they are
     * off on both real callers; this makes that a checked precondition rather than a prayer.
     *
     * <p>{@code internal} rather than private since schema 78: `MIGRATION_77_78` runs the S5-12
     * repairs ([Schema78.applyUpgrade]), which are `Schema77`'s own `Schema76.rebuild` calls, and it
     * is the same precondition. One spelling of the check, two migrations that need it.
     */
    internal fun requireForeignKeysOff(exec: SchemaExec) {
        val enabled = exec.rows("PRAGMA foreign_keys", emptyArray()).firstOrNull()?.firstOrNull()
        if (enabled != null && enabled != "0") {
            throw IllegalStateException(
                "the schema-76 rebuild must run with foreign keys off: SQLite fires ON DELETE " +
                    "CASCADE on DROP TABLE when they are on, and the rebuild drops the parents " +
                    "whose children it is carrying across. The opener enables them in onOpen, " +
                    "after the version transaction this runs inside - so this refused rather " +
                    "than deleted the owner's rows.",
            )
        }
    }

    private fun mirrorDisplayedText(exec: SchemaExec) {
        exec.exec(MIRROR_DISPLAYED_TEXT)
        exec.exec(INDEX_MISSING_MIRRORED_ROWS)
    }

    /**
     * The seed: the *only* place the old derivations are read, and they run once.
     *
     * An account or a conversation whose anchor is zero gets no row at all, because that is exactly
     * what the engine's `initial()` is and a row of zeroes would be a second spelling of it
     * ("Design: synchronisation" §1.3, §1.5).
     *
     * `archive_first_id` is NULL after an upgrade and that is the one documented loss:
     * `mFirstMamReference` is an in-memory field and is not persisted anywhere today. It is only
     * consumed when the anchor time is 0, and the seed makes that false for any conversation with
     * history - so the loss is confined to a conversation with no local history, which re-derives it
     * from its first REVERSE page. It is one query, not one purchase.
     */
    private fun seed(exec: SchemaExec, now: Long) {
        for (account in exec.rows("SELECT " + Account.UUID + " FROM " + Account.TABLENAME, emptyArray())) {
            val uuid = account[0] ?: continue
            val received = readAnchor(exec, LAST_MESSAGE_RECEIVED, uuid)
            val cleared = lastClearDate(exec, uuid)
            val anchor = MamReference.max(received, cleared) ?: continue
            if (anchor.getTimestamp() <= 0) {
                continue
            }
            exec.exec(
                "INSERT OR REPLACE INTO " +
                    CURSOR_TABLE +
                    " (" +
                    CURSOR_ACCOUNT +
                    ", " +
                    CURSOR_ANCHOR_STANZA +
                    ", " +
                    CURSOR_ANCHOR_TIME +
                    ", " +
                    CURSOR_ANCHOR_SOURCE +
                    ", " +
                    CURSOR_GAP_END +
                    ", " +
                    CURSOR_UPDATED_AT +
                    ") VALUES (?,?,?,?,?,?)",
                arrayOf(
                    uuid,
                    anchor.getReference(),
                    anchor.getTimestamp(),
                    ANCHOR_SOURCE_SEEDED_FROM_STORE,
                    0L,
                    now,
                ),
            )
        }
        val existing =
            exec.rows(
                "SELECT " +
                    Conversation.UUID +
                    ", " +
                    Conversation.ACCOUNT +
                    ", " +
                    Conversation.ATTRIBUTES +
                    " FROM " +
                    Conversation.TABLENAME,
                emptyArray(),
            )
        for (conversation in existing) {
            val uuid = conversation[0] ?: continue
            val account = conversation[1] ?: continue
            val transmitted = readAnchor(exec, LAST_MESSAGE_TRANSMITTED, uuid)
            val cleared = lastClearHistory(conversation[2])
            val anchor = MamReference.max(transmitted, cleared) ?: continue
            if (anchor.getTimestamp() <= 0) {
                continue
            }
            exec.exec(
                "INSERT OR REPLACE INTO " +
                    CONVERSATION_TABLE +
                    " (" +
                    CONVERSATION_UUID +
                    ", " +
                    CONVERSATION_ACCOUNT +
                    ", " +
                    CONVERSATION_ANCHOR_STANZA +
                    ", " +
                    CONVERSATION_ANCHOR_TIME +
                    ", " +
                    CONVERSATION_ARCHIVE_FIRST +
                    ", " +
                    CONVERSATION_SWEPT_THROUGH +
                    ", " +
                    CONVERSATION_UPDATED_AT +
                    ") VALUES (?,?,?,?,NULL,?,?)",
                arrayOf(uuid, account, anchor.getReference(), anchor.getTimestamp(), anchor.getTimestamp(), now),
            )
        }
    }

    private fun readAnchor(exec: SchemaExec, sql: String, uuid: String): MamReference? {
        val rows = exec.rows(sql, arrayOf(uuid))
        if (rows.isEmpty()) {
            return null
        }
        val row = rows[0]
        val time = row[0]?.toLongOrNull() ?: 0L
        if (time <= 0) {
            return null
        }
        return MamReference(time, row[1])
    }

    /** `DatabaseBackend.getLastClearDate`: the greatest `last_clear_history` over the account. */
    private fun lastClearDate(exec: SchemaExec, account: String): MamReference {
        val rows =
            exec.rows(
                "SELECT " +
                    Conversation.ATTRIBUTES +
                    " FROM " +
                    Conversation.TABLENAME +
                    " WHERE " +
                    Conversation.ACCOUNT +
                    " = ?",
                arrayOf(account),
            )
        var max = MamReference(0)
        for (row in rows) {
            max = MamReference.max(max, lastClearHistory(row[0])) ?: max
        }
        return max
    }

    /** One conversation's `last_clear_history`, as `Conversation.getAttribute` reads it. */
    private fun lastClearHistory(attributes: String?): MamReference {
        if (attributes == null) {
            return MamReference(0)
        }
        return try {
            val value = JSONObject(attributes).optString(Conversation.ATTRIBUTE_LAST_CLEAR_HISTORY, null)
            MamReference.fromAttribute(value)
        } catch (e: Exception) {
            // The JSON cannot be parsed; the conversation contributes no clear-history anchor. The
            // same silence `getLastClearDate` has always had.
            MamReference(0)
        }
    }
}

/** The two adapters. They exist so the shared body never names a database type. */
internal object SchemaExecs {

    fun of(db: SupportSQLiteDatabase): SchemaExec =
        object : SchemaExec {
            override fun exec(sql: String) = db.execSQL(sql)

            override fun exec(sql: String, args: Array<Any?>) = db.execSQL(sql, args)

            override fun hasColumn(table: String, column: String): Boolean =
                db.query("PRAGMA table_info(`" + table + "`)").use { cursor ->
                    cursor.hasColumnNamed(column)
                }

            override fun hasTable(table: String): Boolean =
                db.query(
                        "SELECT 1 FROM sqlite_master WHERE type IN ('table','view') AND name=?",
                        arrayOf<Any?>(table),
                    )
                    .use { it.count > 0 }

            override fun rows(sql: String, args: Array<String>): List<Array<String>> =
                db.query(sql, arrayOf<Any?>(*args)).use { it.toRows() }
        }

    fun of(db: SQLiteDatabase): SchemaExec =
        object : SchemaExec {
            override fun exec(sql: String) = db.execSQL(sql)

            override fun exec(sql: String, args: Array<Any?>) = db.execSQL(sql, args)

            override fun hasColumn(table: String, column: String): Boolean =
                db.rawQuery("PRAGMA table_info(`" + table + "`)", null).use { cursor ->
                    cursor.hasColumnNamed(column)
                }

            override fun hasTable(table: String): Boolean =
                db.rawQuery(
                        "SELECT 1 FROM sqlite_master WHERE type IN ('table','view') AND name=?",
                        arrayOf(table),
                    )
                    .use { it.count > 0 }

            override fun rows(sql: String, args: Array<String>): List<Array<String>> =
                db.rawQuery(sql, args).use { it.toRows() }
        }

    private fun Cursor.hasColumnNamed(name: String): Boolean {
        val index = getColumnIndex("name")
        if (index < 0) {
            return false
        }
        while (moveToNext()) {
            if (name.equals(getString(index), ignoreCase = true)) {
                return true
            }
        }
        return false
    }

    private fun Cursor.toRows(): List<Array<String>> {
        val out = ArrayList<Array<String>>(count)
        val columns = columnCount
        while (moveToNext()) {
            val row = arrayOfNulls<String>(columns)
            for (i in 0 until columns) {
                row[i] = if (isNull(i)) null else getString(i)
            }
            @Suppress("UNCHECKED_CAST")
            out.add(row as Array<String>)
        }
        return out
    }
}
