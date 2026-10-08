package uk.xa0.tulkki.xmpp.services

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import uk.xa0.tulkki.xmpp.Config

/**
 * Tulkki: the allow-listed internal event receiver, un-nested out of `XmppConnectionService`
 * and converted from the Java.
 *
 * The nested class was `private`; a top-level type cannot be private, so this is `internal` - the
 * narrowest Kotlin spelling for "visible inside this module", which is what the package-private
 * Java was in practice. `internal` does **not** name-mangle a class: `javap` on a scratch
 * `internal class` shows a public class with its plain name and a **public, unmangled constructor**,
 * so `XmppConnectionService.java:503-504 new RestrictedEventReceiver(this,
 * Arrays.asList(TorServiceUtils.ACTION_STATUS))` still resolves from Java and no `@JvmName` is owed.
 * (Only internal *functions* get the `$module` suffix, and `onReceive` is an override, which Kotlin
 * never mangles.)
 *
 * Two readings that the Java's platform types left open:
 *
 *  * `intent` is **nullable**. Android's `BroadcastReceiver.onReceive` may deliver null, and the
 *    Java said so itself with `intent == null ? null : intent.getAction()`; the whole point of this
 *    receiver is the allow-list test, which runs on the possibly-null action.
 *  * The Java's `allowedActions.contains(action)` is spelled `action != null &&
 *    allowedActions.contains(action)` here, because Kotlin's `Collection<String>.contains` takes a
 *    non-null element. The two are equivalent for this receiver's own allow-list: the only
 *    construction site passes `Arrays.asList(TorServiceUtils.ACTION_STATUS)`, a one-element list of
 *    non-null strings, and a collection with no null element answers `false` to `contains(null)`
 *    exactly as the short circuit does.
 *
 * `context` is unused, as it was in the Java; the service travels in as a constructor argument.
 */
internal class RestrictedEventReceiver(
    private val service: XmppConnectionService,
    private val allowedActions: Collection<String>,
) : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.getAction()
        if (action != null && allowedActions.contains(action)) {
            service.onStartCommand(intent, 0, 0)
        } else {
            Log.e(Config.LOGTAG, "restricting broadcast of event " + action)
        }
    }
}
