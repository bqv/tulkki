package uk.xa0.tulkki.data.accounts

import net.zetetic.database.sqlcipher.SQLiteDatabase
import uk.xa0.tulkki.data.model.Account
import uk.xa0.tulkki.libs.Jid

/**
 * The one part of an account's deletion that cannot be a row in the history file, handed to
 * [AccountStore.delete] rather than reached for.
 *
 * <p>`deleteAccount` removes the account's UnifiedPush registrations from `updb`'s `push`, a
 * **second SQLCipher file** with its own key material, and SQLite has no cross-file cascade at all.
 * That cleanup needs the Android `Context`, which none of the stores carries - `DatabaseBackend` is
 * the one owner of the file and of the context - so the caller passes the work in:
 * `DatabaseBackend.deleteAccount` hands `this::clearDeletedAccountPushRegistrations`, which keeps
 * the original `context != null` gate and its `RuntimeException` catch. The store still calls it at
 * the same point (only after its own `DELETE` reported one row), so a distributor file that cannot
 * be opened still turns a completed deletion into no failure.
 */
fun interface PushRegistrationCleanup {

    /** Remove `accountUuid`'s push registrations from the user database. */
    fun clear(accountUuid: String)
}

/**
 * `accounts/`'s capability: the account row's write trio, the ordered list every screen reads, and
 * the deletion its cascades ride on.
 *
 * <p>**The next capability out of `DatabaseBackend`'s grab-bag, beside `roster/`.** Eight methods
 * (the ref-typed `createAccount` and `updateAccount` are the overload pair, and the private
 * `getAccounts(SQLiteDatabase)` is the list's own body): the insert, the ordered read, the enabled
 * accounts' JIDs, the update, and the delete with its out-of-file cleanup. One table (`accounts`),
 * and it is the foreign key's target for almost everything. `docs/MIGRATION.md`'s `port-45` row is
 * the inventory this comes out of; it records the group as last left behind because `deleteAccount`
 * "needs a `context` and the user database", and that is answered here by the handed-in
 * [PushRegistrationCleanup] rather than by widening anything.
 *
 * <p>**The home is an `object` that takes the `SQLiteDatabase` rather than a store that owns it.**
 * `DatabaseBackend` is the one owner of the file (`HistoryDatabase`'s Room connection, handed in by
 * `getInstance`), so a package that opened its own would be the second owner the design forbids; the
 * seam is the same one `schema/RawTables.applySchema(exec)` uses.
 *
 * <p>**The statements are `accounts/`'s own, not a second spelling.** The ordered read executes the
 * published `AccountQueries.ALL_ACCOUNTS`; the delete names the published table and key. The two
 * writes still go through `db.insert`/`db.update` with the model's `getContentValues()`, because
 * the row count and the write's conflict behaviour are the `SQLiteDatabase` API's, and a
 * `ContentValues` write is not an SQL string to publish.
 *
 * <p>**The Java-visible surface is unchanged: `@JvmStatic` on all five.** `DatabaseBackend`'s
 * methods are the "one delegating step", so `AccountRegistry`, `XmppConnectionService`,
 * `ImportBackupWorker`, `ExportBackupWorker`, `ProvisioningUtils`, `EditAccountActivity` and the
 * rest compile against byte for byte what they did before. A `@JvmStatic` member of an `object` is
 * the only shape whose static bridge carries the un-mangled name - a Kotlin `internal` member would
 * be emitted as `all$data` and Java could not see it (the `ScriptReading`/`TranslationLanguages`
 * precedent).
 *
 * <p>**The null contracts are read off the callers and off the Java's own guards.** `create` and
 * `update` take the caller's non-null model `Account`; `delete` takes the caller's own
 * `account.getUuid()`; `enabledOnly` is the caller's primitive. The reads answer the real, mutable
 * `ArrayList` the Java handed back - `all`'s empty list on a failure is the Java's own catch, kept
 * on `DatabaseBackend.getAccounts` because the Java's `try` also covered `getReadableDatabase()`,
 * which the store cannot see. `jids` keeps its own catch because the Java's `try` was around the
 * query alone, and a partial list is what it answered.
 *
 * <p>**No `@Throws`: nothing on this path throws a checked exception the Java catches.** The
 * original methods declared none.
 */
object AccountStore {

    /** A new row, as `Account.getContentValues()` spells it. */
    @JvmStatic
    fun create(db: SQLiteDatabase, account: Account) {
        db.insert(AccountQueries.TABLE, null, account.getContentValues())
    }

    /**
     * Every account, in the order the owner arranged them: the published
     * `AccountQueries.ALL_ACCOUNTS`, which orders by `ordering` alone and leaves a `null` ordering
     * first because SQLite reads NULL as smallest, exactly as the live Java did.
     */
    @JvmStatic
    fun all(db: SQLiteDatabase): List<Account> {
        val list = ArrayList<Account>()
        db.rawQuery(AccountQueries.ALL_ACCOUNTS, null).use { cursor ->
            while (cursor.moveToNext()) {
                list.add(Account.fromCursor(cursor))
            }
        }
        return list
    }

    /**
     * Every account's JID, or only the accounts that are not disabled. The where clause is the
     * Java's own `not options & (1 <<1)`, spelled from `Account.OPTIONS` rather than a second
     * literal.
     *
     * @param enabledOnly the caller's `true` from `ProvisioningUtils`, its `false` from
     *     `UriHandlerActivity` and `ImportBackupWorker`
     */
    @JvmStatic
    fun jids(db: SQLiteDatabase, enabledOnly: Boolean): List<Jid> {
        val jids = ArrayList<Jid>()
        val columns = arrayOf(Account.USERNAME, Account.SERVER)
        val where = if (enabledOnly) "not " + Account.OPTIONS + " & (1 <<1)" else null
        try {
            db.query(AccountQueries.TABLE, columns, where, null, null, null, null).use { cursor ->
                while (cursor.moveToNext()) {
                    jids.add(Jid.of(cursor.getString(0), cursor.getString(1), null))
                }
            }
        } catch (e: Exception) {
            return jids
        }
        return jids
    }

    /**
     * The whole row again, by its own key. `true` when exactly one row moved, as the Java answered.
     */
    @JvmStatic
    fun update(db: SQLiteDatabase, account: Account): Boolean {
        val args = arrayOf(account.getUuid())
        val rows =
            db.update(
                AccountQueries.TABLE,
                account.getContentValues(),
                AccountQueries.UUID + "=?",
                args,
            )
        return rows == 1
    }

    /**
     * One account gone; the schema's own cascades take its children. When exactly one row was
     * deleted the cleanup runs, and only then - the Java's `rows == 1` gate, kept rather than
     * tidied.
     *
     * @param cleanup the caller's `DatabaseBackend::clearDeletedAccountPushRegistrations`
     */
    @JvmStatic
    fun delete(db: SQLiteDatabase, uuid: String, cleanup: PushRegistrationCleanup): Boolean {
        val rows = db.delete(AccountQueries.TABLE, AccountQueries.UUID + "=?", arrayOf(uuid))
        if (rows == 1) {
            cleanup.clear(uuid)
        }
        return rows == 1
    }
}
