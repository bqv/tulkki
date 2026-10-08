package uk.xa0.tulkki.xmpp.jingle

import android.os.Environment
import android.telecom.TelecomManager
import android.telecom.VideoProfile
import android.util.Log
import com.google.common.base.Joiner
import com.google.common.base.Optional
import com.google.common.base.Preconditions
import com.google.common.base.Stopwatch
import com.google.common.base.Strings
import com.google.common.base.Throwables
import com.google.common.collect.Collections2
import com.google.common.collect.ImmutableMultimap
import com.google.common.collect.ImmutableSet
import com.google.common.collect.Maps
import com.google.common.collect.Sets
import com.google.common.util.concurrent.FutureCallback
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import java.io.File
import java.io.IOException
import java.util.Arrays
import java.util.Collections
import java.util.LinkedList
import java.util.Queue
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.function.Consumer
import org.webrtc.EglBase
import org.webrtc.IceCandidate
import org.webrtc.PeerConnection
import org.webrtc.VideoTrack
import uk.xa0.tulkki.libs.AudioDevice
import uk.xa0.tulkki.xml.Element
import uk.xa0.tulkki.xml.Namespace
import uk.xa0.tulkki.xmpp.BuildConfig
import uk.xa0.tulkki.xmpp.Config
import uk.xa0.tulkki.libs.Jid
import uk.xa0.tulkki.xmpp.crypto.OmemoFailure
import uk.xa0.tulkki.xmpp.crypto.OmemoSessionPort
import uk.xa0.tulkki.xmpp.jingle.AbstractJingleConnection.Id
import uk.xa0.tulkki.xmpp.jingle.AbstractJingleConnection.State
import uk.xa0.tulkki.xmpp.jingle.stanzas.Content
import uk.xa0.tulkki.xmpp.jingle.stanzas.Group
import uk.xa0.tulkki.xmpp.jingle.stanzas.IceUdpTransportInfo
import uk.xa0.tulkki.xmpp.jingle.stanzas.Proceed
import uk.xa0.tulkki.xmpp.jingle.stanzas.Propose
import uk.xa0.tulkki.xmpp.jingle.stanzas.Reason
import uk.xa0.tulkki.xmpp.jingle.stanzas.RtpDescription
import uk.xa0.tulkki.xmpp.models.jingle.Jingle
import uk.xa0.tulkki.xmpp.models.stanza.Iq
import uk.xa0.tulkki.xmpp.refs.AccountRef
import uk.xa0.tulkki.xmpp.refs.ConversationRef
import uk.xa0.tulkki.xmpp.refs.ConversationalRef
import uk.xa0.tulkki.xmpp.refs.MessageRef
import uk.xa0.tulkki.xmpp.services.CallIntegrationPort
import uk.xa0.tulkki.xmpp.services.XmppConnectionService

/**
 * One Jingle RTP session, out of Java.
 *
 * Ported from Java by lane `E` in five commits: four seams left first (`RtpCallLog`,
 * `IceServerDiscovery`, `RtpCandidates`, `RtpEndUserStates`) and the remainder moved whole.
 *
 * Decisions taken rather than inherited:
 *
 * 1. **The two overrides of `AbstractJingleConnection`'s package-private abstract methods compile
 *    as they are.** Kotlin cannot spell package-private, but for a *Kotlin subclass of a Java*
 *    package-private member the compiler emits the override as `protected` - stronger than
 *    package-private, no source modifier, no `internal`, no javac error. Measured on a scratch
 *    base with kotlinc 2.3.21 and `javap` (lane `H` measured the same, both directions). The
 *    forbidden direction is the reverse: a *Java* subclass overriding a *Kotlin* member.
 * 2. **The two package-private members Java still calls keep their names**: `deliveryMessage`,
 *    `deliverFailedProceed` and `setProposedMedia` are `internal` with an explicit `@JvmName`,
 *    because `JingleConnectionManager` (still Java) reaches them and `internal` alone would mangle
 *    them to `deliveryMessage$xmpp`.
 * 3. **The class is final**, as Kotlin's default: nothing in the tree extends `JingleRtpConnection`
 *    and nothing did. `callIntegration` is `@JvmField`, restoring the Java's own
 *    `public final CallIntegration callIntegration;` field: `:app` is a separate Gradle module and
 *    reads `rtp.callIntegration`, so the private property would not compile there, and a plain
 *    public `val` is impossible - it would emit `getCallIntegration()` and clash with the
 *    interface's `getCallIntegration()` override beside it, which the Java also had as a separate
 *    method. `@JvmField` puts back the `public final` field and leaves that override alone.
 * 4. **`java.util.Map`'s Java spellings become Kotlin's**: `contents.keySet()` is `contents.keys`,
 *    `contents.entrySet()` is `contents.entries`, `size()`/`isEmpty()` are the properties. The
 *    `"" + jid` concatenations stay (the Java compiled them through `String.valueOf`), as does
 *    `Collections2.transform`/`filter`/`Maps.transformEntries`.
 * 5. **The nulls Java dereferenced are dereferenced the same way**: `getRemoteContentMap()` and
 *    `getLocalContentMap()` answer nullable because Java tested them for null in two places, and a
 *    site that dereferenced one reads `?: throw NullPointerException()`. `proposedMedia` is nullable
 *    and its `Preconditions.checkNotNull` calls stay Guava's, which return the value.
 * 6. **`AbstractJingleConnection`'s statics are qualified**: Kotlin does not inherit Java statics
 *    into a subclass's scope, so `TERMINATED`, `reasonToState(...)` and
 *    `JINGLE_MESSAGE_PROCEED_ID_PREFIX` are written at the declaring class. That is also why the
 *    six Kotlin call sites that spelled `JingleRtpConnection.JINGLE_MESSAGE_*_ID_PREFIX` moved to
 *    `AbstractJingleConnection` in the same commit (`MessageParser`, `MessageGenerator`).
 * 7. **The end-of-call file dump's commented-out block is kept verbatim**, with `created`,
 *    `Environment`, `File` and `IOException` alongside it, so no behaviour or comment leaves the
 *    file by accident.
 * 8. **`transition(State, Runnable)` takes a nullable `Runnable`**: the Java base's one-argument
 *    `transition` calls the two-argument one with `null`, and that self-call dispatches here, so a
 *    non-null parameter would have thrown before the override ran.
 * 9. **The `media` set is the Java's read-only `Set<Media>`, not a `MutableSet`.** The Java's field,
 *    `setProposedMedia(final Set<Media>)` and `sendSessionInitiate(final Set<Media>, ...)` merely
 *    read it (`Media.audioOnly`, `initialAudioDevice`, `initializePeerConnection`), so
 *    `setProposedMedia` and the `sendSessionInitiate` pair accept `Set<Media>` and the field is
 *    `Set<Media>?`. `getMedia()` still answers `MutableSet` because `OngoingRtpSession` declares it
 *    so, hence the erased cast of the same shape `RtpSessionProposal.getMedia()` makes.
 */
class JingleRtpConnection :
    AbstractJingleConnection,
    WebRTCWrapper.EventCallback,
    CallIntegrationPort.Callback,
    OngoingRtpSession {

    companion object {
        // TODO consider adding State.SESSION_INITIALIZED to ongoing call states for direct init mode
        @JvmField
        val STATES_SHOWING_ONGOING_CALL: List<State> =
            Arrays.asList(
                State.PROPOSED,
                State.PROCEED,
                State.SESSION_INITIALIZED_PRE_APPROVED,
                State.SESSION_ACCEPTED,
            )

        private const val BUSY_TIME_OUT = 30L
    }

    private val webRTCWrapper = WebRTCWrapper(this)
    private val pendingIceCandidates:
        Queue<Map.Entry<String, DescriptionTransport<RtpDescription, IceUdpTransportInfo>>> =
        LinkedList()
    private val omemoVerification = OmemoVerification()
    @JvmField val callIntegration: CallIntegrationPort
    private val message: MessageRef
    private val callLog: RtpCallLog

    private var proposedMedia: Set<Media>? = null
    private var initiatorRtpContentMap: RtpContentMap? = null
    private var responderRtpContentMap: RtpContentMap? = null
    private var incomingContentAdd: RtpContentMap? = null
    private var outgoingContentAdd: RtpContentMap? = null
    private var peerDtlsSetup: IceUdpTransportInfo.Setup? = null
    private val sessionDuration = Stopwatch.createUnstarted()
    private val stateHistory: Queue<PeerConnection.PeerConnectionState> = LinkedList()
    private var ringingTimeoutFuture: ScheduledFuture<*>? = null
    private val created = System.currentTimeMillis() / 1000L

    internal constructor(
        jingleConnectionManager: JingleConnectionManager,
        id: Id,
        initiator: Jid,
    ) : this(
        jingleConnectionManager,
        id,
        initiator,
        jingleConnectionManager
            .getXmppConnectionService()
            .callIntegration()
            .create(jingleConnectionManager.getXmppConnectionService().getApplicationContext()),
    ) {
        this.callIntegration.setAddress(
            jingleConnectionManager.getXmppConnectionService().callIntegration()
                .address(id.with.asBareJid()),
            TelecomManager.PRESENTATION_ALLOWED,
        )
        val contact = id.getContact()
        this.callIntegration.setCallerDisplayName(
            contact.getDisplayName(),
            TelecomManager.PRESENTATION_ALLOWED,
        )
        this.callIntegration.setInitialized()
    }

    internal constructor(
        jingleConnectionManager: JingleConnectionManager,
        id: Id,
        initiator: Jid,
        callIntegration: CallIntegrationPort,
    ) : super(jingleConnectionManager, id, initiator) {
        val conversation =
            jingleConnectionManager
                .getXmppConnectionService()
                .findOrCreateConversation(id.account, id.with.asBareJid(), false, false)
        this.message =
            XmppConnectionService.dataStatics()
                .newMessage(
                    conversation,
                    if (isInitiator()) MessageRef.STATUS_SEND else MessageRef.STATUS_RECEIVED,
                    MessageRef.TYPE_RTP_SESSION,
                    id.sessionId,
                )
        this.callIntegration = callIntegration
        this.callIntegration.setCallback(this)
        this.callLog = RtpCallLog(this.message, this.xmppConnectionService, this::getCallDuration)
    }

    @Synchronized
    override fun deliverPacket(iq: Iq) {
        val jingle = iq.getExtension(Jingle::class.java) ?: throw NullPointerException()
        when (val action = jingle.getAction() ?: throw NullPointerException()) {
            Jingle.Action.SESSION_INITIATE -> receiveSessionInitiate(iq, jingle)
            Jingle.Action.TRANSPORT_INFO -> receiveTransportInfo(iq, jingle)
            Jingle.Action.SESSION_ACCEPT -> receiveSessionAccept(iq, jingle)
            Jingle.Action.SESSION_TERMINATE -> receiveSessionTerminate(iq)
            Jingle.Action.CONTENT_ADD -> receiveContentAdd(iq, jingle)
            Jingle.Action.CONTENT_ACCEPT -> receiveContentAccept(iq)
            Jingle.Action.CONTENT_REJECT -> receiveContentReject(iq, jingle)
            Jingle.Action.CONTENT_REMOVE -> receiveContentRemove(iq, jingle)
            Jingle.Action.CONTENT_MODIFY -> receiveContentModify(iq, jingle)
            else -> {
                respondOk(iq)
                Log.d(
                    Config.LOGTAG,
                    String.format(
                        "%s: received unhandled jingle action %s",
                        id.account.getJid().asBareJid(),
                        action,
                    ),
                )
            }
        }
    }

    @Synchronized
    override fun notifyRebound() {
        if (isTerminated()) {
            return
        }
        webRTCWrapper.close()
        if (isResponder() && isInState(State.PROPOSED, State.SESSION_INITIALIZED)) {
            xmppConnectionService.getNotificationService().cancelIncomingCallNotification()
        }
        if (isInState(
                State.SESSION_INITIALIZED,
                State.SESSION_INITIALIZED_PRE_APPROVED,
                State.SESSION_ACCEPTED,
            )
        ) {
            // we might have already changed resources (full jid) at this point; so this might not
            // even reach the other party
            sendSessionTerminate(Reason.CONNECTIVITY_ERROR)
        } else {
            transitionOrThrow(State.TERMINATED_CONNECTIVITY_ERROR)
            finish()
        }
    }

    override fun applyDtmfTone(tone: String): Boolean = webRTCWrapper.applyDtmfTone(tone)

    private fun receiveSessionTerminate(jinglePacket: Iq) {
        respondOk(jinglePacket)
        val jingle =
            jinglePacket.getExtension(Jingle::class.java) ?: throw NullPointerException()
        val wrapper = jingle.getReason()
        val previous = this.state
        Log.d(
            Config.LOGTAG,
            "" +
                id.account.getJid().asBareJid() +
                ": received session terminate reason=" +
                wrapper.reason +
                "(" +
                Strings.nullToEmpty(wrapper.text) +
                ") while in state " +
                previous,
        )
        if (AbstractJingleConnection.TERMINATED.contains(previous)) {
            Log.d(
                Config.LOGTAG,
                "" +
                    id.account.getJid().asBareJid() +
                    ": ignoring session terminate because already in " +
                    previous,
            )
            return
        }
        webRTCWrapper.close()
        val target = AbstractJingleConnection.reasonToState(wrapper.reason)
        transitionOrThrow(target)
        this.callLog.writeLogMessage(target)
        if (previous == State.PROPOSED || previous == State.SESSION_INITIALIZED) {
            xmppConnectionService.getNotificationService().cancelIncomingCallNotification()
        }
        finish()
    }

    private fun receiveTransportInfo(jinglePacket: Iq, jingle: Jingle) {
        // Due to the asynchronicity of processing session-init we might move from NULL|PROCEED to
        // INITIALIZED only after transport-info has been received
        if (isInState(
                State.NULL,
                State.PROCEED,
                State.SESSION_INITIALIZED,
                State.SESSION_INITIALIZED_PRE_APPROVED,
                State.SESSION_ACCEPTED,
            )
        ) {
            val contentMap: RtpContentMap
            try {
                contentMap = RtpContentMap.of(jingle)
            } catch (e: IllegalArgumentException) {
                Log.d(
                    Config.LOGTAG,
                    "" + id.account.getJid().asBareJid() + ": improperly formatted contents; ignoring",
                    e,
                )
                respondOk(jinglePacket)
                return
            } catch (e: NullPointerException) {
                Log.d(
                    Config.LOGTAG,
                    "" + id.account.getJid().asBareJid() + ": improperly formatted contents; ignoring",
                    e,
                )
                respondOk(jinglePacket)
                return
            }
            receiveTransportInfo(jinglePacket, contentMap)
        } else {
            receiveOutOfOrderAction(jinglePacket, Jingle.Action.TRANSPORT_INFO)
        }
    }

    private fun receiveTransportInfo(jinglePacket: Iq, contentMap: RtpContentMap) {
        val candidates = contentMap.contents.entries
        val remote = getRemoteContentMap()
        val remoteContentIds: Set<String> =
            if (remote == null) Collections.emptySet<String>() else remote.contents.keys
        if (Collections.disjoint(remoteContentIds, contentMap.contents.keys)) {
            Log.d(
                Config.LOGTAG,
                "received transport-info for unknown contents " +
                    contentMap.contents.keys +
                    " (known: " +
                    remoteContentIds +
                    ")",
            )
            respondOk(jinglePacket)
            pendingIceCandidates.addAll(candidates)
            return
        }
        if (this.state != State.SESSION_ACCEPTED) {
            Log.d(Config.LOGTAG, "received transport-info prematurely. adding to backlog")
            respondOk(jinglePacket)
            pendingIceCandidates.addAll(candidates)
            return
        }
        // zero candidates + modified credentials are an ICE restart offer
        if (checkForIceRestart(jinglePacket, contentMap)) {
            return
        }
        respondOk(jinglePacket)
        try {
            processCandidates(candidates)
        } catch (e: WebRTCWrapper.PeerConnectionNotInitialized) {
            Log.w(
                Config.LOGTAG,
                "" +
                    id.account.getJid().asBareJid() +
                    ": PeerConnection was not initialized when processing transport info." +
                    " this usually indicates a race condition that can be ignored",
            )
        }
    }

    private fun receiveContentAdd(iq: Iq, jingle: Jingle) {
        val modification: RtpContentMap
        try {
            modification = RtpContentMap.of(jingle)
            modification.requireContentDescriptions()
        } catch (e: RuntimeException) {
            Log.d(
                Config.LOGTAG,
                "" + id.account.getJid().asBareJid() + ": improperly formatted contents",
                Throwables.getRootCause(e),
            )
            respondOk(iq)
            webRTCWrapper.close()
            sendSessionTerminate(Reason.of(e), e.message)
            return
        }
        if (isInState(State.SESSION_ACCEPTED)) {
            val hasFullTransportInfo = modification.hasFullTransportInfo()
            val future =
                receiveRtpContentMap(
                    modification,
                    this.omemoVerification.hasFingerprint() && hasFullTransportInfo,
                )
            Futures.addCallback(
                future,
                object : FutureCallback<RtpContentMap> {
                    override fun onSuccess(rtpContentMap: RtpContentMap) {
                        receiveContentAdd(iq, rtpContentMap)
                    }

                    override fun onFailure(throwable: Throwable) {
                        respondOk(iq)
                        val rootCause = Throwables.getRootCause(throwable)
                        Log.d(
                            Config.LOGTAG,
                            "" +
                                id.account.getJid().asBareJid() +
                                ": improperly formatted contents in content-add",
                            throwable,
                        )
                        webRTCWrapper.close()
                        sendSessionTerminate(
                            Reason.ofThrowable(rootCause),
                            rootCause.message,
                        )
                    }
                },
                MoreExecutors.directExecutor(),
            )
        } else {
            receiveOutOfOrderAction(iq, Jingle.Action.CONTENT_ADD)
        }
    }

    private fun receiveContentAdd(jinglePacket: Iq, modification: RtpContentMap) {
        val remote = getRemoteContentMap() ?: throw NullPointerException()
        if (!Collections.disjoint(modification.getNames(), remote.getNames())) {
            respondOk(jinglePacket)
            this.webRTCWrapper.close()
            sendSessionTerminate(
                Reason.FAILED_APPLICATION,
                String.format(
                    "contents with names %s already exists",
                    Joiner.on(", ").join(modification.getNames()),
                ),
            )
            return
        }
        val contentAddition =
            ContentAddition.of(ContentAddition.Direction.INCOMING, modification)

        val outgoing = this.outgoingContentAdd
        val outgoingContentAddSummary: MutableSet<ContentAddition.Summary> =
            if (outgoing == null) {
                Collections.emptySet<ContentAddition.Summary>()
            } else {
                ContentAddition.summary(outgoing)
            }

        if (outgoingContentAddSummary.equals(contentAddition.summary)) {
            if (isInitiator()) {
                Log.d(
                    Config.LOGTAG,
                    "" +
                        id.account.getJid().asBareJid() +
                        ": respond with tie break to matching content-add offer",
                )
                respondWithTieBreak(jinglePacket)
            } else {
                Log.d(
                    Config.LOGTAG,
                    "" +
                        id.account.getJid().asBareJid() +
                        ": automatically accept matching content-add offer",
                )
                acceptContentAdd(contentAddition.summary, modification)
            }
            return
        }

        // once we can display multiple video tracks we can be more loose with this condition
        // theoretically it should also be fine to automatically accept audio only contents
        if (Media.audioOnly(remote.getMedia()) && Media.videoOnly(contentAddition.media())) {
            Log.d(
                Config.LOGTAG,
                "" + id.account.getJid().asBareJid() + ": received " + contentAddition,
            )
            this.incomingContentAdd = modification
            respondOk(jinglePacket)
            updateEndUserState()
        } else {
            respondOk(jinglePacket)
            // TODO do we want to add a reason?
            rejectContentAdd(modification)
        }
    }

    private fun receiveContentAccept(jinglePacket: Iq) {
        val jingle =
            jinglePacket.getExtension(Jingle::class.java) ?: throw NullPointerException()
        val receivedContentAccept: RtpContentMap
        try {
            receivedContentAccept = RtpContentMap.of(jingle)
            receivedContentAccept.requireContentDescriptions()
        } catch (e: RuntimeException) {
            Log.d(
                Config.LOGTAG,
                "" + id.account.getJid().asBareJid() + ": improperly formatted contents",
                Throwables.getRootCause(e),
            )
            respondOk(jinglePacket)
            webRTCWrapper.close()
            sendSessionTerminate(Reason.of(e), e.message)
            return
        }

        val outgoingContentAdd = this.outgoingContentAdd
        if (outgoingContentAdd == null) {
            Log.d(Config.LOGTAG, "received content-accept when we had no outgoing content add")
            receiveOutOfOrderAction(jinglePacket, Jingle.Action.CONTENT_ACCEPT)
            return
        }
        val ourSummary = ContentAddition.summary(outgoingContentAdd)
        if (ourSummary.equals(ContentAddition.summary(receivedContentAccept))) {
            this.outgoingContentAdd = null
            respondOk(jinglePacket)
            val hasFullTransportInfo = receivedContentAccept.hasFullTransportInfo()
            val future =
                receiveRtpContentMap(
                    receivedContentAccept,
                    this.omemoVerification.hasFingerprint() && hasFullTransportInfo,
                )
            Futures.addCallback(
                future,
                object : FutureCallback<RtpContentMap> {
                    override fun onSuccess(result: RtpContentMap) {
                        receiveContentAccept(result)
                    }

                    override fun onFailure(throwable: Throwable) {
                        webRTCWrapper.close()
                        sendSessionTerminate(Reason.ofThrowable(throwable), throwable.message)
                    }
                },
                MoreExecutors.directExecutor(),
            )
        } else {
            Log.d(Config.LOGTAG, "received content-accept did not match our outgoing content-add")
            receiveOutOfOrderAction(jinglePacket, Jingle.Action.CONTENT_ACCEPT)
        }
    }

    private fun receiveContentAccept(receivedContentAccept: RtpContentMap) {
        val peerDtlsSetup = getPeerDtlsSetup()
        val modifiedContentMap =
            (getRemoteContentMap() ?: throw NullPointerException())
                .addContent(receivedContentAccept, peerDtlsSetup)

        setRemoteContentMap(modifiedContentMap)

        val answer = SessionDescription.of(modifiedContentMap, isResponder())

        val sdp =
            org.webrtc.SessionDescription(
                org.webrtc.SessionDescription.Type.ANSWER,
                answer.toString(),
            )

        try {
            this.webRTCWrapper.setRemoteDescription(sdp).get()
        } catch (e: Exception) {
            val cause = Throwables.getRootCause(e)
            Log.d(
                Config.LOGTAG,
                "" +
                    id.account.getJid().asBareJid() +
                    ": unable to set remote description after receiving content-accept",
                cause,
            )
            webRTCWrapper.close()
            sendSessionTerminate(Reason.FAILED_APPLICATION, cause.message)
            return
        }
        Log.d(
            Config.LOGTAG,
            "" +
                id.account.getJid().asBareJid() +
                ": remote has accepted content-add " +
                ContentAddition.summary(receivedContentAccept),
        )
        processCandidates(receivedContentAccept.contents.entries)
        updateEndUserState()
    }

    private fun receiveContentModify(jinglePacket: Iq, jingle: Jingle) {
        if (this.state != State.SESSION_ACCEPTED) {
            receiveOutOfOrderAction(jinglePacket, Jingle.Action.CONTENT_MODIFY)
            return
        }
        val modification: Map<String, Content.Senders> =
            Maps.transformEntries(jingle.getJingleContents()) { _, value -> value.getSenders() }
        val isInitiator = isInitiator()
        val currentOutgoing = this.outgoingContentAdd
        val remoteContentMap = this.getRemoteContentMap()
        val currentOutgoingMediaIds: Set<String> =
            if (currentOutgoing == null) {
                Collections.emptySet<String>()
            } else {
                currentOutgoing.contents.keys
            }
        Log.d(Config.LOGTAG, "receiveContentModification(" + modification + ")")
        if (currentOutgoing != null && currentOutgoingMediaIds.containsAll(modification.keys)) {
            respondOk(jinglePacket)
            val modifiedContentMap: RtpContentMap
            try {
                modifiedContentMap =
                    currentOutgoing.modifiedSendersChecked(isInitiator, modification)
            } catch (e: IllegalArgumentException) {
                webRTCWrapper.close()
                sendSessionTerminate(Reason.FAILED_APPLICATION, e.message)
                return
            }
            this.outgoingContentAdd = modifiedContentMap
            Log.d(
                Config.LOGTAG,
                "" +
                    id.account.getJid().asBareJid() +
                    ": processed content-modification for pending content-add",
            )
        } else if (remoteContentMap != null &&
            remoteContentMap.contents.keys.containsAll(modification.keys)
        ) {
            respondOk(jinglePacket)
            val modifiedRemoteContentMap: RtpContentMap
            try {
                modifiedRemoteContentMap =
                    remoteContentMap.modifiedSendersChecked(isInitiator, modification)
            } catch (e: IllegalArgumentException) {
                webRTCWrapper.close()
                sendSessionTerminate(Reason.FAILED_APPLICATION, e.message)
                return
            }
            val offer: SessionDescription
            try {
                offer = SessionDescription.of(modifiedRemoteContentMap, isResponder())
            } catch (e: IllegalArgumentException) {
                Log.d(
                    Config.LOGTAG,
                    "" +
                        id.account.getJid().asBareJid() +
                        ": unable convert offer from content-modify to SDP",
                    e,
                )
                webRTCWrapper.close()
                sendSessionTerminate(Reason.FAILED_APPLICATION, e.message)
                return
            } catch (e: NullPointerException) {
                Log.d(
                    Config.LOGTAG,
                    "" +
                        id.account.getJid().asBareJid() +
                        ": unable convert offer from content-modify to SDP",
                    e,
                )
                webRTCWrapper.close()
                sendSessionTerminate(Reason.FAILED_APPLICATION, e.message)
                return
            }
            Log.d(
                Config.LOGTAG,
                "" + id.account.getJid().asBareJid() + ": auto accepting content-modification",
            )
            this.autoAcceptContentModify(modifiedRemoteContentMap, offer)
        } else {
            Log.d(Config.LOGTAG, "received unsupported content modification " + modification)
            respondWithItemNotFound(jinglePacket)
        }
    }

    private fun autoAcceptContentModify(
        modifiedRemoteContentMap: RtpContentMap,
        offer: SessionDescription,
    ) {
        this.setRemoteContentMap(modifiedRemoteContentMap)
        val sdp =
            org.webrtc.SessionDescription(
                org.webrtc.SessionDescription.Type.OFFER,
                offer.toString(),
            )
        try {
            this.webRTCWrapper.setRemoteDescription(sdp).get()
            // auto accept is only done when we already have tracks
            val answer = setLocalSessionDescription()
            val rtpContentMap = RtpContentMap.of(answer, isInitiator())
            modifyLocalContentMap(rtpContentMap)
            // we do not need to send an answer but do we have to resend the candidates currently in
            // SDP?
            // resendCandidatesFromSdp(answer);
            webRTCWrapper.setIsReadyToReceiveIceCandidates(true)
        } catch (e: Exception) {
            Log.d(Config.LOGTAG, "unable to accept content add", Throwables.getRootCause(e))
            webRTCWrapper.close()
            sendSessionTerminate(Reason.FAILED_APPLICATION)
        }
    }

    private fun receiveContentReject(jinglePacket: Iq, jingle: Jingle) {
        val receivedContentReject: RtpContentMap
        try {
            receivedContentReject = RtpContentMap.of(jingle)
        } catch (e: RuntimeException) {
            Log.d(
                Config.LOGTAG,
                "" + id.account.getJid().asBareJid() + ": improperly formatted contents",
                Throwables.getRootCause(e),
            )
            respondOk(jinglePacket)
            this.webRTCWrapper.close()
            sendSessionTerminate(Reason.of(e), e.message)
            return
        }

        val outgoingContentAdd = this.outgoingContentAdd
        if (outgoingContentAdd == null) {
            Log.d(Config.LOGTAG, "received content-reject when we had no outgoing content add")
            receiveOutOfOrderAction(jinglePacket, Jingle.Action.CONTENT_REJECT)
            return
        }
        val ourSummary = ContentAddition.summary(outgoingContentAdd)
        if (ourSummary.equals(ContentAddition.summary(receivedContentReject))) {
            this.outgoingContentAdd = null
            respondOk(jinglePacket)
            Log.d(Config.LOGTAG, jinglePacket.toString())
            receiveContentReject(ourSummary)
        } else {
            Log.d(Config.LOGTAG, "received content-reject did not match our outgoing content-add")
            receiveOutOfOrderAction(jinglePacket, Jingle.Action.CONTENT_REJECT)
        }
    }

    private fun receiveContentReject(summary: MutableSet<ContentAddition.Summary>) {
        try {
            this.webRTCWrapper.removeTrack(Media.VIDEO)
            val localContentMap = customRollback()
            modifyLocalContentMap(localContentMap)
        } catch (e: Exception) {
            val cause = Throwables.getRootCause(e)
            Log.d(
                Config.LOGTAG,
                "" +
                    id.account.getJid().asBareJid() +
                    ": unable to rollback local description after receiving" +
                    " content-reject",
                cause,
            )
            webRTCWrapper.close()
            sendSessionTerminate(Reason.FAILED_APPLICATION, cause.message)
            return
        }
        Log.d(
            Config.LOGTAG,
            "" +
                id.account.getJid().asBareJid() +
                ": remote has rejected our content-add " +
                summary,
        )
    }

    private fun receiveContentRemove(jinglePacket: Iq, jingle: Jingle) {
        val receivedContentRemove: RtpContentMap
        try {
            receivedContentRemove = RtpContentMap.of(jingle)
            receivedContentRemove.requireContentDescriptions()
        } catch (e: RuntimeException) {
            Log.d(
                Config.LOGTAG,
                "" + id.account.getJid().asBareJid() + ": improperly formatted contents",
                Throwables.getRootCause(e),
            )
            respondOk(jinglePacket)
            this.webRTCWrapper.close()
            sendSessionTerminate(Reason.of(e), e.message)
            return
        }
        respondOk(jinglePacket)
        receiveContentRemove(receivedContentRemove)
    }

    private fun receiveContentRemove(receivedContentRemove: RtpContentMap) {
        val incomingContentAdd = this.incomingContentAdd
        val contentAddSummary: MutableSet<ContentAddition.Summary> =
            if (incomingContentAdd == null) {
                Collections.emptySet<ContentAddition.Summary>()
            } else {
                ContentAddition.summary(incomingContentAdd)
            }
        val removeSummary = ContentAddition.summary(receivedContentRemove)
        if (contentAddSummary.equals(removeSummary)) {
            this.incomingContentAdd = null
            updateEndUserState()
        } else {
            webRTCWrapper.close()
            sendSessionTerminate(
                Reason.FAILED_APPLICATION,
                String.format(
                    "%s only supports %s as a means to retract a not yet accepted %s",
                    BuildConfig.APP_NAME,
                    Jingle.Action.CONTENT_REMOVE,
                    Jingle.Action.CONTENT_ADD,
                ),
            )
        }
    }

    @Synchronized
    fun retractContentAdd() {
        val outgoingContentAdd = this.outgoingContentAdd
        if (outgoingContentAdd == null) {
            throw IllegalStateException("Not outgoing content add")
        }
        try {
            webRTCWrapper.removeTrack(Media.VIDEO)
            val localContentMap = customRollback()
            modifyLocalContentMap(localContentMap)
        } catch (e: Exception) {
            val cause = Throwables.getRootCause(e)
            Log.d(
                Config.LOGTAG,
                "" +
                    id.account.getJid().asBareJid() +
                    ": unable to rollback local description after trying to retract" +
                    " content-add",
                cause,
            )
            webRTCWrapper.close()
            sendSessionTerminate(Reason.FAILED_APPLICATION, cause.message)
            return
        }
        this.outgoingContentAdd = null
        val retract =
            outgoingContentAdd
                .toStub()
                .toJinglePacket(Jingle.Action.CONTENT_REMOVE, id.sessionId)
        this.send(retract)
        Log.d(
            Config.LOGTAG,
            "" + id.account.getJid() +
                ": retract content-add " +
                ContentAddition.summary(outgoingContentAdd),
        )
    }

    private fun customRollback(): RtpContentMap {
        val sdp = setLocalSessionDescription()
        val localRtpContentMap = RtpContentMap.of(sdp, isInitiator())
        val answer = generateFakeResponse(localRtpContentMap)
        this.webRTCWrapper
            .setRemoteDescription(
                org.webrtc.SessionDescription(
                    org.webrtc.SessionDescription.Type.ANSWER,
                    answer.toString(),
                )
            )
            .get()
        return localRtpContentMap
    }

    private fun generateFakeResponse(localContentMap: RtpContentMap): SessionDescription {
        val currentRemote = getRemoteContentMap() ?: throw NullPointerException()
        val diff = currentRemote.diff(localContentMap)
        if (diff.isEmpty()) {
            throw IllegalStateException(
                "Unexpected rollback condition. No difference between local and remote",
            )
        }
        val patch = localContentMap.toContentModification(diff.added)
        if (ImmutableSet.of(Content.Senders.NONE).equals(patch.getSenders())) {
            val nextRemote =
                currentRemote.addContent(
                    patch.modifiedSenders(Content.Senders.NONE),
                    getPeerDtlsSetup(),
                )
            return SessionDescription.of(nextRemote, isResponder())
        }
        throw IllegalStateException(
            "Unexpected rollback condition. Senders were not uniformly none",
        )
    }

    @Synchronized
    fun acceptContentAdd(contentAddition: MutableSet<ContentAddition.Summary>) {
        val incomingContentAdd = this.incomingContentAdd
        if (incomingContentAdd == null) {
            throw IllegalStateException("No incoming content add")
        }

        if (contentAddition.equals(ContentAddition.summary(incomingContentAdd))) {
            this.incomingContentAdd = null
            val senders = incomingContentAdd.getSenders()
            Log.d(Config.LOGTAG, "senders of incoming content-add: " + senders)
            if (senders.equals(Content.Senders.receiveOnly(isInitiator()))) {
                Log.d(
                    Config.LOGTAG,
                    "content addition is receive only. we want to upgrade to 'both'",
                )
                val modifiedSenders = incomingContentAdd.modifiedSenders(Content.Senders.BOTH)
                val proposedContentModification =
                    modifiedSenders
                        .toStub()
                        .toJinglePacket(Jingle.Action.CONTENT_MODIFY, id.sessionId)
                proposedContentModification.setTo(id.with)
                xmppConnectionService.sendIqPacket(
                    id.account,
                    proposedContentModification,
                ) { response ->
                    if (response.getType() == Iq.Type.RESULT) {
                        Log.d(
                            Config.LOGTAG,
                            "" +
                                id.account.getJid().asBareJid() +
                                ": remote has accepted our upgrade to" +
                                " senders=both",
                        )
                        acceptContentAdd(
                            ContentAddition.summary(modifiedSenders),
                            modifiedSenders,
                        )
                    } else {
                        Log.d(
                            Config.LOGTAG,
                            "" +
                                id.account.getJid().asBareJid() +
                                ": remote has rejected our upgrade to" +
                                " senders=both",
                        )
                        acceptContentAdd(contentAddition, incomingContentAdd)
                    }
                }
            } else {
                acceptContentAdd(contentAddition, incomingContentAdd)
            }
        } else {
            throw IllegalStateException(
                "Accepted content add does not match pending content-add",
            )
        }
    }

    private fun acceptContentAdd(
        contentAddition: MutableSet<ContentAddition.Summary>,
        incomingContentAdd: RtpContentMap,
    ) {
        val setup = getPeerDtlsSetup()
        val modifiedContentMap =
            (getRemoteContentMap() ?: throw NullPointerException())
                .addContent(incomingContentAdd, setup)
        this.setRemoteContentMap(modifiedContentMap)

        val offer: SessionDescription
        try {
            offer = SessionDescription.of(modifiedContentMap, isResponder())
        } catch (e: IllegalArgumentException) {
            Log.d(
                Config.LOGTAG,
                "" + id.account.getJid().asBareJid() + ": unable convert offer from content-add to SDP",
                e,
            )
            webRTCWrapper.close()
            sendSessionTerminate(Reason.FAILED_APPLICATION, e.message)
            return
        } catch (e: NullPointerException) {
            Log.d(
                Config.LOGTAG,
                "" + id.account.getJid().asBareJid() + ": unable convert offer from content-add to SDP",
                e,
            )
            webRTCWrapper.close()
            sendSessionTerminate(Reason.FAILED_APPLICATION, e.message)
            return
        }
        this.incomingContentAdd = null
        acceptContentAdd(contentAddition, offer)
    }

    private fun acceptContentAdd(
        contentAddition: MutableSet<ContentAddition.Summary>,
        offer: SessionDescription,
    ) {
        val sdp =
            org.webrtc.SessionDescription(
                org.webrtc.SessionDescription.Type.OFFER,
                offer.toString(),
            )
        try {
            this.webRTCWrapper.setRemoteDescription(sdp).get()

            // TODO add tracks for 'media' where contentAddition.senders matches

            // TODO if senders.sending(isInitiator())

            this.webRTCWrapper.addTrack(Media.VIDEO)

            // TODO add additional transceivers for recv only cases

            val answer = setLocalSessionDescription()
            val rtpContentMap = RtpContentMap.of(answer, isInitiator())

            val contentAcceptMap =
                rtpContentMap.toContentModification(
                    Collections2.transform(contentAddition) { ca -> ca.name },
                )

            Log.d(
                Config.LOGTAG,
                "" +
                    id.account.getJid().asBareJid() +
                    ": sending content-accept " +
                    ContentAddition.summary(contentAcceptMap),
            )

            addIceCandidatesFromBlackLog()

            modifyLocalContentMap(rtpContentMap)
            val future = prepareOutgoingContentMap(contentAcceptMap)
            Futures.addCallback(
                future,
                object : FutureCallback<RtpContentMap> {
                    override fun onSuccess(rtpContentMap: RtpContentMap) {
                        sendContentAccept(rtpContentMap)
                        webRTCWrapper.setIsReadyToReceiveIceCandidates(true)
                    }

                    override fun onFailure(throwable: Throwable) {
                        failureToPerformAction(Jingle.Action.CONTENT_ACCEPT, throwable)
                    }
                },
                MoreExecutors.directExecutor(),
            )
        } catch (e: Exception) {
            Log.d(Config.LOGTAG, "unable to accept content add", Throwables.getRootCause(e))
            webRTCWrapper.close()
            sendSessionTerminate(Reason.FAILED_APPLICATION)
        }
    }

    private fun sendContentAccept(contentAcceptMap: RtpContentMap) {
        val iq = contentAcceptMap.toJinglePacket(Jingle.Action.CONTENT_ACCEPT, id.sessionId)
        send(iq)
    }

    @Synchronized
    fun rejectContentAdd() {
        val incomingContentAdd = this.incomingContentAdd
        if (incomingContentAdd == null) {
            throw IllegalStateException("No incoming content add")
        }
        this.incomingContentAdd = null
        updateEndUserState()
        rejectContentAdd(incomingContentAdd)
    }

    private fun rejectContentAdd(contentMap: RtpContentMap) {
        val iq =
            contentMap.toStub().toJinglePacket(Jingle.Action.CONTENT_REJECT, id.sessionId)
        Log.d(
            Config.LOGTAG,
            "" +
                id.account.getJid().asBareJid() +
                ": rejecting content " +
                ContentAddition.summary(contentMap),
        )
        send(iq)
    }

    private fun checkForIceRestart(jinglePacket: Iq, rtpContentMap: RtpContentMap): Boolean {
        val existing = getRemoteContentMap() ?: throw NullPointerException()
        val existingCredentials: MutableSet<IceUdpTransportInfo.Credentials>
        val newCredentials: IceUdpTransportInfo.Credentials
        try {
            existingCredentials = existing.getCredentials()
            newCredentials = rtpContentMap.getDistinctCredentials()
        } catch (e: IllegalStateException) {
            Log.d(Config.LOGTAG, "unable to gather credentials for comparison", e)
            return false
        }
        if (existingCredentials.contains(newCredentials)) {
            return false
        }
        // TODO an alternative approach is to check if we already got an iq result to our
        // ICE-restart
        // and if that's the case we are seeing an answer.
        // This might be more spec compliant but also more error prone potentially
        val isSignalStateStable =
            this.webRTCWrapper.getSignalingState() == PeerConnection.SignalingState.STABLE
        // TODO a stable signal state can be another indicator that we have an offer to restart ICE
        val isOffer = rtpContentMap.emptyCandidates()
        val restartContentMap: RtpContentMap
        try {
            if (isOffer) {
                Log.d(Config.LOGTAG, "received offer to restart ICE " + newCredentials)
                restartContentMap =
                    existing.modifiedCredentials(
                        newCredentials,
                        IceUdpTransportInfo.Setup.ACTPASS,
                    )
            } else {
                val setup = getPeerDtlsSetup()
                Log.d(
                    Config.LOGTAG,
                    "received confirmation of ICE restart" +
                        newCredentials +
                        " peer_setup=" +
                        setup,
                )
                // DTLS setup attribute needs to be rewritten to reflect current peer state
                // https://groups.google.com/g/discuss-webrtc/c/DfpIMwvUfeM
                restartContentMap = existing.modifiedCredentials(newCredentials, setup)
            }
            if (applyIceRestart(jinglePacket, restartContentMap, isOffer)) {
                return isOffer
            } else {
                Log.d(Config.LOGTAG, "ignoring ICE restart. sending tie-break")
                respondWithTieBreak(jinglePacket)
                return true
            }
        } catch (exception: Exception) {
            respondOk(jinglePacket)
            val rootCause = Throwables.getRootCause(exception)
            if (rootCause is WebRTCWrapper.PeerConnectionNotInitialized) {
                // If this happens a termination is already in progress
                Log.d(Config.LOGTAG, "ignoring PeerConnectionNotInitialized on ICE restart")
                return true
            }
            Log.d(Config.LOGTAG, "failure to apply ICE restart", rootCause)
            webRTCWrapper.close()
            sendSessionTerminate(Reason.ofThrowable(rootCause), rootCause.message)
            return true
        }
    }

    private fun getPeerDtlsSetup(): IceUdpTransportInfo.Setup {
        val peerSetup = this.peerDtlsSetup
        if (peerSetup == null || peerSetup == IceUdpTransportInfo.Setup.ACTPASS) {
            throw IllegalStateException("Invalid peer setup")
        }
        return peerSetup
    }

    private fun storePeerDtlsSetup(setup: IceUdpTransportInfo.Setup?) {
        if (setup == null || setup == IceUdpTransportInfo.Setup.ACTPASS) {
            throw IllegalArgumentException("Trying to store invalid peer dtls setup")
        }
        this.peerDtlsSetup = setup
    }

    private fun applyIceRestart(
        jinglePacket: Iq,
        restartContentMap: RtpContentMap,
        isOffer: Boolean,
    ): Boolean {
        val sessionDescription = SessionDescription.of(restartContentMap, isResponder())
        val type =
            if (isOffer) {
                org.webrtc.SessionDescription.Type.OFFER
            } else {
                org.webrtc.SessionDescription.Type.ANSWER
            }
        val sdp = org.webrtc.SessionDescription(type, sessionDescription.toString())
        if (isOffer && webRTCWrapper.getSignalingState() != PeerConnection.SignalingState.STABLE) {
            if (isInitiator()) {
                // We ignore the offer and respond with tie-break. This will clause the responder
                // not to apply the content map
                return false
            }
        }
        webRTCWrapper.setRemoteDescription(sdp).get()
        setRemoteContentMap(restartContentMap)
        if (isOffer) {
            val localSessionDescription = setLocalSessionDescription()
            setLocalContentMap(RtpContentMap.of(localSessionDescription, isInitiator()))
            // We need to respond OK before sending any candidates
            respondOk(jinglePacket)
            webRTCWrapper.setIsReadyToReceiveIceCandidates(true)
        } else {
            storePeerDtlsSetup(restartContentMap.getDtlsSetup())
        }
        return true
    }

    private fun processCandidates(
        contents: Collection<Map.Entry<String, DescriptionTransport<RtpDescription, IceUdpTransportInfo>>>,
    ) {
        for (content in contents) {
            processCandidate(content)
        }
    }

    private fun processCandidate(
        content: Map.Entry<String, DescriptionTransport<RtpDescription, IceUdpTransportInfo>>,
    ) {
        val rtpContentMap = getRemoteContentMap() ?: throw NullPointerException()
        val indices = RtpCandidates.toIdentificationTags(rtpContentMap, id.account.getJid())
        val sdpMid = content.key // aka content name
        val transport = content.value.transport
        val credentials = transport.getCredentials()

        // TODO check that credentials remained the same

        for (candidate in transport.getCandidates()) {
            val sdp: String
            try {
                sdp = candidate.toSdpAttribute(credentials.ufrag)
            } catch (e: IllegalArgumentException) {
                Log.d(
                    Config.LOGTAG,
                    "" +
                        id.account.getJid().asBareJid() +
                        ": ignoring invalid ICE candidate " +
                        e.message,
                )
                continue
            }
            val mLineIndex = indices.indexOf(sdpMid)
            if (mLineIndex < 0) {
                Log.w(
                    Config.LOGTAG,
                    "mLineIndex not found for " + sdpMid + ". available indices " + indices,
                )
            }
            val iceCandidate = IceCandidate(sdpMid, mLineIndex, sdp)
            Log.d(Config.LOGTAG, "received candidate: " + iceCandidate)
            this.webRTCWrapper.addIceCandidate(iceCandidate)
        }
    }

    private fun getRemoteContentMap(): RtpContentMap? =
        if (isInitiator()) this.responderRtpContentMap else this.initiatorRtpContentMap

    private fun getLocalContentMap(): RtpContentMap? =
        if (isInitiator()) this.initiatorRtpContentMap else this.responderRtpContentMap

    private fun receiveRtpContentMap(
        jinglePacket: Jingle,
        expectVerification: Boolean,
    ): ListenableFuture<RtpContentMap> =
        try {
            receiveRtpContentMap(RtpContentMap.of(jinglePacket), expectVerification)
        } catch (e: Exception) {
            Futures.immediateFailedFuture<RtpContentMap>(e)
        }

    private fun receiveRtpContentMap(
        receivedContentMap: RtpContentMap,
        expectVerification: Boolean,
    ): ListenableFuture<RtpContentMap> {
        Log.d(
            Config.LOGTAG,
            "receiveRtpContentMap(" +
                receivedContentMap.javaClass.simpleName +
                ",expectVerification=" +
                expectVerification +
                ")",
        )
        if (receivedContentMap is OmemoVerifiedRtpContentMap) {
            val future: ListenableFuture<OmemoSessionPort.OmemoVerifiedPayload<RtpContentMap>> =
                (id.account.getOmemoSession() ?: throw NullPointerException("no omemo session")).decryptVerified(receivedContentMap, id.with)
            return Futures.transform(
                future,
                { omemoVerifiedPayload ->
                    // TODO test if an exception here triggers a correct abort
                    omemoVerification.setOrEnsureEqual(omemoVerifiedPayload)
                    Log.d(
                        Config.LOGTAG,
                        "" +
                            id.account.getJid().asBareJid() +
                            ": received verifiable DTLS fingerprint via " +
                            omemoVerification,
                    )
                    omemoVerifiedPayload.payload
                },
                MoreExecutors.directExecutor(),
            )
        } else if (Config.REQUIRE_RTP_VERIFICATION || expectVerification) {
            return Futures.immediateFailedFuture(
                SecurityException("DTLS fingerprint was unexpectedly not verifiable"),
            )
        } else {
            return Futures.immediateFuture(receivedContentMap)
        }
    }

    private fun receiveSessionInitiate(jinglePacket: Iq, jingle: Jingle) {
        if (isInitiator()) {
            receiveOutOfOrderAction(jinglePacket, Jingle.Action.SESSION_INITIATE)
            return
        }
        val future = receiveRtpContentMap(jingle, false)
        Futures.addCallback(
            future,
            object : FutureCallback<RtpContentMap> {
                override fun onSuccess(rtpContentMap: RtpContentMap) {
                    receiveSessionInitiate(jinglePacket, rtpContentMap)
                }

                override fun onFailure(throwable: Throwable) {
                    respondOk(jinglePacket)
                    sendSessionTerminate(Reason.ofThrowable(throwable), throwable.message)
                }
            },
            MoreExecutors.directExecutor(),
        )
    }

    private fun receiveSessionInitiate(jinglePacket: Iq, contentMap: RtpContentMap) {
        try {
            contentMap.requireContentDescriptions()
            contentMap.requireDTLSFingerprint(true)
        } catch (e: RuntimeException) {
            Log.d(
                Config.LOGTAG,
                "" + id.account.getJid().asBareJid() + ": improperly formatted contents",
                Throwables.getRootCause(e),
            )
            respondOk(jinglePacket)
            sendSessionTerminate(Reason.of(e), e.message)
            return
        }
        Log.d(
            Config.LOGTAG,
            "processing session-init with " + contentMap.contents.size + " contents",
        )
        val target: State
        if (this.state == State.PROCEED) {
            val proposed = proposedMedia
            Preconditions.checkState(
                proposed != null && proposed.size > 0,
                "proposed media must be set when processing pre-approved session-initiate",
            )
            if (proposed != contentMap.getMedia()) {
                sendSessionTerminate(
                    Reason.SECURITY_ERROR,
                    String.format(
                        "Your session proposal (Jingle Message Initiation) included media" +
                            " %s but your session-initiate was %s",
                        this.proposedMedia,
                        contentMap.getMedia(),
                    ),
                )
                return
            }
            target = State.SESSION_INITIALIZED_PRE_APPROVED
        } else {
            target = State.SESSION_INITIALIZED
            setProposedMedia(contentMap.getMedia())
        }
        if (transition(target, Runnable { this.initiatorRtpContentMap = contentMap })) {
            respondOk(jinglePacket)
            pendingIceCandidates.addAll(contentMap.contents.entries)
            if (target == State.SESSION_INITIALIZED_PRE_APPROVED) {
                Log.d(
                    Config.LOGTAG,
                    "" +
                        id.account.getJid().asBareJid() +
                        ": automatically accepting session-initiate",
                )
                sendSessionAccept()
            } else {
                Log.d(
                    Config.LOGTAG,
                    "" +
                        id.account.getJid().asBareJid() +
                        ": received not pre-approved session-initiate. start ringing",
                )
                startRinging()
            }
        } else {
            Log.d(
                Config.LOGTAG,
                String.format(
                    "%s: received session-initiate while in state %s",
                    id.account.getJid().asBareJid(),
                    state,
                ),
            )
            terminateWithOutOfOrder(jinglePacket)
        }
    }

    private fun receiveSessionAccept(jinglePacket: Iq, jingle: Jingle) {
        if (isResponder()) {
            receiveOutOfOrderAction(jinglePacket, Jingle.Action.SESSION_ACCEPT)
            return
        }
        val future = receiveRtpContentMap(jingle, this.omemoVerification.hasFingerprint())
        Futures.addCallback(
            future,
            object : FutureCallback<RtpContentMap> {
                override fun onSuccess(rtpContentMap: RtpContentMap) {
                    receiveSessionAccept(jinglePacket, rtpContentMap)
                }

                override fun onFailure(throwable: Throwable) {
                    respondOk(jinglePacket)
                    Log.d(
                        Config.LOGTAG,
                        "" +
                            id.account.getJid().asBareJid() +
                            ": improperly formatted contents in session-accept",
                        throwable,
                    )
                    webRTCWrapper.close()
                    sendSessionTerminate(Reason.ofThrowable(throwable), throwable.message)
                }
            },
            MoreExecutors.directExecutor(),
        )
    }

    private fun receiveSessionAccept(jinglePacket: Iq, contentMap: RtpContentMap) {
        try {
            contentMap.requireContentDescriptions()
            contentMap.requireDTLSFingerprint()
        } catch (e: RuntimeException) {
            respondOk(jinglePacket)
            Log.d(
                Config.LOGTAG,
                "" +
                    id.account.getJid().asBareJid() +
                    ": improperly formatted contents in session-accept",
                e,
            )
            webRTCWrapper.close()
            sendSessionTerminate(Reason.of(e), e.message)
            return
        }
        val initiatorMedia =
            (initiatorRtpContentMap ?: throw NullPointerException()).getMedia()
        if (!initiatorMedia.equals(contentMap.getMedia())) {
            sendSessionTerminate(
                Reason.SECURITY_ERROR,
                String.format(
                    "Your session-included included media %s but our session-initiate was" +
                        " %s",
                    this.proposedMedia,
                    contentMap.getMedia(),
                ),
            )
            return
        }
        Log.d(
            Config.LOGTAG,
            "processing session-accept with " + contentMap.contents.size + " contents",
        )
        if (transition(State.SESSION_ACCEPTED)) {
            respondOk(jinglePacket)
            receiveSessionAccept(contentMap)
        } else {
            Log.d(
                Config.LOGTAG,
                String.format(
                    "%s: received session-accept while in state %s",
                    id.account.getJid().asBareJid(),
                    state,
                ),
            )
            respondOk(jinglePacket)
        }
    }

    private fun receiveSessionAccept(contentMap: RtpContentMap) {
        this.responderRtpContentMap = contentMap
        this.storePeerDtlsSetup(contentMap.getDtlsSetup())
        val sessionDescription: SessionDescription
        try {
            sessionDescription = SessionDescription.of(contentMap, false)
        } catch (e: IllegalArgumentException) {
            Log.d(
                Config.LOGTAG,
                "" +
                    id.account.getJid().asBareJid() +
                    ": unable convert offer from session-accept to SDP",
                e,
            )
            webRTCWrapper.close()
            sendSessionTerminate(Reason.FAILED_APPLICATION, e.message)
            return
        } catch (e: NullPointerException) {
            Log.d(
                Config.LOGTAG,
                "" +
                    id.account.getJid().asBareJid() +
                    ": unable convert offer from session-accept to SDP",
                e,
            )
            webRTCWrapper.close()
            sendSessionTerminate(Reason.FAILED_APPLICATION, e.message)
            return
        }
        val answer =
            org.webrtc.SessionDescription(
                org.webrtc.SessionDescription.Type.ANSWER,
                sessionDescription.toString(),
            )
        try {
            this.webRTCWrapper.setRemoteDescription(answer).get()
        } catch (e: Exception) {
            Log.d(
                Config.LOGTAG,
                "" +
                    id.account.getJid().asBareJid() +
                    ": unable to set remote description after receiving session-accept",
                Throwables.getRootCause(e),
            )
            webRTCWrapper.close()
            sendSessionTerminate(
                Reason.FAILED_APPLICATION,
                Throwables.getRootCause(e).message,
            )
            return
        }
        processCandidates(contentMap.contents.entries)
    }

    private fun sendSessionAccept() {
        val rtpContentMap = this.initiatorRtpContentMap
        if (rtpContentMap == null) {
            throw IllegalStateException("initiator RTP Content Map has not been set")
        }
        val offer: SessionDescription
        try {
            offer = SessionDescription.of(rtpContentMap, true)
        } catch (e: IllegalArgumentException) {
            Log.d(
                Config.LOGTAG,
                "" +
                    id.account.getJid().asBareJid() +
                    ": unable convert offer from session-initiate to SDP",
                e,
            )
            webRTCWrapper.close()
            sendSessionTerminate(Reason.FAILED_APPLICATION, e.message)
            return
        } catch (e: NullPointerException) {
            Log.d(
                Config.LOGTAG,
                "" +
                    id.account.getJid().asBareJid() +
                    ": unable convert offer from session-initiate to SDP",
                e,
            )
            webRTCWrapper.close()
            sendSessionTerminate(Reason.FAILED_APPLICATION, e.message)
            return
        }
        sendSessionAccept(rtpContentMap.getMedia(), offer)
    }

    private fun sendSessionAccept(media: MutableSet<Media>, offer: SessionDescription) {
        discoverIceServers(id.account, xmppConnectionService) { iceServers ->
            sendSessionAccept(media, offer, iceServers)
        }
    }

    @Synchronized
    private fun sendSessionAccept(
        media: Set<Media>,
        offer: SessionDescription,
        iceServers: Collection<PeerConnection.IceServer>,
    ) {
        if (isTerminated()) {
            Log.w(
                Config.LOGTAG,
                "" +
                    id.account.getJid().asBareJid() +
                    ": ICE servers got discovered when session was already terminated." +
                    " nothing to do.",
            )
            return
        }
        val includeCandidates = remoteHasSdpOfferAnswer()
        try {
            setupWebRTC(media, iceServers, !includeCandidates)
        } catch (e: WebRTCWrapper.InitializationException) {
            Log.d(Config.LOGTAG, "" + id.account.getJid().asBareJid() + ": unable to initialize WebRTC")
            webRTCWrapper.close()
            sendSessionTerminate(Reason.FAILED_APPLICATION, e.message)
            return
        }
        val sdp =
            org.webrtc.SessionDescription(
                org.webrtc.SessionDescription.Type.OFFER,
                offer.toString(),
            )
        try {
            this.webRTCWrapper.setRemoteDescription(sdp).get()
            addIceCandidatesFromBlackLog()
            val webRTCSessionDescription =
                this.webRTCWrapper.setLocalDescription(includeCandidates).get()
            prepareSessionAccept(webRTCSessionDescription, includeCandidates)
        } catch (e: Exception) {
            failureToAcceptSession(e)
        }
    }

    private fun failureToAcceptSession(throwable: Throwable) {
        if (isTerminated()) {
            return
        }
        val rootCause = Throwables.getRootCause(throwable)
        Log.d(Config.LOGTAG, "unable to send session accept", rootCause)
        webRTCWrapper.close()
        sendSessionTerminate(Reason.ofThrowable(rootCause), rootCause.message)
    }

    private fun failureToPerformAction(action: Jingle.Action, throwable: Throwable) {
        if (isTerminated()) {
            return
        }
        val rootCause = Throwables.getRootCause(throwable)
        Log.d(Config.LOGTAG, "unable to send " + action, rootCause)
        webRTCWrapper.close()
        sendSessionTerminate(Reason.ofThrowable(rootCause), rootCause.message)
    }

    private fun addIceCandidatesFromBlackLog() {
        while (true) {
            val candidate = this.pendingIceCandidates.poll() ?: break
            processCandidate(candidate)
            Log.d(
                Config.LOGTAG,
                "" + id.account.getJid().asBareJid() + ": added candidate from back log",
            )
        }
    }

    private fun prepareSessionAccept(
        webRTCSessionDescription: org.webrtc.SessionDescription,
        includeCandidates: Boolean,
    ) {
        val sessionDescription = SessionDescription.parse(webRTCSessionDescription.description)
        val respondingRtpContentMap = RtpContentMap.of(sessionDescription, false)
        val candidates: ImmutableMultimap<String, IceUdpTransportInfo.Candidate> =
            if (includeCandidates) {
                RtpCandidates.parseCandidates(sessionDescription)
            } else {
                ImmutableMultimap.of()
            }
        this.responderRtpContentMap = respondingRtpContentMap
        storePeerDtlsSetup(respondingRtpContentMap.getDtlsSetup().flip())
        val outgoingContentMapFuture = prepareOutgoingContentMap(respondingRtpContentMap)
        Futures.addCallback(
            outgoingContentMapFuture,
            object : FutureCallback<RtpContentMap> {
                override fun onSuccess(outgoingContentMap: RtpContentMap) {
                    if (includeCandidates) {
                        Log.d(
                            Config.LOGTAG,
                            "including " + candidates.size() + " candidates in session accept",
                        )
                        sendSessionAccept(outgoingContentMap.withCandidates(candidates))
                    } else {
                        sendSessionAccept(outgoingContentMap)
                    }
                    webRTCWrapper.setIsReadyToReceiveIceCandidates(true)
                }

                override fun onFailure(throwable: Throwable) {
                    failureToAcceptSession(throwable)
                }
            },
            MoreExecutors.directExecutor(),
        )
    }

    private fun sendSessionAccept(rtpContentMap: RtpContentMap) {
        if (isTerminated()) {
            Log.w(
                Config.LOGTAG,
                "" +
                    id.account.getJid().asBareJid() +
                    ": preparing session accept was too slow. already terminated. nothing" +
                    " to do.",
            )
            return
        }
        transitionOrThrow(State.SESSION_ACCEPTED)
        val sessionAccept =
            rtpContentMap.toJinglePacket(Jingle.Action.SESSION_ACCEPT, id.sessionId)
        send(sessionAccept)
    }

    private fun prepareOutgoingContentMap(rtpContentMap: RtpContentMap): ListenableFuture<RtpContentMap> {
        if (this.omemoVerification.hasDeviceId()) {
            val verifiedPayloadFuture:
                ListenableFuture<OmemoSessionPort.OmemoVerifiedPayload<RtpContentMap>> =
                (id.account.getOmemoSession() ?: throw NullPointerException("no omemo session"))
                    .encryptVerified(
                        rtpContentMap,
                        id.with,
                        omemoVerification.getDeviceId(),
                    )
            return Futures.transform(
                verifiedPayloadFuture,
                { verifiedPayload ->
                    omemoVerification.setOrEnsureEqual(verifiedPayload)
                    verifiedPayload.payload
                },
                MoreExecutors.directExecutor(),
            )
        } else {
            return Futures.immediateFuture(rtpContentMap)
        }
    }

    @Synchronized
    @JvmName("deliveryMessage")
    internal fun deliveryMessage(
        from: Jid,
        message: Element,
        serverMessageId: String?,
        timestamp: Long,
    ) {
        Log.d(
            Config.LOGTAG,
            "" +
                id.account.getJid().asBareJid() +
                ": delivered message to JingleRtpConnection " +
                message,
        )
        when (message.getName()) {
            "propose" ->
                receivePropose(from, Propose.upgrade(message), serverMessageId, timestamp)
            "proceed" ->
                receiveProceed(from, Proceed.upgrade(message), serverMessageId, timestamp)
            "retract" -> receiveRetract(from, serverMessageId, timestamp)
            "reject" -> receiveReject(from, serverMessageId, timestamp)
            "accept" -> receiveAccept(from, serverMessageId, timestamp)
        }
    }

    @JvmName("deliverFailedProceed")
    internal fun deliverFailedProceed(message: String?) {
        Log.d(
            Config.LOGTAG,
            "" +
                id.account.getJid().asBareJid() +
                ": receive message error for proceed message (" +
                Strings.nullToEmpty(message) +
                ")",
        )
        if (transition(State.TERMINATED_CONNECTIVITY_ERROR)) {
            webRTCWrapper.close()
            Log.d(
                Config.LOGTAG,
                "" + id.account.getJid().asBareJid() + ": transitioned into connectivity error",
            )
            this.finish()
        }
    }

    private fun receiveAccept(from: Jid, serverMsgId: String?, timestamp: Long) {
        val originatedFromMyself =
            from.asBareJid().equals(id.account.getJid().asBareJid())
        if (originatedFromMyself) {
            if (transition(State.ACCEPTED)) {
                acceptedOnOtherDevice(serverMsgId, timestamp)
            } else {
                Log.d(
                    Config.LOGTAG,
                    "" +
                        id.account.getJid().asBareJid() +
                        ": unable to transition to accept because already in state=" +
                        this.state,
                )
                Log.d(Config.LOGTAG, "" + id.account.getJid() + ": received accept from " + from)
            }
        } else {
            Log.d(
                Config.LOGTAG,
                "" + id.account.getJid().asBareJid() + ": ignoring 'accept' from " + from,
            )
        }
    }

    private fun acceptedOnOtherDevice(serverMsgId: String?, timestamp: Long) {
        if (serverMsgId != null) {
            this.message.setServerMsgId(serverMsgId)
        }
        this.message.setTime(timestamp)
        this.message.setCarbon(true) // indicate that call was accepted on other device
        this.callLog.writeLogMessageSuccess(0)
        this.xmppConnectionService.getNotificationService().cancelIncomingCallNotification()
        this.finish()
    }

    private fun receiveReject(from: Jid, serverMsgId: String?, timestamp: Long) {
        val originatedFromMyself =
            from.asBareJid().equals(id.account.getJid().asBareJid())
        // reject from another one of my clients
        if (originatedFromMyself) {
            receiveRejectFromMyself(serverMsgId, timestamp)
        } else if (isInitiator()) {
            if (from.equals(id.with)) {
                receiveRejectFromResponder()
            } else {
                Log.d(
                    Config.LOGTAG,
                    "" + id.account.getJid() +
                        ": ignoring reject from " +
                        from +
                        " for session with " +
                        id.with,
                )
            }
        } else {
            Log.d(
                Config.LOGTAG,
                "" + id.account.getJid() +
                    ": ignoring reject from " +
                    from +
                    " for session with " +
                    id.with,
            )
        }
    }

    private fun receiveRejectFromMyself(serverMsgId: String?, timestamp: Long) {
        if (transition(State.REJECTED)) {
            this.xmppConnectionService.getNotificationService().cancelIncomingCallNotification()
            this.finish()
            if (serverMsgId != null) {
                this.message.setServerMsgId(serverMsgId)
            }
            this.message.setTime(timestamp)
            this.message.setCarbon(true) // indicate that call was rejected on other device
            this.callLog.writeLogMessageMissed()
        } else {
            Log.d(
                Config.LOGTAG,
                "not able to transition into REJECTED because already in " + this.state,
            )
        }
    }

    private fun receiveRejectFromResponder() {
        if (isInState(State.PROCEED)) {
            Log.d(
                Config.LOGTAG,
                "" + id.account.getJid() +
                    ": received reject while still in proceed. callee reconsidered",
            )
            closeTransitionLogFinish(State.REJECTED_RACED)
            return
        }
        if (isInState(State.SESSION_INITIALIZED_PRE_APPROVED)) {
            Log.d(
                Config.LOGTAG,
                "" + id.account.getJid() +
                    ": received reject while in SESSION_INITIATED_PRE_APPROVED. callee" +
                    " reconsidered before receiving session-init",
            )
            closeTransitionLogFinish(State.TERMINATED_DECLINED_OR_BUSY)
            return
        }
        Log.d(
            Config.LOGTAG,
            "" + id.account.getJid() +
                ": ignoring reject from responder because already in state " +
                this.state,
        )
    }

    private fun receivePropose(
        from: Jid,
        propose: Propose,
        serverMsgId: String?,
        timestamp: Long,
    ) {
        val originatedFromMyself =
            from.asBareJid().equals(id.account.getJid().asBareJid())
        if (originatedFromMyself) {
            Log.d(
                Config.LOGTAG,
                "" + id.account.getJid().asBareJid() + ": saw proposal from myself. ignoring",
            )
        } else if (transition(
                State.PROPOSED,
                Runnable {
                    val descriptions: Collection<RtpDescription> =
                        Collections2.transform(
                            Collections2.filter(propose.getDescriptions()) { d ->
                                d is RtpDescription
                            },
                            { input -> input as RtpDescription },
                        )
                    val media: Collection<Media> =
                        Collections2.transform(descriptions) { input -> input.getMedia() }
                    Preconditions.checkState(
                        !media.contains(Media.UNKNOWN),
                        "RTP descriptions contain unknown media",
                    )
                    Log.d(
                        Config.LOGTAG,
                        "" +
                            id.account.getJid().asBareJid() +
                            ": received session proposal from " +
                            from +
                            " for " +
                            media,
                    )
                    this.setProposedMedia(Sets.newHashSet(media))
                },
            )
        ) {
            if (serverMsgId != null) {
                this.message.setServerMsgId(serverMsgId)
            }
            this.message.setTime(timestamp)
            startRinging()
            // in environments where we always use discovery timeouts we always want to respond with
            // 'ringing'
            if (Config.JINGLE_MESSAGE_INIT_STRICT_DEVICE_TIMEOUT ||
                id.getContact().showInContactList()
            ) {
                sendJingleMessage("ringing")
            }
        } else {
            Log.d(
                Config.LOGTAG,
                "" + id.account.getJid() +
                    ": ignoring session proposal because already in " +
                    state,
            )
        }
    }

    private fun startRinging() {
        this.callIntegration.setRinging()
        Log.d(
            Config.LOGTAG,
            "" +
                id.account.getJid().asBareJid() +
                ": received call from " +
                id.with +
                ". start ringing",
        )
        ringingTimeoutFuture =
            jingleConnectionManager.schedule(
                Runnable { ringingTimeout() },
                BUSY_TIME_OUT,
                TimeUnit.SECONDS,
            )
        if (xmppConnectionService.callIntegration().addNewIncomingCall(xmppConnectionService, getId())) {
            return
        }
        xmppConnectionService.getNotificationService().startRinging(id, getMedia())
    }

    @Synchronized
    private fun ringingTimeout() {
        Log.d(Config.LOGTAG, "" + id.account.getJid().asBareJid() + ": timeout reached for ringing")
        when (this.state) {
            State.PROPOSED -> {
                message.markUnread()
                rejectCallFromProposed()
            }
            State.SESSION_INITIALIZED -> {
                message.markUnread()
                rejectCallFromSessionInitiate()
            }
            else -> {}
        }
        xmppConnectionService.getNotificationService().pushMissedCallNow(message)
    }

    private fun cancelRingingTimeout() {
        val future = this.ringingTimeoutFuture
        if (future != null && !future.isCancelled()) {
            future.cancel(false)
        }
    }

    private fun receiveProceed(
        from: Jid,
        proceed: Proceed,
        serverMsgId: String?,
        timestamp: Long,
    ) {
        val media: Set<Media> =
            Preconditions.checkNotNull<Set<Media>>(
                this.proposedMedia,
                "Proposed media has to be set before handling proceed",
            )
        Preconditions.checkState(media.size > 0, "Proposed media should not be empty")
        if (from.equals(id.with)) {
            if (isInitiator()) {
                if (transition(State.PROCEED)) {
                    if (serverMsgId != null) {
                        this.message.setServerMsgId(serverMsgId)
                    }
                    this.message.setTime(timestamp)
                    val remoteDeviceId = proceed.getDeviceId()
                    if (isOmemoEnabled()) {
                        this.omemoVerification.setDeviceId(remoteDeviceId)
                    } else {
                        if (remoteDeviceId != null) {
                            Log.d(
                                Config.LOGTAG,
                                "" +
                                    id.account.getJid().asBareJid() +
                                    ": remote party signaled support for OMEMO" +
                                    " verification but we have OMEMO disabled",
                            )
                        }
                        this.omemoVerification.setDeviceId(null)
                    }
                    this.sendSessionInitiate(media, State.SESSION_INITIALIZED_PRE_APPROVED)
                } else {
                    Log.d(
                        Config.LOGTAG,
                        String.format(
                            "%s: ignoring proceed because already in %s",
                            id.account.getJid().asBareJid(),
                            this.state,
                        ),
                    )
                }
            } else {
                Log.d(
                    Config.LOGTAG,
                    String.format(
                        "%s: ignoring proceed because we were not initializing",
                        id.account.getJid().asBareJid(),
                    ),
                )
            }
        } else if (from.asBareJid().equals(id.account.getJid().asBareJid())) {
            if (transition(State.ACCEPTED)) {
                Log.d(
                    Config.LOGTAG,
                    "" +
                        id.account.getJid().asBareJid() +
                        ": moved session with " +
                        id.with +
                        " into state accepted after received carbon copied proceed",
                )
                acceptedOnOtherDevice(serverMsgId, timestamp)
            }
        } else {
            Log.d(
                Config.LOGTAG,
                String.format(
                    "%s: ignoring proceed from %s. was expected from %s",
                    id.account.getJid().asBareJid(),
                    from,
                    id.with,
                ),
            )
        }
    }

    private fun receiveRetract(from: Jid, serverMsgId: String?, timestamp: Long) {
        if (from.equals(id.with)) {
            val target =
                if (this.state == State.PROCEED) State.RETRACTED_RACED else State.RETRACTED
            if (transition(target)) {
                xmppConnectionService.getNotificationService().cancelIncomingCallNotification()
                xmppConnectionService.getNotificationService().pushMissedCallNow(message)
                Log.d(
                    Config.LOGTAG,
                    "" +
                        id.account.getJid().asBareJid() +
                        ": session with " +
                        id.with +
                        " has been retracted (serverMsgId=" +
                        serverMsgId +
                        ")",
                )
                if (serverMsgId != null) {
                    this.message.setServerMsgId(serverMsgId)
                }
                this.message.setTime(timestamp)
                if (target == State.RETRACTED) {
                    this.message.markUnread()
                }
                this.callLog.writeLogMessageMissed()
                finish()
            } else {
                Log.d(Config.LOGTAG, "ignoring retract because already in " + this.state)
            }
        } else {
            // TODO parse retract from self
            Log.d(
                Config.LOGTAG,
                "" +
                    id.account.getJid().asBareJid() +
                    ": received retract from " +
                    from +
                    ". expected retract from" +
                    id.with +
                    ". ignoring",
            )
        }
    }

    fun sendSessionInitiate() {
        sendSessionInitiate(this.proposedMedia, State.SESSION_INITIALIZED)
    }

    private fun sendSessionInitiate(media: Set<Media>?, targetState: State) {
        Log.d(Config.LOGTAG, "" + id.account.getJid().asBareJid() + ": prepare session-initiate")
        discoverIceServers(id.account, xmppConnectionService) { iceServers ->
            sendSessionInitiate(media, targetState, iceServers)
        }
    }

    @Synchronized
    private fun sendSessionInitiate(
        media: Set<Media>?,
        targetState: State,
        iceServers: Collection<PeerConnection.IceServer>,
    ) {
        if (isTerminated()) {
            Log.w(
                Config.LOGTAG,
                "" +
                    id.account.getJid().asBareJid() +
                    ": ICE servers got discovered when session was already terminated." +
                    " nothing to do.",
            )
            return
        }
        val includeCandidates = remoteHasSdpOfferAnswer()
        try {
            setupWebRTC(media ?: throw NullPointerException(), iceServers, !includeCandidates)
        } catch (e: WebRTCWrapper.InitializationException) {
            Log.d(Config.LOGTAG, "" + id.account.getJid().asBareJid() + ": unable to initialize WebRTC")
            webRTCWrapper.close()
            sendRetract(Reason.ofThrowable(e))
            return
        }
        try {
            val webRTCSessionDescription =
                this.webRTCWrapper.setLocalDescription(includeCandidates).get()
            prepareSessionInitiate(webRTCSessionDescription, includeCandidates, targetState)
        } catch (e: Exception) {
            // TODO sending the error text is worthwhile as well. Especially for FailureToSet
            // exceptions
            failureToInitiateSession(e, targetState)
        }
    }

    private fun failureToInitiateSession(throwable: Throwable, targetState: State) {
        if (isTerminated()) {
            return
        }
        Log.d(
            Config.LOGTAG,
            "" + id.account.getJid().asBareJid() + ": unable to sendSessionInitiate",
            Throwables.getRootCause(throwable),
        )
        webRTCWrapper.close()
        val reason = Reason.ofThrowable(throwable)
        if (isInState(targetState)) {
            sendSessionTerminate(reason, throwable.message)
        } else {
            sendRetract(reason)
        }
    }

    private fun sendRetract(reason: Reason) {
        // TODO embed reason into retract
        sendJingleMessage("retract", id.with.asBareJid())
        transitionOrThrow(AbstractJingleConnection.reasonToState(reason))
        this.finish()
    }

    private fun prepareSessionInitiate(
        webRTCSessionDescription: org.webrtc.SessionDescription,
        includeCandidates: Boolean,
        targetState: State,
    ) {
        val sessionDescription = SessionDescription.parse(webRTCSessionDescription.description)
        val rtpContentMap = RtpContentMap.of(sessionDescription, true)
        val candidates: ImmutableMultimap<String, IceUdpTransportInfo.Candidate> =
            if (includeCandidates) {
                RtpCandidates.parseCandidates(sessionDescription)
            } else {
                ImmutableMultimap.of()
            }
        this.initiatorRtpContentMap = rtpContentMap
        val outgoingContentMapFuture = encryptSessionInitiate(rtpContentMap)
        Futures.addCallback(
            outgoingContentMapFuture,
            object : FutureCallback<RtpContentMap> {
                override fun onSuccess(outgoingContentMap: RtpContentMap) {
                    if (includeCandidates) {
                        Log.d(
                            Config.LOGTAG,
                            "including " +
                                candidates.size() +
                                " candidates in session initiate",
                        )
                        sendSessionInitiate(
                            outgoingContentMap.withCandidates(candidates),
                            targetState,
                        )
                    } else {
                        sendSessionInitiate(outgoingContentMap, targetState)
                    }
                    webRTCWrapper.setIsReadyToReceiveIceCandidates(true)
                }

                override fun onFailure(throwable: Throwable) {
                    failureToInitiateSession(throwable, targetState)
                }
            },
            MoreExecutors.directExecutor(),
        )
    }

    private fun sendSessionInitiate(rtpContentMap: RtpContentMap, targetState: State) {
        if (isTerminated()) {
            Log.w(
                Config.LOGTAG,
                "" +
                    id.account.getJid().asBareJid() +
                    ": preparing session was too slow. already terminated. nothing to" +
                    " do.",
            )
            return
        }
        this.transitionOrThrow(targetState)
        val sessionInitiate =
            rtpContentMap.toJinglePacket(Jingle.Action.SESSION_INITIATE, id.sessionId)
        send(sessionInitiate)
    }

    private fun encryptSessionInitiate(rtpContentMap: RtpContentMap): ListenableFuture<RtpContentMap> {
        if (this.omemoVerification.hasDeviceId()) {
            val verifiedPayloadFuture:
                ListenableFuture<OmemoSessionPort.OmemoVerifiedPayload<RtpContentMap>> =
                (id.account.getOmemoSession() ?: throw NullPointerException("no omemo session"))
                    .encryptVerified(
                        rtpContentMap,
                        id.with,
                        omemoVerification.getDeviceId(),
                    )
            val future: ListenableFuture<RtpContentMap> =
                Futures.transform(
                    verifiedPayloadFuture,
                    { verifiedPayload ->
                        omemoVerification.setSessionFingerprint(
                            verifiedPayload.fingerprint,
                        )
                        verifiedPayload.payload
                    },
                    MoreExecutors.directExecutor(),
                )
            if (Config.REQUIRE_RTP_VERIFICATION) {
                return future
            }
            return Futures.catching(
                future,
                OmemoFailure::class.java,
                { e ->
                    Log.w(
                        Config.LOGTAG,
                        "" +
                            id.account.getJid().asBareJid() +
                            ": unable to use OMEMO DTLS verification on outgoing" +
                            " session initiate. falling back",
                        e,
                    )
                    rtpContentMap
                },
                MoreExecutors.directExecutor(),
            )
        } else {
            return Futures.immediateFuture(rtpContentMap)
        }
    }

    protected fun sendSessionTerminate(reason: Reason) {
        sendSessionTerminate(reason, null)
    }

    protected fun sendSessionTerminate(reason: Reason, text: String?) {
        sendSessionTerminate(
            reason,
            text,
            Consumer { state -> this.callLog.writeLogMessage(state) },
        )
        sendJingleMessageFinish(reason)
    }

    private fun sendTransportInfo(contentName: String, candidate: IceUdpTransportInfo.Candidate) {
        val transportInfo: RtpContentMap
        try {
            val rtpContentMap =
                if (isInitiator()) this.initiatorRtpContentMap else this.responderRtpContentMap
            transportInfo =
                (rtpContentMap ?: throw NullPointerException())
                    .transportInfo(contentName, candidate)
        } catch (e: Exception) {
            Log.d(
                Config.LOGTAG,
                "" +
                    id.account.getJid().asBareJid() +
                    ": unable to prepare transport-info from candidate for content=" +
                    contentName,
            )
            return
        }
        val iq = transportInfo.toJinglePacket(Jingle.Action.TRANSPORT_INFO, id.sessionId)
        send(iq)
    }

    fun getEndUserState(): RtpEndUserState =
        RtpEndUserStates.of(
            this.state,
            isInitiator(),
            this::getPendingContentAddition,
            this::getPeerConnectionStateOrNull,
            this::zeroDuration,
        )

    private fun getPeerConnectionStateOrNull(): PeerConnection.PeerConnectionState? =
        try {
            webRTCWrapper.getState()
        } catch (e: WebRTCWrapper.PeerConnectionNotInitialized) {
            // We usually close the WebRTCWrapper *before* transitioning so we might still
            // be in SESSION_ACCEPTED even though the peerConnection has been torn down
            null
        }

    private fun getPeerConnectionStateAsEndUserState(): RtpEndUserState =
        RtpEndUserStates.ofPeerConnection(getPeerConnectionStateOrNull(), this::zeroDuration)

    private fun isPeerConnectionConnected(): Boolean =
        try {
            webRTCWrapper.getState() == PeerConnection.PeerConnectionState.CONNECTED
        } catch (e: WebRTCWrapper.PeerConnectionNotInitialized) {
            false
        }

    private fun updateCallIntegrationState() {
        RtpEndUserStates.updateCallIntegration(
            this.state,
            isInitiator(),
            isPeerConnectionConnected(),
            this.callIntegration,
        )
    }

    fun getPendingContentAddition(): ContentAddition? {
        val incoming = this.incomingContentAdd
        val outgoing = this.outgoingContentAdd
        if (outgoing != null) {
            return ContentAddition.of(ContentAddition.Direction.OUTGOING, outgoing)
        } else if (incoming != null) {
            return ContentAddition.of(ContentAddition.Direction.INCOMING, incoming)
        } else {
            return null
        }
    }

    // `proposedMedia` is the Java's `Set<Media>` field, and `OngoingRtpSession` spells the return
    // `MutableSet` for the callers that hand it on (see that interface). The cast is erased, the
    // same one `RtpSessionProposal.getMedia()` makes.
    @Suppress("UNCHECKED_CAST")
    override fun getMedia(): MutableSet<Media> {
        val current = getState()
        if (current == State.NULL) {
            if (isInitiator()) {
                return Preconditions.checkNotNull<Set<Media>>(
                    this.proposedMedia,
                    "RTP connection has not been initialized properly",
                ) as MutableSet<Media>
            }
            throw IllegalStateException("RTP connection has not been initialized yet")
        }
        if (Arrays.asList(State.PROPOSED, State.PROCEED).contains(current)) {
            return Preconditions.checkNotNull<Set<Media>>(
                this.proposedMedia,
                "RTP connection has not been initialized properly",
            ) as MutableSet<Media>
        }
        val localContentMap = getLocalContentMap()
        val initiatorContentMap = initiatorRtpContentMap
        if (localContentMap != null) {
            return localContentMap.getMedia()
        } else if (initiatorContentMap != null) {
            return initiatorContentMap.getMedia()
        } else if (isTerminated()) {
            return Collections.emptySet() // we might fail before we ever got a chance to set media
        } else {
            return Preconditions.checkNotNull<Set<Media>>(
                this.proposedMedia,
                "RTP connection has not been initialized properly",
            ) as MutableSet<Media>
        }
    }

    fun isVerified(): Boolean {
        val fingerprint = this.omemoVerification.getFingerprint()
        if (fingerprint == null) {
            return false
        }
        return (id.account.getOmemoSession() ?: throw NullPointerException("no omemo session")).isFingerprintVerified(fingerprint)
    }

    fun addMedia(media: Media): Boolean {
        val currentMedia = getMedia()
        if (currentMedia.contains(media)) {
            throw IllegalStateException(String.format("%s has already been proposed", media))
        }
        // TODO add state protection - can only add while ACCEPTED or so
        Log.d(Config.LOGTAG, "adding media: " + media)
        return webRTCWrapper.addTrack(media)
    }

    @Synchronized
    fun acceptCall() {
        when (this.state) {
            State.PROPOSED -> {
                cancelRingingTimeout()
                acceptCallFromProposed()
            }
            State.SESSION_INITIALIZED -> {
                cancelRingingTimeout()
                acceptCallFromSessionInitialized()
            }
            State.ACCEPTED ->
                Log.w(
                    Config.LOGTAG,
                    "" +
                        id.account.getJid().asBareJid() +
                        ": the call has already been accepted  with another client." +
                        " UI was just lagging behind",
                )
            State.PROCEED,
            State.SESSION_ACCEPTED ->
                Log.w(
                    Config.LOGTAG,
                    "" +
                        id.account.getJid().asBareJid() +
                        ": the call has already been accepted. user probably double" +
                        " tapped the UI",
                )
            else -> throw IllegalStateException("Can not accept call from " + this.state)
        }
    }

    @Synchronized
    fun rejectCall() {
        if (isTerminated()) {
            Log.w(
                Config.LOGTAG,
                "" +
                    id.account.getJid().asBareJid() +
                    ": received rejectCall() when session has already been terminated." +
                    " nothing to do",
            )
            return
        }
        when (this.state) {
            State.PROPOSED -> rejectCallFromProposed()
            State.SESSION_INITIALIZED -> rejectCallFromSessionInitiate()
            else -> throw IllegalStateException("Can not reject call from " + this.state)
        }
    }

    @Synchronized
    fun integrationFailure() {
        val state = getState()
        if (state == State.PROPOSED) {
            Log.e(
                Config.LOGTAG,
                "" + id.account.getJid().asBareJid() + ": failed call integration in state proposed",
            )
            rejectCallFromProposed()
        } else if (state == State.SESSION_INITIALIZED) {
            Log.e(Config.LOGTAG, "" + id.account.getJid().asBareJid() + ": failed call integration")
            this.webRTCWrapper.close()
            sendSessionTerminate(Reason.FAILED_APPLICATION, "CallIntegration failed")
        } else {
            throw IllegalStateException(
                String.format("Can not fail integration in state %s", state),
            )
        }
    }

    @Synchronized
    fun endCall() {
        if (isTerminated()) {
            Log.w(
                Config.LOGTAG,
                "" +
                    id.account.getJid().asBareJid() +
                    ": received endCall() when session has already been terminated." +
                    " nothing to do",
            )
            return
        }
        if (isInState(State.PROPOSED) && isResponder()) {
            rejectCallFromProposed()
            return
        }
        if (isInState(State.PROCEED)) {
            if (isInitiator()) {
                retractFromProceed()
            } else {
                rejectCallFromProceed()
            }
            return
        }
        if (isInitiator() &&
            isInState(State.SESSION_INITIALIZED, State.SESSION_INITIALIZED_PRE_APPROVED)
        ) {
            this.webRTCWrapper.close()
            sendSessionTerminate(Reason.CANCEL)
            return
        }
        if (isInState(State.SESSION_INITIALIZED)) {
            rejectCallFromSessionInitiate()
            return
        }
        if (isInState(State.SESSION_INITIALIZED_PRE_APPROVED, State.SESSION_ACCEPTED)) {
            this.webRTCWrapper.close()
            sendSessionTerminate(Reason.SUCCESS)
            return
        }
        if (isInState(
                State.TERMINATED_APPLICATION_FAILURE,
                State.TERMINATED_CONNECTIVITY_ERROR,
                State.TERMINATED_DECLINED_OR_BUSY,
            )
        ) {
            Log.d(
                Config.LOGTAG,
                "ignoring request to end call because already in state " + this.state,
            )
            return
        }
        throw IllegalStateException(
            "called 'endCall' while in state " + this.state + ". isInitiator=" + isInitiator(),
        )
    }

    private fun retractFromProceed() {
        Log.d(Config.LOGTAG, "retract from proceed")
        this.sendJingleMessage("retract")
        closeTransitionLogFinish(State.RETRACTED_RACED)
    }

    private fun closeTransitionLogFinish(state: State) {
        this.webRTCWrapper.close()
        transitionOrThrow(state)
        this.callLog.writeLogMessage(state)
        finish()
    }

    private fun setupWebRTC(
        media: Set<Media>,
        iceServers: Collection<PeerConnection.IceServer>,
        trickle: Boolean,
    ) {
        this.jingleConnectionManager.ensureConnectionIsRegistered(this)
        this.webRTCWrapper.setup(this.xmppConnectionService)
        val appSettings =
            XmppConnectionService.dataStatics()
                .settings(xmppConnectionService.getApplicationContext())
        this.webRTCWrapper.initializePeerConnection(
            media,
            iceServers,
            trickle,
            appSettings.isUseRelays(),
        )
        // this.webRTCWrapper.setMicrophoneEnabledOrThrow(callIntegration.isMicrophoneEnabled());
        this.webRTCWrapper.setMicrophoneEnabledOrThrow(true)
    }

    private fun acceptCallFromProposed() {
        transitionOrThrow(State.PROCEED)
        xmppConnectionService.getNotificationService().cancelIncomingCallNotification()
        this.callIntegration.startAudioRouting()
        this.sendJingleMessage("accept", id.account.getJid().asBareJid())
        this.sendJingleMessage("proceed")
    }

    private fun rejectCallFromProposed() {
        transitionOrThrow(State.REJECTED)
        this.callLog.writeLogMessageMissed()
        xmppConnectionService.getNotificationService().cancelIncomingCallNotification()
        this.sendJingleMessage("reject")
        finish()
    }

    private fun rejectCallFromProceed() {
        this.sendJingleMessage("reject")
        closeTransitionLogFinish(State.REJECTED_RACED)
    }

    private fun rejectCallFromSessionInitiate() {
        webRTCWrapper.close()
        sendSessionTerminate(Reason.DECLINE)
        xmppConnectionService.getNotificationService().cancelIncomingCallNotification()
    }

    private fun sendJingleMessage(action: String) {
        sendJingleMessage(action, id.with)
    }

    private fun sendJingleMessage(action: String, to: Jid) {
        val messagePacket = uk.xa0.tulkki.xmpp.models.stanza.Message()
        // we want to carbon copy those
        messagePacket.setType(uk.xa0.tulkki.xmpp.models.stanza.Message.Type.CHAT)
        messagePacket.setTo(to)
        val intent =
            messagePacket
                .addChild(action, Namespace.JINGLE_MESSAGE)
                .setAttribute("id", id.sessionId)
        if ("proceed" == action) {
            messagePacket.setId(
                AbstractJingleConnection.JINGLE_MESSAGE_PROCEED_ID_PREFIX + id.sessionId
            )
            if (isOmemoEnabled()) {
                val deviceId = (id.account.getOmemoSession() ?: throw NullPointerException("no omemo session")).getOwnDeviceId()
                val device =
                    intent.addChild("device", Namespace.OMEMO_DTLS_SRTP_VERIFICATION)
                device.setAttribute("id", deviceId)
            }
        }
        messagePacket.addChild("store", "urn:xmpp:hints")
        xmppConnectionService.sendMessagePacket(id.account, messagePacket)
    }

    private fun sendJingleMessageFinish(reason: Reason) {
        val account = id.account
        val messagePacket =
            xmppConnectionService
                .getMessageGenerator()
                .sessionFinish(id.with, id.sessionId, reason)
        xmppConnectionService.sendMessagePacket(account, messagePacket)
    }

    private fun isOmemoEnabled(): Boolean {
        val conversational = message.getConversation()
        if (conversational is ConversationRef) {
            return conversational.getNextEncryption() == MessageRef.ENCRYPTION_AXOLOTL
        }
        return false
    }

    private fun acceptCallFromSessionInitialized() {
        xmppConnectionService.getNotificationService().cancelIncomingCallNotification()
        this.callIntegration.startAudioRouting()
        sendSessionAccept()
    }

    @Synchronized
    override fun transition(target: State, runnable: Runnable?): Boolean {
        return if (super.transition(target, runnable)) {
            updateEndUserState()
            updateOngoingCallNotification()
            true
        } else {
            false
        }
    }

    override fun onIceCandidate(iceCandidate: IceCandidate) {
        val rtpContentMap =
            if (isInitiator()) this.initiatorRtpContentMap else this.responderRtpContentMap
        val credentials: IceUdpTransportInfo.Credentials
        try {
            credentials =
                (rtpContentMap ?: throw NullPointerException())
                    .getCredentials(iceCandidate.sdpMid)
        } catch (e: IllegalArgumentException) {
            Log.d(Config.LOGTAG, "ignoring (not sending) candidate: " + iceCandidate, e)
            return
        }
        val uFrag = credentials.ufrag
        val candidate =
            IceUdpTransportInfo.Candidate.fromSdpAttribute(iceCandidate.sdp, uFrag)
        if (candidate == null) {
            Log.d(Config.LOGTAG, "ignoring (not sending) candidate: " + iceCandidate)
            return
        }
        Log.d(Config.LOGTAG, "sending candidate: " + iceCandidate)
        sendTransportInfo(iceCandidate.sdpMid, candidate)
    }

    override fun onConnectionChange(newState: PeerConnection.PeerConnectionState) {
        Log.d(
            Config.LOGTAG,
            "" + id.account.getJid().asBareJid() + ": PeerConnectionState changed to " + newState,
        )
        this.stateHistory.add(newState)
        if (newState == PeerConnection.PeerConnectionState.CONNECTED) {
            this.sessionDuration.start()
            updateOngoingCallNotification()
        } else if (this.sessionDuration.isRunning) {
            this.sessionDuration.stop()
            updateOngoingCallNotification()
        }

        val neverConnected =
            !this.stateHistory.contains(PeerConnection.PeerConnectionState.CONNECTED)

        if (newState == PeerConnection.PeerConnectionState.FAILED) {
            if (neverConnected) {
                if (isTerminated()) {
                    Log.d(
                        Config.LOGTAG,
                        "" +
                            id.account.getJid().asBareJid() +
                            ": not sending session-terminate after connectivity error" +
                            " because session is already in state " +
                            this.state,
                    )
                    return
                }
                webRTCWrapper.execute { closeWebRTCSessionAfterFailedConnection() }
                return
            } else {
                this.restartIce()
            }
        }
        updateEndUserState()
    }

    private fun restartIce() {
        this.stateHistory.clear()
        this.webRTCWrapper.restartIceAsync()
    }

    override fun onRenegotiationNeeded() {
        this.webRTCWrapper.execute { renegotiate() }
    }

    @Synchronized
    private fun renegotiate() {
        val sessionDescription: SessionDescription
        try {
            sessionDescription = setLocalSessionDescription()
        } catch (e: Exception) {
            val cause = Throwables.getRootCause(e)
            webRTCWrapper.close()
            if (isTerminated()) {
                Log.d(
                    Config.LOGTAG,
                    "failed to renegotiate. session was already terminated",
                    cause,
                )
                return
            }
            Log.d(Config.LOGTAG, "failed to renegotiate. sending session-terminate", cause)
            sendSessionTerminate(Reason.FAILED_APPLICATION, cause.message)
            return
        }
        val rtpContentMap = RtpContentMap.of(sessionDescription, isInitiator())
        val currentContentMap = getLocalContentMap() ?: throw NullPointerException()
        val iceRestart = currentContentMap.iceRestart(rtpContentMap)
        val diff = currentContentMap.diff(rtpContentMap)

        Log.d(
            Config.LOGTAG,
            "" +
                id.account.getJid().asBareJid() +
                ": renegotiate. iceRestart=" +
                iceRestart +
                " content id diff=" +
                diff,
        )

        if (diff.hasModifications() && iceRestart) {
            webRTCWrapper.close()
            sendSessionTerminate(
                Reason.FAILED_APPLICATION,
                "WebRTC unexpectedly tried to modify content and transport at once",
            )
            return
        }

        if (iceRestart) {
            initiateIceRestart(rtpContentMap)
            return
        } else if (diff.isEmpty()) {
            Log.d(
                Config.LOGTAG,
                "renegotiation. nothing to do. SignalingState=" +
                    this.webRTCWrapper.getSignalingState(),
            )
        }

        if (diff.added.isEmpty()) {
            return
        }
        modifyLocalContentMap(rtpContentMap)
        sendContentAdd(rtpContentMap, diff.added)
    }

    private fun initiateIceRestart(rtpContentMap: RtpContentMap) {
        val transportInfo = rtpContentMap.transportInfo()
        val iq = transportInfo.toJinglePacket(Jingle.Action.TRANSPORT_INFO, id.sessionId)
        Log.d(Config.LOGTAG, "initiating ice restart: " + iq)
        iq.setTo(id.with)
        xmppConnectionService.sendIqPacket(
            id.account,
            iq,
        ) { response ->
            if (response.getType() == Iq.Type.RESULT) {
                Log.d(Config.LOGTAG, "received success to our ice restart")
                setLocalContentMap(rtpContentMap)
                webRTCWrapper.setIsReadyToReceiveIceCandidates(true)
                return@sendIqPacket
            }
            if (response.getType() == Iq.Type.ERROR) {
                if (isTieBreak(response)) {
                    Log.d(Config.LOGTAG, "received tie-break as result of ice restart")
                    return@sendIqPacket
                }
                handleIqErrorResponse(response)
            }
            if (response.getType() == Iq.Type.TIMEOUT) {
                handleIqTimeoutResponse(response)
            }
        }
    }

    private fun isTieBreak(response: Iq): Boolean {
        val error = response.findChild("error")
        return error != null && error.hasChild("tie-break", Namespace.JINGLE_ERRORS)
    }

    private fun sendContentAdd(rtpContentMap: RtpContentMap, added: Collection<String>) {
        val contentAdd = rtpContentMap.toContentModification(added)
        this.outgoingContentAdd = contentAdd
        val outgoingContentMapFuture = prepareOutgoingContentMap(contentAdd)
        Futures.addCallback(
            outgoingContentMapFuture,
            object : FutureCallback<RtpContentMap> {
                override fun onSuccess(outgoingContentMap: RtpContentMap) {
                    sendContentAdd(outgoingContentMap)
                    webRTCWrapper.setIsReadyToReceiveIceCandidates(true)
                }

                override fun onFailure(throwable: Throwable) {
                    failureToPerformAction(Jingle.Action.CONTENT_ADD, throwable)
                }
            },
            MoreExecutors.directExecutor(),
        )
    }

    private fun sendContentAdd(contentAdd: RtpContentMap) {
        val iq = contentAdd.toJinglePacket(Jingle.Action.CONTENT_ADD, id.sessionId)
        iq.setTo(id.with)
        xmppConnectionService.sendIqPacket(
            id.account,
            iq,
        ) { response ->
            if (response.getType() == Iq.Type.RESULT) {
                Log.d(
                    Config.LOGTAG,
                    "" +
                        id.account.getJid().asBareJid() +
                        ": received ACK to our content-add",
                )
                return@sendIqPacket
            }
            if (response.getType() == Iq.Type.ERROR) {
                if (isTieBreak(response)) {
                    this.outgoingContentAdd = null
                    Log.d(Config.LOGTAG, "received tie-break as result of our content-add")
                    return@sendIqPacket
                }
                handleIqErrorResponse(response)
            }
            if (response.getType() == Iq.Type.TIMEOUT) {
                handleIqTimeoutResponse(response)
            }
        }
    }

    private fun setLocalContentMap(rtpContentMap: RtpContentMap) {
        if (isInitiator()) {
            this.initiatorRtpContentMap = rtpContentMap
        } else {
            this.responderRtpContentMap = rtpContentMap
        }
    }

    private fun setRemoteContentMap(rtpContentMap: RtpContentMap) {
        if (isInitiator()) {
            this.responderRtpContentMap = rtpContentMap
        } else {
            this.initiatorRtpContentMap = rtpContentMap
        }
    }

    // this method is to be used for content map modifications that modify media
    private fun modifyLocalContentMap(rtpContentMap: RtpContentMap) {
        val activeContents = rtpContentMap.activeContents()
        setLocalContentMap(activeContents)
        this.callIntegration.setAudioDeviceWhenAvailable(
            xmppConnectionService
                .callIntegration()
                .initialAudioDevice(activeContents.getMedia()),
        )
        updateEndUserState()
    }

    private fun setLocalSessionDescription(): SessionDescription {
        val sessionDescription = this.webRTCWrapper.setLocalDescription(false).get()
        return SessionDescription.parse(sessionDescription.description)
    }

    private fun closeWebRTCSessionAfterFailedConnection() {
        this.webRTCWrapper.close()
        synchronized(this) {
            if (isTerminated()) {
                Log.d(
                    Config.LOGTAG,
                    "" +
                        id.account.getJid().asBareJid() +
                        ": no need to send session-terminate after failed connection." +
                        " Other party already did",
                )
                return
            }
            sendSessionTerminate(Reason.CONNECTIVITY_ERROR)
        }
    }

    fun zeroDuration(): Boolean =
        this.sessionDuration.elapsed(TimeUnit.NANOSECONDS) <= 0

    fun getCallDuration(): Long = this.sessionDuration.elapsed(TimeUnit.MILLISECONDS)

    override fun getCallIntegration(): CallIntegrationPort =
        this.callIntegration

    fun isMicrophoneEnabled(): Boolean = webRTCWrapper.isMicrophoneEnabled()

    fun setMicrophoneEnabled(enabled: Boolean): Boolean =
        webRTCWrapper.setMicrophoneEnabledOrThrow(enabled)

    fun isVideoEnabled(): Boolean = webRTCWrapper.isVideoEnabled()

    fun setVideoEnabled(enabled: Boolean) {
        webRTCWrapper.setVideoEnabled(enabled)
    }

    fun isCameraSwitchable(): Boolean = webRTCWrapper.isCameraSwitchable()

    fun isFrontCamera(): Boolean = webRTCWrapper.isFrontCamera()

    fun switchCamera(): ListenableFuture<Boolean> = webRTCWrapper.switchCamera()

    @Synchronized
    override fun onCallIntegrationShowIncomingCallUi() {
        if (isTerminated()) {
            // there might be race conditions with the call integration service invoking this
            // callback when the rtp session has already ended.
            Log.w(
                Config.LOGTAG,
                "CallIntegration requested incoming call UI but session was already" +
                    " terminated",
            )
            return
        }
        // TODO apparently this can be called too early as well?
        xmppConnectionService.getNotificationService().startRinging(id, getMedia())
    }

    override fun onCallIntegrationDisconnect() {
        Log.d(Config.LOGTAG, "a phone call has just been started. killing jingle rtp connections")
        if (Arrays.asList(State.PROPOSED, State.SESSION_INITIALIZED).contains(this.state)) {
            rejectCall()
        } else {
            endCall()
        }
    }

    override fun onCallIntegrationReject() {
        Log.d(Config.LOGTAG, "rejecting call from system notification / call integration")
        try {
            rejectCall()
        } catch (e: IllegalStateException) {
            Log.w(Config.LOGTAG, "race condition on rejecting call from notification", e)
        }
    }

    override fun onCallIntegrationAnswer() {
        // we need to start the UI to a) show it and b) be able to ask for permissions
        // Pair 11 (D4): the intent, its action and its extras are the call screen's vocabulary, so the
        // port owns them; this island only says which call has to be answered. The two flags, the
        // service as the starting context and the log line are unchanged.
        Log.d(Config.LOGTAG, "start activity to accept call from call integration")
        xmppConnectionService
            .rtpSessionPort()
            .launchAcceptCall(
                xmppConnectionService,
                id.account.getJid().toString(),
                id.with.toString(),
                id.sessionId,
            )
    }

    override fun onCallIntegrationSilence() {
        xmppConnectionService.getNotificationService().stopSoundAndVibration()
    }

    override fun onCallIntegrationMicrophoneEnabled(enabled: Boolean) {
        // this is called every time we switch audio devices. Thus it would re-enable a microphone
        // that was previous disabled by the user. A proper implementation would probably be to
        // track user choice and enable the microphone with a userEnabled() &&
        // callIntegration.isMicrophoneEnabled() condition
        Log.d(Config.LOGTAG, "ignoring onCallIntegrationMicrophoneEnabled(" + enabled + ")")
        // this.webRTCWrapper.setMicrophoneEnabled(enabled);
    }

    @JvmSuppressWildcards
    override fun onAudioDeviceChanged(
        selectedAudioDevice: AudioDevice,
        availableAudioDevices: Set<AudioDevice>,
    ) {
        Log.d(
            Config.LOGTAG,
            "onAudioDeviceChanged(" + selectedAudioDevice + "," + availableAudioDevices + ")",
        )
        xmppConnectionService.notifyJingleRtpConnectionUpdate(
            selectedAudioDevice,
            availableAudioDevices,
        )
    }

    private fun updateEndUserState() {
        val endUserState = getEndUserState()
        this.updateCallIntegrationState()
        xmppConnectionService.notifyJingleRtpConnectionUpdate(
            id.account,
            id.with,
            id.sessionId,
            endUserState,
        )
    }

    private fun updateOngoingCallNotification() {
        val currentState = this.state
        if (STATES_SHOWING_ONGOING_CALL.contains(currentState)) {
            if (Arrays.asList(State.PROPOSED, State.SESSION_INITIALIZED).contains(currentState) &&
                isResponder()
            ) {
                Log.d(Config.LOGTAG, "do not set ongoing call during incoming call notification")
                xmppConnectionService.removeOngoingCall()
                return
            }
            val reconnecting =
                if (currentState == State.SESSION_ACCEPTED) {
                    getPeerConnectionStateAsEndUserState() == RtpEndUserState.RECONNECTING
                } else {
                    false
                }
            xmppConnectionService.setOngoingCall(id, getMedia(), reconnecting)
        } else {
            xmppConnectionService.removeOngoingCall()
        }
    }

    override fun terminateTransport() {
        this.webRTCWrapper.close()
    }

    override fun finish() {
        if (isTerminated()) {
            this.cancelRingingTimeout()
            this.callIntegration.verifyDisconnected()
            this.webRTCWrapper.verifyClosed()
            this.jingleConnectionManager.setTerminalSessionState(id, getEndUserState(), getMedia())
            super.finish()
            /*       // Disable call log files for now
            try {
                File log = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "Cheogram/calls/" + id.getWith().asBareJid() + "." + id.getSessionId() + "." + created + ".log");
                log.getParentFile().mkdirs();
                Runtime.getRuntime().exec(new String[]{"logcat", "-dT", "" + created + ".0", "-f", log.getAbsolutePath()});
            } catch (final IOException e) { }

                         */
        } else {
            throw IllegalStateException(
                String.format("Unable to call finish from %s", this.state),
            )
        }
    }

    fun getLocalVideoTrack(): Optional<VideoTrack> = webRTCWrapper.getLocalVideoTrack()

    fun getRemoteVideoTrack(): Optional<VideoTrack> = webRTCWrapper.getRemoteVideoTrack()

    fun getEglBaseContext(): EglBase.Context = webRTCWrapper.getEglBaseContext()

    @JvmName("setProposedMedia")
    internal fun setProposedMedia(media: Set<Media>) {
        this.proposedMedia = media
        this.callIntegration.setVideoState(
            if (Media.audioOnly(media)) {
                VideoProfile.STATE_AUDIO_ONLY
            } else {
                VideoProfile.STATE_BIDIRECTIONAL
            },
        )
        this.callIntegration.setInitialAudioDevice(
            xmppConnectionService.callIntegration().initialAudioDevice(media),
        )
    }

    fun fireStateUpdate() {
        val endUserState = getEndUserState()
        xmppConnectionService.notifyJingleRtpConnectionUpdate(
            id.account,
            id.with,
            id.sessionId,
            endUserState,
        )
    }

    fun isSwitchToVideoAvailable(): Boolean {
        val prerequisite =
            Media.audioOnly(getMedia()) &&
                Arrays.asList(RtpEndUserState.CONNECTED, RtpEndUserState.RECONNECTING)
                    .contains(getEndUserState())
        return prerequisite && remoteHasVideoFeature()
    }

    private fun remoteHasVideoFeature(): Boolean =
        remoteHasFeature(Namespace.JINGLE_FEATURE_VIDEO)

    private fun remoteHasSdpOfferAnswer(): Boolean =
        remoteHasFeature(Namespace.SDP_OFFER_ANSWER)

    override fun getAccount(): AccountRef = id.account

    override fun getWith(): Jid = id.with

    override fun getSessionId(): String = id.sessionId
}
