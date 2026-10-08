package uk.xa0.tulkki.xmpp.services

import android.media.MediaMetadata
import android.os.Bundle
import android.util.Log
import me.leolin.shortcutbadger.ShortcutBadger
import uk.xa0.tulkki.xmpp.Config
import uk.xa0.tulkki.libs.Jid
import uk.xa0.tulkki.xmpp.models.stanza.Iq
import uk.xa0.tulkki.xmpp.pep.PublishOptions
import uk.xa0.tulkki.xmpp.refs.AccountRef
import uk.xa0.tulkki.xmpp.refs.ConversationRef
import uk.xa0.tulkki.xmpp.refs.ConversationalRef
import uk.xa0.tulkki.xmpp.refs.MessageRef
import uk.xa0.tulkki.xml.Namespace
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * Tulkki: the read-state machine and the "now playing" PEP publisher - lifted out of
 * `XmppConnectionService`.
 *
 * The chunk's three fields move **whole** here: nothing outside these methods read the cached
 * `unreadCount`, `userTuneUpdateExecutor` or `pendingUserTuneUpdate`. The Java's
 * `synchronized updateUnreadCountBadge()` locked the service instance, so the lock travels with the
 * body as `synchronized(service)` - the same monitor, so the cached count keeps its protection.
 *
 * C70's private `mNotificationService` (nullable) and C02's private database-writer executor travel
 * in by hand, so the one branch that dereferenced the bare field still raises the Java's NPE rather
 * than a `checkNotNullParameter` at the seam. C16's `scheduleNextExpiry` and C47's `unreadCount` are
 * public. C26b's private `pushNodeAndEnforcePublishOptions` is not reached: as `C36b` did with
 * `ResendPlumbing`, the moved body calls the public `BookmarkPublication` home directly, so that
 * private delegation is deleted from the service rather than widened.
 *
 * The Guava `Iterables.getLast(Collections2.filter(...), null)` is `lastOrNull { ... }`, exactly the
 * same last matching element or null, and `Strings.isNullOrEmpty` is Kotlin's `isNullOrEmpty()`.
 */
object ReadMarkers {

    private val userTuneUpdateExecutor: ScheduledExecutorService =
        Executors.newSingleThreadScheduledExecutor()
    private var pendingUserTuneUpdate: ScheduledFuture<*>? = null
    private var unreadCount = -1

    @JvmStatic
    fun markRead(
        service: XmppConnectionService,
        conversation: ConversationRef,
        upToUuid: String?,
        dismiss: Boolean,
        notificationService: NotificationPort?,
        databaseWriterExecutor: Executor,
    ): List<MessageRef> {
        if (dismiss) {
            notificationService!!.clear(conversation)
        }
        val readMessages = conversation.markRead(upToUuid)
        if (readMessages.size > 0) {
            val runnable = Runnable {
                for (message in readMessages) {
                    if (message.getEphemeralTimer() > 0 && message.getExpireAt() == 0L) {
                        message.setExpireAt(
                            System.currentTimeMillis() + message.getEphemeralTimer() * 1000L,
                        )
                    }
                    (service.databaseBackend ?: throw NullPointerException("database backend is not open")).updateMessage(message, false)
                }
                service.scheduleNextExpiry()
            }
            databaseWriterExecutor.execute(runnable)
            service.updateConversationUi()
            service.updateUnreadCountBadge()
            return readMessages
        } else {
            return readMessages
        }
    }

    @JvmStatic
    @JvmSuppressWildcards
    fun markNotificationDismissed(
        service: XmppConnectionService,
        messages: List<MessageRef>,
        databaseWriterExecutor: Executor,
    ) {
        val runnable = Runnable {
            for (message in messages) {
                message.markNotificationDismissed()
                (service.databaseBackend ?: throw NullPointerException("database backend is not open")).updateMessage(message, false)
            }
        }
        databaseWriterExecutor.execute(runnable)
    }

    @JvmStatic
    fun updateUnreadCountBadge(service: XmppConnectionService) {
        synchronized(service) {
            val count = service.unreadCount()
            if (unreadCount != count) {
                Log.d(Config.LOGTAG, "update unread count to $count")
                if (count > 0) {
                    ShortcutBadger.applyCount(service.getApplicationContext(), count)
                } else {
                    ShortcutBadger.removeCount(service.getApplicationContext())
                }
                unreadCount = count
            }
        }
    }

    @JvmStatic
    fun sendReadMarker(
        service: XmppConnectionService,
        conversation: ConversationRef,
        upToUuid: String?,
        notificationService: NotificationPort?,
        databaseWriterExecutor: Executor,
    ) {
        val isPrivateAndNonAnonymousMuc =
            conversation.getMode() == ConversationalRef.MODE_MULTI &&
                conversation.isPrivateAndNonAnonymous()
        val readMessages =
            markRead(
                service,
                conversation,
                upToUuid,
                true,
                notificationService,
                databaseWriterExecutor,
            )
        if (readMessages.isEmpty()) {
            return
        }
        val account = conversation.getAccount() ?: throw NullPointerException("conversation has no account")
        val connection = account.getXmppConnection()
        service.updateConversationUi()
        val last =
            readMessages.lastOrNull {
                !it.isPrivateMessage() && it.getStatus() == MessageRef.STATUS_RECEIVED
            }
        if (last == null) {
            return
        }

        val sendDisplayedMarker =
            service.confirmMessages() &&
                (last.trusted() || isPrivateAndNonAnonymousMuc) &&
                last.getRemoteMsgId() != null &&
                (last.isMarkable() || isPrivateAndNonAnonymousMuc)
        val serverAssist = connection != null && connection.getFeatures().mdsServerAssist()

        val stanzaId = last.getServerMsgId()

        if (sendDisplayedMarker && serverAssist) {
            val mdsDisplayed = service.getIqGenerator().mdsDisplayed(stanzaId, conversation)
            val packet = service.getMessageGenerator().confirm(last)
            packet.addChild(mdsDisplayed)
            if (!last.isPrivateMessage()) {
                packet.setTo((packet.getTo() ?: throw NullPointerException()).asBareJid())
            }
            Log.d(Config.LOGTAG, "${account.getJid().asBareJid()}: server assisted $packet")
            service.sendMessagePacket(account, packet)
        } else {
            publishMds(service, last)
            // read markers will be sent after MDS to flush the CSI stanza queue
            if (sendDisplayedMarker) {
                Log.d(
                    Config.LOGTAG,
                    "${(conversation.getAccount() ?: throw NullPointerException("conversation has no account")).getJid().asBareJid()}: sending displayed marker to " +
                        last.getCounterpart().toString(),
                )
                val packet = service.getMessageGenerator().confirm(last)
                service.sendMessagePacket(account, packet)
            }
        }
    }

    private fun publishMds(service: XmppConnectionService, message: MessageRef?) {
        val stanzaId = message?.getServerMsgId()
        if (stanzaId.isNullOrEmpty()) {
            return
        }
        val conversation = message!!.getConversation() as? ConversationRef ?: return
        val account = conversation.getAccount() ?: throw NullPointerException("conversation has no account")
        val connection = account.getXmppConnection()
        if (connection == null || !connection.getFeatures().mds()) {
            return
        }
        val itemId: Jid
        if (message.isPrivateMessage()) {
            itemId = message.getCounterpart() ?: throw NullPointerException("message has no counterpart")
        } else {
            itemId = (conversation.getJid() ?: throw NullPointerException("conversation has no jid")).asBareJid()
        }
        Log.d(Config.LOGTAG, "publishing mds for $itemId/$stanzaId")
        publishMds(service, account, itemId, stanzaId, conversation)
    }

    private fun publishMds(
        service: XmppConnectionService,
        account: AccountRef,
        itemId: Jid,
        stanzaId: String,
        conversation: ConversationRef,
    ) {
        val item = service.getIqGenerator().mdsDisplayed(stanzaId, conversation)
        BookmarkPublication.pushNodeAndEnforcePublishOptions(
            service,
            account,
            Namespace.MDS_DISPLAYED,
            item,
            itemId.toString(),
            PublishOptions.persistentWhitelistAccessMaxItems(),
        )
    }

    @JvmStatic
    fun publishUserTuneAsync(service: XmppConnectionService, metadata: MediaMetadata): Boolean {
        val artist = metadata.getText(MediaMetadata.METADATA_KEY_ARTIST)
        val title = metadata.getText(MediaMetadata.METADATA_KEY_TITLE)
        val album = metadata.getText(MediaMetadata.METADATA_KEY_ALBUM)

        // Media without artist/title/album are likely not music, abort updating this track.
        if (artist == null || title == null || album == null) {
            return false
        }

        if (pendingUserTuneUpdate != null && !pendingUserTuneUpdate!!.isDone) {
            pendingUserTuneUpdate!!.cancel(false)
        }

        // XEP-0118 Implementation Notes
        // To prevent a large number of updates when a user is skipping through tracks, an
        // implementation SHOULD wait several seconds before publishing new tune information.
        pendingUserTuneUpdate =
            userTuneUpdateExecutor.schedule(
                Runnable {
                    for (account in XmppConnectionService.dataStatics().accounts().getAccounts()) {
                        val options: Bundle? = null

                        Log.d(
                            Config.LOGTAG,
                            "${account.getJid().asBareJid()}: publishing user tune. options=$options",
                        )

                        val packet = service.getIqGenerator().publishUserTune(metadata, options)
                        service.sendIqPacket(
                            account,
                            packet,
                        ) { result ->
                            if (result.getType() != Iq.Type.RESULT) {
                                val error = result.findChild("error")
                                Log.d(
                                    Config.LOGTAG,
                                    "${account.getJid().asBareJid()}: server rejected user tune " +
                                        (error?.toString() ?: ""),
                                )
                            }
                        }
                    }
                },
                3,
                TimeUnit.SECONDS,
            )

        Log.d(Config.LOGTAG, "Update user tune: $artist - $title")

        return true
    }

    @JvmStatic
    fun stopPublishingUserTuneAsync(service: XmppConnectionService) {
        if (pendingUserTuneUpdate != null && !pendingUserTuneUpdate!!.isDone) {
            pendingUserTuneUpdate!!.cancel(false)
        }
        pendingUserTuneUpdate =
            userTuneUpdateExecutor.schedule(
                Runnable {
                    for (account in XmppConnectionService.dataStatics().accounts().getAccounts()) {
                        if (!account.isOnlineAndConnected()) {
                            continue
                        }
                        Log.d(
                            Config.LOGTAG,
                            "${account.getJid().asBareJid()}: revoking user tune",
                        )
                        service.sendIqPacket(
                            account,
                            service.getIqGenerator().publishUserTune(),
                            null,
                        )
                    }
                },
                3,
                TimeUnit.SECONDS,
            )
    }
}
