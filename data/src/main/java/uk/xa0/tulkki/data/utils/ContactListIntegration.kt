package uk.xa0.tulkki.data.utils

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import com.google.common.collect.Iterables

/**
 * Whether this install has declared `READ_CONTACTS`, moved down out of
 * `uk.xa0.tulkki.app.services.AbstractContactListSyncService` by 3.7 pair 2.
 *
 * The reader is pure `Context` + `PackageManager` and answers a question about the app's own manifest,
 * so it belongs below the service that happened to hold it: `JabberIdContact` - which moved to `:data`
 * in the same commit - asks it before it touches the contacts provider, and could not ask `:app`.
 *
 * `AbstractContactListSyncService.isContactListIntegration` stays and forwards, because four other
 * callers (`:ui` x3, `:xmpp`, and `:app`'s `PhoneHelper`) name it there and each of those modules may
 * import `:data` without a new edge. The cached answer lives here now, so one process still asks the
 * package manager once.
 */
object ContactListIntegration {

    private var declaredReadContacts: Boolean? = null

    /** Whether the app declared `READ_CONTACTS`, asked once per process. */
    @JvmStatic
    fun isContactListIntegration(context: Context): Boolean {
        val readContacts = declaredReadContacts
        if (readContacts != null) {
            return readContacts == true
        }
        val declared = hasDeclaredReadContacts(context)
        declaredReadContacts = declared
        return declared
    }

    private fun hasDeclaredReadContacts(context: Context): Boolean {
        val permissions =
            try {
                context.packageManager
                    .getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS)
                    .requestedPermissions
            } catch (e: PackageManager.NameNotFoundException) {
                return false
            }
        return Iterables.any((permissions ?: throw NullPointerException()).asList()) { p ->
            p == Manifest.permission.READ_CONTACTS
        }
    }
}
