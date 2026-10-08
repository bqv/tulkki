package uk.xa0.tulkki.ui.conversationlist

import android.content.Context
import uk.xa0.tulkki.data.AccountRegistry
import uk.xa0.tulkki.data.messages.ConversationSnapshot
import uk.xa0.tulkki.data.messages.ConversationSnapshots
import uk.xa0.tulkki.data.messages.MessageSnapshot
import uk.xa0.tulkki.data.messages.MessageSnapshots
import uk.xa0.tulkki.data.model.Account

/**
 * The reads both conversation-list hosts run: `ConversationListFragment` and `ShareWithActivity`.
 *
 * <p>It exists because the second host made the first host's read a shared thing, and it is a plain
 * object rather than a member of [ConversationListHost] because that class's own comment is explicit -
 * "**The read is not here.**" - and the split it draws is the right one: the host owns the *decision* to
 * read, and the statements are `:data`'s and are the same whoever asks.
 *
 * <p>Nothing here is JVM-reachable: [rows] opens SQLite, so its cell is the gate's build and the two
 * hosts' own runs, and what a cell can hold - that the order and the projection agree about a row - is
 * `ConversationOrderTest`'s and `ConversationProjectionTest`'s.
 */
object ConversationListRead {

    /** What one read returned, in the shape [ConversationListHost.assemble] asks for. */
    class Rows(val snapshots: List<ConversationSnapshot>, val lastMessages: Map<String, MessageSnapshot>)

    /**
     * One list query per account, with each row's pointer already resolved, and one more query for the
     * single message each pointer names - never a body copied onto the list (§2.3 invariant 4, so that
     * concealing the row conceals the preview by construction).
     *
     * @param allowed the uuids the caller's own filter keeps. It is the drawer's answer for the list and
     *     the share intent's capability filter for the picker; a row outside it is not read at all.
     * @param accounts the accounts those rows name.
     */
    @JvmStatic
    fun rows(context: Context, allowed: Set<String>, accounts: Set<String>): Rows {
        val conversationRead = ConversationSnapshots.get(context)
        val messageRead = MessageSnapshots.get(context)
        val snapshots = ArrayList<ConversationSnapshot>()
        val lastMessages = HashMap<String, MessageSnapshot>()
        for (account in accounts) {
            for (snapshot in conversationRead.readAccount(account)) {
                if (!allowed.contains(snapshot.id)) {
                    continue
                }
                snapshots.add(snapshot)
                val pointer = snapshot.lastMessageId
                if (pointer != null) {
                    val message = messageRead.readOne(pointer)
                    if (message != null) {
                        lastMessages[pointer] = message
                    }
                }
            }
        }
        return Rows(snapshots, lastMessages)
    }

    /**
     * What the account is doing, for §3.5's status line: an account that is online answers for the whole
     * screen, one that is connecting is the next best answer, and anything else is not connected.
     *
     * <p>It is a read of the accounts rather than of the file, which is why it is here beside them: both
     * hosts draw the same line from the same answer, and neither keeps a second opinion.
     */
    @JvmStatic
    fun connection(): UiConnection {
        var connecting = false
        for (account in AccountRegistry.get().getAccounts()) {
            val state = account.getStatus()
            if (state == Account.State.ONLINE) {
                return UiConnection.CONNECTED
            }
            if (state == Account.State.CONNECTING) {
                connecting = true
            }
        }
        return if (connecting) UiConnection.CONNECTING else UiConnection.DISCONNECTED
    }
}
