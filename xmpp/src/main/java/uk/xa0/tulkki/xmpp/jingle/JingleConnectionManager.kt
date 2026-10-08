package uk.xa0.tulkki.xmpp.jingle

import android.telecom.TelecomManager
import android.telecom.VideoProfile
import android.util.Base64
import android.util.Log
import com.google.common.base.Objects
import com.google.common.base.Optional
import com.google.common.base.Preconditions
import com.google.common.cache.Cache
import com.google.common.cache.CacheBuilder
import com.google.common.collect.Collections2
import com.google.common.collect.ComparisonChain
import com.google.common.collect.ImmutableSet
import java.lang.ref.WeakReference
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import uk.xa0.tulkki.libs.AudioDevice
import uk.xa0.tulkki.xmpp.Config
import uk.xa0.tulkki.libs.Jid
import uk.xa0.tulkki.xmpp.R
import uk.xa0.tulkki.xmpp.XmppConnection
import uk.xa0.tulkki.xmpp.jingle.stanzas.Content
import uk.xa0.tulkki.xmpp.jingle.stanzas.GenericDescription
import uk.xa0.tulkki.xmpp.jingle.stanzas.Propose
import uk.xa0.tulkki.xmpp.jingle.stanzas.Reason
import uk.xa0.tulkki.xmpp.jingle.stanzas.RtpDescription
import uk.xa0.tulkki.xmpp.jingle.transports.InbandBytestreamsTransport
import uk.xa0.tulkki.xmpp.models.jingle.Jingle
import uk.xa0.tulkki.xmpp.models.stanza.Iq
import uk.xa0.tulkki.xmpp.refs.AccountRef
import uk.xa0.tulkki.xmpp.refs.ContactRef
import uk.xa0.tulkki.xmpp.refs.ConversationRef
import uk.xa0.tulkki.xmpp.refs.ConversationalRef
import uk.xa0.tulkki.xmpp.refs.MessageRef
import uk.xa0.tulkki.xmpp.services.AbstractConnectionManager
import uk.xa0.tulkki.xmpp.services.CallIntegrationPort
import uk.xa0.tulkki.xmpp.services.XmppConnectionService
import uk.xa0.tulkki.xml.Element
import uk.xa0.tulkki.xml.Namespace

/**
 * The router between the XMPP wire and the two Jingle connection families (XEP-0166/0167/0234).
 *
 * Ported from Java by lane `H`. Decisions taken rather than inherited:
 *
 * 1. **Every package-private member a Java caller reaches is `internal` with an explicit
 *    `@JvmName`**, the `WebRTCWrapper` precedent: Kotlin's `internal` mangles the JVM name
 *    (`nextRandomId$xmpp`) and `AbstractJingleConnection:231,284,393,408` and
 *    `JingleRtpConnection:1708,2348,2891` call these by name. `@JvmName` pins the plain name, so the
 *    surface javac sees is unchanged; the `internal` half keeps the declaration out of other
 *    modules. The members no Java caller reaches (`schedule` has one Java caller too, so it is
 *    pinned as well; `isUsingClearNet`, the private helpers) keep their plain names.
 * 2. **A Java `==` on two objects is `===` here, not `==`.** Every `proposal.account == account`,
 *    `id.account == account` and `id.account == contact.getAccount()` in the Java compared
 *    references; Kotlin's `==` would have answered `equals`, and `AccountRef`'s implementations do
 *    not override it. The five sites are `===`/`!==` in this file.
 * 3. **`RtpSessionProposal`'s two private fields are renamed** (`accountRef`, `callIntegrationRef`).
 *    Java reached a nested class's `private` member from the enclosing class; Kotlin's `private` is
 *    class-scoped. Keeping the name `account` would have clashed with the `getAccount()` override
 *    the `OngoingRtpSession` interface demands (a Kotlin property named `account` already generates
 *    `getAccount()`), so the backing fields take the `Ref` suffix and the outer class goes through
 *    the public accessors, which return the same fields. `with`/`sessionId`/`media` stay `@JvmField`
 *    (Java reads them as fields; `ConversationFragment:2864` and `MessageGenerator:448`) and their
 *    `getWith`/`getSessionId`/`getMedia` overrides are separate methods.
 * 4. **`RtpSessionProposal.getMedia()` is `MutableSet`** because `OngoingRtpSession` declares it so,
 *    while the stored field is Java's `Set<Media>`; the field is the same `java.util.Set` either way
 *    and the widening cast is erased. `TerminatedRtpSession.media` stays a read-only `Set`, which is
 *    what its one caller (`RtpSessionActivity.resetIntent`) takes.
 * 5. **`Arrays.asList(...).contains(x)` with a nullable `x` becomes an explicit comparison**: Kotlin's
 *    `List.contains` takes a non-null element, and Java's `Arrays.asList` answered `false` for
 *    `null`, so the two `==` tests are the identical predicate.
 * 6. **A `Jid + "text"` concatenation is `"" + jid + "text"`.** Java compiled the `Jid` receiver
 *    through `String.valueOf`; Kotlin has no `plus` on a Java type, so the string receiver is spelled
 *    out at each site.
 * 7. **The class is `open`.** Java declared it `public class` and nothing extends it today; the
 *    Java's own modifier is written out rather than Kotlin's `final` default.
 * 8. **`rejectRtpSession`/`endRtpSession` take a nullable `sessionId`, and `failProceed` a nullable
 *    `message`.** The Java's `onStartCommand` passed `intent.getStringExtra(...)` straight in, and
 *    those bodies tested it with `sessionId.equals(...)`/`Strings.nullToEmpty(message)` - a null
 *    matched nothing and a null message logged empty, no throw. The declarations follow the Java's
 *    behaviour instead of forcing a throw the Java never had.
 * 9. **A null session id is a lookup miss, so the three lookups that take one are `String?`.**
 *    `getTerminalSessionState` is the shape: the Java built `PersistableSessionId(with, sessionId)`
 *    (whose `equals`/`hashCode` are `Objects.equal`/`Objects.hashCode`) and handed it to Guava's
 *    `getIfPresent`, so a null id was a miss and never an NPE. `getRtpSessionProposal` is the same
 *    miss one level down - the Java wrote `rtpSessionProposal.sessionId.equals(sessionId)`, which
 *    answers `false` for a null argument - and `updateProposedSessionDiscovered` inherits it and
 *    logs it. **These are restorations, not widenings in the forbidden sense**: the by-value rule
 *    forbids a Kotlin declaration *wider* than the Java's, and each of these was *narrower* than
 *    the Java it came from (`RtpSessionActivity`'s Java passed `intent.getStringExtra(...)` straight
 *    in). The forwarders that do not look anything up (`writeLogMissedOutgoing`,
 *    `writeLogMissedIncoming`, `sendJingleMessageFinish`) keep a non-null `sessionId`: their bodies
 *    only hand it to a callee another file owns, so the tolerance there is that callee's contract.
 */
open class JingleConnectionManager(service: XmppConnectionService) :
    AbstractConnectionManager(service) {

    private val rtpSessionProposals: HashMap<RtpSessionProposal, DeviceDiscoveryState> = HashMap()
    private val connections:
        ConcurrentHashMap<AbstractJingleConnection.Id, AbstractJingleConnection> =
        ConcurrentHashMap()

    private val terminatedSessions: Cache<PersistableSessionId, TerminatedRtpSession> =
        CacheBuilder.newBuilder()
            .expireAfterWrite(24, TimeUnit.HOURS)
            .build<PersistableSessionId, TerminatedRtpSession>()

    fun deliverPacket(account: AccountRef, packet: Iq) {
        val jingle =
            Preconditions.checkNotNull(
                packet.getExtension(Jingle::class.java),
                "Passed iq packet w/o jingle extension to Connection Manager",
            )
        val sessionId: String? = jingle.getSessionId()
        val action: Jingle.Action? = jingle.getAction()
        if (sessionId == null) {
            respondWithJingleError(account, packet, "unknown-session", "item-not-found", "cancel")
            return
        }
        if (action == null) {
            respondWithJingleError(account, packet, null, "bad-request", "cancel")
            return
        }
        val id = AbstractJingleConnection.Id.of(account, packet, jingle)
        val existingJingleConnection = connections[id]
        if (existingJingleConnection != null) {
            existingJingleConnection.deliverPacket(packet)
        } else if (action == Jingle.Action.SESSION_INITIATE) {
            val from: Jid = packet.getFrom() ?: throw NullPointerException()
            val content: Content? = jingle.getJingleContent()
            val descriptionNamespace = content?.getDescriptionNamespace()
            val connection: AbstractJingleConnection
            if (Namespace.JINGLE_APPS_FILE_TRANSFER == descriptionNamespace) {
                connection = JingleFileTransferConnection(this, id, from)
            } else if (
                Namespace.JINGLE_APPS_RTP == descriptionNamespace && isUsingClearNet(account)
            ) {
                // START of the fix for session-initiate
                val contact = account.getRoster().getContact(packet.getFrom() ?: throw NullPointerException("packet has no from"))
                if (contact != null && contact.areCallsDisabled()) {
                    Log.d(
                        Config.LOGTAG,
                        "" +
                            id.account.getJid().asBareJid() +
                            ": rejecting session-initiate with disabled contact " +
                            id.with,
                    )
                    sendDecline(account, packet, id)
                    return
                }
                // END of the fix for session-initiate
                val sessionEnded =
                    this.terminatedSessions.asMap().containsKey(PersistableSessionId.of(id))
                val stranger = isWithStrangerAndStrangerNotificationsAreOff(account, id.with)
                val busy = isBusy()
                if (busy || sessionEnded || stranger) {
                    Log.d(
                        Config.LOGTAG,
                        "" +
                            id.account.getJid().asBareJid() +
                            ": rejected session with " +
                            id.with +
                            " because busy. sessionEnded=" +
                            sessionEnded +
                            ", stranger=" +
                            stranger,
                    )
                    sendSessionTerminate(account, packet, id)
                    if (busy || stranger) {
                        writeLogMissedIncoming(
                            account,
                            id.with,
                            id.sessionId,
                            null,
                            System.currentTimeMillis(),
                            stranger,
                        )
                    }
                    return
                }
                connection = JingleRtpConnection(this, id, from)
            } else {
                respondWithJingleError(
                    account,
                    packet,
                    "unsupported-info",
                    "feature-not-implemented",
                    "cancel",
                )
                return
            }
            connections[id] = connection
            mXmppConnectionService.updateConversationUi()
            connection.deliverPacket(packet)
            if (connection is JingleRtpConnection) {
                addNewIncomingCall(connection)
            }
        } else {
            Log.d(Config.LOGTAG, "unable to route jingle packet: $packet")
            respondWithJingleError(account, packet, "unknown-session", "item-not-found", "cancel")
        }
    }

    private fun addNewIncomingCall(rtpConnection: JingleRtpConnection) {
        if (true) {
            // We do this inside the startRinging in the rtpConnection now so that fallback is
            // possible
            return
        }
        if (rtpConnection.isTerminated()) {
            Log.d(
                Config.LOGTAG,
                "skip call integration because something must have gone during initiate",
            )
            return
        }
        if (
            mXmppConnectionService
                .callIntegration()
                .addNewIncomingCall(mXmppConnectionService, rtpConnection.getId())
        ) {
            return
        }
        rtpConnection.integrationFailure()
    }

    private fun sendSessionTerminate(
        account: AccountRef,
        request: Iq,
        id: AbstractJingleConnection.Id,
    ) {
        mXmppConnectionService.sendIqPacket(
            account,
            request.generateResponse(Iq.Type.RESULT),
            null,
        )
        val iq = Iq(Iq.Type.SET)
        iq.setTo(id.with)
        val sessionTermination =
            iq.addExtension(Jingle(Jingle.Action.SESSION_TERMINATE, id.sessionId))
        sessionTermination.setReason(Reason.BUSY, null)
        mXmppConnectionService.sendIqPacket(account, iq, null)
    }

    private fun isUsingClearNet(account: AccountRef): Boolean =
        !account.isOnion() &&
            !mXmppConnectionService.useTorToConnect() &&
            !account.isI2P() &&
            !mXmppConnectionService.useI2PToConnect()

    fun isBusy(): Boolean {
        for (connection in this.connections.values) {
            if (connection is JingleRtpConnection) {
                if (connection.isTerminated() && connection.getCallIntegration().isDestroyed()) {
                    continue
                }
                return true
            }
        }
        synchronized(this.rtpSessionProposals) {
            if (this.rtpSessionProposals.containsValue(DeviceDiscoveryState.DISCOVERED)) return true
            if (this.rtpSessionProposals.containsValue(DeviceDiscoveryState.SEARCHING)) return true
            if (
                this.rtpSessionProposals.containsValue(DeviceDiscoveryState.SEARCHING_ACKNOWLEDGED)
            ) {
                return true
            }
            return false
        }
    }

    fun hasJingleRtpConnection(account: AccountRef): Boolean {
        for (connection in this.connections.values) {
            if (connection is JingleRtpConnection) {
                if (connection.isTerminated()) {
                    continue
                }
                if (connection.getId().account === account) {
                    return true
                }
            }
        }
        return false
    }

    private fun findMatchingSessionProposal(
        account: AccountRef,
        with: Jid,
        media: Set<Media>,
    ): Optional<RtpSessionProposal> {
        synchronized(this.rtpSessionProposals) {
            for ((proposal, state) in this.rtpSessionProposals) {
                val openProposal =
                    state == DeviceDiscoveryState.DISCOVERED ||
                        state == DeviceDiscoveryState.SEARCHING ||
                        state == DeviceDiscoveryState.SEARCHING_ACKNOWLEDGED
                if (
                    openProposal &&
                        proposal.getAccount() === account &&
                        proposal.with == with.asBareJid() &&
                        proposal.media == media
                ) {
                    return Optional.of(proposal)
                }
            }
        }
        return Optional.absent()
    }

    private fun hasMatchingRtpSession(
        account: AccountRef,
        with: Jid,
        media: Set<Media>,
    ): String? {
        for (connection in this.connections.values) {
            if (connection is JingleRtpConnection) {
                if (connection.isTerminated()) {
                    continue
                }
                if (
                    connection.getId().account === account &&
                        connection.getId().with.asBareJid() == with.asBareJid() &&
                        connection.getMedia() == media
                ) {
                    return connection.getId().sessionId
                }
            }
        }
        return null
    }

    private fun isWithStrangerAndStrangerNotificationsAreOff(
        account: AccountRef,
        with: Jid,
    ): Boolean {
        val ringFromStrangers =
            mXmppConnectionService.getBooleanPreference(
                "ring_from_strangers",
                R.bool.notifications_from_strangers,
            )
        if (ringFromStrangers) return false
        val conversation = mXmppConnectionService.findOrCreateConversation(account, with, false, true)
        return conversation.isWithStranger()
    }

    @JvmName("schedule")
    internal fun schedule(
        runnable: Runnable,
        delay: Long,
        timeUnit: TimeUnit,
    ): ScheduledFuture<*> = SCHEDULED_EXECUTOR_SERVICE.schedule(runnable, delay, timeUnit)

    /**
     * Tulkki: 3.7 C5-E4 - the parameter is the island's ref now, because `Id.account` is and this is
     * the callee that kept it model-typed. The body reads only `account.getXmppConnection()`, which
     * is the ref's own member, so this is a one-line widening with no cast and no `instanceof`
     * guard.
     */
    @JvmName("respondWithJingleError")
    internal fun respondWithJingleError(
        account: AccountRef,
        original: Iq,
        jingleCondition: String?,
        condition: String,
        conditionType: String,
    ) {
        val response = original.generateResponse(Iq.Type.ERROR)
        val error = response.addChild("error")
        error.setAttribute("type", conditionType)
        error.addChild(condition, "urn:ietf:params:xml:ns:xmpp-stanzas")
        if (jingleCondition != null) {
            error.addChild(jingleCondition, Namespace.JINGLE_ERRORS)
        }
        (account.getXmppConnection() ?: throw NullPointerException("account has no xmpp connection")).sendIqPacket(response, null)
    }

    /**
     * Tulkki: 3.7 C5-E4 - the pair-9 delegating wrapper is gone. `MessageParser` holds an
     * `AccountRef` and so does this method now; the two would have the same erasure, and the
     * `(Account) accountRef` cast it carried is exactly the narrowing this slice deletes.
     */
    fun deliverMessage(
        account: AccountRef,
        to: Jid?,
        from: Jid,
        message: Element,
        remoteMsgId: String?,
        serverMsgId: String?,
        timestamp: Long,
    ) {
        Preconditions.checkArgument(Namespace.JINGLE_MESSAGE == message.getNamespace())
        val sessionId = message.getAttribute("id")
        if (sessionId == null) {
            return
        }

        if ("propose" == message.getName()) {
            val contact = account.getRoster().getContact(from)
            if (contact != null && contact.areCallsDisabled()) {
                Log.d(
                    Config.LOGTAG,
                    "" +
                        account.getJid().asBareJid() +
                        ": ignoring incoming call (propose) from disabled contact " +
                        from,
                )
                val rejection = uk.xa0.tulkki.xmpp.models.stanza.Message()
                rejection.setType(uk.xa0.tulkki.xmpp.models.stanza.Message.Type.CHAT)
                rejection.setTo(from)
                rejection
                    .addChild("reject", Namespace.JINGLE_MESSAGE)
                    .setAttribute("id", sessionId)
                rejection.addChild("store", "urn:xmpp:hints")
                mXmppConnectionService.sendMessagePacket(account, rejection)
                return
            }
        }

        if ("accept" == message.getName() || "reject" == message.getName()) {
            for (connection in connections.values) {
                if (connection is JingleRtpConnection) {
                    val id = connection.getId()
                    if (id.account === account && id.sessionId == sessionId) {
                        connection.deliveryMessage(from, message, serverMsgId, timestamp)
                        return
                    }
                }
            }
            if ("accept" == message.getName()) return
        }
        val fromSelf = from.asBareJid() == account.getJid().asBareJid()
        // XEP version 0.6.0 sends proceed, reject, ringing to bare jid
        val addressedDirectly = to != null && to == account.getJid()
        val id: AbstractJingleConnection.Id
        if (fromSelf) {
            if (to != null && to.isFullJid()) {
                id = AbstractJingleConnection.Id.of(account, to, sessionId)
            } else {
                return
            }
        } else {
            id = AbstractJingleConnection.Id.of(account, from, sessionId)
        }
        val existingJingleConnection = connections[id]
        if (existingJingleConnection != null) {
            if (existingJingleConnection is JingleRtpConnection) {
                existingJingleConnection.deliveryMessage(from, message, serverMsgId, timestamp)
            } else {
                Log.d(
                    Config.LOGTAG,
                    "" +
                        account.getJid().asBareJid() +
                        ": " +
                        existingJingleConnection.javaClass.name +
                        " does not support jingle messages",
                )
            }
            return
        }

        if (fromSelf) {
            if ("proceed" == message.getName()) {
                val c = mXmppConnectionService.findOrCreateConversation(account, id.with, false, false)
                val previousBusy = c.findRtpSession(sessionId, MessageRef.STATUS_RECEIVED)
                if (previousBusy != null) {
                    previousBusy.setBody(
                        XmppConnectionService.dataStatics().newRtpSessionStatus(true, 0),
                    )
                    if (serverMsgId != null) {
                        previousBusy.setServerMsgId(serverMsgId)
                    }
                    previousBusy.setTime(timestamp)
                    mXmppConnectionService.updateMessage(previousBusy, true)
                    Log.d(
                        Config.LOGTAG,
                        "" +
                            id.account.getJid().asBareJid() +
                            ": updated previous busy because call got picked up by another device",
                    )
                    mXmppConnectionService.getNotificationService().clearMissedCall(previousBusy)
                    return
                }
            }
            // TODO handle reject for cases where we don't have carbon copies (normally reject is to
            // be sent to own bare jid as well)
            Log.d(
                Config.LOGTAG,
                "" + account.getJid().asBareJid() + ": ignore jingle message from self",
            )
            return
        }

        if ("propose" == message.getName()) {
            val propose = Propose.upgrade(message)
            val descriptions: List<GenericDescription> = propose.getDescriptions()
            val filtered: Collection<GenericDescription> =
                Collections2.filter(descriptions) { d -> d is RtpDescription }
            val rtpDescriptions: Collection<RtpDescription> =
                Collections2.transform(filtered) { input -> input as RtpDescription }
            if (
                rtpDescriptions.size > 0 &&
                    rtpDescriptions.size == descriptions.size &&
                    isUsingClearNet(account)
            ) {
                val media: Collection<Media> =
                    Collections2.transform(rtpDescriptions) { it.getMedia() }
                if (media.contains(Media.UNKNOWN)) {
                    Log.d(
                        Config.LOGTAG,
                        "" +
                            account.getJid().asBareJid() +
                            ": encountered unknown media in session proposal. " +
                            propose,
                    )
                    return
                }
                val matchingSessionProposal =
                    findMatchingSessionProposal(account, id.with, ImmutableSet.copyOf(media))
                if (matchingSessionProposal.isPresent) {
                    val ourSessionId = matchingSessionProposal.get().sessionId
                    val theirSessionId = id.sessionId
                    if (
                        ComparisonChain.start()
                            .compare(ourSessionId, theirSessionId)
                            .compare(account.getJid().toString(), id.with.toString())
                            .result() > 0
                    ) {
                        Log.d(
                            Config.LOGTAG,
                            "" +
                                account.getJid().asBareJid() +
                                ": our session lost tie break. automatically accepting their" +
                                " session. winning Session=" +
                                theirSessionId,
                        )
                        // TODO a retract for this reason should probably include some indication of
                        // tie break
                        retractSessionProposal(matchingSessionProposal.get())
                        val rtpConnection = JingleRtpConnection(this, id, from)
                        this.connections[id] = rtpConnection
                        rtpConnection.setProposedMedia(ImmutableSet.copyOf(media))
                        rtpConnection.deliveryMessage(from, message, serverMsgId, timestamp)
                        addNewIncomingCall(rtpConnection)
                        // TODO actually do the automatic accept?!
                    } else {
                        Log.d(
                            Config.LOGTAG,
                            "" +
                                account.getJid().asBareJid() +
                                ": our session won tie break. waiting for other party to accept." +
                                " winningSession=" +
                                ourSessionId,
                        )
                        // TODO reject their session with <tie-break/>?
                    }
                    return
                }
                val stranger = isWithStrangerAndStrangerNotificationsAreOff(account, id.with)
                if (isBusy() || stranger) {
                    writeLogMissedIncoming(
                        account,
                        id.with.asBareJid(),
                        id.sessionId,
                        serverMsgId,
                        timestamp,
                        stranger,
                    )
                    if (stranger) {
                        Log.d(
                            Config.LOGTAG,
                            "" +
                                id.account.getJid().asBareJid() +
                                ": ignoring call proposal from stranger " +
                                id.with,
                        )
                        return
                    }
                    val activeDevices = account.activeDevicesWithRtpCapability()
                    Log.d(Config.LOGTAG, "active devices with rtp capability: $activeDevices")
                    if (activeDevices == 0) {
                        val reject =
                            mXmppConnectionService
                                .getMessageGenerator()
                                .sessionReject(from, sessionId)
                        mXmppConnectionService.sendMessagePacket(account, reject)
                    } else {
                        Log.d(
                            Config.LOGTAG,
                            "" +
                                id.account.getJid().asBareJid() +
                                ": ignoring proposal because busy on this device but there are" +
                                " other devices",
                        )
                    }
                } else {
                    val rtpConnection = JingleRtpConnection(this, id, from)
                    this.connections[id] = rtpConnection
                    rtpConnection.setProposedMedia(ImmutableSet.copyOf(media))
                    rtpConnection.deliveryMessage(from, message, serverMsgId, timestamp)
                    addNewIncomingCall(rtpConnection)
                }
            } else {
                Log.d(
                    Config.LOGTAG,
                    "" +
                        account.getJid().asBareJid() +
                        ": unable to react to proposed session with " +
                        rtpDescriptions.size +
                        " rtp descriptions of " +
                        descriptions.size +
                        " total descriptions",
                )
            }
        } else if (addressedDirectly && "proceed" == message.getName()) {
            synchronized(rtpSessionProposals) {
                val proposal = getRtpSessionProposal(account, from.asBareJid(), sessionId)
                if (proposal != null) {
                    rtpSessionProposals.remove(proposal)
                    val rtpConnection =
                        JingleRtpConnection(this, id, account.getJid(), proposal.getCallIntegration())
                    rtpConnection.setProposedMedia(proposal.media)
                    this.connections[id] = rtpConnection
                    rtpConnection.transitionOrThrow(AbstractJingleConnection.State.PROPOSED)
                    rtpConnection.deliveryMessage(from, message, serverMsgId, timestamp)
                } else {
                    Log.d(
                        Config.LOGTAG,
                        "" +
                            account.getJid().asBareJid() +
                            ": no rtp session (" +
                            sessionId +
                            ") proposal found for " +
                            from +
                            " to deliver proceed",
                    )
                    if (remoteMsgId == null) {
                        return
                    }
                    val errorMessage = uk.xa0.tulkki.xmpp.models.stanza.Message()
                    errorMessage.setTo(from)
                    errorMessage.setId(remoteMsgId)
                    errorMessage.setType(uk.xa0.tulkki.xmpp.models.stanza.Message.Type.ERROR)
                    val error = errorMessage.addChild("error")
                    error.setAttribute("code", "404")
                    error.setAttribute("type", "cancel")
                    error.addChild("item-not-found", "urn:ietf:params:xml:ns:xmpp-stanzas")
                    mXmppConnectionService.sendMessagePacket(account, errorMessage)
                }
            }
        } else if (addressedDirectly && "reject" == message.getName()) {
            val proposal = getRtpSessionProposal(account, from.asBareJid(), sessionId)
            synchronized(rtpSessionProposals) {
                if (proposal != null) {
                    setTerminalSessionState(proposal, RtpEndUserState.DECLINED_OR_BUSY)
                    rtpSessionProposals.remove(proposal)
                    proposal.getCallIntegration().busy()
                    writeLogMissedOutgoing(
                        account,
                        proposal.with,
                        proposal.sessionId,
                        serverMsgId,
                        timestamp,
                    )
                    mXmppConnectionService.notifyJingleRtpConnectionUpdate(
                        account,
                        proposal.with,
                        proposal.sessionId,
                        RtpEndUserState.DECLINED_OR_BUSY,
                    )
                } else {
                    Log.d(
                        Config.LOGTAG,
                        "" +
                            account.getJid().asBareJid() +
                            ": no rtp session proposal found for " +
                            from +
                            " to deliver reject",
                    )
                }
            }
        } else if (addressedDirectly && "ringing" == message.getName()) {
            Log.d(
                Config.LOGTAG,
                "" + account.getJid().asBareJid() + ": " + from + " started ringing",
            )
            updateProposedSessionDiscovered(
                account,
                from,
                sessionId,
                DeviceDiscoveryState.DISCOVERED,
            )
        } else {
            Log.d(
                Config.LOGTAG,
                "" +
                    account.getJid() +
                    ": received out of order jingle message from=" +
                    from +
                    ", message=" +
                    message +
                    ", addressedDirectly=" +
                    addressedDirectly,
            )
        }
    }

    private fun getRtpSessionProposal(
        account: AccountRef,
        from: Jid,
        sessionId: String?,
    ): RtpSessionProposal? {
        for (rtpSessionProposal in rtpSessionProposals.keys) {
            if (
                rtpSessionProposal.sessionId == sessionId &&
                    rtpSessionProposal.with == from &&
                    rtpSessionProposal.getAccount().getJid() == account.getJid()
            ) {
                return rtpSessionProposal
            }
        }
        return null
    }

    private fun writeLogMissedOutgoing(
        account: AccountRef,
        with: Jid,
        sessionId: String,
        serverMsgId: String?,
        timestamp: Long,
    ) {
        val conversation =
            mXmppConnectionService.findOrCreateConversation(account, with.asBareJid(), false, false)
        val message =
            XmppConnectionService.dataStatics()
                .newMessage(
                    conversation,
                    MessageRef.STATUS_SEND,
                    MessageRef.TYPE_RTP_SESSION,
                    sessionId,
                )
        message.setBody(XmppConnectionService.dataStatics().newRtpSessionStatus(false, 0))
        message.setServerMsgId(serverMsgId)
        message.setTime(timestamp)
        writeMessage(message)
    }

    private fun writeLogMissedIncoming(
        account: AccountRef,
        with: Jid,
        sessionId: String,
        serverMsgId: String?,
        timestamp: Long,
        stranger: Boolean,
    ) {
        val conversation =
            mXmppConnectionService.findOrCreateConversation(account, with.asBareJid(), false, false)
        val message =
            XmppConnectionService.dataStatics()
                .newMessage(
                    conversation,
                    MessageRef.STATUS_RECEIVED,
                    MessageRef.TYPE_RTP_SESSION,
                    sessionId,
                )
        message.setBody(XmppConnectionService.dataStatics().newRtpSessionStatus(false, 0))
        message.setServerMsgId(serverMsgId)
        message.setTime(timestamp)
        message.setCounterpart(with)
        writeMessage(message)
        if (stranger) {
            return
        }
        mXmppConnectionService.getNotificationService().pushMissedCallNow(message)
    }

    private fun writeMessage(message: MessageRef) {
        val conversational: ConversationalRef = message.getConversation()
            ?: throw NullPointerException("message has no conversation")
        if (conversational is ConversationRef) {
            conversational.add(message)
            (mXmppConnectionService.databaseBackend ?: throw NullPointerException("database backend is not open")).createMessage(message)
            mXmppConnectionService.updateConversationUi()
        } else {
            throw IllegalStateException("Somehow the conversation in a message was a stub")
        }
    }

    fun startJingleFileTransfer(message: MessageRef) {
        Preconditions.checkArgument(
            message.isFileOrImage(),
            "Message is not of type file or image",
        )
        val old = message.getTransferable()
        if (old != null) {
            old.cancel()
        }
        val connection = JingleFileTransferConnection(this, message)
        this.connections[connection.getId()] = connection
        connection.sendSessionInitialize()
    }

    fun getOngoingRtpConnection(contact: ContactRef): Optional<OngoingRtpSession> {
        for ((key, value) in this.connections) {
            if (value is JingleRtpConnection) {
                val id = key
                if (
                    id.account === contact.getAccount() &&
                        id.with.asBareJid() == contact.getJid().asBareJid()
                ) {
                    return Optional.of(value as OngoingRtpSession)
                }
            }
        }
        synchronized(this.rtpSessionProposals) {
            for ((proposal, state) in this.rtpSessionProposals) {
                if (
                    proposal.getAccount() === contact.getAccount() &&
                        contact.getJid().asBareJid() == proposal.with
                ) {
                    if (state != DeviceDiscoveryState.FAILED) {
                        return Optional.of(proposal as OngoingRtpSession)
                    }
                }
            }
        }
        return Optional.absent()
    }

    fun getOngoingRtpConnection(): JingleRtpConnection? {
        for (jingleConnection in this.connections.values) {
            if (jingleConnection is JingleRtpConnection) {
                if (jingleConnection.isTerminated()) {
                    continue
                }
                return jingleConnection
            }
        }
        return null
    }

    @JvmName("finishConnectionOrThrow")
    internal fun finishConnectionOrThrow(connection: AbstractJingleConnection) {
        val id = connection.getId()
        if (this.connections.remove(id) == null) {
            throw IllegalStateException("Unable to finish connection with id=$id")
        }
        // update chat UI to remove 'ongoing call' icon
        mXmppConnectionService.updateConversationUi()
    }

    fun fireJingleRtpConnectionStateUpdates(): Boolean {
        for (connection in this.connections.values) {
            if (connection is JingleRtpConnection) {
                if (connection.isTerminated()) {
                    continue
                }
                connection.fireStateUpdate()
                return true
            }
        }
        return false
    }

    fun retractSessionProposal(account: AccountRef, with: Jid) {
        synchronized(this.rtpSessionProposals) {
            var matchingProposal: RtpSessionProposal? = null
            for (proposal in this.rtpSessionProposals.keys) {
                if (proposal.getAccount() === account && with.asBareJid() == proposal.with) {
                    matchingProposal = proposal
                    break
                }
            }
            if (matchingProposal != null) {
                retractSessionProposal(matchingProposal, false)
            }
        }
    }

    private fun retractSessionProposal(rtpSessionProposal: RtpSessionProposal) {
        retractSessionProposal(rtpSessionProposal, true)
    }

    private fun retractSessionProposal(
        rtpSessionProposal: RtpSessionProposal,
        refresh: Boolean,
    ) {
        val account = rtpSessionProposal.getAccount()
        Log.d(
            Config.LOGTAG,
            "" +
                account.getJid().asBareJid() +
                ": retracting rtp session proposal with " +
                rtpSessionProposal.with,
        )
        this.rtpSessionProposals.remove(rtpSessionProposal)
        rtpSessionProposal.getCallIntegration().retracted()
        if (refresh) {
            mXmppConnectionService.notifyJingleRtpConnectionUpdate(
                account,
                rtpSessionProposal.with,
                rtpSessionProposal.sessionId,
                RtpEndUserState.RETRACTED,
            )
        }
        val messagePacket =
            mXmppConnectionService.getMessageGenerator().sessionRetract(rtpSessionProposal)
        writeLogMissedOutgoing(
            account,
            rtpSessionProposal.with,
            rtpSessionProposal.sessionId,
            null,
            System.currentTimeMillis(),
        )
        mXmppConnectionService.sendMessagePacket(account, messagePacket)
    }

    fun initializeRtpSession(
        account: AccountRef,
        with: Jid,
        media: Set<Media>,
    ): JingleRtpConnection {
        val id = AbstractJingleConnection.Id.of(account, with)
        val rtpConnection = JingleRtpConnection(this, id, account.getJid())
        rtpConnection.setProposedMedia(media)
        rtpConnection.getCallIntegration().startAudioRouting()
        this.connections[id] = rtpConnection
        rtpConnection.sendSessionInitiate()
        return rtpConnection
    }

    fun proposeJingleRtpSession(
        account: AccountRef,
        with: Jid,
        media: Set<Media>,
    ): RtpSessionProposal? {
        synchronized(this.rtpSessionProposals) {
            for ((proposal, state) in this.rtpSessionProposals) {
                if (
                    proposal.getAccount() === account && with.asBareJid() == proposal.with
                ) {
                    if (state != DeviceDiscoveryState.FAILED) {
                        val endUserState = state.toEndUserState()
                        mXmppConnectionService.notifyJingleRtpConnectionUpdate(
                            account,
                            with,
                            proposal.sessionId,
                            endUserState,
                        )
                        return proposal
                    }
                }
            }
            if (isBusy()) {
                if (hasMatchingRtpSession(account, with, media) != null) {
                    Log.d(
                        Config.LOGTAG,
                        "ignoring request to propose jingle session because the other party" +
                            " already created one for us",
                    )
                    // TODO return something that we can parse the connection of of
                    return null
                }
                throw IllegalStateException(
                    "There is already a running RTP session. This should have been caught by the UI",
                )
            }
            val callIntegration =
                mXmppConnectionService
                    .callIntegration()
                    .create(mXmppConnectionService.getApplicationContext())
            callIntegration.setVideoState(
                if (Media.audioOnly(media)) {
                    VideoProfile.STATE_AUDIO_ONLY
                } else {
                    VideoProfile.STATE_BIDIRECTIONAL
                },
            )
            callIntegration.setAddress(
                mXmppConnectionService.callIntegration().address(with.asBareJid()),
                TelecomManager.PRESENTATION_ALLOWED,
            )
            val contact = account.getRoster().getContact(with)
            callIntegration.setCallerDisplayName(
                contact.getDisplayName(),
                TelecomManager.PRESENTATION_ALLOWED,
            )
            callIntegration.setInitialized()
            callIntegration.setInitialAudioDevice(
                mXmppConnectionService.callIntegration().initialAudioDevice(media),
            )
            callIntegration.startAudioRouting()
            val proposal =
                RtpSessionProposal.of(account, with.asBareJid(), media, callIntegration)
            callIntegration.setCallback(ProposalStateCallback(proposal))
            this.rtpSessionProposals[proposal] = DeviceDiscoveryState.SEARCHING
            mXmppConnectionService.notifyJingleRtpConnectionUpdate(
                account,
                proposal.with,
                proposal.sessionId,
                RtpEndUserState.FINDING_DEVICE,
            )
            val messagePacket =
                mXmppConnectionService.getMessageGenerator().sessionProposal(proposal)
            // in privacy preserving environments 'propose' is only ACKed when we have presence
            // subscription (to not leak presence). Therefor a timeout is only appropriate for
            // contacts where we can expect the 'ringing' response
            val triggerTimeout =
                Config.JINGLE_MESSAGE_INIT_STRICT_DEVICE_TIMEOUT ||
                    contact.mutualPresenceSubscription()
            SCHEDULED_EXECUTOR_SERVICE.schedule(
                Runnable {
                    val currentProposalState = rtpSessionProposals[proposal]
                    Log.d(Config.LOGTAG, "proposal state after timeout $currentProposalState")
                    if (
                        triggerTimeout &&
                            (currentProposalState == DeviceDiscoveryState.SEARCHING ||
                                currentProposalState ==
                                    DeviceDiscoveryState.SEARCHING_ACKNOWLEDGED)
                    ) {
                        deviceDiscoveryTimeout(account, proposal)
                    }
                },
                Config.DEVICE_DISCOVERY_TIMEOUT,
                TimeUnit.MILLISECONDS,
            )
            mXmppConnectionService.sendMessagePacket(account, messagePacket)
            return proposal
        }
    }

    private fun deviceDiscoveryTimeout(account: AccountRef, proposal: RtpSessionProposal) {
        // 'endUserState' is what we display in the UI. There is an argument to use 'BUSY' here
        // instead
        // we may or may not want to match this with the tone we are playing (see
        // callIntegration.error() or callIntegration.busy())
        val endUserState = RtpEndUserState.CONNECTIVITY_ERROR
        Log.d(Config.LOGTAG, "call proposal still in device discovery state after timeout")
        setTerminalSessionState(proposal, endUserState)

        rtpSessionProposals.remove(proposal)
        // error and busy would probably be both appropriate tones to play
        // playing the error tone is probably more in line with what happens on a technical level
        // and would be a similar UX to what happens when you call a user that doesn't exist
        // playing the busy tone might be more in line with what some telephony networks play
        proposal.getCallIntegration().error()
        writeLogMissedOutgoing(
            account,
            proposal.with,
            proposal.sessionId,
            null,
            System.currentTimeMillis(),
        )
        mXmppConnectionService.notifyJingleRtpConnectionUpdate(
            account,
            proposal.with,
            proposal.sessionId,
            endUserState,
        )

        val retraction =
            mXmppConnectionService.getMessageGenerator().sessionRetract(proposal)
        mXmppConnectionService.sendMessagePacket(account, retraction)
    }

    fun sendJingleMessageFinish(contact: ContactRef, sessionId: String, reason: Reason) {
        val account = contact.getAccount()
        val messagePacket =
            mXmppConnectionService
                .getMessageGenerator()
                .sessionFinish(contact.getJid(), sessionId, reason)
        mXmppConnectionService.sendMessagePacket(account, messagePacket)
    }

    fun matchingProposal(account: AccountRef, with: Jid): Optional<RtpSessionProposal> {
        synchronized(this.rtpSessionProposals) {
            for ((proposal, _) in this.rtpSessionProposals) {
                if (
                    proposal.getAccount() === account && with.asBareJid() == proposal.with
                ) {
                    return Optional.of(proposal)
                }
            }
        }
        return Optional.absent()
    }

    fun hasMatchingProposal(account: AccountRef, with: Jid): Boolean {
        synchronized(this.rtpSessionProposals) {
            for ((proposal, state) in this.rtpSessionProposals) {
                if (
                    proposal.getAccount() === account && with.asBareJid() == proposal.with
                ) {
                    // CallIntegrationConnectionService starts RtpSessionActivity with ACTION_VIEW
                    // and an EXTRA_LAST_REPORTED_STATE of DISCOVERING devices. however due to
                    // possible race conditions the state might have already moved on so we are
                    // going
                    // to update the UI
                    val endUserState = state.toEndUserState()
                    mXmppConnectionService.notifyJingleRtpConnectionUpdate(
                        account,
                        proposal.with,
                        proposal.sessionId,
                        endUserState,
                    )
                    return true
                }
            }
        }
        return false
    }

    fun deliverIbbPacket(account: AccountRef, packet: Iq) {
        val sid: String?
        val payload: Element?
        val packetType: InbandBytestreamsTransport.PacketType?
        if (packet.hasChild("open", Namespace.IBB)) {
            packetType = InbandBytestreamsTransport.PacketType.OPEN
            payload = packet.findChild("open", Namespace.IBB)
            sid = (payload ?: throw NullPointerException()).getAttribute("sid")
        } else if (packet.hasChild("data", Namespace.IBB)) {
            packetType = InbandBytestreamsTransport.PacketType.DATA
            payload = packet.findChild("data", Namespace.IBB)
            sid = (payload ?: throw NullPointerException()).getAttribute("sid")
        } else if (packet.hasChild("close", Namespace.IBB)) {
            packetType = InbandBytestreamsTransport.PacketType.CLOSE
            payload = packet.findChild("close", Namespace.IBB)
            sid = (payload ?: throw NullPointerException()).getAttribute("sid")
        } else {
            packetType = null
            payload = null
            sid = null
        }
        if (sid == null) {
            Log.d(
                Config.LOGTAG,
                "" +
                    account.getJid().asBareJid() +
                    ": unable to deliver ibb packet. missing sid",
            )
            (account.getXmppConnection() ?: throw NullPointerException("account has no xmpp connection")).sendIqPacket(packet.generateResponse(Iq.Type.ERROR), null)
            return
        }
        for (connection in this.connections.values) {
            if (connection is JingleFileTransferConnection) {
                val transport = connection.getTransport()
                if (transport is InbandBytestreamsTransport) {
                    if (sid == transport.streamId) {
                        if (
                            transport.deliverPacket(
                                packetType ?: throw NullPointerException(),
                                packet.getFrom(),
                                payload ?: throw NullPointerException(),
                            )
                        ) {
                            (account.getXmppConnection()
                                ?: throw NullPointerException("account has no xmpp connection"))
                                .sendIqPacket(packet.generateResponse(Iq.Type.RESULT), null)
                        } else {
                            (account.getXmppConnection()
                                ?: throw NullPointerException("account has no xmpp connection"))
                                .sendIqPacket(packet.generateResponse(Iq.Type.ERROR), null)
                        }
                        return
                    }
                }
            }
        }
        Log.d(
            Config.LOGTAG,
            "" + account.getJid().asBareJid() + ": unable to deliver ibb packet with sid=" + sid,
        )
        (account.getXmppConnection() ?: throw NullPointerException("account has no xmpp connection")).sendIqPacket(packet.generateResponse(Iq.Type.ERROR), null)
    }

    fun notifyRebound(account: AccountRef) {
        for (connection in this.connections.values) {
            connection.notifyRebound()
        }
        val xmppConnection: XmppConnection? = account.getXmppConnection()
        if (xmppConnection != null && xmppConnection.getFeatures().sm()) {
            resendSessionProposals(account)
        }
    }

    fun findJingleRtpConnection(
        account: AccountRef,
        with: Jid,
        sessionId: String,
    ): WeakReference<JingleRtpConnection>? {
        val id = AbstractJingleConnection.Id.of(account, with, sessionId)
        val connection = connections[id]
        if (connection is JingleRtpConnection) {
            return WeakReference(connection)
        }
        return null
    }

    fun findJingleRtpConnection(account: AccountRef, with: Jid): JingleRtpConnection? {
        for (connection in this.connections.values) {
            if (connection is JingleRtpConnection) {
                if (connection.isTerminated()) {
                    continue
                }
                val id = connection.getId()
                if (id.account === account && account.getJid() == with) {
                    return connection
                }
            }
        }
        return null
    }

    private fun resendSessionProposals(account: AccountRef) {
        synchronized(this.rtpSessionProposals) {
            for ((proposal, state) in this.rtpSessionProposals) {
                if (
                    state == DeviceDiscoveryState.SEARCHING &&
                        proposal.getAccount() === account
                ) {
                    Log.d(
                        Config.LOGTAG,
                        "" +
                            account.getJid().asBareJid() +
                            ": resending session proposal to " +
                            proposal.with,
                    )
                    val messagePacket =
                        mXmppConnectionService.getMessageGenerator().sessionProposal(proposal)
                    mXmppConnectionService.sendMessagePacket(account, messagePacket)
                }
            }
        }
    }

    /**
     * Tulkki: 3.7 C5-E4 - the pair-9 delegating wrappers are gone with the same erasure reason as
     * `deliverMessage` above: the ref-typed and model-typed declarations would collide once the
     * model type retires, and each wrapper's `(Account) accountRef` cast was the narrowing being
     * deleted. `MessageParser.handleErrorMessage` passes an `AccountRef` and now resolves here.
     */
    fun updateProposedSessionDiscovered(
        account: AccountRef,
        from: Jid,
        sessionId: String?,
        target: DeviceDiscoveryState,
    ) {
        synchronized(this.rtpSessionProposals) {
            val sessionProposal = getRtpSessionProposal(account, from.asBareJid(), sessionId)
            val currentState =
                if (sessionProposal == null) null else rtpSessionProposals[sessionProposal]
            if (sessionProposal == null || currentState == null) {
                Log.d(
                    Config.LOGTAG,
                    "unable to find session proposal for session id $sessionId target=$target",
                )
                return
            }
            if (currentState == DeviceDiscoveryState.DISCOVERED) {
                Log.d(
                    Config.LOGTAG,
                    "session proposal already at discovered. not going to fall back",
                )
                return
            }

            Log.d(
                Config.LOGTAG,
                "" + account.getJid().asBareJid() + ": flagging session " + sessionId + " as " + target,
            )

            val endUserState = target.toEndUserState()

            if (target == DeviceDiscoveryState.FAILED) {
                Log.d(Config.LOGTAG, "removing session proposal after failure")
                setTerminalSessionState(sessionProposal, endUserState)
                this.rtpSessionProposals.remove(sessionProposal)
                sessionProposal.getCallIntegration().error()
                mXmppConnectionService.notifyJingleRtpConnectionUpdate(
                    account,
                    sessionProposal.with,
                    sessionProposal.sessionId,
                    endUserState,
                )
                return
            }

            this.rtpSessionProposals[sessionProposal] = target

            if (endUserState == RtpEndUserState.RINGING) {
                sessionProposal.getCallIntegration().setDialing()
            }

            mXmppConnectionService.notifyJingleRtpConnectionUpdate(
                account,
                sessionProposal.with,
                sessionProposal.sessionId,
                endUserState,
            )
        }
    }

    fun rejectRtpSession(sessionId: String?) {
        for (connection in this.connections.values) {
            if (connection.getId().sessionId == sessionId) {
                if (connection is JingleRtpConnection) {
                    try {
                        connection.rejectCall()
                        return
                    } catch (e: IllegalStateException) {
                        Log.w(
                            Config.LOGTAG,
                            "race condition on rejecting call from notification",
                            e,
                        )
                    }
                }
            }
        }
    }

    fun endRtpSession(sessionId: String?) {
        for (connection in this.connections.values) {
            if (connection.getId().sessionId == sessionId) {
                if (connection is JingleRtpConnection) {
                    connection.endCall()
                }
            }
        }
    }

    fun failProceed(account: AccountRef, with: Jid, sessionId: String, message: String?) {
        val id = AbstractJingleConnection.Id.of(account, with, sessionId)
        val existingJingleConnection = connections[id]
        if (existingJingleConnection is JingleRtpConnection) {
            existingJingleConnection.deliverFailedProceed(message)
        }
    }

    @JvmName("ensureConnectionIsRegistered")
    internal fun ensureConnectionIsRegistered(connection: AbstractJingleConnection) {
        if (connections.containsValue(connection)) {
            return
        }
        val e =
            IllegalStateException(
                "JingleConnection has not been registered with connection manager",
            )
        Log.e(Config.LOGTAG, "ensureConnectionIsRegistered() failed. Going to throw", e)
        throw e
    }

    @JvmName("setTerminalSessionState")
    internal fun setTerminalSessionState(
        id: AbstractJingleConnection.Id,
        state: RtpEndUserState,
        media: Set<Media>,
    ) {
        this.terminatedSessions.put(
            PersistableSessionId.of(id),
            TerminatedRtpSession(state, media),
        )
    }

    internal fun setTerminalSessionState(proposal: RtpSessionProposal, state: RtpEndUserState) {
        this.terminatedSessions.put(
            PersistableSessionId.of(proposal),
            TerminatedRtpSession(state, proposal.media),
        )
    }

    fun getTerminalSessionState(with: Jid, sessionId: String?): TerminatedRtpSession? =
        this.terminatedSessions.getIfPresent(PersistableSessionId(with, sessionId))

    private class PersistableSessionId(
        private val with: Jid,
        private val sessionId: String?,
    ) {

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other == null || javaClass != other.javaClass) return false
            val that = other as PersistableSessionId
            return with == that.with && sessionId == that.sessionId
        }

        override fun hashCode(): Int = Objects.hashCode(with, sessionId)

        companion object {
            @JvmStatic
            fun of(id: AbstractJingleConnection.Id): PersistableSessionId =
                PersistableSessionId(id.with, id.sessionId)

            @JvmStatic
            fun of(proposal: RtpSessionProposal): PersistableSessionId =
                PersistableSessionId(proposal.with, proposal.sessionId)
        }
    }

    class TerminatedRtpSession internal constructor(
        @JvmField val state: RtpEndUserState,
        @JvmField val media: Set<Media>,
    )

    enum class DeviceDiscoveryState {
        SEARCHING,
        SEARCHING_ACKNOWLEDGED,
        DISCOVERED,
        FAILED;

        fun toEndUserState(): RtpEndUserState =
            when (this) {
                SEARCHING, SEARCHING_ACKNOWLEDGED -> RtpEndUserState.FINDING_DEVICE
                DISCOVERED -> RtpEndUserState.RINGING
                else -> RtpEndUserState.CONNECTIVITY_ERROR
            }
    }

    class RtpSessionProposal internal constructor(
        account: AccountRef,
        @JvmField val with: Jid,
        @JvmField val sessionId: String,
        @JvmField val media: Set<Media>,
        callIntegration: CallIntegrationPort,
    ) : OngoingRtpSession {

        // Java reached these two `private` fields from the enclosing class; Kotlin's `private` is
        // class-scoped, and a property named `account` would generate the `getAccount()` the
        // interface already declares, so the backing fields take the `Ref` suffix and the outer
        // class goes through the public accessors below.
        private val accountRef: AccountRef = account
        private val callIntegrationRef: CallIntegrationPort = callIntegration

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other == null || javaClass != other.javaClass) return false
            val proposal = other as RtpSessionProposal
            return accountRef.getJid() == proposal.accountRef.getJid() &&
                with == proposal.with &&
                sessionId == proposal.sessionId
        }

        override fun hashCode(): Int = Objects.hashCode(accountRef.getJid(), with, sessionId)

        override fun getAccount(): AccountRef = accountRef

        override fun getWith(): Jid = with

        override fun getSessionId(): String = sessionId

        override fun getCallIntegration(): CallIntegrationPort =
            callIntegrationRef

        @Suppress("UNCHECKED_CAST")
        override fun getMedia(): MutableSet<Media> = media as MutableSet<Media>

        companion object {
            @JvmStatic
            fun of(
                account: AccountRef,
                with: Jid,
                media: Set<Media>,
                callIntegration: CallIntegrationPort,
            ): RtpSessionProposal =
                RtpSessionProposal(
                    account,
                    with,
                    JingleConnectionManager.nextRandomId(),
                    media,
                    callIntegration,
                )
        }
    }

    inner class ProposalStateCallback(private val proposal: RtpSessionProposal) :
        CallIntegrationPort.Callback {

        override fun onCallIntegrationShowIncomingCallUi() {}

        override fun onCallIntegrationDisconnect() {
            Log.d(Config.LOGTAG, "a phone call has just been started. retracting proposal")
            retractSessionProposal(this.proposal)
        }

        override fun onAudioDeviceChanged(
            selectedAudioDevice: AudioDevice,
            availableAudioDevices: Set<AudioDevice>,
        ) {
            mXmppConnectionService.notifyJingleRtpConnectionUpdate(
                selectedAudioDevice,
                availableAudioDevices,
            )
        }

        override fun onCallIntegrationReject() {}

        override fun onCallIntegrationAnswer() {}

        override fun onCallIntegrationSilence() {}

        override fun onCallIntegrationMicrophoneEnabled(enabled: Boolean) {}

        override fun applyDtmfTone(dtmf: String): Boolean = false
    }

    private fun sendDecline(
        account: AccountRef,
        request: Iq,
        id: AbstractJingleConnection.Id,
    ) {
        mXmppConnectionService.sendIqPacket(
            account,
            request.generateResponse(Iq.Type.RESULT),
            null,
        )
        val iq = Iq(Iq.Type.SET)
        iq.setTo(id.with)
        val sessionTermination =
            iq.addExtension(Jingle(Jingle.Action.SESSION_TERMINATE, id.sessionId))
        sessionTermination.setReason(Reason.DECLINE, null)
        mXmppConnectionService.sendIqPacket(account, iq, null)
    }

    companion object {

        @JvmField
        val SCHEDULED_EXECUTOR_SERVICE: ScheduledExecutorService =
            Executors.newSingleThreadScheduledExecutor()

        @JvmName("nextRandomId")
        @JvmStatic
        internal fun nextRandomId(): String {
            val id = ByteArray(16)
            SecureRandom().nextBytes(id)
            return Base64.encodeToString(id, Base64.NO_WRAP or Base64.NO_PADDING or Base64.URL_SAFE)
        }
    }
}
