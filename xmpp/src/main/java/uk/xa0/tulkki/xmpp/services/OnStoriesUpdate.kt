package uk.xa0.tulkki.xmpp.services

/**
 * Tulkki: the story fan-out's listener contract, un-nested out of `XmppConnectionService`
 *.
 *
 * One method, so it is a `fun interface` and a lambda is still accepted where a Java SAM was. The
 * Java declared it `void onStoriesUpdate()` with no parameters and no return, so there is no
 * nullability to read off it.
 *
 * `:ui`'s `StoriesActivity` spells this type as a fully qualified name in its class header and
 * imports nothing, so the nesting's removal costs the ratchet no new key - the same shape
 * [OnCommentReceived] already uses for `:ui`'s `CommentsAdapter`, and the same reason the type
 * cannot keep a one-line delegation the way a method can.
 */
fun interface OnStoriesUpdate {
    fun onStoriesUpdate()
}
