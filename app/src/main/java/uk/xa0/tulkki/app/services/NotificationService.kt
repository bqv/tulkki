package uk.xa0.tulkki.app.services

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationChannelGroup
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.pm.ShortcutManager
import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.Typeface
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.preference.PreferenceManager
import android.provider.Settings
import android.text.SpannableString
import android.text.style.StyleSpan
import android.util.DisplayMetrics
import android.util.Log
import android.util.TypedValue
import androidx.annotation.Nullable
import androidx.annotation.RequiresApi
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.Person
import androidx.core.app.RemoteInput
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.graphics.drawable.IconCompat
import com.google.common.base.Joiner
import com.google.common.base.Optional
import com.google.common.base.Splitter
import com.google.common.base.Strings
import com.google.common.collect.ImmutableMap
import com.google.common.collect.Iterables
import com.google.common.primitives.Ints
import uk.xa0.tulkki.app.BuildConfig
import uk.xa0.tulkki.app.utils.Compatibility
import uk.xa0.tulkki.app.utils.Compatibility.s
import uk.xa0.tulkki.data.AccountRegistry
import uk.xa0.tulkki.data.AppSettings
import uk.xa0.tulkki.data.FileBackend
import uk.xa0.tulkki.data.FileBackends
import uk.xa0.tulkki.data.model.Account
import uk.xa0.tulkki.data.model.Contact
import uk.xa0.tulkki.data.model.Conversation
import uk.xa0.tulkki.data.model.Conversational
import uk.xa0.tulkki.data.model.Message
import uk.xa0.tulkki.data.model.MucOptions
import uk.xa0.tulkki.translation.ConversationName
import uk.xa0.tulkki.translation.DisplayedBody
import uk.xa0.tulkki.translation.Interpreter
import uk.xa0.tulkki.translation.TranslationSettings
import uk.xa0.tulkki.ui.ConversationListActivity
import uk.xa0.tulkki.ui.EditAccountActivity
import uk.xa0.tulkki.ui.RtpSessionActivity
import uk.xa0.tulkki.ui.TimePreference
import uk.xa0.tulkki.ui.TranslationText
import uk.xa0.tulkki.ui.XmppActivity.Companion.EXTRA_ACCOUNT
import uk.xa0.tulkki.ui.utils.GeoHelper
import uk.xa0.tulkki.ui.utils.UIHelper
import uk.xa0.tulkki.xmpp.Config
import uk.xa0.tulkki.libs.Jid
import uk.xa0.tulkki.xmpp.R
import uk.xa0.tulkki.xmpp.XmppConnection
import uk.xa0.tulkki.xmpp.jingle.AbstractJingleConnection
import uk.xa0.tulkki.xmpp.jingle.Media
import uk.xa0.tulkki.xmpp.refs.AccountRef
import uk.xa0.tulkki.xmpp.refs.ConversationRef
import uk.xa0.tulkki.xmpp.refs.MessageRef
import uk.xa0.tulkki.xmpp.services.XmppConnectionService
import uk.xa0.tulkki.xmpp.utils.AccountUtils
import uk.xa0.tulkki.xmpp.utils.TorServiceUtils
import java.io.File
import java.io.IOException
import java.util.Calendar
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import java.util.regex.Pattern

class NotificationService(private val mXmppConnectionService: XmppConnectionService) :
        uk.xa0.tulkki.xmpp.services.NotificationPort {

    private val notifications = LinkedHashMap<String, ArrayList<Message>>()
    private val mBacklogMessageCounter = HashMap<Conversation, AtomicInteger>()
    private val mMissedCalls = LinkedHashMap<Conversational, MissedCallsInfo>()
    private val possiblyMissedCalls = HashMap<String, Message>()
    private var mOpenConversation: Conversation? = null
    private var mIsInForeground = false
    private var mLastNotification = 0L

    /**
     * Tulkki: the pending delayed notification, and when the burst it belongs to started. One
     * runnable, re-posted for each message in the window, so a burst becomes one notification rather
     * than one per message - and the start time is what keeps the debounce bounded by
     * [TULKKI_NOTIFY_MAX_DELAY_MS].
     */
    private val mTulkkiNotifyHandler = Handler(Looper.getMainLooper())

    private var mTulkkiPendingNotify: Runnable? = null
    private var mTulkkiBurstStartedAt = 0L

    // -- 3.7 pair 4: the island's port ------------------------------------------------------------
    //
    // The island used to name this class for four of its constants and one static monitor; the
    // accessors below are those five, and every other method in this class already had the port's
    // signature, so `implements` cost nothing but the `override` on the constant readers.

    override fun catchupLock(): Any = CATCHUP_LOCK

    override fun foregroundNotificationId(): Int = FOREGROUND_NOTIFICATION_ID

    override fun ongoingCallNotificationId(): Int = ONGOING_CALL_NOTIFICATION_ID

    override fun ongoingVideoTranscodingNotificationId(): Int =
            ONGOING_VIDEO_TRANSCODING_NOTIFICATION_ID

    @RequiresApi(api = Build.VERSION_CODES.O)
    override fun initializeChannels() {
        val c: Context = mXmppConnectionService
        val notificationManager = c.getSystemService(NotificationManager::class.java)
        if (notificationManager == null) {
            return
        }

        notificationManager.deleteNotificationChannel("export")
        notificationManager.deleteNotificationChannel("incoming_calls")
        notificationManager.deleteNotificationChannel(INCOMING_CALLS_NOTIFICATION_CHANNEL)

        notificationManager.createNotificationChannelGroup(
                NotificationChannelGroup(
                        "status",
                        c.getString(uk.xa0.tulkki.ui.R.string.notification_group_status_information)))
        notificationManager.createNotificationChannelGroup(
                NotificationChannelGroup(
                        "chats", c.getString(uk.xa0.tulkki.ui.R.string.notification_group_messages)))
        notificationManager.createNotificationChannelGroup(
                NotificationChannelGroup(
                        "calls", c.getString(uk.xa0.tulkki.ui.R.string.notification_group_calls)))
        val foregroundServiceChannel =
                NotificationChannel(
                        "foreground",
                        c.getString(uk.xa0.tulkki.ui.R.string.foreground_service_channel_name),
                        NotificationManager.IMPORTANCE_MIN)
        foregroundServiceChannel.setDescription(
                c.getString(
                        uk.xa0.tulkki.ui.R.string.foreground_service_channel_description,
                        BuildConfig.APP_NAME))
        foregroundServiceChannel.setShowBadge(false)
        foregroundServiceChannel.setGroup("status")
        notificationManager.createNotificationChannel(foregroundServiceChannel)
        val errorChannel =
                NotificationChannel(
                        "error",
                        c.getString(uk.xa0.tulkki.ui.R.string.error_channel_name),
                        NotificationManager.IMPORTANCE_LOW)
        errorChannel.setDescription(c.getString(uk.xa0.tulkki.ui.R.string.error_channel_description))
        errorChannel.setShowBadge(false)
        errorChannel.setGroup("status")
        notificationManager.createNotificationChannel(errorChannel)

        val videoCompressionChannel =
                NotificationChannel(
                        "compression",
                        c.getString(uk.xa0.tulkki.ui.R.string.video_compression_channel_name),
                        NotificationManager.IMPORTANCE_LOW)
        videoCompressionChannel.setShowBadge(false)
        videoCompressionChannel.setGroup("status")
        notificationManager.createNotificationChannel(videoCompressionChannel)

        val exportChannel =
                NotificationChannel(
                        "backup",
                        c.getString(uk.xa0.tulkki.ui.R.string.backup_channel_name),
                        NotificationManager.IMPORTANCE_LOW)
        exportChannel.setShowBadge(false)
        exportChannel.setGroup("status")
        notificationManager.createNotificationChannel(exportChannel)

        createInitialIncomingCallChannelIfNecessary(c)

        val ongoingCallsChannel =
                NotificationChannel(
                        "ongoing_calls",
                        c.getString(uk.xa0.tulkki.ui.R.string.ongoing_calls_channel_name),
                        NotificationManager.IMPORTANCE_LOW)
        ongoingCallsChannel.setShowBadge(false)
        ongoingCallsChannel.setGroup("calls")
        notificationManager.createNotificationChannel(ongoingCallsChannel)

        val missedCallsChannel =
                NotificationChannel(
                        "missed_calls",
                        c.getString(uk.xa0.tulkki.ui.R.string.missed_calls_channel_name),
                        NotificationManager.IMPORTANCE_HIGH)
        missedCallsChannel.setShowBadge(true)
        missedCallsChannel.setSound(null, null)
        missedCallsChannel.setLightColor(LED_COLOR)
        missedCallsChannel.enableLights(true)
        missedCallsChannel.setGroup("calls")
        notificationManager.createNotificationChannel(missedCallsChannel)

        val messagesChannel = prepareMessagesChannel(mXmppConnectionService, MESSAGES_NOTIFICATION_CHANNEL)
        notificationManager.createNotificationChannel(messagesChannel)
        val silentMessagesChannel =
                NotificationChannel(
                        "silent_messages",
                        c.getString(uk.xa0.tulkki.ui.R.string.silent_messages_channel_name),
                        NotificationManager.IMPORTANCE_LOW)
        silentMessagesChannel.setDescription(
                c.getString(uk.xa0.tulkki.ui.R.string.silent_messages_channel_description))
        silentMessagesChannel.setShowBadge(true)
        silentMessagesChannel.setLightColor(LED_COLOR)
        silentMessagesChannel.enableLights(true)
        silentMessagesChannel.setGroup("chats")
        notificationManager.createNotificationChannel(silentMessagesChannel)

        val deliveryFailedChannel =
                NotificationChannel(
                        "delivery_failed",
                        c.getString(uk.xa0.tulkki.ui.R.string.delivery_failed_channel_name),
                        NotificationManager.IMPORTANCE_DEFAULT)
        deliveryFailedChannel.setShowBadge(false)
        deliveryFailedChannel.setSound(
                RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION),
                AudioAttributes.Builder()
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                        .build())
        deliveryFailedChannel.setGroup("chats")
        notificationManager.createNotificationChannel(deliveryFailedChannel)

        val liveLocationChannel =
                NotificationChannel(
                        "live_location",
                        c.getString(uk.xa0.tulkki.ui.R.string.live_location_channel_name),
                        NotificationManager.IMPORTANCE_LOW)
        liveLocationChannel.setShowBadge(false)
        liveLocationChannel.setGroup("status")
        notificationManager.createNotificationChannel(liveLocationChannel)
    }

    private fun notifyMessage(message: Message): Boolean {
        val conversation = message.getConversation() as Conversation
        val chatRequestsPref =
                mXmppConnectionService.getStringPreference(
                        "chat_requests", uk.xa0.tulkki.ui.R.string.default_chat_requests)
        return message.getStatus() == Message.STATUS_RECEIVED &&
                !conversation.isMuted() &&
                (conversation.alwaysNotify() ||
                        (wasHighlightedOrPrivate(message) ||
                                (conversation.notifyReplies() && wasReplyToMe(message)))) &&
                !conversation.isChatRequest(chatRequestsPref) &&
                message.getType() != Message.TYPE_RTP_SESSION
    }

    private fun notifyMissedCall(message: Message): Boolean {
        return message.getType() == Message.TYPE_RTP_SESSION &&
                message.getStatus() == Message.STATUS_RECEIVED
    }

    private fun isQuietHours(account: Account?): Boolean =
            isQuietHours(mXmppConnectionService, account)

    fun pushFromBacklog(message: Message) {
        if (notifyMessage(message)) {
            synchronized(notifications) {
                getBacklogMessageCounter(message.getConversation() as Conversation).incrementAndGet()
                pushToStack(message)
            }
        } else if (notifyMissedCall(message)) {
            synchronized(mMissedCalls) {
                pushMissedCall(message)
            }
        }
    }

    private fun getBacklogMessageCounter(conversation: Conversation): AtomicInteger {
        synchronized(mBacklogMessageCounter) {
            if (!mBacklogMessageCounter.containsKey(conversation)) {
                mBacklogMessageCounter.put(conversation, AtomicInteger(0))
            }
            return mBacklogMessageCounter.get(conversation)!!
        }
    }

    fun pushFromDirectReply(message: Message) {
        synchronized(notifications) {
            pushToStack(message)
            updateNotification(false)
        }
    }

    override fun finishBacklog(notify: Boolean, account: AccountRef?) {
        synchronized(notifications) {
            mXmppConnectionService.updateUnreadCountBadge()
            if (account == null || !notify) {
                updateNotification(notify)
            } else {
                val conversationList: List<String>
                val count: Int
                synchronized(this.mBacklogMessageCounter) {
                    conversationList = getBacklogConversationUuids(account)
                    count = getBacklogMessageCount(account)
                }
                updateNotification(count > 0, conversationList)
            }
        }
        synchronized(possiblyMissedCalls) {
            for (entry in possiblyMissedCalls.entries) {
                pushFromBacklog(entry.value)
            }
            possiblyMissedCalls.clear()
        }
        synchronized(mMissedCalls) {
            updateMissedCallNotifications(mMissedCalls.keys)
        }
    }

    private fun getBacklogConversationUuids(account: AccountRef): List<String> {
        val conversationList = ArrayList<String>()
        for (entry in mBacklogMessageCounter.entries) {
            if (entry.key.getAccount() === account) {
                // port-5: `Conversational.getUuid()` is nullable; a backlog entry with no uuid has no
                // notification key to answer, so it is left out rather than keyed on null.
                val uuid = entry.key.getUuid() ?: continue
                conversationList.add(uuid)
            }
        }
        return conversationList
    }

    private fun getBacklogMessageCount(account: AccountRef): Int {
        var count = 0
        val it = mBacklogMessageCounter.entries.iterator()
        while (it.hasNext()) {
            val entry = it.next()
            if (entry.key.getAccount() === account) {
                count += entry.value.get()
                it.remove()
            }
        }
        Log.d(
                Config.LOGTAG,
                account.getJid().asBareJid().toString() + ": backlog message count=" + count)
        return count
    }

    override fun finishBacklog() {
        finishBacklog(false, null)
    }

    private fun pushToStack(message: Message) {
        // Tulkki: no conversation uuid, nowhere to stack the message.
        val conversationUuid = message.getConversationUuid() ?: return
        if (notifications.containsKey(conversationUuid)) {
            notifications.get(conversationUuid)!!.add(message)
        } else {
            val mList = ArrayList<Message>()
            mList.add(message)
            notifications.put(conversationUuid, mList)
        }
    }

    // -------------------------------------------------------------------------------------------
    // Tulkki: 3.7 pair 9, part 12. `MessageParser` holds refs now, and this class is the island
    // port's implementation, so the five methods it calls answer for both static types. Each casts
    // once and delegates to the model-typed method below, which keeps serving every `:ui` and `:app`
    // caller unchanged - a `Message` argument still selects it, because a class is strictly more
    // specific than the interface it implements.

    override fun push(message: MessageRef) {
        push(message as Message)
    }

    override fun push(
            reactingTo: MessageRef,
            counterpart: Jid,
            occupantId: String?,
            newReactions: Collection<String>
    ) {
        push(reactingTo as Message, counterpart, occupantId, newReactions)
    }

    override fun pushFromBacklog(message: MessageRef) {
        pushFromBacklog(message as Message)
    }

    override fun possiblyMissedCall(sessionId: String, message: MessageRef) {
        possiblyMissedCall(sessionId, message as Message)
    }

    override fun markRetracted(message: MessageRef) {
        markRetracted(message as Message)
    }

    // Tulkki: 3.7 pair 9, part 16 - four more of the same shape, plus
    // `updateFileAddingNotification`. Part 12 only needed the five `MessageParser` calls; with the
    // island's own model type gone, every remaining port member the island calls has to answer for
    // the ref as well. A `Message` argument still selects the model method below.

    override fun pushFromDirectReply(message: MessageRef) {
        pushFromDirectReply(message as Message)
    }

    override fun pushFailedDelivery(message: MessageRef) {
        pushFailedDelivery(message as Message)
    }

    override fun pushMissedCallNow(message: MessageRef) {
        pushMissedCallNow(message as Message)
    }

    override fun clearMissedCall(message: MessageRef) {
        clearMissedCall(message as Message)
    }

    override fun updateFileAddingNotification(current: Int, message: MessageRef) {
        updateFileAddingNotification(current, message as Message)
    }

    fun push(message: Message) {
        synchronized(CATCHUP_LOCK) {
            val connection = message.getConversation()?.getAccount()?.getXmppConnection()
            if (connection != null && connection.isWaitingForSmCatchup()) {
                connection.incrementSmCatchupMessageCounter()
                pushFromBacklog(message)
            } else {
                pushNow(message)
            }
        }
    }

    fun push(
            reactingTo: Message,
            counterpart: Jid,
            occupantId: String?,
            newReactions: Collection<String>
    ) {
        if (newReactions.isEmpty()) return

        val message = reactingTo.reply()
        val quoteable = reactingTo.getQuoteableBody()
        if (quoteable == null && !reactingTo.isOOb()) return
        // Tulkki: the quoted parent has to be what the interface shows for it - the translation, or
        // a placeholder while it is still untranslated - because a notification is readable on a
        // locked screen.
        val quoted = UIHelper.getDisplayedBody(mXmppConnectionService, reactingTo)
        val parentTxt =
                if (reactingTo.isOOb()) "media"
                else
                        "'" +
                                (if (quoted.length > 35)
                                        quoted.subSequence(0, 35).toString() + "…"
                                else quoted) +
                                "'"
        message.appendBody(
                newReactions.joinToString(" ") +
                        " " +
                        mXmppConnectionService.getString(uk.xa0.tulkki.ui.R.string.reaction_to) +
                        " " +
                        parentTxt)
        // Tulkki: this notification text is assembled by the app rather than received from anyone, so
        // there is nothing to translate and it must not be covered. It is never stored or shown in a
        // conversation.
        message.setTranslationState(Message.TRANSLATION_SAME_LANGUAGE)
        message.setCounterpart(counterpart)
        message.setOccupantId(occupantId)
        message.setStatus(Message.STATUS_RECEIVED)
        synchronized(CATCHUP_LOCK) {
            val connection = message.getConversation()?.getAccount()?.getXmppConnection()
            if (connection != null && connection.isWaitingForSmCatchup()) {
                connection.incrementSmCatchupMessageCounter()
                pushFromBacklog(message)
            } else {
                pushNow(message)
            }
        }
    }

    fun pushFailedDelivery(message: Message) {
        val conversation = message.getConversation() as Conversation
        val isScreenLocked = !mXmppConnectionService.isScreenLocked()
        if (this.mIsInForeground &&
                isScreenLocked &&
                this.mOpenConversation === message.getConversation()) {
            Log.d(
                    Config.LOGTAG,
                    message.getConversation()?.getAccount()?.getJid()?.asBareJid().toString() +
                            ": suppressing failed delivery notification because conversation is" +
                            " open")
            return
        }
        val pendingIntent = createContentIntent(conversation)
        val notificationId = generateRequestCode(conversation, 0) + DELIVERY_FAILED_NOTIFICATION_ID
        val failedDeliveries = conversation.countFailedDeliveries()
        val notification =
                NotificationCompat.Builder(mXmppConnectionService, "delivery_failed")
                        .setContentTitle(conversation.getName())
                        .setAutoCancel(true)
                        .setSmallIcon(uk.xa0.tulkki.ui.R.drawable.ic_error_24dp)
                        .setContentText(
                                mXmppConnectionService
                                        .getResources()
                                        .getQuantityText(
                                                uk.xa0.tulkki.ui.R.plurals.some_messages_could_not_be_delivered,
                                                failedDeliveries))
                        .setGroup("delivery_failed")
                        .setContentIntent(pendingIntent)
                        .build()
        val summaryNotification =
                NotificationCompat.Builder(mXmppConnectionService, "delivery_failed")
                        .setContentTitle(
                                mXmppConnectionService.getString(uk.xa0.tulkki.ui.R.string.failed_deliveries))
                        .setContentText(
                                mXmppConnectionService
                                        .getResources()
                                        .getQuantityText(
                                                uk.xa0.tulkki.ui.R.plurals.some_messages_could_not_be_delivered,
                                                1024))
                        .setSmallIcon(uk.xa0.tulkki.ui.R.drawable.ic_error_24dp)
                        .setGroup("delivery_failed")
                        .setGroupSummary(true)
                        .setAutoCancel(true)
                        .build()
        notify(notificationId, notification)
        notify(DELIVERY_FAILED_NOTIFICATION_ID, summaryNotification)
    }

    @Synchronized
    override fun startRinging(id: AbstractJingleConnection.Id, media: Set<Media>) {
        if (isQuietHours(id.getContact().getAccount() as Account)) return

        showIncomingCallNotification(id, media, false)
    }

    private fun showIncomingCallNotification(
            id: AbstractJingleConnection.Id,
            media: Set<Media>,
            onlyAlertOnce: Boolean
    ) {
        val fullScreenIntent = Intent(mXmppConnectionService, RtpSessionActivity::class.java)
        fullScreenIntent.putExtra(EXTRA_ACCOUNT, id.account.getJid().asBareJid().toString())
        fullScreenIntent.putExtra(RtpSessionActivity.EXTRA_WITH, id.with.toString())
        fullScreenIntent.putExtra(RtpSessionActivity.EXTRA_SESSION_ID, id.sessionId)
        fullScreenIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        fullScreenIntent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val channelIteration =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    getCurrentIncomingCallChannelIteration(mXmppConnectionService).or(0)
                } else {
                    0
                }
        val channelId = INCOMING_CALLS_NOTIFICATION_CHANNEL_PREFIX + channelIteration
        Log.d(
                Config.LOGTAG,
                "showing incoming call notification on channel " +
                        channelId +
                        ", onlyAlertOnce=" +
                        onlyAlertOnce)
        val builder = NotificationCompat.Builder(mXmppConnectionService, channelId)
        val contact = id.getContact() as Contact
        builder.addPerson(getPerson(contact))
        val info = mXmppConnectionService.getShortcutService().getShortcutInfo(contact)
        builder.setShortcutInfo(info)
        if (Build.VERSION.SDK_INT >= 30) {
            mXmppConnectionService
                    .getSystemService(ShortcutManager::class.java)
                    .pushDynamicShortcut(info.toShortcutInfo())
        }
        if (AccountRegistry.get().getAccounts().size > 1) {
            builder.setSubText(contact.getAccount().getJid().asBareJid().toString())
        }
        val style =
                NotificationCompat.CallStyle.forIncomingCall(
                        getPerson(contact),
                        createCallAction(
                                id.sessionId, uk.xa0.tulkki.xmpp.services.ServiceActions.ACTION_DISMISS_CALL, 102),
                        createPendingRtpSession(
                                id, RtpSessionActivity.ACTION_ACCEPT_CALL, 103))
        if (media.contains(Media.VIDEO)) {
            style.setIsVideo(true)
            builder.setSmallIcon(uk.xa0.tulkki.ui.R.drawable.ic_videocam_24dp)
            builder.setContentTitle(
                    mXmppConnectionService.getString(uk.xa0.tulkki.ui.R.string.rtp_state_incoming_video_call))
        } else {
            style.setIsVideo(false)
            builder.setSmallIcon(uk.xa0.tulkki.ui.R.drawable.ic_call_24dp)
            builder.setContentTitle(
                    mXmppConnectionService.getString(uk.xa0.tulkki.ui.R.string.rtp_state_incoming_call))
        }
        builder.setStyle(style)
        builder.setLargeIcon(
                FileBackend.drawDrawable(
                        mXmppConnectionService
                                .getAvatarService()
                                .get(contact, AvatarService.getSystemUiAvatarSize(mXmppConnectionService))))
        val systemAccount = contact.getSystemAccount()
        if (systemAccount != null) {
            builder.addPerson(systemAccount.toString())
        }
        if (!onlyAlertOnce) {
            val appSettings = AppSettings(mXmppConnectionService)
            val ringtone = appSettings.getRingtone()
            if (ringtone != null) {
                builder.setSound(ringtone, AudioManager.STREAM_RING)
            }
            builder.setVibrate(CALL_PATTERN)
        }
        builder.setOnlyAlertOnce(onlyAlertOnce)
        builder.setContentText(id.account.getRoster().getContact(id.with).getDisplayName())
        builder.setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
        builder.setPriority(NotificationCompat.PRIORITY_HIGH)
        builder.setCategory(NotificationCompat.CATEGORY_CALL)
        val pendingIntent = createPendingRtpSession(id, Intent.ACTION_VIEW, 101)
        builder.setFullScreenIntent(pendingIntent, true)
        builder.setContentIntent(pendingIntent) // old androids need this?
        builder.setOngoing(true)
        builder.addAction(
                NotificationCompat.Action.Builder(
                                uk.xa0.tulkki.ui.R.drawable.ic_call_end_24dp,
                                mXmppConnectionService.getString(
                                        uk.xa0.tulkki.ui.R.string.dismiss_call),
                                createCallAction(
                                        id.sessionId,
                                        uk.xa0.tulkki.xmpp.services.ServiceActions.ACTION_DISMISS_CALL,
                                        102))
                        .build())
        builder.addAction(
                NotificationCompat.Action.Builder(
                                uk.xa0.tulkki.ui.R.drawable.ic_call_24dp,
                                mXmppConnectionService.getString(uk.xa0.tulkki.ui.R.string.answer_call),
                                createPendingRtpSession(
                                        id, RtpSessionActivity.ACTION_ACCEPT_CALL, 103))
                        .build())
        modifyIncomingCall(builder, id.account)
        val notification = builder.build()
        notification.audioAttributes =
                AudioAttributes.Builder()
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                        .build()
        notification.flags = notification.flags or Notification.FLAG_INSISTENT
        notify(INCOMING_CALL_NOTIFICATION_ID, notification)
    }

    override fun getOngoingCallNotification(
            ongoingCall: uk.xa0.tulkki.xmpp.services.OngoingCall
    ): Notification {
        val id = ongoingCall.id
        val builder = NotificationCompat.Builder(mXmppConnectionService, "ongoing_calls")
        // Tulkki: 3.7 C5-E4 - `id.account` is the island's ref now, so `getRoster()` answers a
        // `RosterRef` and this line would hand `getPerson(Contact)` a `ContactRef`. The model is
        // fetched back through the registry, which returns the element of `List<Account>` - the same
        // instance the ref wraps - so no cast is introduced.
        val account = AccountRegistry.get().findAccountByUuid(id.account.getUuid())
        if (account == null) {
            // Null is possible: the account can be removed while the call is still live, and the
            // service asks for this notification again on its next foreground update. There is no
            // call left to show, so answer with the notification the caller uses when there is no
            // ongoing call at all, rather than the null `startForeground` would reject.
            Log.d(Config.LOGTAG, "ongoing call's account is gone from the registry")
            return createForegroundNotification()
        }
        val contact = account.getRoster().getContact(id.with)
        val style =
                NotificationCompat.CallStyle.forOngoingCall(
                        getPerson(contact),
                        createCallAction(id.sessionId, uk.xa0.tulkki.xmpp.services.ServiceActions.ACTION_END_CALL, 104))
        if (ongoingCall.media.contains(Media.VIDEO)) {
            style.setIsVideo(true)
            builder.setSmallIcon(uk.xa0.tulkki.ui.R.drawable.ic_videocam_24dp)
            if (ongoingCall.reconnecting) {
                builder.setContentTitle(
                        mXmppConnectionService.getString(uk.xa0.tulkki.ui.R.string.reconnecting_video_call))
            } else {
                builder.setContentTitle(
                        mXmppConnectionService.getString(uk.xa0.tulkki.ui.R.string.ongoing_video_call))
            }
        } else {
            style.setIsVideo(false)
            builder.setSmallIcon(uk.xa0.tulkki.ui.R.drawable.ic_call_24dp)
            if (ongoingCall.reconnecting) {
                builder.setContentTitle(
                        mXmppConnectionService.getString(uk.xa0.tulkki.ui.R.string.reconnecting_call))
            } else {
                builder.setContentTitle(
                        mXmppConnectionService.getString(uk.xa0.tulkki.ui.R.string.ongoing_call))
            }
        }
        builder.setStyle(style)
        builder.setContentText(contact.getDisplayName())
        builder.setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
        builder.setPriority(NotificationCompat.PRIORITY_HIGH)
        builder.setCategory(NotificationCompat.CATEGORY_CALL)
        builder.setContentIntent(createPendingRtpSession(id, Intent.ACTION_VIEW, 101))
        builder.setOngoing(true)
        builder.addAction(
                NotificationCompat.Action.Builder(
                                uk.xa0.tulkki.ui.R.drawable.ic_call_end_24dp,
                                mXmppConnectionService.getString(uk.xa0.tulkki.ui.R.string.hang_up),
                                createCallAction(
                                        id.sessionId, uk.xa0.tulkki.xmpp.services.ServiceActions.ACTION_END_CALL, 104))
                        .build())
        builder.setLocalOnly(true)
        return builder.build()
    }

    private fun createPendingRtpSession(
            id: AbstractJingleConnection.Id,
            action: String,
            requestCode: Int
    ): PendingIntent {
        val fullScreenIntent = Intent(mXmppConnectionService, RtpSessionActivity::class.java)
        fullScreenIntent.setAction(action)
        fullScreenIntent.putExtra(EXTRA_ACCOUNT, id.account.getJid().asBareJid().toString())
        fullScreenIntent.putExtra(RtpSessionActivity.EXTRA_WITH, id.with.toString())
        fullScreenIntent.putExtra(RtpSessionActivity.EXTRA_SESSION_ID, id.sessionId)
        return PendingIntent.getActivity(
                mXmppConnectionService,
                requestCode,
                fullScreenIntent,
                if (s())
                        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                else PendingIntent.FLAG_UPDATE_CURRENT)
    }

    override fun cancelIncomingCallNotification() {
        cancel(INCOMING_CALL_NOTIFICATION_ID)
    }

    override fun stopSoundAndVibration(): Boolean {
        val jingleRtpConnection =
                mXmppConnectionService.getJingleConnectionManager().getOngoingRtpConnection()
        if (jingleRtpConnection == null) {
            return false
        }
        val notificationManager =
                mXmppConnectionService.getSystemService(NotificationManager::class.java)
        if (Iterables.any(
                notificationManager.getActiveNotifications().asList(),
                { n -> n.getId() == INCOMING_CALL_NOTIFICATION_ID })) {
            Log.d(Config.LOGTAG, "stopping sound and vibration for incoming call notification")
            showIncomingCallNotification(
                    jingleRtpConnection.getId(), jingleRtpConnection.getMedia(), true)
            return true
        }
        return false
    }

    private fun pushNow(message: Message) {
        mXmppConnectionService.updateUnreadCountBadge()
        if (!notifyMessage(message)) {
            Log.d(
                    Config.LOGTAG,
                    message.getConversation()?.getAccount()?.getJid()?.asBareJid().toString() +
                            ": suppressing notification because turned off")
            return
        }
        val isScreenLocked = mXmppConnectionService.isScreenLocked()
        if (this.mIsInForeground &&
                !isScreenLocked &&
                this.mOpenConversation === message.getConversation()) {
            Log.d(
                    Config.LOGTAG,
                    message.getConversation()?.getAccount()?.getJid()?.asBareJid().toString() +
                            ": suppressing notification because conversation is open")
            return
        }
        synchronized(notifications) {
            pushToStack(message)
            // Tulkki: a message with no conversation has nowhere to be notified; the Java
            // dereferenced it unchecked.
            val conversation = message.getConversation() ?: return
            // port-5: `getAccount()` and `getUuid()` are nullable; without either there is no
            // notification to build, so the message stays stacked and unannounced rather than being
            // keyed on a null.
            val account = conversation.getAccount() ?: return
            val conversationUuid = conversation.getUuid() ?: return
            val doNotify =
                    (!(this.mIsInForeground && this.mOpenConversation == null) || isScreenLocked) &&
                            !account.inGracePeriod() &&
                            !this.inMiniGracePeriod(account)
            // Tulkki: a message that still needs translating is not announced yet, so the buzz
            // carries the translation instead of "not translated yet". The delay is bounded and the
            // covered wording is what a notification falls back to, never the original. With the
            // interpreter off nothing is ever pending, so the buzz is immediate.
            if (doNotify &&
                    translationPending(
                            message, TranslationSettings.get(mXmppConnectionService).interpreter())) {
                scheduleNotification(conversationUuid)
            } else {
                updateNotification(
                        doNotify, java.util.Collections.singletonList(conversationUuid))
            }
        }
    }

    /**
     * Tulkki: hold the notification for this conversation's burst. One runnable is re-posted for each
     * message, so a burst coalesces into one notification built when the window closes; the window
     * never runs past [TULKKI_NOTIFY_MAX_DELAY_MS] from the first message, so a busy room still gets
     * its buzz. No `sleep` and no new worker: the app's own handler, the same mechanism the rest of
     * this class uses.
     */
    private fun scheduleNotification(conversationUuid: String) {
        val now = SystemClock.elapsedRealtime()
        if (mTulkkiPendingNotify == null) {
            // A new burst: the ceiling is measured from this message.
            mTulkkiBurstStartedAt = now
        }
        val untilCeiling = Math.max(0L, mTulkkiBurstStartedAt + TULKKI_NOTIFY_MAX_DELAY_MS - now)
        val delay = Math.min(TULKKI_NOTIFY_DELAY_MS, untilCeiling)
        val pending =
                Runnable {
                    synchronized(notifications) {
                        mTulkkiPendingNotify = null
                        updateNotification(
                                true, java.util.Collections.singletonList(conversationUuid))
                    }
                }
        // Only the callback is replaced: the burst's start time has to survive, or every message
        // after the first would see a ceiling that is already spent.
        mTulkkiPendingNotify?.let { mTulkkiNotifyHandler.removeCallbacks(it) }
        mTulkkiPendingNotify = pending
        mTulkkiNotifyHandler.postDelayed(pending, delay)
    }

    /** Tulkki: drop a held notification. A burst that is cancelled starts its ceiling over. */
    private fun cancelScheduledNotification() {
        mTulkkiPendingNotify?.let { mTulkkiNotifyHandler.removeCallbacks(it) }
        mTulkkiPendingNotify = null
        mTulkkiBurstStartedAt = 0L
    }

    /**
     * Tulkki: stop holding notifications for good. Called when the service goes away, so a pending
     * runnable cannot fire against a dead service; the handler is on the main looper, so the call is
     * safe from either thread.
     */
    override fun stop() {
        mTulkkiNotifyHandler.post { cancelScheduledNotification() }
    }

    private fun pushMissedCall(message: Message) {
        // Tulkki: the map is keyed on the conversation, so a message with none has no entry to add.
        val conversation = message.getConversation() ?: return
        val info = mMissedCalls.get(conversation)
        if (info == null) {
            mMissedCalls.put(conversation, MissedCallsInfo(message.getTimeSent()))
        } else {
            info.newMissedCall(message.getTimeSent())
        }
    }

    fun possiblyMissedCall(sessionId: String, message: Message) {
        synchronized(possiblyMissedCalls) {
            possiblyMissedCalls.put(sessionId, message)
        }
    }

    fun pushMissedCallNow(message: Message) {
        synchronized(mMissedCalls) {
            pushMissedCall(message)
            updateMissedCallNotifications(java.util.Collections.singleton(message.getConversation()))
        }
    }

    fun clear(conversation: Conversation) {
        clearMessages(conversation)
        clearMissedCalls(conversation)
    }

    // Tulkki: 3.7 pair 9, part 17 - `NotificationPort`'s four conversation members are ref-typed now
    // that the island holds a `ConversationRef`, and a port member is an override, so it cannot be
    // overloaded away. Each adapter casts once and delegates to the model-typed body above/below, so
    // every existing `:ui` and `:app` caller keeps the signature it had.

    override fun clear(conversation: ConversationRef) {
        clear(conversation as Conversation)
    }

    override fun clearMessages(conversation: ConversationRef) {
        clearMessages(conversation as Conversation)
    }

    override fun clearMissedCalls(conversation: ConversationRef) {
        clearMissedCalls(conversation as Conversation)
    }

    override fun clearMessages() {
        synchronized(notifications) {
            for (messages in notifications.values) {
                markAsReadIfHasDirectReply(messages)
                markAsNotificationDismissed(messages)
            }
            notifications.clear()
            updateNotification(false)
        }
    }

    fun clearMessages(conversation: Conversation) {
        synchronized(this.mBacklogMessageCounter) {
            this.mBacklogMessageCounter.remove(conversation)
        }
        synchronized(notifications) {
            markAsReadIfHasDirectReply(conversation)
            markAsNotificationDismissed(conversation)
            // port-5: `Conversation.getUuid()` is nullable; with no uuid there is no notification
            // key to remove, so nothing is dismissed rather than keyed on a null.
            val conversationUuid = conversation.getUuid()
            if (conversationUuid != null && notifications.remove(conversationUuid) != null) {
                cancel(conversationUuid, NOTIFICATION_ID)
                updateNotification(false, null, true)
            }
        }
    }

    fun clearMissedCall(message: Message) {
        synchronized(mMissedCalls) {
            val iterator = mMissedCalls.entries.iterator()
            while (iterator.hasNext()) {
                val entry = iterator.next()
                val conversational = entry.key
                val missedCallsInfo = entry.value
                // port-5: `Conversational.getUuid()`/`getAccount()` are nullable. A missed-call entry
                // with no uuid cannot match a message's conversation, and one with no account has no
                // roster JID for the log line, so each is skipped instead of dereferenced.
                val conversationalUuid = conversational.getUuid()
                if (conversationalUuid != null &&
                        conversationalUuid == message.getConversation()?.getUuid()) {
                    if (missedCallsInfo.removeMissedCall()) {
                        cancel(conversationalUuid, MISSED_CALL_NOTIFICATION_ID)
                        val account = conversational.getAccount()
                        if (account != null) {
                            Log.d(
                                    Config.LOGTAG,
                                    account.getJid().asBareJid().toString() +
                                            ": dismissed missed call because call was picked up on" +
                                            " other device")
                        }
                        iterator.remove()
                    }
                }
            }
            updateMissedCallNotifications(null)
        }
    }

    override fun clearMissedCalls() {
        synchronized(mMissedCalls) {
            for (conversation in mMissedCalls.keys) {
                // port-5: a missed call with no uuid has no notification key to cancel.
                val uuid = conversation.getUuid() ?: continue
                cancel(uuid, MISSED_CALL_NOTIFICATION_ID)
            }
            mMissedCalls.clear()
            updateMissedCallNotifications(null)
        }
    }

    fun clearMissedCalls(conversation: Conversation) {
        synchronized(mMissedCalls) {
            if (mMissedCalls.remove(conversation) != null) {
                // port-5: `Conversation.getUuid()` is nullable; with no uuid there is no key to
                // cancel, but the entry is still removed and the summary still updated.
                val conversationUuid = conversation.getUuid()
                if (conversationUuid != null) {
                    cancel(conversationUuid, MISSED_CALL_NOTIFICATION_ID)
                }
                updateMissedCallNotifications(null)
            }
        }
    }

    private fun markAsReadIfHasDirectReply(conversation: Conversation) {
        markAsReadIfHasDirectReply(notifications.get(conversation.getUuid()))
    }

    private fun markAsReadIfHasDirectReply(messages: ArrayList<Message>?) {
        if (messages != null && !messages.isEmpty()) {
            val last = messages.get(messages.size - 1)
            if (last.getStatus() != Message.STATUS_RECEIVED) {
                if (mXmppConnectionService.markRead(last.getConversation() as Conversation, false)) {
                    mXmppConnectionService.updateConversationUi()
                }
            }
        }
    }

    private fun markAsNotificationDismissed(conversation: Conversation) {
        markAsNotificationDismissed(notifications.get(conversation.getUuid()))
    }

    private fun markAsNotificationDismissed(messages: ArrayList<Message>?) {
        if (messages != null && !messages.isEmpty()) {
            // Tulkki: part 16 - the service's parameter is `List<MessageRef>` now; the elements are
            // the `Message`s this map already holds, so the copy is a widening one.
            mXmppConnectionService.markNotificationDismissed(ArrayList<MessageRef>(messages))
        }
    }

    /**
     * Tulkki: 3.7 C5-E4 - the parameter is the island's ref because `modifyIncomingCall` passes
     * `id.account`. The body reads `account != null` and `account.getColor(false)`, both ref-typed;
     * every other caller passes a model `Account`, which is an upcast.
     */
    private fun setNotificationColor(mBuilder: NotificationCompat.Builder, account: AccountRef?) {
        val color: Int
        if (account != null && AccountRegistry.get().getAccounts().size > 1) {
            color = account.getColor(false)
        } else {
            val typedValue = TypedValue()
            mXmppConnectionService
                    .getTheme()
                    .resolveAttribute(androidx.appcompat.R.attr.colorPrimary, typedValue, true)
            color = typedValue.data
        }
        mBuilder.setColor(color)
    }

    override fun updateNotification() {
        synchronized(notifications) {
            updateNotification(false)
        }
    }

    private fun updateNotification(notify: Boolean) {
        updateNotification(notify, null, false)
    }

    private fun updateNotification(notify: Boolean, conversationList: List<String>?) {
        updateNotification(notify, conversationList, false)
    }

    private fun updateNotification(
            notify: Boolean,
            conversationList: List<String>?,
            summaryOnly: Boolean
    ) {
        // Tulkki: anything that updates the notification now supersedes a held one - the owner read
        // the conversation, the app came to the foreground, the message went away - so the delayed
        // runnable is dropped rather than allowed to post late. The delayed runnable clears its own
        // field before calling this, so its own call is a no-op here.
        cancelScheduledNotification()
        val preferences =
                PreferenceManager.getDefaultSharedPreferences(mXmppConnectionService)

        val notifyOnlyOneChild =
                notify &&
                        conversationList != null &&
                        conversationList.size == 1 // if this check is changed to > 0 catchup messages will
        // create one notification per conversation

        if (notifications.isEmpty()) {
            cancel(NOTIFICATION_ID)
        } else {
            if (notify) {
                this.markLastNotification()
            }
            val mBuilder: NotificationCompat.Builder
            if (notifications.size == 1 && Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
                val account =
                        notifications.values.iterator().next().get(0).getConversation()?.getAccount()
                mBuilder =
                        buildConversationNotification(
                                notifications.values.iterator().next(),
                                notify,
                                isQuietHours(account))
                modifyForSoundVibrationAndLight(mBuilder, notify, preferences, account)
                notify(NOTIFICATION_ID, mBuilder.build())
            } else {
                mBuilder = buildMultipleConversation(notify, isQuietHours(null))
                if (notifyOnlyOneChild) {
                    mBuilder.setGroupAlertBehavior(NotificationCompat.GROUP_ALERT_CHILDREN)
                }
                modifyForSoundVibrationAndLight(mBuilder, notify, preferences, null)
                if (!summaryOnly) {
                    for (entry in notifications.entries) {
                        val uuid = entry.key
                        val notifyThis =
                                if (notifyOnlyOneChild) conversationList!!.contains(uuid) else notify
                        val account =
                                if (entry.value.isEmpty()) null
                                else entry.value.get(0).getConversation()?.getAccount()
                        val singleBuilder =
                                buildConversationNotification(
                                        entry.value, notifyThis, isQuietHours(account))
                        if (!notifyOnlyOneChild) {
                            singleBuilder.setGroupAlertBehavior(
                                    NotificationCompat.GROUP_ALERT_SUMMARY)
                        }
                        modifyForSoundVibrationAndLight(singleBuilder, notifyThis, preferences, account)
                        singleBuilder.setGroup(MESSAGES_GROUP)
                        notify(entry.key, NOTIFICATION_ID, singleBuilder.build())
                    }
                }
                notify(NOTIFICATION_ID, mBuilder.build())
            }
        }
    }

    private fun updateMissedCallNotifications(update: Set<Conversational>?) {
        if (mMissedCalls.isEmpty()) {
            cancel(MISSED_CALL_NOTIFICATION_ID)
            return
        }
        if (mMissedCalls.size == 1 && Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            val conversation = mMissedCalls.keys.iterator().next()
            val info = mMissedCalls.values.iterator().next()
            val notification = missedCall(conversation, info)
            notify(MISSED_CALL_NOTIFICATION_ID, notification)
        } else {
            val summary = missedCallsSummary()
            notify(MISSED_CALL_NOTIFICATION_ID, summary)
            if (update != null) {
                for (conversation in update) {
                    val info = mMissedCalls.get(conversation)
                    if (info != null) {
                        val notification = missedCall(conversation, info)
                        // port-5: a missed call with no uuid has no notification key.
                        val uuid = conversation.getUuid() ?: continue
                        notify(uuid, MISSED_CALL_NOTIFICATION_ID, notification)
                    }
                }
            }
        }
    }

    private fun modifyForSoundVibrationAndLight(
            mBuilder: NotificationCompat.Builder,
            notify: Boolean,
            preferences: SharedPreferences,
            account: Account?
    ) {
        val resources = mXmppConnectionService.getResources()
        val ringtone =
                preferences.getString(
                        AppSettings.NOTIFICATION_RINGTONE,
                        resources.getString(uk.xa0.tulkki.data.R.string.notification_ringtone))
        val vibrate =
                preferences.getBoolean(
                        AppSettings.NOTIFICATION_VIBRATE,
                        resources.getBoolean(uk.xa0.tulkki.ui.R.bool.vibrate_on_notification))
        val led =
                preferences.getBoolean(
                        AppSettings.NOTIFICATION_LED, resources.getBoolean(uk.xa0.tulkki.ui.R.bool.led))
        val headsup =
                preferences.getBoolean(
                        AppSettings.NOTIFICATION_HEADS_UP,
                        resources.getBoolean(uk.xa0.tulkki.ui.R.bool.headsup_notifications))
        if (notify && !isQuietHours(account)) {
            if (vibrate) {
                val dat = 70
                val pattern = longArrayOf(0, (3 * dat).toLong(), dat.toLong(), dat.toLong())
                mBuilder.setVibrate(pattern)
            } else {
                mBuilder.setVibrate(longArrayOf(0))
            }
            val uri = Uri.parse(ringtone!!)
            try {
                mBuilder.setSound(fixRingtoneUri(uri))
            } catch (e: SecurityException) {
                Log.d(Config.LOGTAG, "unable to use custom notification sound " + uri.toString())
            }
        } else {
            mBuilder.setLocalOnly(true)
        }
        mBuilder.setCategory(Notification.CATEGORY_MESSAGE)
        mBuilder.setPriority(
                if (notify)
                        (if (headsup) NotificationCompat.PRIORITY_HIGH
                        else NotificationCompat.PRIORITY_DEFAULT)
                else NotificationCompat.PRIORITY_LOW)
        setNotificationColor(mBuilder, account)
        mBuilder.setDefaults(0)
        if (led) {
            mBuilder.setLights(LED_COLOR, 2000, 3000)
        }
    }

    /**
     * Tulkki: 3.7 C5-E4 - the parameter is the island's ref because `getIncomingCallNotification`
     * passes `id.account`. The body reads `account != null` and delegates to
     * [setNotificationColor], which is ref-typed too; the other caller passes a model `Account`.
     */
    private fun modifyIncomingCall(mBuilder: NotificationCompat.Builder, account: AccountRef?) {
        mBuilder.setPriority(NotificationCompat.PRIORITY_HIGH)
        setNotificationColor(mBuilder, account)
        if (Build.VERSION.SDK_INT >= 26 &&
                account != null &&
                AccountRegistry.get().getAccounts().size > 1) {
            mBuilder.setColorized(true)
        }
        mBuilder.setLights(LED_COLOR, 2000, 3000)
    }

    private fun fixRingtoneUri(uri: Uri): Uri {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && "file" == uri.getScheme()) {
            val file = File(uri.getPath()!!)
            FileBackend.getUriForFile(mXmppConnectionService, file, file.getName())
        } else {
            uri
        }
    }

    private fun missedCallsSummary(): Notification {
        val publicBuilder = buildMissedCallsSummary(true)
        val builder = buildMissedCallsSummary(false)
        builder.setPublicVersion(publicBuilder.build())
        return builder.build()
    }

    private fun buildMissedCallsSummary(publicVersion: Boolean): NotificationCompat.Builder {
        val builder = NotificationCompat.Builder(mXmppConnectionService, "missed_calls")
        var totalCalls = 0
        val names = ArrayList<String>()
        var lastTime = 0L
        for (entry in mMissedCalls.entries) {
            val conversation = entry.key
            val missedCallsInfo = entry.value
            names.add(conversation.getContact().getDisplayName())
            totalCalls += missedCallsInfo.numberOfCalls
            lastTime = Math.max(lastTime, missedCallsInfo.lastTime)
        }
        val title =
                if (totalCalls == 1)
                        mXmppConnectionService.getString(uk.xa0.tulkki.ui.R.string.missed_call)
                else if (mMissedCalls.size == 1)
                        mXmppConnectionService
                                .getResources()
                                .getQuantityString(
                                        uk.xa0.tulkki.ui.R.plurals.n_missed_calls,
                                        totalCalls,
                                        totalCalls)
                else
                        mXmppConnectionService
                                .getResources()
                                .getQuantityString(
                                        uk.xa0.tulkki.ui.R.plurals.n_missed_calls_from_m_contacts,
                                        mMissedCalls.size,
                                        totalCalls,
                                        mMissedCalls.size)
        builder.setContentTitle(title)
        builder.setTicker(title)
        if (!publicVersion) {
            builder.setContentText(Joiner.on(", ").join(names))
        }
        builder.setSmallIcon(uk.xa0.tulkki.ui.R.drawable.ic_call_missed_24db)
        builder.setGroupSummary(true)
        builder.setGroup(MISSED_CALLS_GROUP)
        builder.setGroupAlertBehavior(NotificationCompat.GROUP_ALERT_CHILDREN)
        builder.setCategory(NotificationCompat.CATEGORY_CALL)
        builder.setWhen(lastTime)
        if (!mMissedCalls.isEmpty()) {
            val firstConversation = mMissedCalls.keys.iterator().next()
            builder.setContentIntent(createContentIntent(firstConversation))
        }
        builder.setDeleteIntent(createMissedCallsDeleteIntent(null))
        modifyMissedCall(builder, null)
        return builder
    }

    private fun missedCall(conversation: Conversational, info: MissedCallsInfo): Notification {
        val publicBuilder = buildMissedCall(conversation, info, true)
        val builder = buildMissedCall(conversation, info, false)
        builder.setPublicVersion(publicBuilder.build())
        return builder.build()
    }

    private fun buildMissedCall(
            conversation: Conversational,
            info: MissedCallsInfo,
            publicVersion: Boolean
    ): NotificationCompat.Builder {
        val builder =
                NotificationCompat.Builder(
                        mXmppConnectionService,
                        if (isQuietHours(conversation.getAccount())) "quiet_hours"
                        else "missed_calls")
        val title =
                if (info.numberOfCalls == 1)
                        mXmppConnectionService.getString(uk.xa0.tulkki.ui.R.string.missed_call)
                else
                        mXmppConnectionService
                                .getResources()
                                .getQuantityString(
                                        uk.xa0.tulkki.ui.R.plurals.n_missed_calls,
                                        info.numberOfCalls,
                                        info.numberOfCalls)
        builder.setContentTitle(title)
        if (AccountRegistry.get().getAccounts().size > 1) {
            // port-5: `getAccount()` is nullable; with no account there is no roster JID for the
            // subtext, so it is left off rather than dereferenced.
            conversation.getAccount()?.let { account ->
                builder.setSubText(account.getJid().asBareJid().toString())
            }
        }
        val name = conversation.getContact().getDisplayName()
        if (publicVersion) {
            builder.setTicker(title)
        } else {
            builder.setTicker(
                    mXmppConnectionService
                            .getResources()
                            .getQuantityString(
                                    uk.xa0.tulkki.ui.R.plurals.n_missed_calls_from_x,
                                    info.numberOfCalls,
                                    info.numberOfCalls,
                                    name))
            builder.setContentText(name)
        }
        builder.setSmallIcon(uk.xa0.tulkki.ui.R.drawable.ic_call_missed_24db)
        builder.setGroup(MISSED_CALLS_GROUP)
        builder.setCategory(NotificationCompat.CATEGORY_CALL)
        builder.setWhen(info.lastTime)
        builder.setContentIntent(createContentIntent(conversation))
        builder.setDeleteIntent(createMissedCallsDeleteIntent(conversation))
        if (!publicVersion && conversation is Conversation) {
            builder.setLargeIcon(
                    FileBackend.drawDrawable(
                            mXmppConnectionService
                                    .getAvatarService()
                                    .getConversationAvatar(
                                            conversation,
                                            AvatarService.getSystemUiAvatarSize(
                                                    mXmppConnectionService))))
        }
        modifyMissedCall(builder, conversation.getAccount())
        return builder
    }

    private fun modifyMissedCall(builder: NotificationCompat.Builder, account: Account?) {
        val preferences =
                PreferenceManager.getDefaultSharedPreferences(mXmppConnectionService)
        val resources = mXmppConnectionService.getResources()
        val led = preferences.getBoolean("led", resources.getBoolean(uk.xa0.tulkki.ui.R.bool.led))
        if (led) {
            builder.setLights(LED_COLOR, 2000, 3000)
        }
        builder.setPriority(
                if (isQuietHours(account)) NotificationCompat.PRIORITY_LOW
                else NotificationCompat.PRIORITY_HIGH)
        builder.setSound(null)
        setNotificationColor(builder, account)
    }

    private fun buildMultipleConversation(
            notify: Boolean,
            quietHours: Boolean
    ): NotificationCompat.Builder {

        val mBuilder =
                NotificationCompat.Builder(
                        mXmppConnectionService,
                        if (notify && !quietHours) MESSAGES_NOTIFICATION_CHANNEL
                        else "silent_messages")
        val style = NotificationCompat.InboxStyle()
        style.setBigContentTitle(
                mXmppConnectionService
                        .getResources()
                        .getQuantityString(
                                uk.xa0.tulkki.ui.R.plurals.unread_conversation_count,
                                notifications.size,
                                notifications.size))
        val names = ArrayList<String>()
        var conversation: Conversation? = null
        for (messages in notifications.values) {
            if (messages.isEmpty()) {
                continue
            }
            val current = messages.get(0).getConversation() as Conversation
            conversation = current
            val name = current.getName().toString()
            val styledString: SpannableString
            if (Config.HIDE_MESSAGE_TEXT_IN_NOTIFICATION) {
                val count = messages.size
                styledString =
                        SpannableString(
                                name +
                                        ": " +
                                        mXmppConnectionService
                                                .getResources()
                                                .getQuantityString(
                                                        uk.xa0.tulkki.ui.R.plurals.x_messages,
                                                        count,
                                                        count))
                styledString.setSpan(StyleSpan(Typeface.BOLD), 0, name.length, 0)
                style.addLine(styledString)
            } else {
                styledString =
                        SpannableString(
                                name +
                                        ": " +
                                        UIHelper.getMessagePreview(
                                                        mXmppConnectionService, messages.get(0))
                                                .first)
                styledString.setSpan(StyleSpan(Typeface.BOLD), 0, name.length, 0)
                style.addLine(styledString)
            }
            names.add(name)
        }
        val contentTitle =
                mXmppConnectionService
                        .getResources()
                        .getQuantityString(
                                uk.xa0.tulkki.ui.R.plurals.unread_conversation_count,
                                notifications.size,
                                notifications.size)
        mBuilder.setContentTitle(contentTitle)
        mBuilder.setTicker(contentTitle)
        mBuilder.setContentText(Joiner.on(", ").join(names))
        mBuilder.setStyle(style)
        if (conversation != null) {
            mBuilder.setContentIntent(createContentIntent(conversation))
        }
        mBuilder.setGroupSummary(true)
        mBuilder.setGroup(MESSAGES_GROUP)
        mBuilder.setDeleteIntent(createDeleteIntent(null))
        mBuilder.setSmallIcon(uk.xa0.tulkki.ui.R.drawable.ic_notification)
        return mBuilder
    }

    private fun buildConversationNotification(
            messages: ArrayList<Message>,
            notify: Boolean,
            quietHours: Boolean
    ): NotificationCompat.Builder {
        val channel =
                if (notify && !quietHours) MESSAGES_NOTIFICATION_CHANNEL else "silent_messages"
        val notificationBuilder = NotificationCompat.Builder(mXmppConnectionService, channel)
        if (messages.isEmpty()) {
            return notificationBuilder
        }
        val conversation = messages.get(0).getConversation() as Conversation
        notificationBuilder.setLargeIcon(
                FileBackend.drawDrawable(
                        mXmppConnectionService
                                .getAvatarService()
                                .getConversationAvatar(
                                        conversation,
                                        AvatarService.getSystemUiAvatarSize(mXmppConnectionService))))
        notificationBuilder.setContentTitle(conversation.getName())
        if (Config.HIDE_MESSAGE_TEXT_IN_NOTIFICATION) {
            val count = messages.size
            notificationBuilder.setContentText(
                    mXmppConnectionService
                            .getResources()
                            .getQuantityString(uk.xa0.tulkki.ui.R.plurals.x_messages, count, count))
        } else {
            val message = getImage(messages)
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P && message != null) {
                modifyForImage(notificationBuilder, message, messages)
            } else {
                modifyForTextOnly(notificationBuilder, messages)
            }
            val remoteInput =
                    RemoteInput.Builder("text_reply")
                            .setLabel(UIHelper.getMessageHint(mXmppConnectionService, conversation))
                            .build()
            val markAsReadPendingIntent = createReadPendingIntent(conversation)
            val markReadAction =
                    NotificationCompat.Action.Builder(
                                    uk.xa0.tulkki.ui.R.drawable.ic_mark_chat_read_24dp,
                                    mXmppConnectionService.getString(
                                            uk.xa0.tulkki.ui.R.string.mark_as_read),
                                    markAsReadPendingIntent)
                            .setSemanticAction(
                                    NotificationCompat.Action.SEMANTIC_ACTION_MARK_AS_READ)
                            .setShowsUserInterface(false)
                            .build()
            val replyLabel = mXmppConnectionService.getString(uk.xa0.tulkki.ui.R.string.reply)
            // port-5: `Message.getUuid()` is nullable; a last message with no uuid has nothing to
            // reply to, so the reply actions are left off rather than built on a stand-in.
            val lastMessageUuid = Iterables.getLast(messages).getUuid()
            val replyAction =
                    if (lastMessageUuid != null) {
                        NotificationCompat.Action.Builder(
                                        uk.xa0.tulkki.ui.R.drawable.ic_send_24dp,
                                        replyLabel,
                                        createReplyIntent(conversation, lastMessageUuid, false))
                                .setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_REPLY)
                                .setShowsUserInterface(false)
                                .addRemoteInput(remoteInput)
                                .build()
                    } else {
                        null
                    }
            val wearReplyAction =
                    if (lastMessageUuid != null) {
                        NotificationCompat.Action.Builder(
                                        uk.xa0.tulkki.ui.R.drawable.ic_reply_24dp,
                                        replyLabel,
                                        createReplyIntent(conversation, lastMessageUuid, true))
                                .addRemoteInput(remoteInput)
                                .build()
                    } else {
                        null
                    }
            if (wearReplyAction != null) {
                notificationBuilder.extend(
                        NotificationCompat.WearableExtender().addAction(wearReplyAction))
            }
            var addedActionsCount = 1
            notificationBuilder.addAction(markReadAction)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && replyAction != null) {
                notificationBuilder.addAction(replyAction)
                ++addedActionsCount
            }

            if (displaySnoozeAction(messages)) {
                val label = mXmppConnectionService.getString(uk.xa0.tulkki.ui.R.string.snooze)
                val pendingSnoozeIntent = createSnoozeIntent(conversation)
                val snoozeAction =
                        NotificationCompat.Action.Builder(
                                        uk.xa0.tulkki.ui.R.drawable.ic_notifications_paused_24dp,
                                        label,
                                        pendingSnoozeIntent)
                                .build()
                notificationBuilder.addAction(snoozeAction)
                ++addedActionsCount
            }
            if (addedActionsCount < 3) {
                val firstLocationMessage = getFirstLocationMessage(messages)
                if (firstLocationMessage != null) {
                    val pendingShowLocationIntent = createShowLocationIntent(firstLocationMessage)
                    if (pendingShowLocationIntent != null) {
                        val label =
                                mXmppConnectionService
                                        .getResources()
                                        .getString(uk.xa0.tulkki.ui.R.string.show_location)
                        val locationAction =
                                NotificationCompat.Action.Builder(
                                                uk.xa0.tulkki.ui.R.drawable.ic_location_pin_24dp,
                                                label,
                                                pendingShowLocationIntent)
                                        .build()
                        notificationBuilder.addAction(locationAction)
                        ++addedActionsCount
                    }
                }
            }
            if (addedActionsCount < 3) {
                val firstDownloadableMessage = getFirstDownloadableMessage(messages)
                if (firstDownloadableMessage != null) {
                    val label =
                            mXmppConnectionService
                                    .getResources()
                                    .getString(
                                            uk.xa0.tulkki.ui.R.string.download_x_file,
                                            UIHelper.getFileDescriptionString(
                                                    mXmppConnectionService,
                                                    firstDownloadableMessage))
                    val pendingDownloadIntent = createDownloadIntent(firstDownloadableMessage)
                    if (pendingDownloadIntent != null) {
                        val downloadAction =
                                NotificationCompat.Action.Builder(
                                                uk.xa0.tulkki.ui.R.drawable.ic_download_24dp,
                                                label,
                                                pendingDownloadIntent)
                                        .build()
                        notificationBuilder.addAction(downloadAction)
                        ++addedActionsCount
                    }
                }
            }
        }
        val info: ShortcutInfoCompat
        if (conversation.getMode() == Conversation.MODE_SINGLE) {
            val contact = conversation.getContact()
            val systemAccount = contact.getSystemAccount()
            if (systemAccount != null) {
                notificationBuilder.addPerson(systemAccount.toString())
            }
            info =
                    mXmppConnectionService
                            .getShortcutService()
                            .getShortcutInfo(contact, conversation.getUuid())
        } else {
            info =
                    mXmppConnectionService
                            .getShortcutService()
                            .getShortcutInfo(conversation.getMucOptions())
        }
        notificationBuilder.setWhen(conversation.getLatestMessage().getTimeSent())
        notificationBuilder.setSmallIcon(uk.xa0.tulkki.ui.R.drawable.ic_notification)
        notificationBuilder.setDeleteIntent(createDeleteIntent(conversation))
        notificationBuilder.setContentIntent(createContentIntent(conversation))
        if (AccountRegistry.get().getAccounts().size > 1) {
            notificationBuilder.setSubText(
                    conversation.getAccount()!!.getJid().asBareJid().toString())
        }
        if (channel == MESSAGES_NOTIFICATION_CHANNEL) {
            // when do not want 'customized' notifications for silent notifications in their
            // respective channels
            notificationBuilder.setShortcutInfo(info)
            if (Build.VERSION.SDK_INT >= 30) {
                mXmppConnectionService
                        .getSystemService(ShortcutManager::class.java)
                        .pushDynamicShortcut(info.toShortcutInfo())
                // mBuilder.setBubbleMetadata(new NotificationCompat.BubbleMetadata.Builder(info.getId()).build());
            }
        }
        return notificationBuilder
    }

    private fun modifyForImage(
            builder: NotificationCompat.Builder,
            message: Message,
            messages: ArrayList<Message>
    ) {
        try {
            val bitmap =
                    FileBackends.get()
                            .getThumbnailBitmap(
                                    message,
                                    mXmppConnectionService.getResources(),
                                    getPixel(288))
            val tmp = ArrayList<Message>()
            for (msg in messages) {
                if (msg.getType() == Message.TYPE_TEXT && msg.getTransferable() == null) {
                    tmp.add(msg)
                }
            }
            val bigPictureStyle = NotificationCompat.BigPictureStyle()
            bigPictureStyle.bigPicture(bitmap)
            if (tmp.isEmpty()) {
                val description = UIHelper.getFileDescriptionString(mXmppConnectionService, message)
                builder.setContentText(description)
                builder.setTicker(description)
            } else {
                val text = getMergedBodies(tmp)
                bigPictureStyle.setSummaryText(text)
                builder.setContentText(text)
                builder.setTicker(text)
            }
            builder.setStyle(bigPictureStyle)
        } catch (e: IOException) {
            modifyForTextOnly(builder, messages)
        }
    }

    private fun getPerson(message: Message): Person {
        val contact = message.getContact()
        val builder = Person.Builder()
        if (contact != null) {
            builder.setName(contact.getDisplayName())
            val uri = contact.getSystemAccount()
            if (uri != null) {
                builder.setUri(uri.toString())
            }
        } else {
            builder.setName(UIHelper.getColoredUsername(mXmppConnectionService, message))
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val jid = if (contact == null) message.getCounterpart() else contact.getJid()
            builder.setKey(jid.toString())
            // Tulkki: `find` needs the account, which only the conversation carries; with none the
            // pinned-on-top lookup is skipped and the person keeps no extra flag.
            val conversation = message.getConversation()
            val c =
                    if (conversation == null) null
                    else mXmppConnectionService.find(conversation.getAccount(), jid)
                            as Conversation?
            if (c != null) {
                builder.setImportant(
                        c.getBooleanAttribute(Conversation.ATTRIBUTE_PINNED_ON_TOP, false))
            }
            builder.setIcon(
                    IconCompat.createWithBitmap(
                            FileBackend.drawDrawable(
                                    mXmppConnectionService
                                            .getAvatarService()
                                            .getMessageAvatar(
                                                    message,
                                                    AvatarService.getSystemUiAvatarSize(
                                                            mXmppConnectionService),
                                                    false)) ?: throw NullPointerException()))
        }
        return builder.build()
    }

    private fun getPerson(contact: Contact): Person {
        val builder = Person.Builder()
        builder.setName(contact.getDisplayName())
        val uri = contact.getSystemAccount()
        if (uri != null) {
            builder.setUri(uri.toString())
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val jid = contact.getJid()
            builder.setKey(jid.toString())
            val c = mXmppConnectionService.find(contact.getAccount(), jid) as Conversation?
            if (c != null) {
                builder.setImportant(
                        c.getBooleanAttribute(Conversation.ATTRIBUTE_PINNED_ON_TOP, false))
            }
            builder.setIcon(
                    IconCompat.createWithBitmap(
                            FileBackend.drawDrawable(
                                    mXmppConnectionService
                                            .getAvatarService()
                                            .get(
                                                    contact,
                                                    AvatarService.getSystemUiAvatarSize(
                                                            mXmppConnectionService),
                                                    false)) ?: throw NullPointerException()))
        }
        return builder.build()
    }

    private fun modifyForTextOnly(
            builder: NotificationCompat.Builder,
            messages: ArrayList<Message>
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            val conversation = messages.get(0).getConversation() as Conversation
            val meBuilder =
                    Person.Builder()
                            .setName(
                                    mXmppConnectionService.getString(
                                            uk.xa0.tulkki.ui.R.string.me))
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                meBuilder.setIcon(
                        IconCompat.createWithBitmap(
                                FileBackend.drawDrawable(
                                        mXmppConnectionService
                                                .getAvatarService()
                                                .getAccountAvatar(
                                                        // Tulkki: the Java's `AccountRef` parameter
                                                        // was an unannotated platform type, so this
                                                        // nullable `Conversation.getAccount()` was
                                                        // passed and the cache read it
                                                        // (`key(account, size)` ->
                                                        // `account.getUuid()`); a null was the NPE
                                                        // the Java threw, so the null stays here.
                                                        conversation.getAccount()
                                                                ?: throw NullPointerException(),
                                                        AvatarService.getSystemUiAvatarSize(
                                                                mXmppConnectionService))) ?: throw NullPointerException()))
            }
            val me = meBuilder.build()
            val messagingStyle = NotificationCompat.MessagingStyle(me)
            val multiple =
                    conversation.getMode() == Conversation.MODE_MULTI ||
                            messages.get(0).getTrueCounterpart() != null
            if (multiple) {
                messagingStyle.setConversationTitle(conversation.getName())
            }
            for (message in messages) {
                val sender =
                        if (message.getStatus() == Message.STATUS_RECEIVED) getPerson(message)
                        else null
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && isImageMessage(message)) {
                    val dataUri =
                            FileBackend.getMediaUri(
                                    mXmppConnectionService, FileBackends.get().getFile(message))
                    val imageMessage =
                            NotificationCompat.MessagingStyle.Message(
                                    UIHelper.getMessagePreview(mXmppConnectionService, message)
                                            .first,
                                    message.getTimeSent(),
                                    sender)
                    if (dataUri != null) {
                        imageMessage.setData(message.getMimeType(), dataUri)
                    }
                    messagingStyle.addMessage(imageMessage)
                } else {
                    messagingStyle.addMessage(
                            coverLine(message)
                                    ?: UIHelper.getMessagePreview(mXmppConnectionService, message)
                                            .first,
                            message.getTimeSent(),
                            sender)
                }
            }
            messagingStyle.setGroupConversation(multiple)
            builder.setStyle(messagingStyle)
        } else {
            if (messages.get(0).getConversation()?.getMode() == Conversation.MODE_SINGLE &&
                    messages.get(0).getTrueCounterpart() == null) {
                builder.setStyle(
                        NotificationCompat.BigTextStyle().bigText(getMergedBodies(messages)))
                val last = messages.get(messages.size - 1)
                val preview =
                        coverLine(last)
                                ?: UIHelper.getMessagePreview(mXmppConnectionService, last).first
                builder.setContentText(preview)
                builder.setTicker(preview)
                builder.setNumber(messages.size)
            } else {
                val style = NotificationCompat.InboxStyle()
                var styledString: SpannableString
                for (message in messages) {
                    val name = UIHelper.getColoredUsername(mXmppConnectionService, message)
                    styledString =
                            SpannableString(
                                    name.toString() +
                                            ": " +
                                            displayedLine(message))
                    styledString.setSpan(StyleSpan(Typeface.BOLD), 0, name.length, 0)
                    style.addLine(styledString)
                }
                builder.setStyle(style)
                val count = messages.size
                if (count == 1) {
                    val name =
                            UIHelper.getColoredUsername(
                                    mXmppConnectionService, messages.get(0))
                    styledString =
                            SpannableString(
                                    name.toString() +
                                            ": " +
                                            displayedLine(messages.get(0)))
                    styledString.setSpan(StyleSpan(Typeface.BOLD), 0, name.length, 0)
                    builder.setContentText(styledString)
                    builder.setTicker(styledString)
                } else {
                    val text =
                            mXmppConnectionService
                                    .getResources()
                                    .getQuantityString(
                                            uk.xa0.tulkki.ui.R.plurals.x_messages, count, count)
                    builder.setContentText(text)
                    builder.setTicker(text)
                }
            }
        }
    }

    private fun getImage(messages: Iterable<Message>): Message? {
        var image: Message? = null
        for (message in messages) {
            if (message.getStatus() != Message.STATUS_RECEIVED) {
                return null
            }
            if (isImageMessage(message)) {
                image = message
            }
        }
        return image
    }

    private fun getFirstDownloadableMessage(messages: Iterable<Message>): Message? {
        for (message in messages) {
            if (message.getTransferable() != null ||
                    (message.getType() == Message.TYPE_TEXT && message.treatAsDownloadable())) {
                return message
            }
        }
        return null
    }

    private fun getFirstLocationMessage(messages: Iterable<Message>): Message? {
        for (message in messages) {
            if (message.isGeoUri()) {
                return message
            }
        }
        return null
    }

    private fun getMergedBodies(messages: ArrayList<Message>): CharSequence {
        val text = StringBuilder()
        for (message in messages) {
            if (text.length != 0) {
                text.append("\n")
            }
            text.append(displayedLine(message))
        }
        return text.toString()
    }

    /**
     * Tulkki: what the shade draws for one message's own line - the cover's own words when the
     * translation did not happen, and the renderer's preview otherwise.
     *
     * <p>A notification cannot cover a body the way a bubble can: it has no tap, no blur and no room.
     * So a message whose translation did not happen has to *say so* here, and it has to say it in the
     * cover's own words - "DeepSeek could not translate this message", "No DeepSeek key" - rather than
     * in the renderer's generic "not translated yet", and never in the message's own words
     * (docs/MIGRATION.md item 17, one). [DisplayedBody.notificationCover] is the one decision, and
     * it needs no word of the body: a reason the store recorded for this very message is a fact about
     * the message. When it answers nothing, the renderer's own preview stands, so a message that needs
     * no cover is drawn by the one place that decides what a body looks like.
     *
     * <p>The wording is `:ui`'s own cover vocabulary ([TranslationText.coverCaption]); nothing
     * here invents a string for a state the rest of the app already names.
     */
    private fun coverLine(message: Message): CharSequence? {
        val settings = TranslationSettings.get(mXmppConnectionService)
        if (!settings.interpreter().enabled()) {
            // Off, nothing was translated and nothing is owed: a plain client's notification.
            return null
        }
        val failure = settings.activity().reasonFor(message.getUuid())
        val cover =
                DisplayedBody.notificationCover(
                        message.getTranslationState(),
                        message.getStatus(),
                        failure,
                        failure != null)
                        ?: return null
        return mXmppConnectionService.getString(TranslationText.coverCaption(cover))
    }

    /** One message's own notification line: the cover's words when there is one, the preview otherwise. */
    private fun displayedLine(message: Message): CharSequence =
            coverLine(message)
                    ?: UIHelper.getMessagePreview(mXmppConnectionService, message).first

    private fun createShowLocationIntent(message: Message): PendingIntent? {
        // Tulkki: the request code is keyed on the conversation; with none there is no intent to build.
        val conversation = message.getConversation() ?: return null
        val intents = GeoHelper.createGeoIntentsFromMessage(mXmppConnectionService, message)
        for (intent in intents) {
            if (intent.resolveActivity(mXmppConnectionService.getPackageManager()) != null) {
                return PendingIntent.getActivity(
                        mXmppConnectionService,
                        generateRequestCode(conversation, 18),
                        intent,
                        if (s())
                                PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                        else PendingIntent.FLAG_UPDATE_CURRENT)
            }
        }
        return null
    }

    private fun createContentIntent(
            conversationUuid: String,
            downloadMessageUuid: String?
    ): PendingIntent {
        val viewConversationIntent =
                Intent(mXmppConnectionService, ConversationListActivity::class.java)
        viewConversationIntent.setAction(ConversationListActivity.ACTION_VIEW_CONVERSATION)
        viewConversationIntent.putExtra(
                ConversationListActivity.EXTRA_CONVERSATION, conversationUuid)
        return if (downloadMessageUuid != null) {
            viewConversationIntent.putExtra(
                    ConversationListActivity.EXTRA_DOWNLOAD_UUID, downloadMessageUuid)
            PendingIntent.getActivity(
                    mXmppConnectionService,
                    generateRequestCode(conversationUuid, 8),
                    viewConversationIntent,
                    if (s())
                            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                    else PendingIntent.FLAG_UPDATE_CURRENT)
        } else {
            PendingIntent.getActivity(
                    mXmppConnectionService,
                    generateRequestCode(conversationUuid, 10),
                    viewConversationIntent,
                    if (s())
                            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                    else PendingIntent.FLAG_UPDATE_CURRENT)
        }
    }

    private fun generateRequestCode(uuid: String, actionId: Int): Int {
        return (actionId * NOTIFICATION_ID_MULTIPLIER) +
                (uuid.hashCode() % NOTIFICATION_ID_MULTIPLIER)
    }

    private fun generateRequestCode(conversation: Conversational, actionId: Int): Int {
        // port-5: `getUuid()` is nullable. A conversation with no uuid has no identity to fold into
        // the code, so the action's own band is the code; `Conversation`'s uuid is its row identity,
        // so every production caller reaches the first branch.
        val uuid = conversation.getUuid() ?: return actionId * NOTIFICATION_ID_MULTIPLIER
        return generateRequestCode(uuid, actionId)
    }

    private fun createDownloadIntent(message: Message): PendingIntent? {
        // Tulkki: the content intent is keyed on the conversation uuid; with none there is nothing to
        // open, so the caller drops the download action instead of being handed a stand-in.
        val conversationUuid = message.getConversationUuid() ?: return null
        return createContentIntent(conversationUuid, message.getUuid())
    }

    private fun createContentIntent(conversation: Conversational): PendingIntent? {
        // port-5: `getUuid()` is nullable; with no uuid there is nothing to open, so the caller's
        // `setContentIntent` gets the null it already tolerates (`createDownloadIntent` returns null
        // the same way).
        val conversationUuid = conversation.getUuid() ?: return null
        return createContentIntent(conversationUuid, null)
    }

    private fun createDeleteIntent(conversation: Conversation?): PendingIntent {
        val intent = Intent(mXmppConnectionService, XmppConnectionService::class.java)
        intent.setAction(uk.xa0.tulkki.xmpp.services.ServiceActions.ACTION_CLEAR_MESSAGE_NOTIFICATION)
        if (conversation != null) {
            intent.putExtra("uuid", conversation.getUuid())
            return PendingIntent.getService(
                    mXmppConnectionService,
                    generateRequestCode(conversation, 20),
                    intent,
                    if (s())
                            PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                    else PendingIntent.FLAG_UPDATE_CURRENT)
        }
        return PendingIntent.getService(
                mXmppConnectionService,
                0,
                intent,
                if (s()) PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                else PendingIntent.FLAG_UPDATE_CURRENT)
    }

    private fun createMissedCallsDeleteIntent(conversation: Conversational?): PendingIntent {
        val intent = Intent(mXmppConnectionService, XmppConnectionService::class.java)
        intent.setAction(uk.xa0.tulkki.xmpp.services.ServiceActions.ACTION_CLEAR_MISSED_CALL_NOTIFICATION)
        if (conversation != null) {
            intent.putExtra("uuid", conversation.getUuid())
            return PendingIntent.getService(
                    mXmppConnectionService,
                    generateRequestCode(conversation, 21),
                    intent,
                    if (s())
                            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                    else PendingIntent.FLAG_UPDATE_CURRENT)
        }
        return PendingIntent.getService(
                mXmppConnectionService,
                1,
                intent,
                if (s()) PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                else PendingIntent.FLAG_UPDATE_CURRENT)
    }

    private fun createReplyIntent(
            conversation: Conversation,
            lastMessageUuid: String,
            dismissAfterReply: Boolean
    ): PendingIntent {
        val intent = Intent(mXmppConnectionService, XmppConnectionService::class.java)
        intent.setAction(uk.xa0.tulkki.xmpp.services.ServiceActions.ACTION_REPLY_TO_CONVERSATION)
        intent.putExtra("uuid", conversation.getUuid())
        intent.putExtra("dismiss_notification", dismissAfterReply)
        intent.putExtra("last_message_uuid", lastMessageUuid)
        val id = generateRequestCode(conversation, if (dismissAfterReply) 12 else 14)
        return PendingIntent.getService(
                mXmppConnectionService,
                id,
                intent,
                if (s()) PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                else PendingIntent.FLAG_UPDATE_CURRENT)
    }

    private fun createReadPendingIntent(conversation: Conversation): PendingIntent {
        val intent = Intent(mXmppConnectionService, XmppConnectionService::class.java)
        intent.setAction(uk.xa0.tulkki.xmpp.services.ServiceActions.ACTION_MARK_AS_READ)
        intent.putExtra("uuid", conversation.getUuid())
        intent.setPackage(mXmppConnectionService.getPackageName())
        return PendingIntent.getService(
                mXmppConnectionService,
                generateRequestCode(conversation, 16),
                intent,
                if (s()) PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                else PendingIntent.FLAG_UPDATE_CURRENT)
    }

    private fun createCallAction(
            sessionId: String,
            action: String,
            requestCode: Int
    ): PendingIntent {
        return pendingServiceIntent(
                mXmppConnectionService,
                action,
                requestCode,
                ImmutableMap.of(RtpSessionActivity.EXTRA_SESSION_ID, sessionId))
    }

    private fun createSnoozeIntent(conversation: Conversation): PendingIntent? {
        // port-5: `getUuid()` is nullable and Guava's `ImmutableMap.of` refuses a null value, so a
        // conversation with no uuid gets no snooze action rather than a map built on a stand-in.
        val uuid = conversation.getUuid() ?: return null
        return pendingServiceIntent(
                mXmppConnectionService,
                uk.xa0.tulkki.xmpp.services.ServiceActions.ACTION_SNOOZE,
                generateRequestCode(uuid, 22),
                ImmutableMap.of("uuid", uuid))
    }

    private fun wasHighlightedOrPrivate(message: Message): Boolean {
        if (message.getConversation() is Conversation) {
            val conversation = message.getConversation() as Conversation
            val sender =
                    conversation.getMucOptions().findUserByFullJid(message.getCounterpart())
            val muted =
                    message.getStatus() == Message.STATUS_RECEIVED &&
                            mXmppConnectionService.isMucUserMuted(
                                    conversation.getAccountUuid(),
                                    "" + conversation.getJid(),
                                    message.getOccupantId())
            if (muted) return false
            if (sender != null &&
                    sender.getAffiliation().ranks(MucOptions.Affiliation.MEMBER) &&
                    message.isAttention()) {
                return true
            }
            val nick = conversation.getMucOptions().getActualNick()
            val highlight = generateNickHighlightPattern(nick)
            val name = conversation.getMucOptions().getActualName()
            val highlightName = generateNickHighlightPattern(name)
            if (message.getBody() == null || (nick == null && name == null)) {
                return false
            }
            val m = highlight.matcher(message.getBody(true))
            val m2 = highlightName.matcher(message.getBody(true))
            return m.find() || m2.find() || message.isPrivateMessage()
        } else {
            return false
        }
    }

    private fun wasReplyToMe(message: Message): Boolean {
        val reply = message.getReply()
        if (reply == null) return false
        val id = reply.getAttribute("id") ?: return false
        val parent =
                (message.getConversation() as Conversation)
                        .findMessageWithRemoteIdAndCounterpart(id, null)
        if (parent == null) return false
        return parent.getStatus() >= Message.STATUS_SEND
    }

    // Tulkki: both parameters are nullable because a null here is how the UI says "nothing is
    // open" - `ConversationFragment.onStop` calls `setOpenConversation(null)` in as many words, and
    // the field is already `Conversation?`. The non-null parameter (and the `as Conversation` cast
    // behind it) threw inside the Kotlin check on the main thread instead.
    fun setOpenConversation(conversation: Conversation?) {
        this.mOpenConversation = conversation
    }

    override fun setOpenConversation(conversation: ConversationRef?) {
        setOpenConversation(conversation as Conversation?)
    }

    override fun setIsInForeground(foreground: Boolean) {
        this.mIsInForeground = foreground
    }

    private fun getPixel(dp: Int): Int {
        val metrics: DisplayMetrics = mXmppConnectionService.getResources().getDisplayMetrics()
        return ((dp * metrics.density).toInt())
    }

    private fun markLastNotification() {
        this.mLastNotification = SystemClock.elapsedRealtime()
    }

    private fun inMiniGracePeriod(account: Account): Boolean {
        val miniGrace =
                if (account.getStatus() == Account.State.ONLINE) Config.MINI_GRACE_PERIOD
                else Config.MINI_GRACE_PERIOD * 2
        return SystemClock.elapsedRealtime() < (this.mLastNotification + miniGrace)
    }

    override fun createForegroundNotification(): Notification {
        val mBuilder = Notification.Builder(mXmppConnectionService)
        mBuilder.setContentTitle(BuildConfig.APP_NAME)
        val accounts = AccountRegistry.get().getAccounts()
        val enabled: Int
        val connected: Int
        if (accounts == null) {
            enabled = 0
            connected = 0
        } else {
            enabled = Iterables.size(Iterables.filter(accounts) { it.isEnabled() })
            connected = Iterables.size(Iterables.filter(accounts) { it.isOnlineAndConnected() })
        }
        mBuilder.setContentText(
                mXmppConnectionService.getString(
                        uk.xa0.tulkki.ui.R.string.connected_accounts, connected, enabled))
        val openIntent = createOpenConversationListIntent()
        if (openIntent != null) {
            mBuilder.setContentIntent(openIntent)
        }
        mBuilder.setWhen(0)
                .setPriority(Notification.PRIORITY_MIN)
                .setSmallIcon(
                        if (connected >= enabled) uk.xa0.tulkki.ui.R.drawable.ic_link_24dp
                        else uk.xa0.tulkki.ui.R.drawable.ic_link_off_24dp)
                .setLocalOnly(true)

        if (Compatibility.runsTwentySix()) {
            mBuilder.setChannelId("foreground")
            mBuilder.addAction(
                    uk.xa0.tulkki.ui.R.drawable.ic_logout_24dp,
                    mXmppConnectionService.getString(uk.xa0.tulkki.ui.R.string.log_out),
                    pendingServiceIntent(
                            mXmppConnectionService,
                            uk.xa0.tulkki.xmpp.services.ServiceActions.ACTION_TEMPORARILY_DISABLE,
                            87))
            mBuilder.addAction(
                    uk.xa0.tulkki.ui.R.drawable.ic_notifications_off_24dp,
                    mXmppConnectionService.getString(uk.xa0.tulkki.ui.R.string.hide_notification),
                    pendingNotificationSettingsIntent(mXmppConnectionService))
        }

        return mBuilder.build()
    }

    private fun createOpenConversationListIntent(): PendingIntent? {
        return try {
            PendingIntent.getActivity(
                    mXmppConnectionService,
                    0,
                    Intent(mXmppConnectionService, ConversationListActivity::class.java),
                    if (s())
                            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                    else PendingIntent.FLAG_UPDATE_CURRENT)
        } catch (e: RuntimeException) {
            null
        }
    }

    override fun updateErrorNotification() {
        if (Config.SUPPRESS_ERROR_NOTIFICATION) {
            cancel(ERROR_NOTIFICATION_ID)
            return
        }
        val errors = ArrayList<Account>()
        var torNotAvailable = false
        for (account in AccountRegistry.get().getAccounts()) {
            if (account.hasErrorStatus() && account.showErrorNotification()) {
                errors.add(account)
                torNotAvailable =
                        torNotAvailable || account.getStatus() == Account.State.TOR_NOT_AVAILABLE
            }
        }
        if (mXmppConnectionService.foregroundNotificationNeedsUpdatingWhenErrorStateChanges()) {
            try {
                notify(FOREGROUND_NOTIFICATION_ID, createForegroundNotification())
            } catch (e: RuntimeException) {
                Log.d(
                        Config.LOGTAG,
                        "not refreshing foreground service notification because service has died",
                        e)
            }
        }
        val mBuilder = Notification.Builder(mXmppConnectionService)
        if (errors.isEmpty()) {
            cancel(ERROR_NOTIFICATION_ID)
            return
        } else if (errors.size == 1) {
            mBuilder.setContentTitle(
                    mXmppConnectionService.getString(
                            uk.xa0.tulkki.ui.R.string.problem_connecting_to_account))
            mBuilder.setContentText(errors.get(0).getJid().asBareJid().toString())
        } else {
            mBuilder.setContentTitle(
                    mXmppConnectionService.getString(
                            uk.xa0.tulkki.ui.R.string.problem_connecting_to_accounts))
            mBuilder.setContentText(
                    mXmppConnectionService.getString(uk.xa0.tulkki.ui.R.string.touch_to_fix))
        }
        try {
            mBuilder.addAction(
                    uk.xa0.tulkki.ui.R.drawable.ic_autorenew_24dp,
                    mXmppConnectionService.getString(uk.xa0.tulkki.ui.R.string.try_again),
                    pendingServiceIntent(
                            mXmppConnectionService, uk.xa0.tulkki.xmpp.services.ServiceActions.ACTION_TRY_AGAIN, 45))
            mBuilder.setDeleteIntent(
                    pendingServiceIntent(
                            mXmppConnectionService,
                            uk.xa0.tulkki.xmpp.services.ServiceActions.ACTION_DISMISS_ERROR_NOTIFICATIONS,
                            69))
        } catch (e: RuntimeException) {
            Log.d(
                    Config.LOGTAG,
                    "not including some actions in error notification because service has died",
                    e)
        }
        if (torNotAvailable) {
            if (TorServiceUtils.isOrbotInstalled(mXmppConnectionService)) {
                mBuilder.addAction(
                        uk.xa0.tulkki.ui.R.drawable.ic_play_circle_24dp,
                        mXmppConnectionService.getString(uk.xa0.tulkki.ui.R.string.start_orbot),
                        PendingIntent.getActivity(
                                mXmppConnectionService,
                                147,
                                TorServiceUtils.LAUNCH_INTENT,
                                if (s())
                                        PendingIntent.FLAG_IMMUTABLE or
                                                PendingIntent.FLAG_UPDATE_CURRENT
                                else PendingIntent.FLAG_UPDATE_CURRENT))
            } else {
                mBuilder.addAction(
                        uk.xa0.tulkki.ui.R.drawable.ic_download_24dp,
                        mXmppConnectionService.getString(R.string.install_orbot),
                        PendingIntent.getActivity(
                                mXmppConnectionService,
                                146,
                                TorServiceUtils.INSTALL_INTENT,
                                if (s())
                                        PendingIntent.FLAG_IMMUTABLE or
                                                PendingIntent.FLAG_UPDATE_CURRENT
                                else PendingIntent.FLAG_UPDATE_CURRENT))
            }
        }
        mBuilder.setVisibility(Notification.VISIBILITY_PRIVATE)
        mBuilder.setSmallIcon(uk.xa0.tulkki.ui.R.drawable.ic_warning_24dp)
        mBuilder.setLocalOnly(true)
        mBuilder.setPriority(Notification.PRIORITY_LOW)
        val intent: Intent
        if (AccountUtils.MANAGE_ACCOUNT_ACTIVITY != null) {
            intent = Intent(mXmppConnectionService, AccountUtils.MANAGE_ACCOUNT_ACTIVITY)
        } else {
            intent = Intent(mXmppConnectionService, EditAccountActivity::class.java)
            intent.putExtra("jid", errors.get(0).getJid().asBareJid().toString())
            intent.putExtra(EditAccountActivity.EXTRA_OPENED_FROM_NOTIFICATION, true)
        }
        mBuilder.setContentIntent(
                PendingIntent.getActivity(
                        mXmppConnectionService,
                        145,
                        intent,
                        if (s())
                                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                        else PendingIntent.FLAG_UPDATE_CURRENT))
        if (Compatibility.runsTwentySix()) {
            mBuilder.setChannelId("error")
        }
        notify(ERROR_NOTIFICATION_ID, mBuilder.build())
    }

    fun updateFileAddingNotification(current: Int, message: Message) {
        val notification = videoTranscoding(current, message)
        notify(ONGOING_VIDEO_TRANSCODING_NOTIFICATION_ID, notification)
    }

    private fun videoTranscoding(current: Int, message: Message?): Notification {
        val builder = Notification.Builder(mXmppConnectionService)
        builder.setContentTitle(
                mXmppConnectionService.getString(uk.xa0.tulkki.ui.R.string.transcoding_video))
        if (current >= 0) {
            builder.setProgress(100, current, false)
        } else {
            builder.setProgress(100, 0, true)
        }
        builder.setSmallIcon(uk.xa0.tulkki.ui.R.drawable.ic_hourglass_top_24dp)
        if (message != null) {
            // Tulkki: no conversation, no content intent to attach - the notification still stands.
            message.getConversation()?.let { builder.setContentIntent(createContentIntent(it)) }
        }
        builder.setOngoing(true)
        if (Compatibility.runsTwentySix()) {
            builder.setChannelId("compression")
        }
        return builder.build()
    }

    override fun getIndeterminateVideoTranscoding(): Notification {
        return videoTranscoding(-1, null)
    }

    private fun notify(tag: String, id: Int, notification: Notification) {
        if (ActivityCompat.checkSelfPermission(
                        mXmppConnectionService, Manifest.permission.POST_NOTIFICATIONS) !=
                PackageManager.PERMISSION_GRANTED) {
            return
        }
        val notificationManager =
                mXmppConnectionService.getSystemService(NotificationManager::class.java)
        try {
            notificationManager.notify(tag, id, notification)
        } catch (e: RuntimeException) {
            Log.d(Config.LOGTAG, "unable to make notification", e)
        }
    }

    override fun notify(id: Int, notification: Notification) {
        if (ActivityCompat.checkSelfPermission(
                        mXmppConnectionService, Manifest.permission.POST_NOTIFICATIONS) !=
                PackageManager.PERMISSION_GRANTED) {
            return
        }
        val notificationManager =
                mXmppConnectionService.getSystemService(NotificationManager::class.java)
        try {
            notificationManager.notify(id, notification)
        } catch (e: RuntimeException) {
            Log.d(Config.LOGTAG, "unable to make notification", e)
        }
    }

    override fun cancel(id: Int) {
        val notificationManager = NotificationManagerCompat.from(mXmppConnectionService)
        try {
            notificationManager.cancel(id)
        } catch (e: RuntimeException) {
            Log.d(Config.LOGTAG, "unable to cancel notification", e)
        }
    }

    private fun cancel(tag: String, id: Int) {
        val notificationManager = NotificationManagerCompat.from(mXmppConnectionService)
        try {
            notificationManager.cancel(tag, id)
        } catch (e: RuntimeException) {
            Log.d(Config.LOGTAG, "unable to cancel notification", e)
        }
    }

    private class MissedCallsInfo(time: Long) {
        var numberOfCalls = 1
            private set
        var lastTime = time
            private set

        fun newMissedCall(time: Long) {
            ++numberOfCalls
            lastTime = time
        }

        fun removeMissedCall(): Boolean {
            --numberOfCalls
            return numberOfCalls <= 0
        }
    }

    fun markRetracted(message: Message) {
        synchronized(notifications) {
            // Tulkki: no conversation uuid, nothing to retract.
            val conversationUuid = message.getConversationUuid() ?: return
            val messages = notifications.get(conversationUuid)
            if (messages != null) {
                var removed = false
                val iterator = messages.iterator()
                while (iterator.hasNext()) {
                    val m = iterator.next()
                    if (m.getUuid().equals(message.getUuid())) {
                        iterator.remove()
                        removed = true
                    }
                }
                if (removed) {
                    if (messages.isEmpty()) {
                        notifications.remove(conversationUuid)
                        cancel(conversationUuid, NOTIFICATION_ID)
                    }
                    updateNotification(false)
                }
            }
        }
    }

    override fun hasNewMissedCalls(): Boolean {
        synchronized(mMissedCalls) {
            return !mMissedCalls.isEmpty()
        }
    }

    override fun showLiveLocationNotification(conversationUuid: String?) {
        val stopIntent = Intent(mXmppConnectionService, XmppConnectionService::class.java)
        stopIntent.setAction(uk.xa0.tulkki.xmpp.services.ServiceActions.ACTION_STOP_LIVE_LOCATION)
        stopIntent.putExtra("uuid", conversationUuid)
        val stopPendingIntent =
                PendingIntent.getService(
                        mXmppConnectionService,
                        0,
                        stopIntent,
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

        val openIntent =
                Intent(mXmppConnectionService, uk.xa0.tulkki.ui.ConversationListActivity::class.java)
        openIntent.setAction(uk.xa0.tulkki.ui.ConversationListActivity.ACTION_VIEW_CONVERSATION)
        openIntent.putExtra(
                uk.xa0.tulkki.ui.ConversationListActivity.EXTRA_CONVERSATION, conversationUuid)
        val openPendingIntent =
                PendingIntent.getActivity(
                        mXmppConnectionService,
                        0,
                        openIntent,
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

        val builder = NotificationCompat.Builder(mXmppConnectionService, "live_location")
        builder.setSmallIcon(uk.xa0.tulkki.ui.R.drawable.ic_location_pin_24dp)
        builder.setContentTitle(
                mXmppConnectionService.getString(uk.xa0.tulkki.ui.R.string.share_live_location))
        builder.setContentText(
                mXmppConnectionService.getString(
                        uk.xa0.tulkki.ui.R.string.live_location_notification_text))
        builder.setOngoing(true)
        builder.setLocalOnly(true)
        builder.setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
        builder.setPriority(NotificationCompat.PRIORITY_LOW)
        builder.setContentIntent(openPendingIntent)
        builder.addAction(
                NotificationCompat.Action.Builder(
                                uk.xa0.tulkki.ui.R.drawable.ic_location_disabled_24dp,
                                mXmppConnectionService.getString(
                                        uk.xa0.tulkki.ui.R.string.stop_live_location),
                                stopPendingIntent)
                        .build())
        notify(LIVE_LOCATION_NOTIFICATION_ID, builder.build())
    }

    override fun cancelLiveLocationNotification() {
        cancel(LIVE_LOCATION_NOTIFICATION_ID)
    }

    companion object {

        @JvmField val CATCHUP_LOCK: Any = Any()

        private val LED_COLOR = 0xff0080FF.toInt()

        private val CALL_PATTERN = longArrayOf(0, 500, 300, 600, 3000)

        /**
         * Tulkki: how long a live message's notification is held back so the buzz carries the
         * translation rather than the news that something arrived.
         *
         * Bounded on purpose, and short on purpose. A queue behind a slow API, a reached cap or no
         * key at all must never mean a silent phone, so the notification is posted when this elapses
         * whether or not a translation landed - carrying the translation when there is one and the
         * same covered wording when there is not. The original must never reach a notification, and
         * this does not weaken that: the delay changes *when* the notification is built, not what it
         * may contain. A couple of seconds is the point - the owner is deliberately trading immediacy
         * for accuracy.
         */
        private const val TULKKI_NOTIFY_DELAY_MS = 2_000L

        /**
         * Tulkki: the ceiling on that hold, measured from the first message of a burst.
         *
         * Re-posting on every arriving message is a debounce, and a debounce with no ceiling means a
         * peer writing every 1.5 seconds - or any busy room - never gets a notification at all, which
         * is the opposite of the point. So the quiet window may be extended by each new message but
         * never past this: ten seconds after the first message of the burst the notification is
         * posted whatever the traffic is doing, carrying the translation if one has landed and the
         * covered wording if not. Silence is the one outcome that is not allowed.
         */
        private const val TULKKI_NOTIFY_MAX_DELAY_MS = 10_000L

        private const val MESSAGES_GROUP = "eu.siacs.conversations.messages"
        private const val MISSED_CALLS_GROUP = "eu.siacs.conversations.missed_calls"
        private const val NOTIFICATION_ID_MULTIPLIER = 1024 * 1024
        const val FOREGROUND_NOTIFICATION_ID = NOTIFICATION_ID_MULTIPLIER * 4
        private const val NOTIFICATION_ID = NOTIFICATION_ID_MULTIPLIER * 2
        private const val ERROR_NOTIFICATION_ID = NOTIFICATION_ID_MULTIPLIER * 6
        private const val INCOMING_CALL_NOTIFICATION_ID = NOTIFICATION_ID_MULTIPLIER * 8
        const val ONGOING_CALL_NOTIFICATION_ID = NOTIFICATION_ID_MULTIPLIER * 10
        const val MISSED_CALL_NOTIFICATION_ID = NOTIFICATION_ID_MULTIPLIER * 12
        private const val DELIVERY_FAILED_NOTIFICATION_ID = NOTIFICATION_ID_MULTIPLIER * 13
        const val ONGOING_VIDEO_TRANSCODING_NOTIFICATION_ID = NOTIFICATION_ID_MULTIPLIER * 14
        const val LIVE_LOCATION_NOTIFICATION_ID = NOTIFICATION_ID_MULTIPLIER * 16

        private const val INCOMING_CALLS_NOTIFICATION_CHANNEL = "incoming_calls_channel"
        private const val INCOMING_CALLS_NOTIFICATION_CHANNEL_PREFIX = "incoming_calls_channel#"
        const val MESSAGES_NOTIFICATION_CHANNEL = "messages"

        private fun displaySnoozeAction(messages: List<Message>): Boolean {
            var numberOfMessagesWithoutReply = 0
            for (message in messages) {
                if (message.getStatus() == Message.STATUS_RECEIVED) {
                    ++numberOfMessagesWithoutReply
                } else {
                    return false
                }
            }
            return numberOfMessagesWithoutReply >= 3
        }

        /**
         * The nick is nullable because upstream's Java quoted it inside a concatenation: `String`'s
         * `+` turns a null into the literal text `null` (`"\\Q" + null + "\\E"` never throws), so a
         * null nick has always highlighted the word "null". `nick.toString()` reproduces exactly
         * that, and the callers keep their own null check where upstream put it.
         */
        @JvmStatic
        fun generateNickHighlightPattern(nick: String?): Pattern {
            return Pattern.compile("(?<=(^|\\s))" + Pattern.quote(nick.toString()) + "(?=\\s|$|\\p{Punct})")
        }

        private fun isImageMessage(message: Message): Boolean {
            return message.getType() != Message.TYPE_TEXT &&
                    message.getTransferable() == null &&
                    !message.isDeleted() &&
                    message.getEncryption() != Message.ENCRYPTION_PGP &&
                    message.getFileParams().height > 0
        }

        @RequiresApi(api = Build.VERSION_CODES.R)
        @JvmStatic
        fun createConversationChannel(context: Context, shortcut: ShortcutInfoCompat) {
            val messagesChannel = prepareMessagesChannel(context, UUID.randomUUID().toString())
            messagesChannel.setName(shortcut.getShortLabel())
            messagesChannel.setConversationId(MESSAGES_NOTIFICATION_CHANNEL, shortcut.getId())
            val notificationManager = context.getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(messagesChannel)
        }

        @RequiresApi(api = Build.VERSION_CODES.O)
        private fun prepareMessagesChannel(context: Context, id: String): NotificationChannel {
            val messagesChannel =
                    NotificationChannel(
                            id,
                            context.getString(uk.xa0.tulkki.ui.R.string.messages_channel_name),
                            NotificationManager.IMPORTANCE_HIGH)
            messagesChannel.setShowBadge(true)
            messagesChannel.setSound(
                    RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION),
                    AudioAttributes.Builder()
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                            .build())
            messagesChannel.setLightColor(LED_COLOR)
            val dat = 70
            val pattern = longArrayOf(0, (3 * dat).toLong(), dat.toLong(), dat.toLong())
            messagesChannel.setVibrationPattern(pattern)
            messagesChannel.enableVibration(true)
            messagesChannel.enableLights(true)
            messagesChannel.setGroup("chats")
            return messagesChannel
        }

        @RequiresApi(api = Build.VERSION_CODES.O)
        private fun createInitialIncomingCallChannelIfNecessary(context: Context) {
            val currentIteration = getCurrentIncomingCallChannelIteration(context)
            if (currentIteration.isPresent) {
                return
            }
            createInitialIncomingCallChannel(context)
        }

        @RequiresApi(api = Build.VERSION_CODES.O)
        @JvmStatic
        fun getCurrentIncomingCallChannelIteration(context: Context): Optional<Int> {
            val notificationManager = context.getSystemService(NotificationManager::class.java)
            for (channel in notificationManager.getNotificationChannels()) {
                val id = channel.getId()
                if (Strings.isNullOrEmpty(id)) {
                    continue
                }
                if (id.startsWith(INCOMING_CALLS_NOTIFICATION_CHANNEL_PREFIX)) {
                    val parts = Splitter.on('#').splitToList(id)
                    if (parts.size == 2) {
                        val iteration = Ints.tryParse(parts.get(1))
                        if (iteration != null) {
                            return Optional.of(iteration)
                        }
                    }
                }
            }
            return Optional.absent()
        }

        @RequiresApi(api = Build.VERSION_CODES.O)
        @JvmStatic
        fun getCurrentIncomingCallChannel(context: Context): Optional<NotificationChannel> {
            val iteration = getCurrentIncomingCallChannelIteration(context)
            return iteration.transform { i: Int ->
                val notificationManager = context.getSystemService(NotificationManager::class.java)
                notificationManager.getNotificationChannel(
                        INCOMING_CALLS_NOTIFICATION_CHANNEL_PREFIX + i)
            }
        }

        @RequiresApi(api = Build.VERSION_CODES.O)
        private fun createInitialIncomingCallChannel(context: Context) {
            val appSettings = AppSettings(context)
            val ringtoneUri = appSettings.getRingtone() ?: throw NullPointerException()
            createIncomingCallChannel(context, ringtoneUri, 0)
        }

        @RequiresApi(api = Build.VERSION_CODES.O)
        @JvmStatic
        fun recreateIncomingCallChannel(context: Context, ringtone: Uri?) {
            val currentIteration = getCurrentIncomingCallChannelIteration(context)
            val nextIteration: Int
            if (currentIteration.isPresent) {
                val notificationManager = context.getSystemService(NotificationManager::class.java)
                notificationManager.deleteNotificationChannel(
                        INCOMING_CALLS_NOTIFICATION_CHANNEL_PREFIX + currentIteration.get())
                nextIteration = currentIteration.get() + 1
            } else {
                nextIteration = 0
            }
            createIncomingCallChannel(context, ringtone, nextIteration)
        }

        @RequiresApi(api = Build.VERSION_CODES.O)
        private fun createIncomingCallChannel(
                context: Context,
                ringtoneUri: Uri?,
                iteration: Int
        ) {
            val notificationManager = context.getSystemService(NotificationManager::class.java)
            val id = INCOMING_CALLS_NOTIFICATION_CHANNEL_PREFIX + iteration
            Log.d(Config.LOGTAG, "creating incoming call channel with id " + id)
            val incomingCallsChannel =
                    NotificationChannel(
                            id,
                            context.getString(
                                    uk.xa0.tulkki.ui.R.string.incoming_calls_channel_name),
                            NotificationManager.IMPORTANCE_HIGH)
            incomingCallsChannel.setSound(
                    ringtoneUri,
                    AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .build())
            incomingCallsChannel.setShowBadge(false)
            incomingCallsChannel.setLightColor(LED_COLOR)
            incomingCallsChannel.enableLights(true)
            incomingCallsChannel.setGroup("calls")
            incomingCallsChannel.setBypassDnd(true)
            incomingCallsChannel.enableVibration(true)
            incomingCallsChannel.setVibrationPattern(CALL_PATTERN)
            notificationManager.createNotificationChannel(incomingCallsChannel)
        }

        @JvmStatic
        fun isQuietHours(context: Context, account: Account?): Boolean {
            // if (mXmppConnectionService.getAccounts().size() < 2) account = null;
            val suffix = if (account == null) "" else ":" + account.getUuid()
            val preferences = PreferenceManager.getDefaultSharedPreferences(context)
            if (!preferences.getBoolean(
                            "enable_quiet_hours" + suffix,
                            context.getResources()
                                    .getBoolean(uk.xa0.tulkki.ui.R.bool.enable_quiet_hours))) {
                return false
            }
            val startTime =
                    TimePreference.minutesToTimestamp(
                            preferences.getLong(
                                    "quiet_hours_start" + suffix, TimePreference.DEFAULT_VALUE))
            val endTime =
                    TimePreference.minutesToTimestamp(
                            preferences.getLong(
                                    "quiet_hours_end" + suffix, TimePreference.DEFAULT_VALUE))
            val nowTime = Calendar.getInstance().getTimeInMillis()

            return if (endTime < startTime) {
                nowTime > startTime || nowTime < endTime
            } else {
                nowTime > startTime && nowTime < endTime
            }
        }

        @JvmStatic
        fun cancelIncomingCallNotification(context: Context) {
            val notificationManager = NotificationManagerCompat.from(context)
            try {
                notificationManager.cancel(INCOMING_CALL_NOTIFICATION_ID)
            } catch (e: RuntimeException) {
                Log.d(Config.LOGTAG, "unable to cancel incoming call notification after crash", e)
            }
        }

        /**
         * Tulkki: whether this message's notification should wait for a translation. True only for a
         * received body that needs one and does not have one yet - a link, a ping, a message already
         * in the app language and a message that already carries its translation all notify
         * immediately. The interpreter is the last input and the first thing read: off, nothing is
         * owed anything and this is false.
         *
         * <p>`getBody(true)`, which is the call the renderer makes (`UIHelper.getMessagePreview`) and
         * deliberately not the bare `getBody()` this used to pass: for a reply whose own body is an
         * emoji or a link and whose fallback carries the language, the two disagree about whether
         * anything was owed, and the disagreement only ever *delays* the buzz. One call, so the hold
         * and the line it holds for cannot differ (`docs/MIGRATION.md`, "Design: the notifier" §2.2).
         * The words themselves never come from here: what the shade draws is the cover's own line
         * or the renderer's answer.
         */
        private fun translationPending(message: Message, interpreter: Interpreter): Boolean {
            return message.getTranslationState() == Message.TRANSLATION_NONE &&
                    DisplayedBody.needsTranslation(
                            message.getStatus(),
                            message.getBody(true),
                            ConversationName.of(message.getConversation()),
                            interpreter)
        }

        private fun pendingServiceIntent(
                context: Context,
                action: String,
                requestCode: Int
        ): PendingIntent {
            return pendingServiceIntent(context, action, requestCode, ImmutableMap.of())
        }

        private fun pendingServiceIntent(
                context: Context,
                action: String,
                requestCode: Int,
                extras: Map<String, String>
        ): PendingIntent {
            val intent = Intent(context, XmppConnectionService::class.java)
            intent.setAction(action)
            for (entry in extras.entries) {
                intent.putExtra(entry.key, entry.value)
            }
            return PendingIntent.getService(
                    context,
                    requestCode,
                    intent,
                    if (s()) PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                    else PendingIntent.FLAG_UPDATE_CURRENT)
        }

        @RequiresApi(api = Build.VERSION_CODES.O)
        private fun pendingNotificationSettingsIntent(context: Context): PendingIntent {
            val intent = Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
            intent.putExtra(Settings.EXTRA_APP_PACKAGE, context.getPackageName())
            intent.putExtra(Settings.EXTRA_CHANNEL_ID, "foreground")
            return PendingIntent.getActivity(
                    context,
                    89,
                    intent,
                    if (s()) PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                    else PendingIntent.FLAG_UPDATE_CURRENT)
        }
    }
}
