package uk.xa0.tulkki.xmpp.services

import uk.xa0.tulkki.xmpp.refs.ConversationRef

/**
 * Tulkki: a page of older messages read out of the archive, un-nested out of `XmppConnectionService`
 *.
 *
 * Converted from the Java. `count` is a Java `int`. `conversation` is **nullable**, and the evidence
 * is the caller, not the interface text:
 * `MessageArchiveService.Query.conversation` is declared `private var conversation: ConversationRef?`
 * (`MessageArchiveService.kt:196`) and `Query.callback(done)` (`:308-313`) hands that field straight
 * to `onMoreMessagesLoaded(actualCount, conversation)`. It compiles today only because the Java
 * parameter is a platform type; a non-null Kotlin parameter is an error at that call. The other
 * caller, `ConversationPaging.kt:90`, passes a non-null `conversation`, and the one implementer is
 * Java (`ConversationFragment.java:694`), which javac checks by erasure - so widening the parameter
 * breaks nothing on the callback side.
 */
interface OnMoreMessagesLoaded {
    fun onMoreMessagesLoaded(count: Int, conversation: ConversationRef?)

    fun informUser(r: Int)
}
