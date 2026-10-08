package uk.xa0.tulkki.xmpp.jingle

import uk.xa0.tulkki.xmpp.jingle.AbstractJingleConnection.State
import uk.xa0.tulkki.xmpp.refs.ConversationRef
import uk.xa0.tulkki.xmpp.refs.MessageRef
import uk.xa0.tulkki.xmpp.services.XmppConnectionService

/**
 * Tulkki: the call-log row a Jingle RTP session writes when it ends, out of `JingleRtpConnection`.
 *
 * The Java held this as four private methods that read the outer `message`, the outer
 * `xmppConnectionService` and the outer `getCallDuration()`. The first two are constructor
 * parameters now and the third is a `() -> Long` the connection hands in as
 * `RtpCallLog(message, xmppConnectionService, this::getCallDuration)` - the duration is asked for
 * at the moment the row is written, which is when the Java asked for it too.
 *
 * `internal` is the narrowest Kotlin spelling a same-module Java caller can still name, and
 * `@JvmName` pins the plain JVM names the three Java call sites use (`callLog.writeLogMessage(state)`,
 * `callLog::writeLogMessage`, `callLog.writeLogMessageMissed()` and
 * `callLog.writeLogMessageSuccess(duration)`): `internal` alone would mangle them to
 * `writeLogMessage$xmpp` and the Java would not resolve.
 *
 * The Java's stub-conversation failure keeps its exact message and its `IllegalStateException`.
 */
internal class RtpCallLog(
    private val message: MessageRef,
    private val service: XmppConnectionService,
    private val callDuration: () -> Long,
) {

    @JvmName("writeLogMessage")
    internal fun writeLogMessage(state: State) {
        val duration = callDuration()
        if (state == State.TERMINATED_SUCCESS ||
            (state == State.TERMINATED_CONNECTIVITY_ERROR && duration > 0)
        ) {
            writeLogMessageSuccess(duration)
        } else {
            writeLogMessageMissed()
        }
    }

    @JvmName("writeLogMessageSuccess")
    internal fun writeLogMessageSuccess(duration: Long) {
        this.message.setBody(XmppConnectionService.dataStatics().newRtpSessionStatus(true, duration))
        this.writeMessage()
    }

    @JvmName("writeLogMessageMissed")
    internal fun writeLogMessageMissed() {
        this.message.setBody(XmppConnectionService.dataStatics().newRtpSessionStatus(false, 0))
        this.writeMessage()
    }

    private fun writeMessage() {
        val conversational = message.getConversation()
        if (conversational is ConversationRef) {
            conversational.add(this.message)
            service.createMessageAsync(message)
            service.updateConversationUi()
            service.updateCallLogUi()
        } else {
            throw IllegalStateException("Somehow the conversation in a message was a stub")
        }
    }
}
