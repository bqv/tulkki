package uk.xa0.tulkki.data.messages

import java.util.concurrent.CopyOnWriteArrayList
import net.zetetic.database.sqlcipher.SQLiteDatabase
import uk.xa0.tulkki.data.model.Conversation
import uk.xa0.tulkki.libs.Jid
import uk.xa0.tulkki.xmpp.refs.AccountRef

/**
 * `messages/`'s conversation half: the row's own writer and its readers.
 *
 * <p>**The next capability out of `DatabaseBackend`'s grab-bag, and the smallest that needs
 * nothing but the opener.** Eight methods over one table (`conversations`): the insert, the
 * status-keyed list the export worker walks, the four finds (by uuid, by the account/JID pair, by
 * uuid through an account's credentials, and the database-connection overload), the whole-row
 * update, and the account-uuid lookup the pinned-message repository reads. `conversations` has no
 * package of its own in the design: `messages/` already owns its DDL (`ConversationQueries`), its
 * DAO (`ConversationDao`) and its entity (`ConversationEntity`), so this store lives beside them.
 * `docs/MIGRATION.md`'s `port-45` row is the inventory this comes out of.
 *
 * <p>**The home is an `object` that takes the `SQLiteDatabase` rather than a store that owns it.**
 * `DatabaseBackend` is the one owner of the file (`HistoryDatabase`'s Room connection, handed in by
 * `getInstance`), so a package that opened its own would be the second owner the design forbids; the
 * seam is the same one `schema/RawTables.applySchema(exec)` uses. There is no `Context` here and no
 * `get()` to add.
 *
 * <p>**The statements and names are the package's own.** The status list executes
 * `ConversationQueries.LIST_FOR_STATUS`; the account-credential read executes
 * `ConversationQueries.UUID_FOR_ACCOUNT_JID`; every other statement names the published table and
 * columns, and the two writes go through the model's own `getContentValues()`. The two new
 * statements are added to `ConversationQueries` in this commit rather than spelled a second time
 * here.
 *
 * <p>**The Java-visible surface is unchanged: `@JvmStatic` on all eight, `find` twice.** The eight
 * `DatabaseBackend` methods are the one delegating step, so `:xmpp`'s `XmppConnectionService`,
 * `MessageParser` and `PresenceParser`, `:ui`'s `ConferenceDetailsActivity`,
 * `ContactDetailsActivity`, `ConversationFragment`, `ShareWithActivity` and
 * `PinnedMessageRepository`, and `:app`'s `ExportBackupWorker` compile against byte for byte what
 * they did before. A `@JvmStatic` member of an `object` is the only shape whose static bridge
 * carries the un-mangled name - a Kotlin `internal` member would be emitted as `find$data` and Java
 * could not see it (the `ScriptReading`/`TranslationLanguages` precedent).
 *
 * <p>**The five ref-typed `ConversationRef` adapters the row counts with this group stay in
 * `DatabaseBackend`.** They cast and delegate to the model-typed overloads exactly as
 * `readRoster(RosterRef)` does, so they are glue rather than capability and the group's fifteen
 * methods reduce to these eight here, as `contacts/roster`'s five reduced to `RosterStore`'s three.
 *
 * <p>**The null contracts are read off the Java's own bodies.** `find` answers `null` on a miss and
 * on a row whose JID will not parse (`Jid.Invalid`), exactly as the Java did; `findUuid` and
 * `accountUuidFor` answer the Java's own `null`; the writes take the caller's non-null model. The
 * Java's `cursor != null` in `accountUuidFor` guarded a `db.query` that never answers null, so the
 * `use` block keeps the same reachable behaviour rather than inventing a second guard.
 *
 * <p>**No `@Throws`: nothing on this path throws a checked exception the Java catches.**
 */
object ConversationStore {

    /** A new row, as `Conversation.getContentValues()` spells it. */
    @JvmStatic
    fun create(db: SQLiteDatabase, conversation: Conversation) {
        db.insert(ConversationQueries.TABLE, null, conversation.getContentValues())
    }

    /**
     * One status's rows that name a contact, newest first, each row refused when its JID will not
     * parse. The list is the real, mutable `CopyOnWriteArrayList` the Java handed back.
     */
    @JvmStatic
    fun list(db: SQLiteDatabase, status: Int): CopyOnWriteArrayList<Conversation> {
        val list = CopyOnWriteArrayList<Conversation>()
        val selectionArgs = arrayOf(status.toString())
        val cursor = db.rawQuery(ConversationQueries.LIST_FOR_STATUS, selectionArgs)
        while (cursor.moveToNext()) {
            val conversation = Conversation.fromCursor(cursor)
            if (conversation.getJid() is Jid.Invalid) {
                continue
            }
            list.add(conversation)
        }
        cursor.close()
        return list
    }

    /** One row by its own key, or `null` when the table has none or the row's JID will not parse. */
    @JvmStatic
    fun find(db: SQLiteDatabase, uuid: String): Conversation? {
        val selectionArgs = arrayOf(uuid)
        db.query(
            ConversationQueries.TABLE,
            null,
            ConversationQueries.UUID + "=?",
            selectionArgs,
            null,
            null,
            null,
        ).use { cursor ->
            if (cursor.count == 0) {
                return null
            }
            cursor.moveToFirst()
            val conversation = Conversation.fromCursor(cursor)
            if (conversation.getJid() is Jid.Invalid) {
                return null
            }
            return conversation
        }
    }

    /**
     * One row by the account/JID pair, or `null` when the table has none or the row's JID will not
     * parse. The bare JID matches the row's contact either exactly or as a resource-bearing prefix;
     * a hit has its account set to the ref the caller passed.
     */
    @JvmStatic
    fun find(db: SQLiteDatabase, account: AccountRef, contactJid: Jid): Conversation? {
        val selectionArgs = arrayOf(
            account.getUuid(),
            contactJid.asBareJid().toString() + "/%",
            contactJid.asBareJid().toString(),
        )
        val where = ConversationQueries.ACCOUNT + "=? AND (" + ConversationQueries.CONTACT_JID +
            " like ? OR " + ConversationQueries.CONTACT_JID + "=?)"
        db.query(
            ConversationQueries.TABLE,
            null,
            where,
            selectionArgs,
            null,
            null,
            null,
        ).use { cursor ->
            if (cursor.count == 0) {
                return null
            }
            cursor.moveToFirst()
            val conversation = Conversation.fromCursor(cursor)
            if (conversation.getJid() is Jid.Invalid) {
                return null
            }
            conversation.setAccount(account)
            return conversation
        }
    }

    /** One conversation's uuid through its account's credentials, or `null` for a miss. */
    @JvmStatic
    fun findUuid(db: SQLiteDatabase, account: Jid, jid: Jid): String? {
        val selectionArgs = arrayOf(
            account.getLocal() ?: throw NullPointerException(),
            account.getDomain().toString(),
            jid.asBareJid().toString() + "/%",
            jid.asBareJid().toString(),
        )
        db.rawQuery(ConversationQueries.UUID_FOR_ACCOUNT_JID, selectionArgs).use { cursor ->
            if (cursor.count == 0) {
                return null
            }
            cursor.moveToFirst()
            return cursor.getString(0)
        }
    }

    /**
     * One row by its own key on a caller-supplied connection, or `null` when the table has none or
     * the row's JID will not parse. The Java took the connection as its second parameter; the
     * delegation reorders it to this store's opener-first shape.
     */
    @JvmStatic
    fun findByUuid(db: SQLiteDatabase, uuid: String): Conversation? {
        val selectionArgs = arrayOf(uuid)
        db.query(
            ConversationQueries.TABLE,
            null,
            ConversationQueries.UUID + "=?",
            selectionArgs,
            null,
            null,
            null,
        ).use { cursor ->
            if (!cursor.moveToFirst()) {
                return null
            }
            val conversation = Conversation.fromCursor(cursor)
            if (conversation.getJid() is Jid.Invalid) {
                return null
            }
            return conversation
        }
    }

    /** The whole row again, by its own key. */
    @JvmStatic
    fun update(db: SQLiteDatabase, conversation: Conversation) {
        val args = arrayOf(conversation.getUuid())
        db.update(
            ConversationQueries.TABLE,
            conversation.getContentValues(),
            ConversationQueries.UUID + "=?",
            args,
        )
    }

    /** The account a conversation belongs to, or `null` when the row is gone. */
    @JvmStatic
    fun accountUuidFor(db: SQLiteDatabase, conversationUuid: String): String? {
        val columns = arrayOf(ConversationQueries.ACCOUNT)
        val selectionArgs = arrayOf(conversationUuid)
        db.query(
            ConversationQueries.TABLE,
            columns,
            ConversationQueries.UUID + "=?",
            selectionArgs,
            null,
            null,
            null,
        ).use { cursor ->
            if (cursor.moveToFirst()) {
                return cursor.getString(0)
            }
        }
        return null
    }
}
