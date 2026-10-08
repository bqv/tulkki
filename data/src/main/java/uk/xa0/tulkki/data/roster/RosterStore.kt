package uk.xa0.tulkki.data.roster

import android.os.SystemClock
import android.util.Log
import net.zetetic.database.sqlcipher.SQLiteDatabase
import uk.xa0.tulkki.data.model.Account
import uk.xa0.tulkki.data.model.Contact
import uk.xa0.tulkki.data.model.Roster
import uk.xa0.tulkki.xmpp.Config

/**
 * The account row's own writer, handed to [RosterStore.write] rather than reached for.
 *
 * <p>`writeRoster` ends by moving the account's roster version onto the account row, and that write
 * is `accounts/`'s capability, not this package's. Rather than make the roster store a second
 * owner of the account's `ContentValues` write, or widen `DatabaseBackend.updateAccount` for a
 * capability being moved out, the caller passes it in: `DatabaseBackend.writeRoster` hands
 * `this::updateAccount`, so the roster version still moves at the same point in the writer (after
 * the transaction closes, before the duration is logged) and through the same method every other
 * caller uses.
 */
fun interface AccountWriter {

    /** Persist the account row, as `DatabaseBackend.updateAccount(Account)` does. */
    fun write(account: Account): Boolean
}

/**
 * `roster/`'s capability: the account's contacts, read from and written to `contacts`.
 *
 * <p>**The next capability out of `DatabaseBackend`'s grab-bag, beside `accounts/`.** Five methods
 * (the ref-typed `readRoster` and `writeRoster` are the overload pair): the single-contact update,
 * the account's roster read, and the whole-roster write. One table (`contacts`), one caller class
 * (`:xmpp`'s `XmppConnectionService`). `docs/MIGRATION.md`'s `port-45` row is the inventory this
 * comes out of; it records the group as last left behind because "`writeRoster` calls
 * `updateAccount`", and that is answered here by the handed-in [AccountWriter] rather than by
 * refusing.
 *
 * <p>**The home is an `object` that takes the `SQLiteDatabase` rather than a store that owns it.**
 * `DatabaseBackend` is the one owner of the file (`HistoryDatabase`'s Room connection, handed in by
 * `getInstance`), so a package that opened its own would be the second owner the design forbids; the
 * seam is the same one `schema/RawTables.applySchema(exec)` uses.
 *
 * <p>**The table and column names are `RosterQueries`'s, not a second spelling.** The roster read
 * and the contact write name the published table, account and jid; the `IN_ROSTER` /
 * `SYNCED_VIA_OTHER` bits and the contact surface are the model's own.
 *
 * <p>**The Java-visible surface is unchanged: `@JvmStatic` on all three.** `DatabaseBackend`'s
 * methods are the "one delegating step", so `XmppConnectionService`'s call sites and the
 * `DatabaseBackendRef` signatures compile against byte for byte what they did before. A `@JvmStatic`
 * member of an `object` is the only shape whose static bridge carries the un-mangled name - a Kotlin
 * `internal` member would be emitted as `read$data` and Java could not see it (the
 * `ScriptReading`/`TranslationLanguages` precedent).
 *
 * <p>**The null contracts are read off the caller and off the Java's own body.** `contact` is the
 * caller's model `Contact` (the ref-typed overload casts once, in `DatabaseBackend`); `roster` is
 * the caller's model `Roster`; the `AccountWriter` is the caller's method reference, never null.
 * The Java NPEs are preserved: `contact.getAccount()` and `contact.getJid()` throw when the model
 * has none, and they are evaluated before the `try`, exactly as the Java evaluated them - only a
 * throw from `db.update` becomes `false`.
 *
 * <p>**No `@Throws`: nothing on this path throws a checked exception the Java catches.** The
 * original methods declared none.
 */
object RosterStore {

    /**
     * One contact updated on the pair the table's `UNIQUE` names, `true` when a row moved; a
     * database failure answers `false` as the Java did.
     */
    @JvmStatic
    fun updateContact(db: SQLiteDatabase, contact: Contact): Boolean {
        val values = contact.getContentValues()
        val args = arrayOf(contact.getAccount().getUuid(), contact.getJid().asBareJid().toString())
        return try {
            db.update(
                RosterQueries.TABLE,
                values,
                RosterQueries.ACCOUNT + "=? AND " + RosterQueries.JID + "=?",
                args,
            ) > 0
        } catch (e: Exception) {
            false
        }
    }

    /**
     * The account's whole contact list, each row handed back through `Roster.initContact`, which
     * sets the account on the row and files it under its bare JID.
     */
    @JvmStatic
    fun read(db: SQLiteDatabase, roster: Roster) {
        val args = arrayOf(roster.getAccount().getUuid())
        db.query(
            RosterQueries.TABLE,
            null,
            RosterQueries.ACCOUNT + "=?",
            args,
            null,
            null,
            null,
        ).use { cursor ->
            while (cursor.moveToNext()) {
                roster.initContact(Contact.fromCursor(cursor))
            }
        }
    }

    /**
     * The whole roster persisted in one transaction: every contact that is in the roster, has an
     * avatar or presence name, or is synced via another account is upserted; every other is deleted.
     * The account's roster version moves afterwards, through the handed-in writer.
     *
     * @param accountWriter the caller's `DatabaseBackend::updateAccount`
     */
    @JvmStatic
    fun write(db: SQLiteDatabase, roster: Roster, accountWriter: AccountWriter) {
        val start = SystemClock.elapsedRealtime()
        val account = roster.getAccount()
        db.beginTransaction()
        for (contact in roster.getContacts()) {
            if (contact.getOption(Contact.Options.IN_ROSTER) ||
                contact.hasAvatarOrPresenceName() ||
                contact.getOption(Contact.Options.SYNCED_VIA_OTHER)
            ) {
                db.insert(RosterQueries.TABLE, null, contact.getContentValues())
            } else {
                db.delete(
                    RosterQueries.TABLE,
                    RosterQueries.ACCOUNT + "=? AND " + RosterQueries.JID + "=?",
                    arrayOf(account.getUuid(), contact.getJid().toString()),
                )
            }
        }
        db.setTransactionSuccessful()
        db.endTransaction()
        account.setRosterVersion(roster.getVersion())
        accountWriter.write(account)
        val duration = SystemClock.elapsedRealtime() - start
        Log.d(
            Config.LOGTAG,
            account.getJid().asBareJid().toString() + ": persisted roster in " + duration + "ms",
        )
    }
}
