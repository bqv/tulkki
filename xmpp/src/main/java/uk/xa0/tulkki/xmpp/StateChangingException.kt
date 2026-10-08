package uk.xa0.tulkki.xmpp

import uk.xa0.tulkki.xmpp.refs.AccountRef
import java.io.IOException

/**
 * Tulkki: `XmppConnection`'s checked state-change signal, out of the class.
 *
 * The Java held it as a `private static class` nested in `XmppConnection`, carrying the
 * `AccountRef.StateRef` the connection must move to. A Java nested type cannot survive the
 * conversion as a member of a Kotlin file it is named from at the same time as the retype, so the
 * three error types leave first, each in its own commit; `internal` is the narrowest Kotlin
 * spelling a same-module Java caller can still name (Kotlin has no package-private, and `private`
 * at file scope would hide it from `XmppConnection`'s own call sites).
 *
 * `state` stays a public field (`@JvmField`) because the Java reads it as `e.state` in `connect`'s
 * and `processIq`'s catch clauses, and the three constructors keep Java's `super()`/`super(message)`/
 * `super(cause)` shapes - the last one, like the Java, lets `IOException(Throwable)` derive the
 * message from the cause.
 */
internal class StateChangingException : IOException {

    @JvmField val state: AccountRef.StateRef

    constructor(state: AccountRef.StateRef) : super() {
        this.state = state
    }

    constructor(state: AccountRef.StateRef, message: String) : super(message) {
        this.state = state
    }

    constructor(state: AccountRef.StateRef, cause: Throwable) : super(cause) {
        this.state = state
    }
}
