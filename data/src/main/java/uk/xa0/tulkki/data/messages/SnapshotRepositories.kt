package uk.xa0.tulkki.data.messages

import android.content.Context
import androidx.sqlite.db.SimpleSQLiteQuery
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import uk.xa0.tulkki.data.DatabaseBackend
import uk.xa0.tulkki.data.HistoryDatabase

/**
 * The conversation read models' producer (S5-6): the one public door from the file to
 * [ConversationSnapshot].
 *
 * <p>`docs/MIGRATION.md`, "Design: the data layer" §2.7 puts the read model on the boundary and
 * §2.1's diagram gives it its shape - "`Flow<ConversationSnapshot>` / `Flow<List<MessageSnapshot>>`
 * ... **`Flow`s, never `LiveData`, never a `Cursor`**". The entity is `internal`, so this is the
 * only way a screen sees the row; the SQL it reads lives in [ConversationQueries] and
 * [ConversationDao], and the statement-and-constant instrument that keeps those two equal is
 * `MessagesDaoTest`.
 *
 * <p>**The pointer and the row are one read.** Both list reads run `ConversationQueries.LIST_FOR_ACCOUNT`
 * - the row's columns and its last-message pointer in one statement, which is `Compose UI` §2.3
 * invariant 4's "one joined query per emission, not N+1" - so the synchronous list and the `Flow`
 * cannot drift. Nothing here reads a body for the list: the pointer is an id and an instant, and the
 * preview is the projector's second read of the one row it names.
 */
class ConversationSnapshots private constructor(private val db: HistoryDatabase) {

    /** One conversation by its own key, with no pointer: the chat screen's own row. */
    fun read(id: String): ConversationSnapshot? =
        db.conversationDao()
            .byUuid(id)
            ?.asSnapshot(lastMessageId = null, lastMessageAt = null)

    /** Every conversation of one account, each with its last-message pointer resolved. */
    fun readAccount(account: String): List<ConversationSnapshot> =
        db.conversationDao().listForAccount(account).map { it.asSnapshot() }

    /** One conversation, re-emitted whenever its row changes. */
    fun watch(id: String): Flow<ConversationSnapshot?> =
        db.conversationDao().watchByUuid(id).map {
            it?.asSnapshot(lastMessageId = null, lastMessageAt = null)
        }

    /** The conversation list, re-emitted whenever one of the account's rows changes (S5-6). */
    fun watchAccount(account: String): Flow<List<ConversationSnapshot>> =
        db.conversationDao().watchAccount(account).map { rows -> rows.map { it.asSnapshot() } }

    companion object {

        /** Over the one open database, as the sync engine is: the caller names the context, not the file. */
        @JvmStatic
        fun get(context: Context): ConversationSnapshots =
            ConversationSnapshots(HistoryDatabase.opened(context))
    }
}

/**
 * The message read models' producer (S5-6): the one public door from the file to [MessageSnapshot].
 *
 * <p>Same rules as [ConversationSnapshots] - public read model, `internal` entity, `Flow` and never a
 * `Cursor`. [read] is the reading order `MessagesDao.BY_CONVERSATION` already publishes; [watch]
 * re-emits that list whenever a row of the conversation changes, which is the flow a Compose chat
 * screen collects.
 */
class MessageSnapshots private constructor(private val db: HistoryDatabase) {

    /** One conversation's rows, oldest first, as `MessagesDao.BY_CONVERSATION` orders them. */
    fun read(conversation: String): List<MessageSnapshot> =
        db.messagesDao().byConversation(conversation).map { it.asSnapshot() }

    /** One row by its own key - the pointer's second read, and the quote's. */
    fun readOne(id: String): MessageSnapshot? = db.messagesDao().byUuid(id)?.asSnapshot()

    /** One conversation's rows, re-emitted whenever one of them changes. */
    fun watch(conversation: String): Flow<List<MessageSnapshot>> =
        db.messagesDao().watchByConversation(conversation).map { rows ->
            rows.map { it.asSnapshot() }
        }

    /**
     * The search read, as read models (S5-6): this is the port of the one consumer that used to run
     * `DatabaseBackend.getMessageSearchCursor` and walk its `Cursor`.
     *
     * <p>The statement is the production one, `DatabaseBackend.buildMessageSearchQuery` - the seam
     * S4-14 left so a JVM test can execute the query the app runs. It matches `translated_body`, so a
     * covered row has nothing to match and no result can leak an original; this read does not change
     * that and must not.
     *
     * <p>Two statements, one per half: the FTS read answers the ids first, and `byUuids` then fetches
     * every row in one more (never one query per hit). The ids' order is restored here, because `IN`
     * promises no order, and it is the order the search query asked for: `timeSent DESC`.
     */
    fun search(term: List<String>, conversation: String?): List<MessageSnapshot> {
        val query = DatabaseBackend.buildMessageSearchQuery(term, conversation)
        val ids = db.messagesDao().search(SimpleSQLiteQuery(query.sql, query.args))
        if (ids.isEmpty()) {
            return emptyList()
        }
        val rows = db.messagesDao().byUuids(ids).associateBy { it.uuid }
        return ids.mapNotNull { rows[it]?.asSnapshot() }
    }

    companion object {

        @JvmStatic fun get(context: Context): MessageSnapshots = MessageSnapshots(HistoryDatabase.opened(context))
    }
}
