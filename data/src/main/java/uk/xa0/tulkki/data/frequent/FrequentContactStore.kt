package uk.xa0.tulkki.data.frequent

import android.util.Log
import net.zetetic.database.sqlcipher.SQLiteDatabase
import uk.xa0.tulkki.data.model.Conversation
import uk.xa0.tulkki.data.model.FrequentContact
import uk.xa0.tulkki.data.model.Message
import uk.xa0.tulkki.xmpp.Config
import uk.xa0.tulkki.libs.Jid

/**
 * `frequent/`'s capability: the four conversations the shortcut publisher offers, picked from the
 * owner's messages.
 *
 * <p>**The next capability out of `DatabaseBackend`'s grab-bag.** One read: the four conversations
 * with the most live messages in the last `days`, each as the conversation's uuid, its account's
 * uuid and the counterpart's JID. It touches `conversations` and `messages` but calls no other
 * group, and its only caller is `:app`'s `ShortcutService`. `docs/MIGRATION.md`'s `port-45` row is
 * the inventory this comes out of.
 *
 * <p>**The home is an `object` that takes the `SQLiteDatabase` rather than a store that owns it.**
 * `DatabaseBackend` is the one owner of the file (`HistoryDatabase`'s Room connection, handed in by
 * `getInstance`), so a package that opened its own would be the second owner the design forbids; the
 * seam is the same one `schema/RawTables.applySchema(exec)` uses. There is no `Context` here and no
 * `get()` to add.
 *
 * <p>**The Java-visible surface is unchanged: `@JvmStatic`.** `DatabaseBackend.getFrequentContacts`
 * is now a one-line delegation (the "one delegating step"), so `ShortcutService`'s call site and the
 * `DatabaseBackend.get()` static it reaches compile against byte for byte what they did before. A
 * `@JvmStatic` member of an `object` is the only shape whose static bridge carries the un-mangled
 * name - a Kotlin `internal` member would be emitted as `list$data` and Java could not see it (the
 * `ScriptReading`/`TranslationLanguages` precedent).
 *
 * <p>**The null contracts are read off the caller and off the Java's own body.** `days` is the
 * caller's own `int` literal, never null; the return is the real, mutable `ArrayList` the Java
 * handed back. A row whose counterpart will not parse is skipped and logged, exactly as the Java's
 * per-row `try` skipped and logged it, so a bad row still costs only itself.
 *
 * <p>**No `@Throws`: nothing on this path throws a checked exception the Java catches.** The
 * original declared none and `ShortcutService` catches nothing around it; `Jid.of`'s
 * `IllegalArgumentException` is caught by the per-row guard, as it was.
 */
object FrequentContactStore {

    /**
     * The four conversations with the most non-carbon, live messages since `days` ago, newest
     * count first.
     *
     * @param days the window `ShortcutService` asks for, its own `30`
     */
    @JvmStatic
    fun list(db: SQLiteDatabase, days: Int): List<FrequentContact> {
        val sql =
            "select " +
                Conversation.TABLENAME +
                "." +
                Conversation.UUID +
                "," +
                Conversation.TABLENAME +
                "." +
                Conversation.ACCOUNT +
                "," +
                Conversation.TABLENAME +
                "." +
                Conversation.CONTACTJID +
                " from " +
                Conversation.TABLENAME +
                " join " +
                Message.TABLENAME +
                " on conversations.uuid=messages.conversationUuid where" +
                " messages.status!=0 and carbon==0  and conversations.mode=0 and" +
                " messages.timeSent>=? group by conversations.uuid order by count(body)" +
                " desc limit 4;"
        val whereArgs =
            arrayOf((System.currentTimeMillis() - (Config.MILLISECONDS_IN_DAY * days)).toString())

        val contacts = ArrayList<FrequentContact>()
        db.rawQuery(sql, whereArgs).use { cursor ->
            while (cursor.moveToNext()) {
                try {
                    contacts.add(
                        FrequentContact(
                            cursor.getString(0),
                            cursor.getString(1),
                            Jid.of(cursor.getString(2)),
                        ),
                    )
                } catch (e: Exception) {
                    Log.e(Config.LOGTAG, "could not create frequent contact", e)
                }
            }
        }
        return contacts
    }
}
