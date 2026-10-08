package uk.xa0.tulkki.xmpp.jingle

import android.util.Log
import com.google.common.base.MoreObjects
import com.google.common.base.Objects
import com.google.common.base.Preconditions
import com.google.common.base.Strings
import com.google.common.collect.ImmutableList
import com.google.common.collect.ImmutableMap
import java.util.Arrays
import java.util.function.Consumer
import uk.xa0.tulkki.xmpp.Config
import uk.xa0.tulkki.libs.Jid
import uk.xa0.tulkki.xmpp.jingle.stanzas.Reason
import uk.xa0.tulkki.xmpp.models.jingle.Jingle
import uk.xa0.tulkki.xmpp.models.stanza.Iq
import uk.xa0.tulkki.xmpp.refs.AccountRef
import uk.xa0.tulkki.xmpp.refs.ContactRef
import uk.xa0.tulkki.xmpp.refs.MessageRef
import uk.xa0.tulkki.libs.PresenceRef
import uk.xa0.tulkki.libs.ServiceDiscoveryResultRef
import uk.xa0.tulkki.xmpp.services.XmppConnectionService

/**
 * The state machine every Jingle session shares: the transition table, the IQ/terminate plumbing and
 * the nested [Id] and [State] vocabulary.
 *
 * Converted from Java by lane `B` (2026-10-08) **because the direction that refused it had gone**.
 * The old file was held back because `deliverPacket` and `notifyRebound` are package-private
 * `abstract` and a *Java* subclass overriding a *Kotlin* member is the failing direction - Kotlin
 * cannot spell package-private, so the Java override comes out weaker than the Kotlin declaration
 * (`weaker access privileges`, javac). The other direction is not an error: a *Kotlin* subclass
 * overriding a Java package-private member compiles and kotlinc emits the override `protected`
 * (lane `H` measured both ways on a scratch base with kotlinc 2.3/`javap`). On this tree **no Java
 * subclass of this class remains**: the only subclasses are [JingleRtpConnection] and
 * [JingleFileTransferConnection], both Kotlin, and `JingleConnectionManager` extends
 * `AbstractConnectionManager` rather than this class. There is no Java override left for the
 * failing direction to fire on, so the class was free and this conversion is the family's own
 * re-draw of the boundary the deferral note asked for.
 *
 * The mechanics, so the next reader does not have to re-derive them:
 * 1. **Package-private for a call is `internal`; package-private for an override has no spelling.**
 *    `deliverPacket`, `notifyRebound`, `isInitiator`, `isResponder`, `isTerminated` and the
 *    `jingleConnectionManager` field stay module-visible as `internal`. No Java caller remains
 *    (`JingleConnectionManager.kt` is Kotlin), so no `@JvmName` is needed to pin a plain JVM name -
 *    Kotlin's `internal` mangling applies to base and Kotlin subclasses alike, and both live in
 *    `:xmpp`, so the override still binds (measured: `deliverPacket$xmpp` on both sides).
 * 2. **`deliverPacket`/`notifyRebound` are `internal abstract`, not `protected`**, because
 *    `JingleConnectionManager` calls them and is not a subclass. `protected` would not compile
 *    those call sites.
 * 3. **Java's own modifiers are written out rather than Kotlin's narrower defaults.** The class is
 *    `abstract` (open for the two subclasses); `finish`, `transition(State, Runnable)` and
 *    `terminateTransport` are `open` because the subclasses override them; `getId`/`getState` are
 *    `open` because Java's were virtual. `id` and `state` are `@JvmField` so the protected field
 *    keeps its own JVM name beside the public `getId()`/`getState()` that Java callers already
 *    reached - a plain `val`/`var` would have generated a clashing accessor.
 * 4. **The statics keep their exact JVM surface.** `TERMINATED` is `@JvmField protected`,
 *    `reasonToState` is `@JvmStatic protected` and the two `JINGLE_MESSAGE_*_ID_PREFIX` constants
 *    are `const val`, so `XmppConnectionService.java`'s inherited `JingleRtpConnection.JINGLE_...`
 *    spelling and the Kotlin call sites that name the declaring class both still resolve.
 * 5. **Nullability follows the Java.** `sendSessionTerminate`'s `text` and `trigger` are nullable
 *    because its callers pass `null`; `transition`'s `Runnable` is nullable because the one-argument
 *    overload forwards `null` into it. No `!!` appears; the one place Java's `Preconditions`
 *    rejected a null, [Id]'s `sessionId`, is spelled `?: throw NullPointerException()`.
 */
abstract class AbstractJingleConnection internal constructor(
    @JvmField internal val jingleConnectionManager: JingleConnectionManager,
    id: Id,
    initiator: Jid,
) {

    @JvmField
    protected val xmppConnectionService: XmppConnectionService =
        jingleConnectionManager.getXmppConnectionService()

    @JvmField protected val id: Id = id

    private val initiator: Jid = initiator

    @JvmField protected var state: State = State.NULL

    open fun getId(): Id = id

    internal fun isInitiator(): Boolean = initiator == id.account.getJid()

    internal fun isResponder(): Boolean = initiator != id.account.getJid()

    open fun getState(): State = this.state

    @Synchronized
    protected open fun isInState(vararg state: State): Boolean =
        Arrays.asList(*state).contains(this.state)

    protected open fun transition(target: State): Boolean = transition(target, null)

    @Synchronized
    protected open fun transition(target: State, runnable: Runnable?): Boolean {
        val validTransitions = VALID_TRANSITIONS[this.state]
        if (validTransitions != null && validTransitions.contains(target)) {
            this.state = target
            runnable?.run()
            Log.d(
                Config.LOGTAG,
                "${id.account.getJid().asBareJid()}: transitioned into $target",
            )
            return true
        } else {
            return false
        }
    }

    /**
     * `internal` rather than `protected`: Java's `protected` is subclass-only in Kotlin, while
     * [JingleConnectionManager] - same package, not a subclass - calls this on a
     * `JingleRtpConnection`. No subclass overrides it and no Java caller names it, so the
     * `internal` spelling is enough and its mangled JVM name reaches nothing outside `:xmpp`.
     * Found by lane `G`'s kotlinc leg over all `:xmpp` Kotlin sources, not by the batch build.
     */
    internal open fun transitionOrThrow(target: State) {
        if (!transition(target)) {
            throw IllegalStateException(
                String.format("Unable to transition from %s to %s", this.state, target),
            )
        }
    }

    internal fun isTerminated(): Boolean = TERMINATED.contains(this.state)

    internal abstract fun deliverPacket(jinglePacket: Iq)

    protected open fun receiveOutOfOrderAction(jinglePacket: Iq, action: Jingle.Action) {
        Log.d(
            Config.LOGTAG,
            String.format(
                "%s: received %s even though we are in state %s",
                id.account.getJid().asBareJid(),
                action,
                getState(),
            ),
        )
        if (isTerminated()) {
            Log.d(
                Config.LOGTAG,
                String.format(
                    "%s: got a reason to terminate with out-of-order. but already in state %s",
                    id.account.getJid().asBareJid(),
                    getState(),
                ),
            )
            respondWithOutOfOrder(jinglePacket)
        } else {
            terminateWithOutOfOrder(jinglePacket)
        }
    }

    protected open fun terminateWithOutOfOrder(jinglePacket: Iq) {
        Log.d(
            Config.LOGTAG,
            "${id.account.getJid().asBareJid()}: terminating session with out-of-order",
        )
        terminateTransport()
        transitionOrThrow(State.TERMINATED_APPLICATION_FAILURE)
        respondWithOutOfOrder(jinglePacket)
        this.finish()
    }

    protected open fun finish() {
        if (isTerminated()) {
            this.jingleConnectionManager.finishConnectionOrThrow(this)
        } else {
            throw AssertionError(String.format("Unable to call finish from %s", this.state))
        }
    }

    protected abstract fun terminateTransport()

    internal abstract fun notifyRebound()

    protected open fun sendSessionTerminate(
        reason: Reason,
        text: String?,
        trigger: Consumer<State>?,
    ) {
        val previous = this.state
        val target = reasonToState(reason)
        transitionOrThrow(target)
        if (previous != State.NULL && trigger != null) {
            trigger.accept(target)
        }
        val iq = Iq(Iq.Type.SET)
        val jinglePacket = iq.addExtension(Jingle(Jingle.Action.SESSION_TERMINATE, id.sessionId))
        jinglePacket.setReason(reason, text)
        send(iq)
        finish()
    }

    protected open fun send(jinglePacket: Iq) {
        jinglePacket.setTo(id.with)
        xmppConnectionService.sendIqPacket(
            id.account,
            jinglePacket,
            Consumer { response -> handleIqResponse(response) },
        )
    }

    protected open fun respondOk(jinglePacket: Iq) {
        xmppConnectionService.sendIqPacket(
            id.account,
            jinglePacket.generateResponse(Iq.Type.RESULT),
            null,
        )
    }

    protected open fun respondWithTieBreak(jinglePacket: Iq) {
        respondWithJingleError(jinglePacket, "tie-break", "conflict", "cancel")
    }

    protected open fun respondWithOutOfOrder(jinglePacket: Iq) {
        respondWithJingleError(jinglePacket, "out-of-order", "unexpected-request", "wait")
    }

    protected open fun respondWithItemNotFound(jinglePacket: Iq) {
        respondWithJingleError(jinglePacket, null, "item-not-found", "cancel")
    }

    private fun respondWithJingleError(
        original: Iq,
        jingleCondition: String?,
        condition: String,
        conditionType: String,
    ) {
        jingleConnectionManager.respondWithJingleError(
            id.account,
            original,
            jingleCondition,
            condition,
            conditionType,
        )
    }

    @Synchronized
    private fun handleIqResponse(response: Iq) {
        if (response.getType() == Iq.Type.ERROR) {
            handleIqErrorResponse(response)
            return
        }
        if (response.getType() == Iq.Type.TIMEOUT) {
            handleIqTimeoutResponse(response)
        }
    }

    protected open fun handleIqErrorResponse(response: Iq) {
        Preconditions.checkArgument(response.getType() == Iq.Type.ERROR)
        val errorCondition = response.getErrorCondition()
        Log.d(
            Config.LOGTAG,
            "${id.account.getJid().asBareJid()}: received IQ-error from " +
                "${response.getFrom()} in RTP session. $errorCondition",
        )
        if (isTerminated()) {
            Log.i(
                Config.LOGTAG,
                "${id.account.getJid().asBareJid()}: ignoring error because session was already terminated",
            )
            return
        }
        this.terminateTransport()
        val target: State
        if (
            Arrays.asList(
                "service-unavailable",
                "recipient-unavailable",
                "remote-server-not-found",
                "remote-server-timeout",
            ).contains(errorCondition)
        ) {
            target = State.TERMINATED_CONNECTIVITY_ERROR
        } else {
            target = State.TERMINATED_APPLICATION_FAILURE
        }
        transitionOrThrow(target)
        this.finish()
    }

    protected open fun handleIqTimeoutResponse(response: Iq) {
        Preconditions.checkArgument(response.getType() == Iq.Type.TIMEOUT)
        Log.d(
            Config.LOGTAG,
            "${id.account.getJid().asBareJid()}: received IQ timeout in RTP session with " +
                "${id.with}. terminating with connectivity error",
        )
        if (isTerminated()) {
            Log.i(
                Config.LOGTAG,
                "${id.account.getJid().asBareJid()}: ignoring error because session was already terminated",
            )
            return
        }
        this.terminateTransport()
        transitionOrThrow(State.TERMINATED_CONNECTIVITY_ERROR)
        this.finish()
    }

    protected open fun remoteHasFeature(feature: String): Boolean {
        val contact = id.getContact()
        val presence: PresenceRef? =
            contact.getPresences().get(Strings.nullToEmpty(id.with.getResource()))
        val serviceDiscoveryResult: ServiceDiscoveryResultRef? =
            presence?.getServiceDiscoveryResult()
        val features = serviceDiscoveryResult?.getFeatures()
        return features != null && features.contains(feature)
    }

    class Id private constructor(
        val account: AccountRef,
        val with: Jid,
        sessionId: String?,
    ) {

        val sessionId: String = sessionId ?: throw NullPointerException()

        fun getContact(): ContactRef = account.getRoster().getContact(with)

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other == null || javaClass != other.javaClass) return false
            val id = other as Id
            return Objects.equal(account.getUuid(), id.account.getUuid()) &&
                Objects.equal(with, id.with) &&
                Objects.equal(sessionId, id.sessionId)
        }

        override fun hashCode(): Int = Objects.hashCode(account.getUuid(), with, sessionId)

        override fun toString(): String =
            MoreObjects.toStringHelper(this)
                .add("account", account.getJid())
                .add("with", with)
                .add("sessionId", sessionId)
                .toString()

        companion object {
            @JvmStatic
            fun of(account: AccountRef, iq: Iq, jingle: Jingle): Id =
                Id(account, iq.getFrom() ?: throw NullPointerException(), jingle.getSessionId())

            @JvmStatic
            fun of(account: AccountRef, with: Jid, sessionId: String): Id =
                Id(account, with, sessionId)

            @JvmStatic
            fun of(account: AccountRef, with: Jid): Id =
                Id(account, with, JingleConnectionManager.nextRandomId())

            /**
             * Tulkki: 3.7 C5-E4 - the narrowing cast C5-B carried here is **deleted, not guarded**:
             * `ConversationalRef.getAccount()` (`ConversationalRef.java:53`) is already an
             * `AccountRef`, so nothing needed it narrowed. C5-B's reason to keep the field
             * model-typed named two callees and neither holds now: `notifyJingleRtpConnectionUpdate`
             * took the ref in C5-C, and `JingleConnectionManager.respondWithJingleError` was widened
             * by one line in this same commit.
             */
            @JvmStatic
            fun of(message: MessageRef): Id =
                Id(
                    (message.getConversation() ?: throw NullPointerException("message has no conversation"))
                        .getAccount()
                        ?: throw NullPointerException("conversation has no account"),
                    message.getCounterpart() ?: throw NullPointerException("message has no counterpart"),
                    JingleConnectionManager.nextRandomId(),
                )
        }
    }

    companion object {

        const val JINGLE_MESSAGE_PROPOSE_ID_PREFIX = "jm-propose-"

        const val JINGLE_MESSAGE_PROCEED_ID_PREFIX = "jm-proceed-"

        @JvmField
        protected val TERMINATED: List<State> =
            Arrays.asList(
                State.ACCEPTED,
                State.REJECTED,
                State.REJECTED_RACED,
                State.RETRACTED,
                State.RETRACTED_RACED,
                State.TERMINATED_SUCCESS,
                State.TERMINATED_DECLINED_OR_BUSY,
                State.TERMINATED_CONNECTIVITY_ERROR,
                State.TERMINATED_CANCEL_OR_TIMEOUT,
                State.TERMINATED_APPLICATION_FAILURE,
                State.TERMINATED_SECURITY_ERROR,
            )

        private val VALID_TRANSITIONS: Map<State, Collection<State>> =
            ImmutableMap.builder<State, Collection<State>>()
                .put(
                    State.NULL,
                    ImmutableList.of(
                        State.PROPOSED,
                        State.SESSION_INITIALIZED,
                        State.TERMINATED_APPLICATION_FAILURE,
                        State.TERMINATED_SECURITY_ERROR,
                    ),
                )
                .put(
                    State.PROPOSED,
                    ImmutableList.of(
                        State.ACCEPTED,
                        State.PROCEED,
                        State.REJECTED,
                        State.RETRACTED,
                        State.TERMINATED_APPLICATION_FAILURE,
                        State.TERMINATED_SECURITY_ERROR,
                        // only used when the xmpp connection rebinds
                        State.TERMINATED_CONNECTIVITY_ERROR,
                    ),
                )
                .put(
                    State.PROCEED,
                    ImmutableList.of(
                        State.REJECTED_RACED,
                        State.RETRACTED_RACED,
                        State.SESSION_INITIALIZED_PRE_APPROVED,
                        State.TERMINATED_SUCCESS,
                        State.TERMINATED_APPLICATION_FAILURE,
                        State.TERMINATED_SECURITY_ERROR,
                        // at this state used for error bounces of the proceed message
                        State.TERMINATED_CONNECTIVITY_ERROR,
                    ),
                )
                .put(
                    State.SESSION_INITIALIZED,
                    ImmutableList.of(
                        State.SESSION_ACCEPTED,
                        State.TERMINATED_SUCCESS,
                        State.TERMINATED_DECLINED_OR_BUSY,
                        // at this state used for IQ errors and IQ timeouts
                        State.TERMINATED_CONNECTIVITY_ERROR,
                        State.TERMINATED_CANCEL_OR_TIMEOUT,
                        State.TERMINATED_APPLICATION_FAILURE,
                        State.TERMINATED_SECURITY_ERROR,
                    ),
                )
                .put(
                    State.SESSION_INITIALIZED_PRE_APPROVED,
                    ImmutableList.of(
                        State.SESSION_ACCEPTED,
                        State.TERMINATED_SUCCESS,
                        State.TERMINATED_DECLINED_OR_BUSY,
                        // at this state used for IQ errors and IQ timeouts
                        State.TERMINATED_CONNECTIVITY_ERROR,
                        State.TERMINATED_CANCEL_OR_TIMEOUT,
                        State.TERMINATED_APPLICATION_FAILURE,
                        State.TERMINATED_SECURITY_ERROR,
                    ),
                )
                .put(
                    State.SESSION_ACCEPTED,
                    ImmutableList.of(
                        State.TERMINATED_SUCCESS,
                        State.TERMINATED_DECLINED_OR_BUSY,
                        State.TERMINATED_CONNECTIVITY_ERROR,
                        State.TERMINATED_CANCEL_OR_TIMEOUT,
                        State.TERMINATED_APPLICATION_FAILURE,
                        State.TERMINATED_SECURITY_ERROR,
                    ),
                )
                .build()

        @JvmStatic
        protected fun reasonToState(reason: Reason): State =
            when (reason) {
                Reason.SUCCESS -> State.TERMINATED_SUCCESS
                Reason.DECLINE, Reason.BUSY -> State.TERMINATED_DECLINED_OR_BUSY
                Reason.CANCEL, Reason.TIMEOUT -> State.TERMINATED_CANCEL_OR_TIMEOUT
                Reason.SECURITY_ERROR -> State.TERMINATED_SECURITY_ERROR
                Reason.FAILED_APPLICATION,
                Reason.UNSUPPORTED_TRANSPORTS,
                Reason.UNSUPPORTED_APPLICATIONS -> State.TERMINATED_APPLICATION_FAILURE
                else -> State.TERMINATED_CONNECTIVITY_ERROR
            }
    }

    enum class State {
        NULL, // default value; nothing has been sent or received yet
        PROPOSED,
        ACCEPTED,
        PROCEED,
        REJECTED,
        REJECTED_RACED, // used when we want to reject but haven’t received session init yet
        RETRACTED,
        RETRACTED_RACED, // used when receiving a retract after we already asked to proceed
        SESSION_INITIALIZED, // equal to 'PENDING'
        SESSION_INITIALIZED_PRE_APPROVED,
        SESSION_ACCEPTED, // equal to 'ACTIVE'
        TERMINATED_SUCCESS, // equal to 'ENDED' (after successful call) ui will just close
        TERMINATED_DECLINED_OR_BUSY, // equal to 'ENDED' (after other party declined the call)
        TERMINATED_CONNECTIVITY_ERROR, // equal to 'ENDED' (but after network failures; ui will
        // display retry button)
        TERMINATED_CANCEL_OR_TIMEOUT, // more or less the same as retracted; caller pressed end call
        // before session was accepted
        TERMINATED_APPLICATION_FAILURE,
        TERMINATED_SECURITY_ERROR,
    }
}
