package uk.xa0.tulkki.xmpp.services

import android.content.Context
import android.content.Intent

/**
 * Tulkki: the contact-list sync service, in island vocabulary.
 *
 * <p>The four predicate/constant members are the class's statics, renamed so that the instance
 * methods can carry them: a port is an interface and a static is not. The other five are the
 * instance work the island and the bind path ask for.
 */
interface ContactListSyncPort {

    fun quicksy(): Boolean

    fun playStoreFlavor(): Boolean

    fun contactListIntegration(context: Context): Boolean

    fun considerSync()

    fun signalAccountStateChange()

    fun isSynchronizing(): Boolean

    fun considerSyncBackground(force: Boolean)

    /**
     * The lifecycle callback's own `Intent?`: `onStartCommand` may be re-delivered a null intent, and
     * the Java handed it straight to a body that never read it. The platform type keeps its tolerance.
     */
    fun handleSmsReceived(intent: Intent?)
}
