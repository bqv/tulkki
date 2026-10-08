package uk.xa0.tulkki.xmpp

import uk.xa0.tulkki.xmpp.refs.AccountRef

/**
 * Tulkki: the unchecked half of [StateChangingException], out of `XmppConnection`.
 *
 * The Java used it where a state change had to cross a `Consumer<Iq>` boundary - an IQ callback
 * cannot declare a checked exception, so the callback throws this `Error` and `processIq` converts
 * it back with `throw new StateChangingException(error.state)`. `state` stays a public field
 * (`@JvmField`) for that read.
 */
internal class StateChangingError(@JvmField val state: AccountRef.StateRef) : Error()
