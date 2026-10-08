package uk.xa0.tulkki.xmpp.services

import uk.xa0.tulkki.libs.CommentRef

/**
 * Tulkki: the comment fan-out's listener contract, un-nested out of `XmppConnectionService`
 *.
 *
 * A nested type cannot keep a one-line delegation the way a method can, so the whole declaration
 * moves. Both parameters are nullable because the Java's were: the two parsers hand over the
 * `ref` attribute they read (`IqParser`, `MessageParser`) and the model object
 * `dataStatics().newComment(entry)` may answer, and neither Java byte ever tested one.
 *
 * `:ui`'s `CommentsAdapter` spells this type as a fully qualified name and imports nothing, so the
 * nesting's removal costs the ratchet no new key — the same shape `RtpSessionActivity` already uses
 * for `OnJingleRtpConnectionUpdate`.
 */
fun interface OnCommentReceived {
    fun onCommentReceived(originalPostUuid: String?, commentRef: CommentRef?)
}
