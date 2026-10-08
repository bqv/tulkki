package uk.xa0.tulkki.ui.fragment.settings

import java.util.function.Predicate

/**
 * Whether the notification-listener grant the platform stores is one this build can still use.
 *
 * <p>The grant is a <em>component</em>, not a package: the platform writes the flattened
 * `ComponentName` of every enabled listener into `Settings.Secure` and binds exactly that component.
 * After the rename the stored entry still names the class the previous build published, so the
 * platform binds nothing - while the old check, `enabledNotificationListeners.contains(packageName)`,
 * answered "access is present", because the <em>package</em> is still inside that string. The app
 * then declined to open the screen that is its own remedy, so the feature was gone and the one thing
 * that could bring it back was disabled by the check itself.
 *
 * <p>An entry therefore counts only when it names a class of this package that still resolves. That
 * is deliberately not the flattened string the app would write today: this class lives in
 * `src/main/java` and the listener does not - it is a flavour class, the same wall
 * `Contact.java:732-737` documents - so naming it here is impossible, and asking the package manager
 * is both rename-proof and the stronger question, because what is wrong with the stale grant is
 * precisely that its component no longer exists.
 *
 * <p>Public, and with the `PackageManager` lookup passed in, so the decision is pinned by a JVM test.
 * The lookup itself is the part only a device can run, and it is stated as unverified.
 */
object NotificationListenerGrant {

    /** The platform's own format: `package/class[:package/class]...`. */
    private const val PACKAGE_SEPARATOR = "/"

    @JvmStatic
    fun granted(
            packageName: String?,
            enabledNotificationListeners: String?,
            declared: Predicate<String>): Boolean {
        if (packageName == null || enabledNotificationListeners == null) {
            return false
        }
        // The split cannot change the answer if it keeps Java's dropped trailing entry: a bare
        // package names no component, so that entry yields null below.
        for (entry in enabledNotificationListeners.split(":")) {
            val className = componentClass(packageName, entry)
            if (className != null && declared.test(className)) {
                return true
            }
        }
        return false
    }

    /**
     * The class `entry` names, when it names one of `packageName`'s components, and `null`
     * otherwise. A bare package name is not a component (older platforms stored one) and a class in
     * another package is somebody else's grant.
     */
    @JvmStatic
    fun componentClass(packageName: String, entry: String?): String? {
        if (entry == null) {
            return null
        }
        // Java's String.trim, which strips every char at or below the space.
        val trimmed = entry.trim { it <= ' ' }
        val separator = trimmed.indexOf(PACKAGE_SEPARATOR)
        if (separator <= 0 || packageName != trimmed.substring(0, separator)) {
            return null
        }
        val className = trimmed.substring(separator + 1)
        if (className.isEmpty()) {
            return null
        }
        // A relative name is resolved against the package, exactly as
        // ComponentName.unflattenFromString does; the platform's own reader accepts both spellings.
        return if (className.startsWith(".")) packageName + className else className
    }
}
