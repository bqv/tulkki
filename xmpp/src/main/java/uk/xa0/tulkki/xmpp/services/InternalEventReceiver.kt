package uk.xa0.tulkki.xmpp.services

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Tulkki: the unrestricted internal event receiver, un-nested out of `XmppConnectionService`
 * and converted from the Java.
 *
 * The nested class was `private`, so the top-level type is `internal` - the narrowest Kotlin
 * spelling, and one a Java file still resolves: `internal` classes are emitted public with their
 * plain name and a public, unmangled constructor (`javap` on a scratch `internal class`), so
 * `XmppConnectionService.java:502/505 new InternalEventReceiver(this)` compiles unchanged and no
 * `@JvmName` is needed.
 *
 * `intent` is **nullable**, and that is the faithful reading rather than the permissive one: the
 * Java never dereferenced it - it forwarded it to `onStartCommand` untouched - so a non-null Kotlin
 * parameter would insert an intrinsic null check the Java did not have, on a callback Android is
 * allowed to deliver a null intent to. The service is **non-null**: the constructor argument is
 * `this` at the only construction site and it is called immediately.
 */
internal class InternalEventReceiver(
    private val service: XmppConnectionService,
) : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        service.onStartCommand(intent, 0, 0)
    }
}
