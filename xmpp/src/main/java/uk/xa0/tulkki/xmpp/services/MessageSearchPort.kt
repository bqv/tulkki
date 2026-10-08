package uk.xa0.tulkki.xmpp.services

/**
 * Tulkki: one message search, run by {@code uk.xa0.tulkki.app.services.MessageSearchTask}.
 *
 * <p>Declared {@code fun interface} because `:app`'s composition root constructs it with a Kotlin
 * SAM lambda (`XmppTulkkiHost`), which a plain Kotlin interface would not accept.
 */
fun interface MessageSearchPort {

    fun search(
        service: XmppConnectionService,
        term: List<String>,
        uuid: String?,
        callback: SearchResultsHook,
    )
}
