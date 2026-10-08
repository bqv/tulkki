package uk.xa0.tulkki.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.sqlite.db.SupportSQLiteDatabase
import net.zetetic.database.sqlcipher.SQLiteDatabase
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
import uk.xa0.tulkki.data.accounts.AccountDao
import uk.xa0.tulkki.data.accounts.AccountEntity
import uk.xa0.tulkki.data.blocking.BlockedJidEntity
import uk.xa0.tulkki.data.blocking.BlockingDao
import uk.xa0.tulkki.data.messages.ConversationDao
import uk.xa0.tulkki.data.messages.ConversationEntity
import uk.xa0.tulkki.data.messages.MessageEntity
import uk.xa0.tulkki.data.messages.MessagesDao
import uk.xa0.tulkki.data.markers.MarkerDao
import uk.xa0.tulkki.data.omemo.IdentityEntity
import uk.xa0.tulkki.data.omemo.OmemoStoreDao
import uk.xa0.tulkki.data.omemo.PreKeyEntity
import uk.xa0.tulkki.data.omemo.SessionEntity
import uk.xa0.tulkki.data.omemo.SignedPreKeyEntity
import uk.xa0.tulkki.data.reactions.ReactionDao
import uk.xa0.tulkki.data.delivery.DeliveryDao
import uk.xa0.tulkki.data.presence.PresenceTemplateDao
import uk.xa0.tulkki.data.presence.PresenceTemplateEntity
import uk.xa0.tulkki.data.references.ReferenceDao
import uk.xa0.tulkki.data.roster.ContactEntity
import uk.xa0.tulkki.data.roster.DiscoveryResultEntity
import uk.xa0.tulkki.data.roster.RosterDao
import uk.xa0.tulkki.data.schema.RawTables
import uk.xa0.tulkki.data.schema.Schema76
import uk.xa0.tulkki.data.schema.Schema80
import uk.xa0.tulkki.data.schema.SchemaExec
import uk.xa0.tulkki.data.schema.SchemaExecs
import uk.xa0.tulkki.data.schema.TulkkiMigrations
import uk.xa0.tulkki.data.sync.SyncConversationDao
import uk.xa0.tulkki.data.sync.SyncConversationEntity
import uk.xa0.tulkki.data.sync.SyncCursorDao
import uk.xa0.tulkki.data.sync.SyncCursorEntity
import uk.xa0.tulkki.data.sync.SyncGapDao
import uk.xa0.tulkki.data.sync.SyncGapEntity
import uk.xa0.tulkki.data.translation.InstantConverters
import uk.xa0.tulkki.data.translation.TranslationQueueDao
import uk.xa0.tulkki.data.translation.TranslationQueueEntity
import uk.xa0.tulkki.data.upload.BlockedMediaEntity
import uk.xa0.tulkki.data.upload.UploadDao
import uk.xa0.tulkki.data.upload.UploadFileEntity

/**
 * The Room opener for the one file, `history`.
 *
 * **What it does.** It points Room at the file that already exists, with the key the app already
 * derives, so that the file is *opened* rather than converted: the same `history`, the same `-wal`,
 * the same `-shm`. Nothing recreates it, and the failure mode it is designed around is a refused
 * open rather than a destructive fallback (`DestructiveMigrationBanTest` holds that line).
 *
 * **The schema is 78, and Room does not set that here.** `@Database` takes the version from
 * `DatabaseBackend.DATABASE_VERSION`, which is `Schema80.VERSION`; a file at 75 is brought to it by
 * `MIGRATION_75_76`, `MIGRATION_76_77` and `MIGRATION_77_78`, and a fresh install creates it through
 * [HistoryCallbacks.onCreate] and [installFreshSchema].
 *
 * **A file below 75 refuses, and that moment was chosen (S5-5).** The legacy `onUpgrade` chain - 74
 * version guards, and the only code that ever knew how to walk a 74 file forward - was deleted with
 * `DatabaseBackend`'s schema half. It could not run under Room anyway (`RoomOpenHelper` calls the
 * registered `Migration`s, never the helper's `onUpgrade`), so what a 74 file gets now is
 * `RoomOpenHelper.onUpgrade` throwing `IllegalStateException("A migration from 74 to 78 was
 * required but not found...")` - the measurement recorded in the S5-5 row. Nothing catches it:
 * `get` below does not, and `XmppConnectionService.initializeDatabaseInBackground` catches
 * `EncryptionException` alone. So a file below 75 is **a crash on the first launch, not a refuse
 * prompt and not a recreate** - the owner's data is untouched and readable by any build that knows
 * the chain, and the app does not open. That is the choice in force, `refuse, never recreate`; a
 * 74 -> 75 path would mean restoring the legacy DDL chain this commit removed, and it is a new task
 * if it is ever wanted. Room is the only opener and the only version owner, so nothing here can
 * drift into recreating the file: `DestructiveMigrationBanTest` fails on the builder call by name.
 *
 * **The late-shape repair is not here any more (schema 78).** `Schema77.repairLateColumns` used to
 * run from [HistoryCallbacks.onOpen] - the only step a stale 77 file passed through, because Room
 * migrates only across a version difference and there was none. 78 is that version difference, so
 * the repair runs inside `MIGRATION_77_78`, which is the position it always needed: **Room validates
 * the declared entities after the migrations and before any callback**, so a repair in `onOpen` can
 * never help an entity table. Leaving it there alongside a declared `translation_queue` would invite
 * exactly that belief. `onOpen` now only turns foreign keys on.
 *
 * **Seventeen entities, and each one is a table Room validates.** `cids` since S5-1;
 * `AccountEntity`, `ConversationEntity` and `MessageEntity` since S5-2b — the same commit that
 * **rebuilt** those three tables, because validation is what makes a rebuild safe rather than
 * silent. Room compares every declared column's normalised **affinity**
 * (`TableInfo.Column.equalsCommon`, the final term unconditional): a `NUMBER`-declared column
 * normalises to `UNDEFINED` (`SchemaInfoUtil.findAffinity`), the entity side can only ever emit
 * `TEXT|INTEGER|REAL|BLOB` (`ColumnInfo.SQLiteTypeAffinity` has no numeric member, and the
 * property's type adapter wins anyway), and no `ALTER` can change a declared type. So those tables
 * are created new, copied, dropped and renamed — by `Schema76` for the three, and by `Schema77` for
 * the seven whose file spelling is `NUMBER`/`boolean` columns, no primary key, or both: `roster/`'s
 * two, `presence/`'s one, and `omemo/`'s four (`identities` carries two `NUMBER` columns, and
 * `sessions`, `prekeys` and `signed_prekeys` have no key at all). The entities are what proves the
 * result name for name. They come as three for the foreign keys because Room compares those too and
 * `@ForeignKey` names its target by entity class: `messages` → `conversations` → `accounts`.
 * `BlockedJidEntity` is S5-1's batch 1 table, the new-in-77 `blocked_jids` (`82907299d9` registered
 * it, which is also what made Room generate its DAO); `ContactEntity` / `DiscoveryResultEntity` are
 * `roster/`'s, `PresenceTemplateEntity` is `presence/`'s, and `IdentityEntity`, `SessionEntity`,
 * `PreKeyEntity` and `SignedPreKeyEntity` are `omemo/`'s — the four tables the OMEMO store's
 * `ON DELETE CASCADE` takes with an account. `TranslationQueueEntity` is `translation/`'s and is the
 * one entity **schema 78** declares rather than a version before it: a file whose queue table was
 * rebuilt by a build that carried the S5-12 rekey but not its foreign key (or, for one build, not its
 * primary key) would have been refused at launch by a declaration made at 77, and 78's migration is
 * what repairs it before Room looks.
 *
 * **Why any entity at all.** Room rejects a `@Database` whose entity list is empty:
 * `e: [ksp] HistoryDatabase.kt: @Database annotation must specify list of entities`, measured by
 * running KSP rather than assumed.
 *
 * **The fresh-install schema is Room's own, plus one list.** Room's generated `createAllTables`
 * runs first, from the seventeen entity declarations, and writes every table one of them names with
 * every column one of them names; [HistoryCallbacks.onCreate] then runs [installFreshSchema], which
 * is [RawTables] - the FTS machinery, the tables no entity declares and Tulkki's four - and then
 * the schema-76 and schema-78 definitions, the same functions the Room migrations run. There is no
 * `DatabaseBackend.createSchema` any more and no legacy DDL in `DatabaseBackend` at all (S5-5):
 * the fresh install and the upgrade converge on the entities and on those two shared definitions,
 * and `FreshInstallSchemaTest` drives this exact function against Room's generated `createAllTables`
 * and compares the result with a migrated file table by table. It calls [Schema80.applySchema] and
 * **not** 77's and then 78's: 78's definition is the whole final shape - 77's statements plus this
 * version's own - so a fresh file is created at 78 directly.
 *
 * **Foreign keys are enabled on open, and that is a repair.** `PRAGMA foreign_keys=ON` used to run
 * in `DatabaseBackend.onConfigure`, which the SQLCipher `SQLiteOpenHelper` called. `DatabaseBackend`
 * stopped being an open helper at S5-1 and lost that method at S5-5, so nothing ran it, every
 * `ON DELETE CASCADE` the schema declares stopped firing, and
 * `DatabaseBackend.deleteAccount` deletes one `accounts` row and relies on them to
 * take the account's own rows — the conversation table, `messages`, `contacts`, `identities`,
 * `sessions`, `prekeys`, `signed_prekeys` and `pinned_messages` — with it.
 * [HistoryCallbacks.onOpen] turns them back on. It is `setForeignKeyConstraintsEnabled` and not a
 * raw `PRAGMA` because that switch reconfigures the whole connection pool, whereas the old statement
 * reached only the one connection `onConfigure` was handed (`foreign_keys` is a per-connection
 * setting; SQLCipher's `SQLiteDatabase.setForeignKeyConstraintsEnabled` calls
 * `SQLiteConnectionPool.reconfigure`). `onOpen` is the legal position for it. Measured, not
 * reasoned: `javap -c` on sqlcipher-android 4.16.0's
 * `net.zetetic.database.sqlcipher.SQLiteOpenHelper.getDatabaseLocked` runs `beginTransaction` at
 * bytecode 493, `onCreate`/`onUpgrade` at 502/536, `setTransactionSuccessful` at 548,
 * `endTransaction` at 552 and `onOpen` at 569; and `SQLiteConnectionPool.reconfigure` throws
 * `IllegalStateException` ("Foreign Key Constraints cannot be enabled or disabled while there are
 * transactions in progress") when a transaction is open. Room does reach it:
 * `RoomOpenHelper.onOpen` (room-runtime-android 2.7.0) iterates the registered callbacks and calls
 * `RoomDatabase.Callback.onOpen(SupportSQLiteDatabase)` — the overload overridden below — and
 * `.openHelperFactory` is the SupportSQLite path, so the driver overload never runs. What no JVM
 * test can do is watch a cascade fire: SQLCipher ships Android ABIs only. `OpenHelperFactoryTest`
 * pins the call as a source pin and says so; the behaviour is a device check.
 */
@Database(
    version = DatabaseBackend.DATABASE_VERSION,
    entities = [
        UploadFileEntity::class,
        AccountEntity::class,
        ConversationEntity::class,
        MessageEntity::class,
        BlockedJidEntity::class,
        SyncCursorEntity::class,
        SyncConversationEntity::class,
        SyncGapEntity::class,
        BlockedMediaEntity::class,
        ContactEntity::class,
        DiscoveryResultEntity::class,
        PresenceTemplateEntity::class,
        IdentityEntity::class,
        SessionEntity::class,
        PreKeyEntity::class,
        SignedPreKeyEntity::class,
        TranslationQueueEntity::class,
    ],

    exportSchema = false,
)
@TypeConverters(InstantConverters::class)
internal abstract class HistoryDatabase : RoomDatabase() {

    /** The blocklist's DAO as the database exposes it: an abstract accessor, not just a `daos` entry. */
    internal abstract fun blockingDao(): BlockingDao

    /** `sync/`'s three DAOs, each one registered by being exposed here and by nothing else. */
    internal abstract fun syncCursorDao(): SyncCursorDao

    internal abstract fun syncConversationDao(): SyncConversationDao

    internal abstract fun syncGapDao(): SyncGapDao

    /** `upload/`'s DAO: `cids`, `blocked_media` and the file-state columns of `messages`. */
    internal abstract fun uploadDao(): UploadDao

    /** `messages/`'s two DAOs: the rows themselves and the conversation list's pointer read. */
    internal abstract fun messagesDao(): MessagesDao

    internal abstract fun conversationDao(): ConversationDao

    /** `roster/`'s DAO: `contacts`, `discovery_results` and `accounts.rosterversion`. */
    internal abstract fun rosterDao(): RosterDao

    /** `accounts/`'s DAO: the account row itself, by key and in the owner's order. */
    internal abstract fun accountDao(): AccountDao

    /** `markers/`'s DAO: three columns of `messages`, and no table of its own. */
    internal abstract fun markerDao(): MarkerDao

    /** `reactions/`'s DAO: the reaction document, one column of `messages`. */
    internal abstract fun reactionDao(): ReactionDao

    /** `references/`'s DAO: the payload document and the reply's own id fallback. */
    internal abstract fun referenceDao(): ReferenceDao

    /** `reliable-delivery/`'s DAO: the receipt bookkeeping columns of `messages`. */
    internal abstract fun deliveryDao(): DeliveryDao

    /** `presence/`'s DAO: the saved status lines. */
    internal abstract fun presenceTemplateDao(): PresenceTemplateDao

    /** `omemo/`'s DAO: the session store's four tables, `sessions` through `identities`. */
    internal abstract fun omemoStoreDao(): OmemoStoreDao

    /**
     * `translation/`'s DAO: the queue's counts, as the `Flow` a screen collects. Declared as an
     * accessor and not only in `entities`, because a DAO Room does not see is a DAO it does not
     * generate - silently (measured; see `HistoryDatabase`'s class comment on `blockingDao`).
     */
    internal abstract fun translationQueueDao(): TranslationQueueDao


    /**
     * Room's callbacks for the one file: the schema on a fresh install, and the connection's own
     * foreign-key setting on every open.
     */
    private class HistoryCallbacks : Callback() {

        /**
         * The fresh install's schema, and the half of the first launch that was missing.
         *
         * <p>This class comment and the one above have always said the callback runs
         * [installFreshSchema] - and until now nothing did. `Callback.onCreate` was not overridden,
         * so a brand-new file got Room's generated `createAllTables` and none of [RawTables]: no
         * `messages_index` and no shadow tables, no `translation_cache`/`translation_usage`, no
         * pins, no `resolver_results`. Every test passed because every test calls
         * [installFreshSchema] directly; nothing drove Room's own callback, because opening the file
         * needs SQLCipher and that ships Android ABIs only. The first reader to touch one of those
         * tables was `restoreFromDatabase`'s `isFtsIndexFragmented`, which threw
         * `SQLiteException: no such table: messages_index_segdir (code 1)` and took the app down on
         * every fresh install - the owner's crash loop.
         *
         * <p>Room calls this once, when it creates the file, before `onOpen`; a file that already
         * exists is migrated instead and never comes here. The SQL is the one definition
         * [installFreshSchema] is, so a new install and a migrated file cannot spell two schemas.
         */
        override fun onCreate(db: SupportSQLiteDatabase) {
            installFreshSchema(db)
        }

        /**
         * The pool-wide `PRAGMA foreign_keys=ON`, restored — see the class comment for the whole
         * measurement. Legal here and not in a transaction: `javap -c` on sqlcipher-android 4.16.0
         * shows `net.zetetic.database.sqlcipher.SQLiteOpenHelper.getDatabaseLocked` calling `onOpen`
         * after `endTransaction`, and `SQLiteConnectionPool.reconfigure` — what
         * `setForeignKeyConstraintsEnabled` runs — throws `IllegalStateException` inside one.
         *
         * <p>And this is all `onOpen` does since schema 78. The late-shape repair that used to run here
         * first moved into `MIGRATION_77_78` (`Schema78.applyUpgrade`), which is the one position
         * Room takes before it validates the declared entities; a callback cannot repair a table an
         * entity declares, so a repair here would be believed to do something it cannot. The order
         * (repairs, then foreign keys) is kept inside the migration, where the rebuilds are.
         */
        override fun onOpen(db: SupportSQLiteDatabase) {
            db.setForeignKeyConstraintsEnabled(true)
        }
    }

    companion object {
        @Volatile private var instance: HistoryDatabase? = null

        @Volatile private var handle: SQLiteDatabase? = null

        /**
         * The fresh install's schema, as one definition: what Room's generated `createAllTables`
         * cannot express ([RawTables], which owns the statements), then the schema-76 and schema-78
         * shapes. `HistoryCallbacks` runs exactly this, and so does `FreshInstallSchemaTest` - so a
         * new install and an owner's upgraded file cannot spell two schemas.
         *
         * <p>It writes no `PRAGMA user_version`: Room owns the version and stamps it once the
         * migration chain or this returns ("Design: the data layer" §1.3).
         */
        @JvmStatic
        fun installFreshSchema(db: SupportSQLiteDatabase) {
            installFreshSchema(SchemaExecs.of(db))
        }

        /** The same, over the seam the JVM harness implements, so the host can drive it. */
        @JvmStatic
        fun installFreshSchema(exec: SchemaExec) {
            RawTables.applySchema(exec)
            Schema76.applySchema(exec)
            Schema80.applySchema(exec)
        }

        /**
         * The same one open database, as the `RoomDatabase` its DAOs hang off, for a caller inside
         * this module that wants the DAOs rather than the raw handle. [get] has already done the
         * opening (and the version check that decides "open" against "create") by the time this
         * returns, and it is the only thing that assigns [instance], so the non-null assertion is the
         * open having succeeded rather than a hope.
         */
        @JvmStatic
        internal fun opened(context: Context): HistoryDatabase {
            get(context)
            return instance ?: error("the history database did not open")
        }

        /**
         * The one open connection, as `net.zetetic.database.sqlcipher.SQLiteDatabase`.
         * `SupportOpenHelperFactory.create` returns a helper whose `getWritableDatabase()` is that
         * concrete type (it implements `androidx.sqlite.db.SupportSQLiteDatabase`), so the cast is
         * the identity, not a conversion — and Room only ever needs the `SupportSQLiteDatabase`
         * view of it.
         */
        @JvmStatic
        fun get(context: Context): SQLiteDatabase {
            handle?.let { return it }
            return synchronized(this) {
                handle?.let { return it }
                System.loadLibrary("sqlcipher")
                val opened =
                    Room.databaseBuilder(context, HistoryDatabase::class.java, DatabaseBackend.DATABASE_NAME)
                        .openHelperFactory(
                            SupportOpenHelperFactory(
                                DatabaseBackend.getKeyBytes(context),
                                DatabaseBackend.ARGON2_DATABASE_HOOK,
                                true,
                            )
                        )
                        .addMigrations(*TulkkiMigrations.ALL)
                        .addCallback(HistoryCallbacks())
                        .build()
                // Opening happens here, not in build(): the Room callbacks (and therefore the
                // version check that decides "open" against "create") run on this call. `instance`
                // is assigned only once the open has succeeded, so a refused open (a database key
                // that is not available yet, which is a legitimate state in Argon2id mode) leaves
                // nothing behind and the next call retries instead of reusing a dead builder.
                val openedHandle = opened.openHelper.writableDatabase as SQLiteDatabase
                instance = opened
                handle = openedHandle
                openedHandle
            }
        }

        /**
         * Named `closeInstance`, not `close`: `@JvmStatic` would put a static `close()` on this
         * class beside `RoomDatabase`'s own instance `close()`, and Kotlin rejects the accidental
         * override outright ("same JVM signature (close()V)"). The name also reads the way the
         * caller does — `DatabaseBackend.closeInstance()` is what drops the singleton.
         */
        @JvmStatic
        fun closeInstance() {
            synchronized(this) {
                instance?.close()
                instance = null
                handle = null
            }
        }
    }
}
