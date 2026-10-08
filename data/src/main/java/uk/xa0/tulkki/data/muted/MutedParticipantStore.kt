package uk.xa0.tulkki.data.muted

import android.content.ContentValues
import com.google.common.collect.HashMultimap
import com.google.common.collect.Multimap
import net.zetetic.database.sqlcipher.SQLiteDatabase
import uk.xa0.tulkki.data.schema.RawTables
import uk.xa0.tulkki.xmpp.refs.MucOptionsRef

/**
 * `muted/`'s capability: the participants the owner muted, one row per room and occupant, in their
 * own table.
 *
 * <p>**The next capability out of `DatabaseBackend`'s grab-bag after `upload/`'s media/CID group.**
 * Four methods: the per-account read the cache is built from, the mute's upsert, the room's owning
 * account, and the unmute's delete. One table (`muted_participants`, no Room entity), one caller
 * class (`:xmpp`'s `XmppConnectionService`), and the group's DDL already lives in
 * `schema/RawTables`. `docs/MIGRATION.md`'s `port-45` row is the inventory this comes out of.
 *
 * <p>**The home is an `object` that takes the `SQLiteDatabase` rather than a store that owns it.**
 * `DatabaseBackend` is the one owner of the file (`HistoryDatabase`'s Room connection, handed in by
 * `getInstance`), so a package that opened its own would be the second owner the design forbids; the
 * seam is the same one `schema/RawTables.applySchema(exec)` uses. There is no `Context` here and no
 * `get()` to add.
 *
 * <p>**The table and column names are `RawTables`'s, not a second spelling**, and the two read
 * statements are its published `MUTED_FOR_ACCOUNT` / `MUTED_ACCOUNT_BY_JID`. The two writes were
 * spelled inline in the Java and are spelled here from the same constants.
 *
 * <p>**The Java-visible surface is unchanged: `@JvmStatic` on all four.** `DatabaseBackend`'s four
 * methods are now one-line delegations (the "one delegating step"), so `XmppConnectionService`'s
 * four call sites and the `DatabaseBackendRef` signatures compile against byte for byte what they
 * did before. A `@JvmStatic` member of an `object` is the only shape whose static bridge carries the
 * un-mangled name - a Kotlin `internal` member would be emitted as `forAccount$data` and Java could
 * not see it (the `ScriptReading`/`TranslationLanguages` precedent).
 *
 * <p>**The null contracts are read off the caller and off the Java's own guards.** `loadMutedMucUsers`
 * and `accountOfMuc` take the caller's non-null `account.getUuid()` / `user.getMuc().toString()`;
 * `muteMucUser` declared `accountUuid` nullable and answered `false` for it, and that guard is kept,
 * so the parameter stays nullable; `unmuteMucUser` guarded only the user, and its one caller has
 * already answered `false` for a null account, so its account is non-null. The user ref's two reads
 * are captured into locals before their null test - Java called each getter twice and answered
 * `false` on either null; Kotlin cannot smart-cast a repeated interface call.
 *
 * <p>**No `@Throws`: nothing on this path throws a checked exception the Java catches.** The
 * original four declared none and `XmppConnectionService` catches nothing around them.
 */
object MutedParticipantStore {

    /**
     * Every mute one account owns, as the multimap the cache is rebuilt from. A row with a null
     * `account_uuid` is nobody's and is not returned.
     */
    @JvmStatic
    fun forAccount(db: SQLiteDatabase, accountUuid: String): Multimap<String, String> {
        val result: Multimap<String, String> = HashMultimap.create()
        db.rawQuery(RawTables.MUTED_FOR_ACCOUNT, arrayOf(accountUuid)).use { cursor ->
            while (cursor.moveToNext()) {
                result.put(cursor.getString(0), cursor.getString(1))
            }
        }
        return result
    }

    /**
     * The participant, muted for the account whose conversation it was read from. The table's own
     * primary key is `(muc_jid, occupant_id)` and `CONFLICT_REPLACE` resolves against it, so muting
     * the same participant twice overwrites its row. The account was bound rather than derived in
     * S5-12: two of the owner's accounts in one room are two accounts.
     *
     * @param accountUuid the owning account, or `null` when the caller could not name one (answered
     *     `false`, as the Java did)
     */
    @JvmStatic
    fun mute(db: SQLiteDatabase, user: MucOptionsRef.UserRef, accountUuid: String?): Boolean {
        val muc = user.getMuc()
        val occupantId = user.getOccupantId()
        if (muc == null || occupantId == null) return false
        if (accountUuid == null) return false

        val values = ContentValues()
        values.put(RawTables.MUTED_ACCOUNT, accountUuid)
        values.put(RawTables.MUTED_MUC, muc.toString())
        values.put(RawTables.MUTED_OCCUPANT, occupantId)
        db.insertWithOnConflict(
            RawTables.MUTED_TABLE,
            null,
            values,
            SQLiteDatabase.CONFLICT_REPLACE,
        )
        return true
    }

    /**
     * The account a room JID belongs to, as the file records it: the owner of a MUC conversation
     * with that JID. Read by the mute cache for a participant the interface built from a JID alone,
     * which carries no conversation to take the owner from. `null` when no conversation names it.
     */
    @JvmStatic
    fun accountOf(db: SQLiteDatabase, mucJid: String): String? =
        db.rawQuery(RawTables.MUTED_ACCOUNT_BY_JID, arrayOf(mucJid)).use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }

    /**
     * The participant, unmuted. The user is guarded as the Java guarded it; the account is the
     * caller's already-checked non-null, as `XmppConnectionService.unmuteMucUser` answers `false`
     * before it gets here.
     */
    @JvmStatic
    fun unmute(db: SQLiteDatabase, user: MucOptionsRef.UserRef, accountUuid: String): Boolean {
        val muc = user.getMuc()
        val occupantId = user.getOccupantId()
        if (muc == null || occupantId == null) return false

        val where =
            RawTables.MUTED_ACCOUNT +
                "=? AND " +
                RawTables.MUTED_MUC +
                "=? AND " +
                RawTables.MUTED_OCCUPANT +
                "=?"
        db.delete(
            RawTables.MUTED_TABLE,
            where,
            arrayOf(accountUuid, muc.toString(), occupantId),
        )
        return true
    }
}
