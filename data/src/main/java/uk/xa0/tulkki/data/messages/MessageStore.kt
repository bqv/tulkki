package uk.xa0.tulkki.data.messages

import android.database.Cursor
import android.os.SystemClock
import android.text.TextUtils
import android.util.Log
import com.google.common.collect.HashMultimap
import com.google.common.collect.Multimap
import java.io.IOException
import java.util.ArrayList
import java.util.HashSet
import java.util.Hashtable
import java.util.NoSuchElementException
import net.zetetic.database.sqlcipher.SQLiteDatabase
import uk.xa0.tulkki.data.model.Conversation
import uk.xa0.tulkki.data.model.Message
import uk.xa0.tulkki.data.model.PinnedMessage
import uk.xa0.tulkki.data.schema.RawTables
import uk.xa0.tulkki.xmpp.Config
import uk.xa0.tulkki.xmpp.utils.CursorUtils

/**
 * `messages/`'s row half: the readers and writers over the `messages` table itself.
 *
 * <p>**The next capability out of `DatabaseBackend`'s grab-bag, and the one the row says to split.**
 * The group is `port-45`'s `messages: rows`, measured at 22 methods / 377 body-lines: sixteen live
 * methods and the six three-line ref adapters the row counted with them. The sixteen move here -
 * the insert, the five fix-up reads (`getMessage`, both `getMessages` windows, `getMessagesNearUuid`,
 * `getMessageFuzzyIds`), the two id lookups, the two writes, the delete, the conversation's own
 * delete, the export iterator and the live-location query - and the eleven `MessageRef`/
 * `ConversationRef` adapters stay in `DatabaseBackend` as glue, exactly as `conversations`' fifteen
 * reduced to `ConversationStore`'s eight and `filepaths`' eleven to `FilePathStore`'s nine.
 *
 * <p>**The home is an `object` that takes the `SQLiteDatabase` rather than a store that owns it.**
 * `DatabaseBackend` is the one owner of the file (`HistoryDatabase`'s Room connection, handed in by
 * `getInstance`), so a package that opened its own would be the second owner the design forbids; the
 * seam is the same one `schema/RawTables.applySchema(exec)` uses. There is no `Context` here and no
 * `get()` to add.
 *
 * <p>**The table and column names are `MessagesQueries`'s, and the statements live there.** The five
 * fixed statements (`ROW_BY_UUID`, `ROW_BY_SERVER_MSG_ID`, `ROW_BY_UUID_OR_REMOTE_ID`, `NEAR_UUID`,
 * `RECENT_LIVE_LOCATION`) are added to the package's own statement object in this commit rather than
 * spelled a second time here; the three the caller's arguments make dynamic - the fuzzy-id `IN` set
 * and the two `getMessages` windows - are the package's builders, so the SQL has one home even when
 * it cannot be a `const val`. `webxdc_updates` is `RawTables.WEBXDC_TABLE`, its one owner.
 *
 * <p>**The Java-visible surface is unchanged: `@JvmStatic` on all sixteen, and every return type is
 * the Java's own.** `DatabaseBackend`'s live methods are the one delegating step, so
 * `XmppConnectionService`'s sixty-six call sites, `MessageParser`'s eighteen,
 * `ConversationCalendarActivity.kt`, `CallsFragment`, `HttpDownloadConnection`,
 * `ExportBackupWorker`, both jingle classes and `MessageContacts`/`ConversationPaging`/
 * `ConversationHistory` compile against byte for byte what they did before - and the eleven
 * ref-typed adapters above them keep casting and delegating as they always have. A `@JvmStatic`
 * member of an `object` is the only shape whose static bridge carries the un-mangled name - a Kotlin
 * `internal` member would be emitted as `getMessage$data` and Java could not see it (the
 * `ScriptReading`/`TranslationLanguages` precedent).
 *
 * <p>**The null contracts are read off the callers and off the Java's own dereferences.**
 * `getMessage`'s `uuid` is nullable because the Java body only ever handed it to `rawQuery`
 * (`BlockedMedia.getMessage` says so in as many words), and `deleteMessage`'s is nullable because
 * `MessageContacts.deleteMessage` passes `message.getUuid()`, which `AbstractEntity` declares
 * nullable; `getRecentOutgoingLiveLocationMessages`' account is nullable for the same reason, the
 * ref's `getUuid()` being a platform `String`. `getMessagesNearUuid`'s `uuid` is nullable because
 * `ConversationPaging.jumpToMessage` declares it so, and its anchor test is `==` rather than the
 * Java's `uuid.equals(...)`: with a non-null uuid the two are the same comparison, and a null one is
 * "not the anchor" rather than a throw - the only spelling available without `!!`. Every other
 * parameter is the caller's non-null value, as the Java dereferenced it.
 *
 * <p>**Two Java quirks are carried, not repaired, because this is a move.** `deleteMessage` runs its
 * `DELETE` twice, on the same table - upstream's `"cheogram."`/`"monocles."` prefix became `""` -
 * so it answers `false` whenever it removed a row; callers only log that. And the Java `getMessage`
 * returns from inside its cursor loop without closing the cursor, so this one does too. The export
 * iterator's `finalize()` (which closes the cursor and then the *shared* connection) is kept as the
 * Java wrote it.
 *
 * <p>**No `@Throws`: nothing here throws a checked exception the Java catches.** The two `IOException`
 * catches around `Message.fromCursor` stay where the Java had them; nothing else declares one.
 */
object MessageStore {

    /** One new row, as `Message.getContentValues()` spells it. */
    @JvmStatic
    fun createMessage(db: SQLiteDatabase, message: Message) {
        db.insert(MessagesQueries.TABLE, null, message.getContentValues())
    }

    /**
     * One row by its own key, refusing a row that will not restore. `null` for a miss. The cursor is
     * deliberately not closed on the hit path, as the Java's early `return` did not close it.
     */
    @JvmStatic
    fun getMessage(db: SQLiteDatabase, conversation: Conversation, uuid: String?): Message? {
        val cursor = db.rawQuery(MessagesQueries.ROW_BY_UUID, arrayOf(uuid))
        while (cursor.moveToNext()) {
            try {
                return Message.fromCursor(cursor, conversation)
            } catch (e: Exception) {
                Log.e(Config.LOGTAG, "unable to restore message")
            }
        }
        cursor.close()
        return null
    }

    /** The newest page of one conversation's rows, in reading order. */
    @JvmStatic
    fun getMessages(db: SQLiteDatabase, conversation: Conversation, limit: Int): ArrayList<Message> =
        getMessages(db, conversation, limit, -1L, false)

    /** One page of a conversation's rows on one side of a timestamp; `""` is the Java's own uuid. */
    @JvmStatic
    fun getMessages(
        db: SQLiteDatabase,
        conversation: Conversation,
        limit: Int,
        timestamp: Long,
        isForward: Boolean,
    ): ArrayList<Message> =
        getMessages(db, conversation, limit, timestamp, "", isForward)

    /**
     * The window around one message, half older and half newer, or `null` when the anchor is not in
     * the conversation. The replies inside it are resolved against their parents before it returns.
     */
    @JvmStatic
    fun getMessagesNearUuid(
        db: SQLiteDatabase,
        conversation: Conversation,
        limit: Int,
        uuid: String?,
    ): ArrayList<Message>? {
        val selectionArgs = arrayOf(
            conversation.getUuid(), uuid, uuid, uuid,
            conversation.getUuid(), (limit / 2).toString(),
            conversation.getUuid(), (limit / 2).toString(),
        )
        val cursor = db.rawQuery(MessagesQueries.NEAR_UUID, selectionArgs)
        CursorUtils.upgradeCursorWindowSize(cursor)
        val list = ArrayList<Message>()
        val waitingForReplies: Multimap<String, Message> = HashMultimap.create()
        val replyIds = HashSet<String>()
        var foundAnchor = false
        while (cursor.moveToNext()) {
            try {
                val m = Message.fromCursor(cursor, conversation)
                if (uuid == m.getServerMsgId() || uuid == m.getRemoteMsgId() || uuid == m.getUuid()) {
                    foundAnchor = true
                }
                val replyId = m.getReply()?.getAttribute("id")
                if (replyId != null) {
                    replyIds.add(replyId)
                    waitingForReplies.put(replyId, m)
                }
                list.add(m)
            } catch (e: Exception) {
                Log.e(Config.LOGTAG, "unable to restore message", e)
            }
        }
        cursor.close()

        if (!foundAnchor) {
            return null
        }

        for ((key, value) in getMessageFuzzyIds(db, conversation, replyIds)) {
            for (m in waitingForReplies.get(key)) {
                m.setInReplyTo(value)
            }
        }

        return list
    }

    /**
     * The rows any of these ids names, keyed by the id that matched: a row may answer under its own
     * uuid, its server id and its remote id at once, exactly as the Java's three tests did.
     */
    @JvmStatic
    fun getMessageFuzzyIds(
        db: SQLiteDatabase,
        conversation: Conversation,
        ids: Collection<String>,
    ): MutableMap<String, Message> {
        val result: MutableMap<String, Message> = Hashtable()
        if (ids.size < 1) return result
        val params = ArrayList<String>()
        val template = ArrayList<String>()
        for (id in ids) {
            template.add("?")
        }
        params.addAll(ids)
        params.addAll(ids)
        params.addAll(ids)
        val cursor = db.rawQuery(
            MessagesQueries.fuzzyIds(TextUtils.join(",", template)),
            params.toTypedArray(),
        )
        while (cursor.moveToNext()) {
            try {
                val m = Message.fromCursor(cursor, conversation)
                val uuid = m.getUuid()
                if (uuid != null && ids.contains(uuid)) result[uuid] = m
                val serverMsgId = m.getServerMsgId()
                if (serverMsgId != null && ids.contains(serverMsgId)) result[serverMsgId] = m
                val remoteMsgId = m.getRemoteMsgId()
                if (remoteMsgId != null && ids.contains(remoteMsgId)) result[remoteMsgId] = m
            } catch (e: Exception) {
                Log.e(Config.LOGTAG, "unable to restore message")
            }
        }
        cursor.close()
        return result
    }

    /**
     * One page of a conversation's rows around a `(timeSent, uuid)` anchor. `timestamp == -1` is the
     * newest page; otherwise `isForward` decides which side of the anchor. Backwards pages are built
     * with each row inserted at the front, so a caller reads them oldest-first either way.
     */
    @JvmStatic
    fun getMessages(
        db: SQLiteDatabase,
        conversation: Conversation,
        limit: Int,
        timestamp: Long,
        uuid: String,
        isForward: Boolean,
    ): ArrayList<Message> {
        val list = ArrayList<Message>()
        val comparisonOperation = if (isForward) ">" else "<"
        val sorting = if (isForward) " ASC " else " DESC "
        val cursor: Cursor
        if (timestamp == -1L) {
            val selectionArgs = arrayOf(conversation.getUuid())
            cursor = db.rawQuery(MessagesQueries.rowsForConversation(sorting, limit), selectionArgs)
        } else {
            val selectionArgs = arrayOf(
                conversation.getUuid(),
                timestamp.toString(),
                uuid,
                timestamp.toString(),
            )
            cursor = db.rawQuery(
                MessagesQueries.rowsAround(sorting, comparisonOperation, limit),
                selectionArgs,
            )
        }
        CursorUtils.upgradeCursorWindowSize(cursor)
        val waitingForReplies: Multimap<String, Message> = HashMultimap.create()
        val replyIds = HashSet<String>()
        while (cursor.moveToNext()) {
            try {
                val m = Message.fromCursor(cursor, conversation)
                val replyId = m.getReply()?.getAttribute("id") // Guard against busted replies
                if (replyId != null) {
                    replyIds.add(replyId)
                    waitingForReplies.put(replyId, m)
                }
                if (isForward) {
                    list.add(m)
                } else {
                    list.add(0, m)
                }
            } catch (e: Exception) {
                Log.e(Config.LOGTAG, "unable to restore message", e)
            }
        }
        for ((key, value) in getMessageFuzzyIds(db, conversation, replyIds)) {
            for (m in waitingForReplies.get(key)) {
                m.setInReplyTo(value)
            }
        }
        cursor.close()
        return list
    }

    /** One row by the conversation and the server's own id, or `null` for a miss. */
    @JvmStatic
    fun getMessageWithServerMsgId(
        db: SQLiteDatabase,
        conversation: Conversation,
        messageId: String,
    ): Message? {
        val cursor = db.rawQuery(
            MessagesQueries.ROW_BY_SERVER_MSG_ID,
            arrayOf(conversation.getUuid(), messageId),
        )
        var message: Message? = null
        try {
            if (cursor.moveToFirst()) {
                message = Message.fromCursor(cursor, conversation)
            }
        } catch (e: IOException) {
            // as the Java: a row that will not restore is "not found"
        }
        cursor.close()
        return message
    }

    /** One row by the conversation and either its own uuid or the remote id, or `null` for a miss. */
    @JvmStatic
    fun getMessageWithUuidOrRemoteId(
        db: SQLiteDatabase,
        conversation: Conversation,
        messageId: String,
    ): Message? {
        val cursor = db.rawQuery(
            MessagesQueries.ROW_BY_UUID_OR_REMOTE_ID,
            arrayOf(conversation.getUuid(), messageId, messageId),
        )
        var message: Message? = null
        try {
            if (cursor.moveToFirst()) {
                message = Message.fromCursor(cursor, conversation)
            }
        } catch (e: IOException) {
            // as the Java: a row that will not restore is "not found"
        }
        cursor.close()
        return message
    }

    /** The row's whole content, minus its uuid and (when asked) its body, written by uuid. */
    @JvmStatic
    fun updateMessage(db: SQLiteDatabase, message: Message, includeBody: Boolean): Boolean {
        val args = arrayOf(message.getUuid())
        val contentValues = message.getContentValues()
        contentValues.remove(Message.UUID)
        if (!includeBody) {
            contentValues.remove(Message.BODY)
        }
        return db.update(MessagesQueries.TABLE, contentValues, MessagesQueries.UUID + "=?", args) == 1
    }

    /** The row's whole content, written by the uuid the caller names rather than the row's own. */
    @JvmStatic
    fun updateMessage(db: SQLiteDatabase, message: Message, uuid: String): Boolean {
        val args = arrayOf(uuid)
        return db.update(
            MessagesQueries.TABLE,
            message.getContentValues(),
            MessagesQueries.UUID + "=?",
            args,
        ) == 1
    }

    /**
     * The row gone, asked twice as the Java asked it: the second `DELETE` names the same table, so
     * it reports no row and the answer is `false` whenever the first removed one. Callers log that.
     */
    @JvmStatic
    fun deleteMessage(db: SQLiteDatabase, uuid: String?): Boolean {
        val args = arrayOf(uuid)
        return db.delete(MessagesQueries.TABLE, MessagesQueries.UUID + "=?", args) == 1 &&
            db.delete(MessagesQueries.TABLE, MessagesQueries.UUID + "=?", args) == 1
    }

    /**
     * Every message of a conversation, and the rows that hang off it: its webxdc stream and its
     * pinned messages. One transaction, so a failure leaves the conversation whole.
     */
    @JvmStatic
    fun deleteMessagesInConversation(db: SQLiteDatabase, conversation: Conversation) {
        val start = SystemClock.elapsedRealtime()
        db.beginTransaction()
        val args = arrayOf(conversation.getUuid())
        val num = db.delete(MessagesQueries.TABLE, MessagesQueries.CONVERSATION + "=?", args)
        db.delete(RawTables.WEBXDC_TABLE, MessagesQueries.CONVERSATION + "=?", args)
        db.delete(PinnedMessage.TABLENAME, PinnedMessage.CONVERSATION_UUID + "=?", args)
        db.setTransactionSuccessful()
        db.endTransaction()
        Log.d(
            Config.LOGTAG,
            "deleted " +
                num +
                " messages and associated data for " +
                conversation.getJid()?.asBareJid() +
                " in " +
                (SystemClock.elapsedRealtime() - start) +
                "ms",
        )
    }

    /**
     * One conversation's rows, oldest first, as the export worker walks them. The iterator is the
     * Java's anonymous one: its query runs when the iterator is made, and its `finalize()` closes
     * the cursor and then the connection it was handed.
     */
    @JvmStatic
    fun getMessagesIterable(db: SQLiteDatabase, conversation: Conversation): Iterable<Message> =
        object : Iterable<Message> {
            override fun iterator(): Iterator<Message> = MessageCursorIterator(db, conversation)
        }

    /** The rows one conversation has of one type, newest first. */
    @JvmStatic
    fun getMessages(
        db: SQLiteDatabase,
        conversation: Conversation,
        type: Int,
        limit: Int,
    ): ArrayList<Message> {
        val list = ArrayList<Message>()
        val cursor = db.query(
            MessagesQueries.TABLE,
            null,
            MessagesQueries.CONVERSATION + "=? AND " + Message.TYPE + "=?",
            arrayOf(conversation.getUuid(), type.toString()),
            null,
            null,
            MessagesQueries.TIME_SENT + " DESC",
            limit.toString(),
        )
        if (cursor.count > 0) {
            cursor.moveToFirst()
            do {
                try {
                    list.add(Message.fromCursor(cursor, conversation))
                } catch (e: Exception) {
                    Log.d(Config.LOGTAG, "unable to load message from database", e)
                }
            } while (cursor.moveToNext())
        }
        cursor.close()
        return list
    }

    /**
     * The account's sent live-location rows of the last eight hours, each as
     * `[conversationUuid, rawPayloads]`.
     */
    @JvmStatic
    fun getRecentOutgoingLiveLocationMessages(
        db: SQLiteDatabase,
        accountUuid: String?,
    ): MutableList<Array<String?>> {
        val result = ArrayList<Array<String?>>()
        val cutoff = System.currentTimeMillis() - 8 * 60 * 60 * 1000L
        try {
            db.rawQuery(
                MessagesQueries.RECENT_LIVE_LOCATION,
                arrayOf(accountUuid, cutoff.toString()),
            ).use { cursor ->
                while (cursor.moveToNext()) {
                    result.add(arrayOf(cursor.getString(0), cursor.getString(1)))
                }
            }
        } catch (e: Exception) {
            Log.e(Config.LOGTAG, "Error querying outgoing live location messages", e)
        }
        return result
    }
}

/**
 * `getMessagesIterable`'s cursor walk, as the Java's anonymous `Iterator` wrote it: the query runs
 * at construction, `next` refuses past the end, `remove` is unsupported, and `finalize` closes the
 * cursor and the connection.
 */
private class MessageCursorIterator(
    private val database: SQLiteDatabase,
    private val conversation: Conversation,
) : MutableIterator<Message> {

    private val queryArgs = arrayOf(conversation.getUuid(), "1")

    private var messageCursor: Cursor? = database.query(
        MessagesQueries.TABLE,
        null,
        MessagesQueries.CONVERSATION + "=? and " + Message.DELETED + "<?",
        queryArgs,
        null,
        null,
        MessagesQueries.TIME_SENT + " ASC",
        null,
    )

    init {
        messageCursor?.moveToFirst()
    }

    override fun hasNext(): Boolean {
        val cursor = messageCursor ?: return false
        return !cursor.isAfterLast
    }

    override fun next(): Message {
        val cursor = messageCursor
        if (cursor == null || cursor.isAfterLast) {
            throw NoSuchElementException()
        }
        val message: Message
        try {
            message = Message.fromCursor(cursor, conversation)
        } catch (e: IOException) {
            cursor.close()
            throw RuntimeException(e)
        }
        cursor.moveToNext()
        return message
    }

    override fun remove() {
        throw UnsupportedOperationException()
    }

    @Suppress("deprecation")
    protected fun finalize() {
        messageCursor?.close()
        database.close()
    }
}
