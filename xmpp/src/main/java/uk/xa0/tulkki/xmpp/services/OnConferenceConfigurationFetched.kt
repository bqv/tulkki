package uk.xa0.tulkki.xmpp.services

import uk.xa0.tulkki.xmpp.refs.ConversationRef

/**
 * Tulkki: the two outcomes of fetching a conference's configuration, un-nested out of
 * `XmppConnectionService`.
 *
 * Converted from the Java, and this is one of the files where the Java platform type was hiding a
 * genuine null:
 *
 *  * `conversation` is **non-null** on both arms - `ConferenceConfiguration.kt:98/111` and
 *    `MucJoin.kt:155/169` all pass a `ConversationRef` they dereference.
 *  * `errorCondition` is **nullable**, from the call site rather than from taste.
 *    `ConferenceConfiguration.kt:111` passes `response.getErrorCondition()`, whose Kotlin
 *    declaration is `Iq.getErrorCondition(): String?` (`models/stanza/Iq.kt:68`) - it answers null
 *    whenever the error has no `<condition/>`. A non-null parameter would be a compile error at
 *    that call. `MucJoin.kt:171`'s override is widened to `String?` in the same commit, because
 *    Kotlin overrides require the parameter type to match exactly (measured with kotlinc 2.3.21).
 */
interface OnConferenceConfigurationFetched {
    fun onConferenceConfigurationFetched(conversation: ConversationRef)

    fun onFetchFailed(conversation: ConversationRef, errorCondition: String?)
}
