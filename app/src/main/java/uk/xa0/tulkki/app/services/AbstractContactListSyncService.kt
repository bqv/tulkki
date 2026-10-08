package uk.xa0.tulkki.app.services

import android.content.Context
import android.content.Intent
import uk.xa0.tulkki.data.utils.ContactListIntegration
import uk.xa0.tulkki.xmpp.services.XmppConnectionService

/**
 * The contact-list sync service's shape, and the flavour predicates every caller reads off it.
 *
 * <p>**The `service` field stays a field.** Java's `protected final XmppConnectionService service` is
 * read as a field by `ContactListSyncService` - still Java in this commit - so it is a
 * `@JvmField protected val` on the primary constructor: the same name, the same visibility, a real
 * field and no accessors, which is what the Java subclass's own `service` reads compile against and
 * what the Kotlin subclass will keep reading.
 *
 * <p>**The five `public static` predicates are `@JvmStatic` companion members**, because Java callers
 * outside this file use them as statics on this class and on `ContactListSyncService`: `UiAppHost`,
 * `AvatarService` and `ImportBackupWorker` inherit them through the subclass, and - after
 * `0f99967c99` and `e171010dfb` - the two Kotlin callers name this class, because Kotlin does not
 * inherit a superclass's statics into scope.
 *
 * <p>`SMS_RETRIEVED_ACTION` is a `const val`, so the re-export stays a JVM compile-time constant with
 * the same name and the island's single definition - which is the whole point of the line. It is the
 * only field of this class that a `case` label could name. Name-string audit: 0 hits for this class in
 * the manifest, `res/xml`, `res/layout*`, `preferences_*.xml` and the ProGuard rules.
 */
abstract class AbstractContactListSyncService(
        @JvmField protected val service: XmppConnectionService
) {

    abstract fun considerSync()

    abstract fun signalAccountStateChange()

    abstract fun isSynchronizing(): Boolean

    abstract fun considerSyncBackground(force: Boolean)

    // Tulkki: the Java's `public abstract void handleSmsReceived(Intent intent)` took an unannotated
    // platform type and the implementation never read it (`Log.d(Config.LOGTAG, "ignoring received
    // SMS")`), so the parameter keeps the platform's tolerance rather than being narrowed to non-null.
    abstract fun handleSmsReceived(intent: Intent?)

    companion object {

        /**
         * 3.7 pair 4: the value is defined once, in the island.
         *
         * <p>The service's own `onStartCommand` switches on it, so it cannot travel through a port -
         * a `case` label must be a compile-time constant, and an island may not read `:app`'s
         * constant. The island owns the definition and this line re-exports it under the name the
         * `:app` callers already use.
         */
        const val SMS_RETRIEVED_ACTION: String = uk.xa0.tulkki.xmpp.services.ServiceActions.SMS_RETRIEVED_ACTION

        // Tulkki: these three used to be read off BuildConfig.FLAVOR_mode/FLAVOR_distribution. There is
        // one flavour now, and for it all three were already false - Tulkki's own flavour is not
        // quicksy, not upstream and was not the playstore distribution - so they are constants
        // rather than a reading of anything. What they gate (the quicksy SMS sign-up paths) is
        // unreachable, which is what the fork wants.
        @JvmStatic fun isQuicksy(): Boolean = false

        @JvmStatic fun isDefaultUpstreamFlavour(): Boolean = false

        @JvmStatic fun isPlayStoreFlavor(): Boolean = false

        /**
         * 3.7 pair 2 moved the reading itself down to `uk.xa0.tulkki.data.utils.ContactListIntegration`,
         * because `JabberIdContact` - now a `:data` class - asks it before it touches the contacts
         * provider. This name stays: three `:ui` callers, `PhoneHelper` here and
         * `XmppConnectionService` all use it, and each of them may import `:data` anyway.
         */
        @JvmStatic
        fun isContactListIntegration(context: Context): Boolean =
                ContactListIntegration.isContactListIntegration(context)

        @JvmStatic fun isQuicksyPlayStore(): Boolean = isQuicksy() && isPlayStoreFlavor()
    }
}
