package uk.xa0.tulkki.xmpp.services

import android.content.Context

/**
 * Tulkki: the call screen, in island vocabulary.
 *
 * <p>{@link #sessionIdExtra()} is the extra this service reads out of the intents it is started
 * with; {@link #launchAcceptCall} is the screen the call-integration answer opens. Both were
 * {@code uk.xa0.tulkki.ui.RtpSessionActivity} constants and the {@code Intent} built around them.
 */
interface RtpSessionPort {

    fun sessionIdExtra(): String

    fun launchAcceptCall(context: Context, accountJid: String, with: String, sessionId: String)
}
