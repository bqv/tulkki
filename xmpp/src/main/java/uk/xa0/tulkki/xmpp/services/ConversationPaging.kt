package uk.xa0.tulkki.xmpp.services

import android.util.Log
import uk.xa0.tulkki.app.generator.AbstractGenerator
import uk.xa0.tulkki.xmpp.Config
import uk.xa0.tulkki.xmpp.R
import uk.xa0.tulkki.xmpp.mam.MamReference
import uk.xa0.tulkki.xmpp.refs.ConversationRef
import uk.xa0.tulkki.xmpp.refs.ConversationalRef
import java.util.concurrent.Executor

/**
 * Tulkki: conversation navigation - the history jump and the archive paging - lifted out of
 * `XmppConnectionService`.
 *
 * Both methods hand their work to the service's **private** database-reader executor (chunk `C02`),
 * which does not move with this chunk, so it travels in as an [Executor]: the Java queued the same
 * task on it and answered the listener from that thread, and it still does. Everything else the two
 * bodies touch is already public on the service - `databaseBackend`, `getMessageArchiveService()`
 * - so nothing had a visibility widened.
 *
 * The guard order is the Java's: [loadMoreMessages] asks the archive service whether a query is
 * already in progress **first**, then answers the zero timestamp, and only then logs and queues. The
 * `messages != null` test in [jumpToMessage] is dead in the Java too - the value is a fresh
 * `ArrayList` - and is kept as the Java wrote it rather than tidied, so the two bodies still read
 * the same.
 */
object ConversationPaging {

    @JvmStatic
    fun jumpToMessage(
        service: XmppConnectionService,
        conversation: ConversationRef,
        uuid: String?,
        listener: JumpToMessageListener,
        reader: Executor,
    ) {
        val runnable = Runnable {
            val messages = ArrayList((service.databaseBackend ?: throw NullPointerException("database backend is not open")).getMessagesNearUuid(conversation, 30, uuid))
            @Suppress("SENSELESS_COMPARISON")
            if (messages != null && messages.isNotEmpty()) {
                conversation.jumpToHistoryPart(messages)
                listener.onSuccess()
            } else {
                listener.onNotFound()
            }
        }
        reader.execute(runnable)
    }

    @JvmStatic
    fun loadMoreMessages(
        service: XmppConnectionService,
        conversation: ConversationRef,
        timestamp: Long,
        isForward: Boolean,
        callback: OnMoreMessagesLoaded,
        reader: Executor,
    ) {
        if (service.getMessageArchiveService().queryInProgress(conversation, callback)) {
            return
        } else if (timestamp == 0L) {
            return
        }
        Log.d(
            Config.LOGTAG,
            "load more messages for ${conversation.getName()} prior to ${AbstractGenerator.getTimestamp(timestamp)}",
        )
        if (isForward) {
            Log.d(
                Config.LOGTAG,
                "load more messages for ${conversation.getName()} after ${AbstractGenerator.getTimestamp(timestamp)}",
            )
        } else {
            Log.d(
                Config.LOGTAG,
                "load more messages for ${conversation.getName()} prior to ${AbstractGenerator.getTimestamp(timestamp)}",
            )
        }
        val runnable = Runnable {
            val account =
                conversation.getAccount() ?: throw NullPointerException("conversation has no account")
            val messages = ArrayList(
                (service.databaseBackend ?: throw NullPointerException("database backend is not open")).getMessages(conversation, Config.PAGE_SIZE, timestamp, isForward),
            )
            if (messages.size > 0) {
                if (isForward) {
                    conversation.addAll(-1, messages, true)
                } else {
                    conversation.addAll(0, messages, true)
                }
                callback.onMoreMessagesLoaded(messages.size, conversation)
            } else if (!isForward
                && conversation.hasMessagesLeftOnServer()
                && account.isOnlineAndConnected()
                && conversation.getLastClearHistory().getTimestamp() == 0L
            ) {
                val mamAvailable: Boolean
                if (conversation.getMode() == ConversationalRef.MODE_SINGLE) {
                    mamAvailable =
                        (account.getXmppConnection()
                            ?: throw NullPointerException("account has no connection"))
                            .getFeatures().mam() &&
                            !conversation.getContact().isBlocked()
                } else {
                    mamAvailable = conversation.getMucOptions().mamSupport()
                }
                if (mamAvailable) {
                    val query = service.getMessageArchiveService().query(
                        conversation,
                        MamReference(0),
                        timestamp,
                        false,
                    )
                    if (query != null) {
                        query.setCallback(callback)
                        callback.informUser(R.string.fetching_history_from_server)
                    } else {
                        callback.informUser(R.string.not_fetching_history_retention_period)
                    }
                }
            }
        }
        reader.execute(runnable)
    }
}
