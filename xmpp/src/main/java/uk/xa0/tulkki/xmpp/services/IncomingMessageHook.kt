package uk.xa0.tulkki.xmpp.services

import uk.xa0.tulkki.xmpp.refs.MessageRef

/**
 * Tulkki: the receive path's two app-side continuations.
 *
 * <p>Both hooks exist for the same reason and both belong beside pair 10's engine host rather
 * than in a surface of their own: {@code MessageParser} has to tell the engine "a live message
 * arrived" and has to ask "is this MAM result the language sample I am waiting for?", and the
 * answers live in `:app` (`TranslationHooks`, `MamLanguageSampler`) because the engine's host is
 * installed there. The island declares the questions; `XmppTulkkiHost` answers them.
 */
interface IncomingMessageHook {

    fun onIncomingMessage(
        message: MessageRef,
        fromArchive: Boolean,
        delayed: Boolean,
        replacement: Boolean,
    )

    /**
     * `queryId` is nullable because `MessageParser` computes it as
     * `result == null ? null : result.getAttribute("queryid")`, so a null reaches here on every
     * non-MAM message; the Java's platform `String` let it through and the implementation answers
     * false for it.
     */
    fun offerLanguageSample(queryId: String?, result: uk.xa0.tulkki.xmpp.models.stanza.Message): Boolean
}
