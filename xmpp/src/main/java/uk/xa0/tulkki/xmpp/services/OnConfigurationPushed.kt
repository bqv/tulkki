package uk.xa0.tulkki.xmpp.services

/**
 * Tulkki: the two outcomes of a configuration push, un-nested out of `XmppConnectionService`
 *.
 *
 * Converted from the Java. Both members are parameterless, so no parameter's nullability is in
 * question. The implementers are all `object : uk.xa0.tulkki.xmpp.services.OnConfigurationPushed`
 * sites (`:crypto`'s `AxolotlService` three times, `:ui`'s `ConferenceDetailsActivity`), so no
 * `fun interface` and no `@JvmSuppressWildcards` is owed.
 */
interface OnConfigurationPushed {
    fun onPushSucceeded()

    fun onPushFailed()
}
