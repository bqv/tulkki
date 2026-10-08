package uk.xa0.tulkki.ui.fragment.settings

import java.util.function.Predicate
import org.junit.Assert
import org.junit.Test

/**
 * Whether the notification-listener grant the platform stores is one this build can still use.
 *
 * The setting is a list of flattened components, `package/class`, and the platform binds exactly what
 * each entry names. An app update does not rewrite it, so an upgrade leaves an entry naming the class
 * the previous build published: the package part is still this app's - which is why
 * `contains(packageName)` answered "access is present" - while the class part is a class that no
 * longer exists. That is the pair these tests are about, and it is why the package here is a
 * stand-in: one package, a class from another, which a grant cannot be repaired out of.
 *
 * What is *not* here is the lookup that decides whether a component exists: that is a
 * `PackageManager` call, and only a device can run it. It is stated as unverified.
 *
 * Every name below is invented. They used to spell an old package and class of this app, which put
 * three banned-word hits in the tree for a fixture that only needs two different packages - and a
 * sweep exception added to silence an invention is an exception that also covers the real thing. So
 * the fixtures are neutral, and the naming sweep reads this file like any other.
 */
class NotificationListenerGrantTest {

    private fun granted(enabledNotificationListeners: String?): Boolean =
        NotificationListenerGrant.granted(
            PACKAGE,
            enabledNotificationListeners,
            Predicate { DECLARED.contains(it) },
        )

    @Test
    fun aGrantNamingTheMovedClassIsNotAccess() {
        Assert.assertFalse(granted(PACKAGE + "/" + STALE))
    }

    @Test
    fun theComponentTheManifestDeclaresIsAccess() {
        Assert.assertTrue(granted(PACKAGE + "/" + CURRENT))
        Assert.assertTrue(granted(PACKAGE + "/uk.xa0.example.listener.UpdateNowPlayingService"))
    }

    @Test
    fun aDeadEntryDoesNotHideALiveOne() {
        // The platform appends, so the stale grant is still the first entry carrying our package; a
        // check that stops at the first one it recognises would still answer wrongly.
        Assert.assertTrue(granted(PACKAGE + "/" + STALE + ":" + PACKAGE + "/" + CURRENT))
    }

    @Test
    fun entriesThatAreNotOurComponentsAreNotAccess() {
        Assert.assertFalse(granted("example.other/example.other.Plugin"))
        Assert.assertFalse("a bare package is not a component", granted(PACKAGE))
        Assert.assertFalse(granted(""))
        Assert.assertFalse(granted(null))
    }

    @Test
    fun aPackageIsRequired() {
        Assert.assertFalse(
            NotificationListenerGrant.granted(null, PACKAGE + "/" + CURRENT, Predicate { true }),
        )
    }

    private companion object {
        const val PACKAGE = "uk.xa0.example"

        /** The class a previous build published - what a grant stored before an update names. */
        const val STALE = "uk.xa0.legacy.UpdateNowPlayingService"

        /** Today's listener, spelled the way the platform flattens it (relative to its package). */
        const val CURRENT = ".listener.UpdateNowPlayingService"

        /** How the package manager answers "is this one of ours, and does it still exist". */
        val DECLARED: Set<String> = setOf("uk.xa0.example.listener.UpdateNowPlayingService")
    }
}
