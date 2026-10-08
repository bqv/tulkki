package uk.xa0.tulkki.xmpp.services

/**
 * Tulkki: the pubsub-feed listener contract, un-nested out of `XmppConnectionService`
 *.
 *
 * Two methods, so not a `fun interface`; the Java implementors are the two `:ui` posts surfaces,
 * which spell this type fully qualified and import nothing new. `feed` is the Java's non-null
 * `String`, and the failure arm takes nothing.
 */
interface OnPubsubItemsFetched {
    fun onPubsubItemsFetched(feed: String)

    fun onPubsubItemsFetchFailed()
}
