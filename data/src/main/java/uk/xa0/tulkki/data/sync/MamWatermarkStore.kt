package uk.xa0.tulkki.data.sync

import android.database.Cursor
import net.zetetic.database.sqlcipher.SQLiteDatabase
import org.json.JSONObject
import uk.xa0.tulkki.data.model.Account
import uk.xa0.tulkki.data.model.Conversation
import uk.xa0.tulkki.xmpp.mam.MamReference
import uk.xa0.tulkki.xmpp.refs.AccountRef

/**
 * `sync/`'s capability: where an account's history ends.
 *
 * <p>**Three reads that bound the catch-up gap, and nothing writes them.** `getLastMessageReceived`
 * is the newest message the account can anchor a MAM query on; `getLastClearDate` is the greatest
 * `last_clear_history` over its conversations; `getLastTimeFingerprintUsed` is the last message one
 * OMEMO fingerprint touched. All three join `accounts` -> `conversations` -> `messages` and answer a
 * scalar, and all three are read only by the anchor derivation (`:app`'s `XmppTulkkiHost.kt` and
 * `:data`'s `CryptoStore.kt`), never by a writer. `docs/MIGRATION.md`'s `port-45` row is the
 * inventory this comes out of; the row places it with `sync/` because that package owns the cursor
 * tables and the seed these values feed.
 *
 * <p>**The home is an `object` that takes the `SQLiteDatabase` rather than a store that owns it.**
 * `DatabaseBackend` is the one owner of the file (`HistoryDatabase`'s Room connection, handed in by
 * `getInstance`), so a package that opened its own would be the second owner the design forbids; the
 * seam is the same one `schema/RawTables.applySchema(exec)` uses. There is no `Context` here and no
 * `get()` to add.
 *
 * <p>**The Java-visible surface is `DatabaseBackend`'s names, unchanged: `@JvmStatic` on all
 * three.** Its three methods are now one-line delegations (the "one delegating step"), so
 * `XmppTulkkiHost`'s two call sites and `CryptoStore`'s one compile against byte for byte what they
 * did before. A `@JvmStatic` member of an `object` is the only shape whose static bridge carries the
 * un-mangled name - a Kotlin `internal` member would be emitted as `lastClearDate$data` and Java
 * could not see it (the `ScriptReading`/`TranslationLanguages` precedent).
 *
 * <p>**The null contracts are read off the Java's own bodies.** `lastMessageReceived` answered
 * `null` on a miss and on any failure, so it answers `MamReference?` here; `lastClearDate` always
 * answers a reference (a zero one when there is nothing) and `lastTimeFingerprintUsed` always
 * answers a `long` (zero on a miss). The one `AccountRef` parameter is the island's own type, whose
 * `getUuid()` is the only member read.
 *
 * <p>**No `@Throws`: nothing on this path throws a checked exception the Java catches.** The first
 * read's `catch (Exception)` is kept as the null answer it was; the other two declared nothing.
 */
object MamWatermarkStore {

    private const val LAST_RECEIVED_SQL =
        "select messages.timeSent,messages.serverMsgId from accounts join conversations" +
            " on accounts.uuid=conversations.accountUuid join messages on" +
            " conversations.uuid=messages.conversationUuid where accounts.uuid=? and" +
            " (messages.status=0 or messages.carbon=1 or messages.serverMsgId not" +
            " null) and (conversations.mode=0 or (messages.serverMsgId not null and" +
            " messages.type=4)) order by messages.timesent desc limit 1"

    private const val LAST_FINGERPRINT_SQL =
        "select messages.timeSent from accounts join conversations on" +
            " accounts.uuid=conversations.accountUuid join messages on" +
            " conversations.uuid=messages.conversationUuid where accounts.uuid=? and" +
            " messages.axolotl_fingerprint=? order by messages.timesent desc limit 1"

    /**
     * The account's newest anchor, or `null` when the join answers no row or the read fails - the
     * Java's `catch (Exception)` arm and its `getCount() == 0` arm, both kept.
     *
     * <p>The Java's shape is kept rather than tidied: the cursor is closed in the `finally`, and the
     * `serverMsgId` column is passed through as the Java passed it, absent value and all.
     */
    @JvmStatic
    fun lastMessageReceived(db: SQLiteDatabase, account: AccountRef): MamReference? {
        var cursor: Cursor? = null
        return try {
            cursor = db.rawQuery(LAST_RECEIVED_SQL, arrayOf(account.getUuid()))
            if (cursor.count == 0) {
                null
            } else {
                cursor.moveToFirst()
                MamReference(cursor.getLong(0), cursor.getString(1))
            }
        } catch (e: Exception) {
            null
        } finally {
            cursor?.close()
        }
    }

    /** The time one fingerprint last touched a message, or `0` when the join answers no row. */
    @JvmStatic
    fun lastTimeFingerprintUsed(
        db: SQLiteDatabase,
        account: Account,
        fingerprint: String,
    ): Long {
        val cursor = db.rawQuery(LAST_FINGERPRINT_SQL, arrayOf(account.getUuid(), fingerprint))
        val time: Long =
            if (cursor.moveToFirst()) {
                cursor.getLong(0)
            } else {
                0
            }
        cursor.close()
        return time
    }

    /**
     * The greatest `last_clear_history` over the account's conversations, or a zero reference when
     * none carries one.
     *
     * <p>Each conversation's `attributes` JSON is read on its own and a row whose JSON will not
     * parse is skipped, exactly as the Java's empty `catch` did.
     */
    @JvmStatic
    fun lastClearDate(db: SQLiteDatabase, account: AccountRef): MamReference {
        val columns = arrayOf(Conversation.ATTRIBUTES)
        val selection = Conversation.ACCOUNT + "=?"
        val args = arrayOf(account.getUuid())
        var maxClearDate = MamReference(0)
        db.query(Conversation.TABLENAME, columns, selection, args, null, null, null).use { cursor ->
            while (cursor.moveToNext()) {
                try {
                    val o = JSONObject(cursor.getString(0))
                    val cleared =
                        MamReference.fromAttribute(
                            o.getString(Conversation.ATTRIBUTE_LAST_CLEAR_HISTORY),
                        )
                    maxClearDate = MamReference.max(maxClearDate, cleared) ?: maxClearDate
                } catch (e: Exception) {
                    // ignored
                }
            }
        }
        return maxClearDate
    }
}
