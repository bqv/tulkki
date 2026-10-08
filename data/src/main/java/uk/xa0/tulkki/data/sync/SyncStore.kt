package uk.xa0.tulkki.data.sync

import uk.xa0.tulkki.data.HistoryDatabase
import uk.xa0.tulkki.data.messages.ConversationDao
import uk.xa0.tulkki.data.messages.MessagesQueries

/**
 * The file, as [SyncEngine] sees it: the four DAOs it drives and the one statement it runs itself.
 *
 * <p><strong>Why this is a seam and not `HistoryDatabase` directly.</strong> The engine must never
 * touch the file on its caller's thread - the defect this exists for is a `RoomDatabase`
 * "Cannot access database on the main thread" thrown from `SyncEngine.persist` on the owner's phone -
 * and a seam is what lets a JVM cell drive the engine with a recording executor and a store that
 * records <em>which thread</em> touched it. A `HistoryDatabase` cannot be built on the host at all,
 * so without this the rule could only be asserted by reading the source.
 *
 * <p>Everything here is `:data`'s own, so the seam leaks nothing outward: the engine stays public,
 * this interface stays internal, and `RoomSyncStore` is the one implementation the app builds.
 */
internal interface SyncStore {

    fun syncCursorDao(): SyncCursorDao

    fun syncGapDao(): SyncGapDao

    fun syncConversationDao(): SyncConversationDao

    fun conversationDao(): ConversationDao

    /**
     * Marks one stored message's `delivery`: `ARCHIVE` when a catch-up carried it, `LIVE` when the
     * session did. The island event carries no uuid - that is the seam's declared shape - so the row
     * is named by the two facts it does carry, and a catch-up inserts its messages in one burst, which
     * is why the pair identifies the row in practice.
     */
    fun markDelivery(conversationUuid: String, timeSent: Long, marker: Long)
}

/** The engine's store over the one open database. */
internal class RoomSyncStore(private val db: HistoryDatabase) : SyncStore {

    override fun syncCursorDao(): SyncCursorDao = db.syncCursorDao()

    override fun syncGapDao(): SyncGapDao = db.syncGapDao()

    override fun syncConversationDao(): SyncConversationDao = db.syncConversationDao()

    override fun conversationDao(): ConversationDao = db.conversationDao()

    override fun markDelivery(conversationUuid: String, timeSent: Long, marker: Long) {
        db.openHelper.writableDatabase.execSQL(
            "UPDATE " +
                MessagesQueries.TABLE +
                " SET " +
                MessagesQueries.DELIVERY +
                " = ? WHERE " +
                MessagesQueries.CONVERSATION +
                " = ? AND " +
                MessagesQueries.TIME_SENT +
                " = ?",
            arrayOf<Any>(marker, conversationUuid, timeSent),
        )
    }
}

/**
 * Where a dispatched event's sweep goes: one callback, on the engine's own thread, after the body
 * that owed it has finished.
 *
 * <p>It is public and the store below is not, and that is the boundary rather than an accident: the
 * engine's dispatcher runs on a thread the app does not own, so the app must be able to hand the work
 * on, while what the engine does with the file is `:data`'s alone. The engine may not name the
 * translation queue (`:data` cannot import `:translation`), so the uuids come back through this and
 * `:app`'s composition root passes them to the queue - the same shape `TranslationService.Client` and
 * `TranslationHooks.Listener` use.
 */
fun interface SweepSink {
    fun onSweep(account: String, messageUuids: List<String>)
}
