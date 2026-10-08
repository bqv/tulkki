package uk.xa0.tulkki.xmpp.services

import uk.xa0.tulkki.xmpp.refs.ConversationRef

/**
 * Tulkki: the room this account has just joined, un-nested out of `XmppConnectionService`
 *.
 *
 * Converted from the Java. `conversation` is **non-null**: `MucJoin.kt:81/125` passes the local
 * `conversation` it dereferenced immediately before (`conversation.getAccount()`), and the two
 * implementers (`MucJoin`'s own anonymous listener and `ConversationChannels`) dereference it with
 * no guard.
 *
 * `fun interface`: the Java this replaced was a single-abstract-method interface, so every Kotlin
 * caller could hand it a **lambda**. A plain Kotlin `interface` cannot be a lambda target -
 * `ConversationChannels.kt:56` is such a call site - and the compiler reports that in the caller's
 * file. The single abstract member is `onConferenceJoined`; there is no body method.
 */
fun interface OnConferenceJoined {
    fun onConferenceJoined(conversation: ConversationRef)
}
