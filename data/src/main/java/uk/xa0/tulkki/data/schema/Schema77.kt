package uk.xa0.tulkki.data.schema

import androidx.sqlite.db.SupportSQLiteDatabase
import net.zetetic.database.sqlcipher.SQLiteDatabase
import uk.xa0.tulkki.data.TranslationTables
import uk.xa0.tulkki.data.blocking.BlockingQueries
import uk.xa0.tulkki.data.messages.MessagesQueries
import uk.xa0.tulkki.data.omemo.OmemoQueries

/**
 * Schema 77 (S5-3): the first capability's own table, and the seven tables `roster/`, `presence/`
 * and `omemo/` declare.
 *
 * <p>`blocking/` owns `blocked_jids` - the design's "the capabilities take their tables" - and this
 * object is how the table reaches the file without the capability having to know about Room's
 * migration machinery. It is executed by both remaining callers, for the reason §6 step 3 gives the
 * table its own migration: `MIGRATION_76_77` and the fresh-install callback. A third caller existed
 * for one commit - the legacy `DatabaseBackend.onUpgrade` hook - and S5-5 deleted it. A new table
 * created by the fresh-install path alone is a fresh install and an upgrade that differ, which is
 * the defect that rule exists for.
 *
 * <p>**Why the declared tables are rebuilt here rather than at a new version.** Room validates a
 * declared entity by comparing each column's normalised affinity, not-null flag and primary key
 * against the file, and the file spells seven of those tables in a way no entity can emit:
 * `contacts` has four `NUMBER`/`boolean` columns - which normalise to `UNDEFINED`, which no entity
 * can emit - and no primary key; `discovery_results` and `presence_templates` have no primary key
 * either; `identities` carries two `NUMBER` columns and no key; and `sessions`, `prekeys` and
 * `signed_prekeys` have no key at all. No `ALTER` can change a declared type, so each is
 * create-copy-drop-rename, the same shape `Schema76` uses for `accounts`, `conversations` and
 * `messages`. The version number does not move: the file's version is 77 and no device has taken
 * this step, so the rebuild belongs in the definition every caller of 77 already runs, and a file
 * at 75 reaches it through 76 -> 77 exactly once. No row and no rowid is lost - the copy carries
 * `rowid` and every column, and each table's own `UNIQUE` is kept verbatim, because that is what
 * the live writers' `REPLACE`/`IGNORE` inserts conflict on.
 *
 * <p>It writes no `PRAGMA user_version`: Room owns the version and sets it to 77 once the migration
 * returns.
 */
object Schema77 {

    /** The file's version once this schema is in place. `DatabaseBackend.DATABASE_VERSION` names it. */
    const val VERSION = 77

    /** Every statement schema 77 adds, each `IF NOT EXISTS` so a second run is a no-op. */
    @JvmField val STATEMENTS: List<String> = listOf(BlockingQueries.CREATE_TABLE)

    /**
     * The FTS delete trigger, replaced, because the one shipped since schema 49 is a leak.
     *
     * <p>`messages_index` is external-content FTS4 over `messages`, and `content=` means the index
     * has no copy of the text: a `DELETE FROM messages_index WHERE rowid=…` reads the row being
     * removed out of `messages` to decide which terms to drop. An `AFTER DELETE` trigger runs when
     * that row is already gone, so the delete resolves to no terms at all and every deletion leaves
     * the removed message's terms in the index for ever. Measured on the host, over the real DDL:
     * with `AFTER` the term is still found after the delete; with `BEFORE` it is gone. The app's own
     * search cannot surface the residue - it joins `messages`, whose row has gone - so this is
     * residue rather than readable text, but it is residue that grows with every deletion until the
     * next full rebuild.
     *
     * <p>`AFTER` is also history: the live `DatabaseBackend.recreateMessageIndex` and schema 49 both
     * spelled it, and the fixture that reproduces the old file spells it too, which is why this is a
     * `DROP`+`CREATE` pair rather than an edit the other callers would have to share. It runs after
     * the rebuilds below, so the trigger that exists at the end is the one `MessagesQueries` now
     * declares.
     */
    @JvmField
    val FTS_DELETE_TRIGGER_STATEMENTS: List<String> =
        listOf(
            "DROP TRIGGER IF EXISTS " + MessagesQueries.DELETE_TRIGGER,
            MessagesQueries.CREATE_DELETE_TRIGGER,
        )

    /**
     * `contacts`' column list for the rebuild: the legacy declaration with the four normalised
     * columns spelled `INTEGER`, in the entity's own order so `PRAGMA table_info` reads the same as
     * Room's generated `CREATE`. The tail is the key the entity declares - the file has only an
     * inline `UNIQUE` - and the foreign key the file already carries.
     */
    private val CONTACTS_COLUMNS: List<Pair<String, String>> =
        listOf(
            "_id" to "INTEGER NOT NULL",
            "accountUuid" to "TEXT",
            "servername" to "TEXT",
            "systemname" to "TEXT",
            "presence_name" to "TEXT",
            "jid" to "TEXT",
            "pgpkey" to "TEXT",
            "photouri" to "TEXT",
            "options" to "INTEGER",
            "systemaccount" to "INTEGER",
            "avatar" to "TEXT",
            "last_presence" to "TEXT",
            "callsDisabled" to "INTEGER DEFAULT 0",
            "last_time" to "INTEGER",
            "rtpCapability" to "TEXT",
            "groups" to "TEXT",
        )

    private const val CONTACTS_TAIL =
        ", PRIMARY KEY(_id)," +
            " UNIQUE(accountUuid, jid) ON CONFLICT REPLACE," +
            " FOREIGN KEY(accountUuid) REFERENCES accounts(uuid) ON DELETE CASCADE"

    /**
     * `discovery_results`' column list: the same surrogate-key treatment as `contacts`, for the same
     * reason - all three of the file's columns are nullable and the pair is an inline `UNIQUE`
     * rather than a key, so a `NOT NULL` key would make the copy throw on a legacy row with a null
     * `hash` or `ver`.
     */
    private val DISCOVERY_RESULT_COLUMNS: List<Pair<String, String>> =
        listOf(
            "_id" to "INTEGER NOT NULL",
            "hash" to "TEXT",
            "ver" to "TEXT",
            "result" to "TEXT",
        )

    private const val DISCOVERY_RESULT_TAIL =
        ", PRIMARY KEY(_id), UNIQUE(hash, ver) ON CONFLICT REPLACE"

    /**
     * `presence_templates`' column list: the same surrogate-key treatment again, for the same reason
     * - no key on the file, four nullable columns, and an inline `UNIQUE(message, status)` the live
     * writer's `REPLACE` depends on.
     */
    private val PRESENCE_TEMPLATE_COLUMNS: List<Pair<String, String>> =
        listOf(
            "_id" to "INTEGER NOT NULL",
            "uuid" to "TEXT",
            "last_used" to "INTEGER",
            "message" to "TEXT",
            "status" to "TEXT",
        )

    private const val PRESENCE_TEMPLATE_TAIL =
        ", PRIMARY KEY(_id), UNIQUE(message, status) ON CONFLICT REPLACE"

    /**
     * `identities`' column list: the OMEMO store's fingerprints, with its two `NUMBER` columns
     * normalised to `INTEGER`, a surrogate key, and the inline `UNIQUE(account, name, fingerprint)
     * ON CONFLICT IGNORE` kept. `certificate` stays `BLOB`, which is the file's own type and the
     * entity's (`IdentityEntity.certificate` is `ByteArray?`).
     */
    private val IDENTITY_COLUMNS: List<Pair<String, String>> =
        listOf(
            "_id" to "INTEGER NOT NULL",
            "account" to "TEXT",
            "name" to "TEXT",
            "ownkey" to "INTEGER",
            "fingerprint" to "TEXT",
            "certificate" to "BLOB",
            "trust" to "TEXT",
            "active" to "INTEGER",
            "last_activation" to "INTEGER",
            "key" to "TEXT",
        )

    private const val IDENTITY_TAIL =
        ", PRIMARY KEY(_id), UNIQUE(account, name, fingerprint) ON CONFLICT IGNORE," +
            " FOREIGN KEY(account) REFERENCES accounts(uuid) ON DELETE CASCADE"

    /** `sessions`: no key on the file, four nullable columns, and the `UNIQUE` the store writes on. */
    private val SESSION_COLUMNS: List<Pair<String, String>> =
        listOf(
            "_id" to "INTEGER NOT NULL",
            "account" to "TEXT",
            "name" to "TEXT",
            "device_id" to "INTEGER",
            "key" to "TEXT",
        )

    private const val SESSION_TAIL =
        ", PRIMARY KEY(_id), UNIQUE(account, name, device_id) ON CONFLICT REPLACE," +
            " FOREIGN KEY(account) REFERENCES accounts(uuid) ON DELETE CASCADE"

    /** `prekeys`: the same shape as `signed_prekeys`, keyed on the pair the file's `UNIQUE` names. */
    private val PREKEY_COLUMNS: List<Pair<String, String>> =
        listOf(
            "_id" to "INTEGER NOT NULL",
            "account" to "TEXT",
            "id" to "INTEGER",
            "key" to "TEXT",
        )

    private const val PREKEY_TAIL =
        ", PRIMARY KEY(_id), UNIQUE(account, id) ON CONFLICT REPLACE," +
            " FOREIGN KEY(account) REFERENCES accounts(uuid) ON DELETE CASCADE"

    /** `signed_prekeys`, verbatim: `signed_prekeys` and `prekeys` are the same four columns. */
    private val SIGNED_PREKEY_COLUMNS: List<Pair<String, String>> = PREKEY_COLUMNS

    private const val SIGNED_PREKEY_TAIL = PREKEY_TAIL

    @JvmStatic fun applySchema(db: SupportSQLiteDatabase) = applySchema(SchemaExecs.of(db))

    @JvmStatic fun applySchema(db: SQLiteDatabase) = applySchema(SchemaExecs.of(db))

    /** The one definition all three callers run; the JVM harness drives exactly this. */
    @JvmStatic
    fun applySchema(exec: SchemaExec) {
        for (statement in STATEMENTS) {
            exec.exec(statement)
        }
        rebuildDeclaredTables(exec)
        attributeAndCascadeTheAccountScopedTables(exec)
        for (statement in FTS_DELETE_TRIGGER_STATEMENTS) {
            exec.exec(statement)
        }
    }

    /**
     * S5-12: the two account-scoped tables the schema never attributed, and the one that was keyed
     * by a message, made the account's rows by a real foreign key each.
     *
     * <p>Every statement here is a repair of the *same* defect - `DatabaseBackend.deleteAccount` is
     * one `DELETE FROM accounts WHERE uuid=?` and relies on the cascade - and the order is what
     * makes it one:
     *
     * <ol>
     *   <li>`translation_queue` first, because its key is a message and both of the tables it can
     *       reach are rebuilt later by schema 76's own rebuilds of `accounts`/`conversations`/
     *       `messages` - a foreign key may point at a table that is dropped and renamed under it as
     *       long as the name survives, and it does. The orphan prune runs *before* the rebuild: the
     *       copy into the new table would otherwise insert a row that violates the key it is being
     *       given, which is a thrown migration and a refused open. Dropping a queue row whose
     *       message is already gone is not data loss; it is the leak's own row.
     *   <li>`webxdc_updates`: `conversationUuid` -> `conversations`, two hops from the account.
     *   <li>`muted_participants`: `muc_jid`+`occupant_id` stays the key, `account_uuid` is added
     *       and carries the key. The copy takes the owner from the file's own conversations where
     *       the room JID names one; a legacy row that names none keeps a null owner, which no
     *       cascade reaches and no scoped read returns - the one row that cannot be attributed at
     *       all is also the one row that must not be guessed at.
     * </ol>
     */
    private fun attributeAndCascadeTheAccountScopedTables(exec: SchemaExec) {
        // Each table is guarded on its own presence: the schema-75 reference fixture never had
        // `translation_queue` (it is Tulkki's own, added at 71), and a statement against a table
        // that is not there is a thrown migration. `IF NOT EXISTS` cannot buy this for a `DELETE` or
        // a rebuild, so existence is read once per table.
        if (exec.hasTable(TranslationTables.QUEUE_TABLE)) {
            exec.exec(TranslationTables.DELETE_ORPHANED_QUEUE_ROWS)
            Schema76.rebuild(
                exec,
                TranslationTables.QUEUE_TABLE,
                QUEUE_COLUMNS,
                TranslationTables.QUEUE_FOREIGN_KEY_TAIL,
                rowidColumn = "rowid",
            )
            exec.exec(TranslationTables.CREATE_QUEUE_INDEX)
        }

        if (exec.hasTable(RawTables.WEBXDC_TABLE)) {
            Schema76.rebuild(
                exec,
                RawTables.WEBXDC_TABLE,
                RawTables.WEBXDC_COLUMNS,
                RawTables.WEBXDC_TAIL,
                rowidColumn = RawTables.WEBXDC_SERIAL,
            )
            for (statement in RawTables.WEBXDC_INDEX_STATEMENTS) {
                exec.exec(statement)
            }
        }

        if (exec.hasTable(RawTables.MUTED_TABLE)) {
            // The owner is read out of the file before the table that carries it is dropped, into
            // the same statement that copies the rows: one pass, no temporary table and no second
            // spelling of the lookup. `MIN` collapses the (impossible) case of one room JID naming
            // more than one account's conversation - the owner of any of them is the owner.
            Schema76.rebuild(
                exec,
                RawTables.MUTED_TABLE,
                MUTED_PARTICIPANTS_COLUMNS,
                RawTables.MUTED_TAIL,
                rowidColumn = "rowid",
                copy = MUTED_PARTICIPANTS_COPY,
            )
        }
    }

    /**
     * The shapes this version's own definition changed **after** files had already taken it, repaired
     * by the *next* migration.
     *
     * <p><strong>Why this exists, and it is a shipped crash rather than a precaution.</strong> S5-12
     * (`a8676ea412`) gave `translation_queue`, `muted_participants` and `webxdc_updates` their
     * account-scoped keys *inside* version 77, whose migration the earlier S5-3 batches
     * (`9c29849772`, `f4c7160af9`, `8512e32346`) had already registered. A device that installed a
     * build in between therefore stamped its file 77 with the legacy shapes, and this build runs no
     * migration for it - Room migrates only across a version difference it finds. The owner's own
     * phone is such a file, and it died at launch on
     * `DatabaseBackend.loadMutedMucUsers` with `no such column: account_uuid`.
     *
     * <p><strong>Who runs it now, and who no longer does.</strong> `MIGRATION_77_78` runs it, through
     * `Schema78.applyUpgrade`: 78 is what makes a version difference exist, and a migration is the
     * one step Room takes *before* it validates the declared entities. Until 78 existed there was no
     * such step, so it ran from `HistoryDatabase`'s `onOpen` - and that call is retired, because an
     * `onOpen` repair cannot help an entity table (Room validates the declared entities before it
     * runs any callback) and a repair that cannot help one invites the belief that it can. See
     * `Schema78` for the retirement and `MutedColumnRepairTest` for the cells that moved with it.
     *
     * <p><strong>It is the migration's own rebuild, not a second shape.</strong> Each branch runs the
     * same `Schema76.rebuild` with the same columns and the same tail that
     * [attributeAndCascadeTheAccountScopedTables] runs, so a repaired file ends up with the table a
     * correctly migrated one has - the foreign key and the cascade included, which an
     * `ALTER TABLE ADD COLUMN` could not add. `MutedColumnRepairTest` compares the repaired file's
     * `PRAGMA table_info` with the migrated file's rather than trusting that.
     *
     * <p>Foreign keys are off when this runs, which [Schema78.applyUpgrade] now checks rather than
     * assumes: a rebuild drops a table, and `DROP TABLE` on a *parent* is an implicit `DELETE` that
     * fires cascades. None of these three is a parent, so nothing would fire - the precondition is
     * kept because the reason `Schema76.requireForeignKeysOff` exists is not worth re-deriving per
     * table.
     */
    @JvmStatic
    fun repairLateColumns(exec: SchemaExec) {
        if (exec.hasTable(RawTables.MUTED_TABLE) &&
            !exec.hasColumn(RawTables.MUTED_TABLE, RawTables.MUTED_ACCOUNT)
        ) {
            Schema76.rebuild(
                exec,
                RawTables.MUTED_TABLE,
                MUTED_PARTICIPANTS_COLUMNS,
                RawTables.MUTED_TAIL,
                rowidColumn = "rowid",
                copy = MUTED_PARTICIPANTS_COPY,
            )
        }
        if (exec.hasTable(TranslationTables.QUEUE_TABLE) &&
            !hasForeignKey(exec, TranslationTables.QUEUE_TABLE)
        ) {
            exec.exec(TranslationTables.DELETE_ORPHANED_QUEUE_ROWS)
            Schema76.rebuild(
                exec,
                TranslationTables.QUEUE_TABLE,
                QUEUE_COLUMNS,
                TranslationTables.QUEUE_FOREIGN_KEY_TAIL,
                rowidColumn = "rowid",
            )
            exec.exec(TranslationTables.CREATE_QUEUE_INDEX)
        }
        if (exec.hasTable(RawTables.WEBXDC_TABLE) &&
            !hasForeignKey(exec, RawTables.WEBXDC_TABLE)
        ) {
            Schema76.rebuild(
                exec,
                RawTables.WEBXDC_TABLE,
                RawTables.WEBXDC_COLUMNS,
                RawTables.WEBXDC_TAIL,
                rowidColumn = RawTables.WEBXDC_SERIAL,
            )
            for (statement in RawTables.WEBXDC_INDEX_STATEMENTS) {
                exec.exec(statement)
            }
        }
    }

    /** Whether a table carries any foreign key at all: the one fact `PRAGMA table_info` omits. */
    private fun hasForeignKey(exec: SchemaExec, table: String): Boolean =
        exec.rows("PRAGMA foreign_key_list(" + table + ")", emptyArray()).isNotEmpty()

    /**
     * `muted_participants`' copy, which is the one rebuild in the tree that fills a column from a
     * read rather than from the old row. `Schema76.rebuild`'s default copy names only the old
     * table's columns; this one hands each new row the account its `muc_jid` belongs to, resolved
     * against `conversations`, and `NULL` when the file names none.
     */
    private const val MUTED_PARTICIPANTS_COPY =
        "INSERT INTO muted_participants_new (rowid, account_uuid, muc_jid, occupant_id) " +
            "SELECT rowid, " +
            "(SELECT MIN(c.accountUuid) FROM conversations c WHERE c.contactJid = t.muc_jid), " +
            "t.muc_jid, t.occupant_id FROM muted_participants t"

    /**
     * `muted_participants`' rebuilt columns, declared here rather than in `RawTables` because the
     * copy above is this migration's own statement and a shape split across two files is how a
     * rebuild drifts.
     */
    private val MUTED_PARTICIPANTS_COLUMNS: List<Pair<String, String>> = RawTables.MUTED_COLUMNS

    /**
     * `translation_queue`'s rebuilt columns: the legacy declaration's, in its own order, with the
     * same key and the same `NOT NULL`s. A Kotlin copy of `TranslationTables`' names for the reason
     * every other rebuild here has one - the shape belongs to the migration that runs it, and the
     * names come from the object that owns them.
     *
     * <p>**The key was missing, and it took Room's own validator to see it (S5-6).** This list said
     * `message_uuid TEXT NOT NULL` and the tail is only the foreign key, so the rebuild produced a
     * table with *no primary key at all* - on the migrated path and, because [applySchema] runs this
     * same rebuild on a fresh install too, on the fresh one as well. Nothing noticed, because
     * `FreshInstallSchemaTest` compares the two paths with each other and they agreed. Declaring
     * `translation_queue` as an entity is what found it: Room requires an entity to have a primary
     * key, and `onValidateSchema` then refused both files. The declaration in
     * `TranslationTables.CREATE_QUEUE_TABLE` always had the key; this list had lost it.
     *
     * <p>**Safe to correct rather than version-bump, and the condition is recorded rather than
     * assumed.** `docs/MIGRATION.md` still lists the 75 -> 77 launch as owed, so no device has run this
     * migration and no file can already be at 77 without the key. A file that *had* run it would
     * need a new migration rather than an edit, because `onValidateSchema` will not re-run
     * `MIGRATION_76_77` for a file already at that version.
     */
    private val QUEUE_COLUMNS: List<Pair<String, String>> =
        listOf(
            TranslationTables.QUEUE_MESSAGE_UUID to "TEXT NOT NULL PRIMARY KEY",
            TranslationTables.QUEUE_CONVERSATION_UUID to "TEXT",
            TranslationTables.QUEUE_BODY to "TEXT NOT NULL",
            TranslationTables.QUEUE_TARGET_LANGUAGE to "TEXT",
            TranslationTables.QUEUE_CACHE_KEY to "TEXT NOT NULL",
            TranslationTables.QUEUE_STATE to "INTEGER NOT NULL DEFAULT 0",
            TranslationTables.QUEUE_ATTEMPTS to "INTEGER NOT NULL DEFAULT 0",
            TranslationTables.QUEUE_NEXT_ATTEMPT_AT to "INTEGER NOT NULL DEFAULT 0",
            TranslationTables.QUEUE_LAST_ERROR to "TEXT",
            TranslationTables.QUEUE_CREATED_AT to "INTEGER NOT NULL",
            TranslationTables.QUEUE_FAILED_AT to "INTEGER",
        )

    /**
     * The seven rebuilds, in the same create-copy-drop-rename shape `Schema76` uses and through the
     * same function, so there is one spelling of what a rebuild is. Every one of them is a *child*
     * of `accounts` (`discovery_results` has no foreign key at all), so dropping one fires no
     * `ON DELETE CASCADE` - the precondition `Schema76` needs is not this one's.
     */
    private fun rebuildDeclaredTables(exec: SchemaExec) {
        Schema76.rebuild(exec, "contacts", CONTACTS_COLUMNS, CONTACTS_TAIL, rowidColumn = "_id")
        Schema76.rebuild(
            exec,
            "discovery_results",
            DISCOVERY_RESULT_COLUMNS,
            DISCOVERY_RESULT_TAIL,
            rowidColumn = "_id",
        )
        Schema76.rebuild(
            exec,
            "presence_templates",
            PRESENCE_TEMPLATE_COLUMNS,
            PRESENCE_TEMPLATE_TAIL,
            rowidColumn = "_id",
        )
        // `omemo/`'s four, named through the package that owns the island's spelling of them.
        Schema76.rebuild(
            exec,
            OmemoQueries.IDENTITIES_TABLE,
            IDENTITY_COLUMNS,
            IDENTITY_TAIL,
            rowidColumn = "_id",
        )
        Schema76.rebuild(
            exec,
            OmemoQueries.SESSIONS_TABLE,
            SESSION_COLUMNS,
            SESSION_TAIL,
            rowidColumn = "_id",
        )
        Schema76.rebuild(
            exec,
            OmemoQueries.PREKEYS_TABLE,
            PREKEY_COLUMNS,
            PREKEY_TAIL,
            rowidColumn = "_id",
        )
        Schema76.rebuild(
            exec,
            OmemoQueries.SIGNED_PREKEYS_TABLE,
            SIGNED_PREKEY_COLUMNS,
            SIGNED_PREKEY_TAIL,
            rowidColumn = "_id",
        )
    }
}
