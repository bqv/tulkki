package uk.xa0.tulkki.xmpp.services

import android.app.Notification
import uk.xa0.tulkki.libs.Jid
import uk.xa0.tulkki.xmpp.jingle.AbstractJingleConnection
import uk.xa0.tulkki.xmpp.jingle.Media
import uk.xa0.tulkki.xmpp.refs.AccountRef
import uk.xa0.tulkki.xmpp.refs.ConversationRef
import uk.xa0.tulkki.xmpp.refs.MessageRef

/**
 * Tulkki: the notification service, in island vocabulary.
 *
 * <p>Method for method with {@code uk.xa0.tulkki.app.services.NotificationService}, which implements it:
 * the call sites did not move, only the *name* the island writes for the object. The three id
 * constants travel as accessors because an interface's fields are constants and these are the
 * class's; {@link #catchupLock} is the static monitor {@code XmppConnection} synchronises on.
 */
interface NotificationPort {

    fun catchupLock(): Any

    fun foregroundNotificationId(): Int

    fun ongoingCallNotificationId(): Int

    fun ongoingVideoTranscodingNotificationId(): Int

    fun initializeChannels()

    fun push(message: MessageRef)

    // -- part 12 and part 16: the notification calls the island and the parser make ---------------
    //
    // Part 12 declared a ref-typed overload of each of these *beside* the model-typed one,
    // because `MessageParser` held refs while this file still spoke the model type. Part 16
    // removes the model type from this file, so each pair collapses into one declaration and the
    // port is the ref's alone. `uk.xa0.tulkki.app.services.NotificationService` implements them by
    // casting once and delegating - which is why no `:ui` file moves.
    //
    // `occupantId` is nullable because the non-MUC reaction path (`MessageParser`) passes a literal
    // `null`: the Java's platform `String` tolerated it and the implementation hands it straight to
    // `Message.setOccupantId`, which takes `String?`.

    fun push(
        reactingTo: MessageRef,
        counterpart: Jid,
        occupantId: String?,
        newReactions: Collection<String>,
    )

    fun pushFromBacklog(message: MessageRef)

    fun pushFromDirectReply(message: MessageRef)

    fun pushFailedDelivery(message: MessageRef)

    fun pushMissedCallNow(message: MessageRef)

    fun possiblyMissedCall(sessionId: String, message: MessageRef)

    fun clear(conversation: ConversationRef)

    fun clearMessages()

    fun clearMessages(conversation: ConversationRef)

    fun clearMissedCall(message: MessageRef)

    fun clearMissedCalls()

    fun clearMissedCalls(conversation: ConversationRef)

    fun hasNewMissedCalls(): Boolean

    fun markRetracted(message: MessageRef)

    fun updateNotification()

    fun updateErrorNotification()

    /** The ongoing-transcoding progress, drawn while a file attachment is being prepared. */
    fun updateFileAddingNotification(current: Int, message: MessageRef)

    fun finishBacklog()

    /**
     * Tulkki: 3.7 C5-D - the ref, because `XmppConnection` and `MessageArchiveService` are the
     * only three callers and all of them now hold an `AccountRef`. The implementation in `:app`
     * needed no cast: both helpers compare the account by identity against
     * `Conversation.getAccount()` and read `getJid()`, which the ref answers.
     */
    fun finishBacklog(notify: Boolean, account: AccountRef?)

    fun setOpenConversation(conversation: ConversationRef?)

    fun setIsInForeground(foreground: Boolean)

    @JvmSuppressWildcards
    fun startRinging(id: AbstractJingleConnection.Id, media: Set<Media>)

    fun stop()

    fun stopSoundAndVibration(): Boolean

    fun cancelIncomingCallNotification()

    fun showLiveLocationNotification(conversationUuid: String?)

    fun cancelLiveLocationNotification()

    fun getOngoingCallNotification(ongoingCall: OngoingCall): Notification

    fun getIndeterminateVideoTranscoding(): Notification

    fun createForegroundNotification(): Notification

    fun notify(id: Int, notification: Notification)

    fun cancel(id: Int)
}
