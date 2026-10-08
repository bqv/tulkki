package uk.xa0.tulkki.xmpp.services

import uk.xa0.tulkki.xml.Element

/**
 * Tulkki: the MAM preferences fetch's two outcomes, un-nested out of `XmppConnectionService`
 *.
 *
 * Converted from the Java. `prefs` is **non-null** and stays that way because the *body* that had
 * the null test is the caller, not the interface: `ServiceDiscovery.kt:277` reads
 * `packet.findChild("prefs", ...)` into `prefs`, tests `prefs != null`, and only calls
 * `onPreferencesFetched(prefs)` on the branch where it is not null. `EditAccountActivity`'s
 * implementer declares `prefs: Element` non-null to match. The failure arm takes nothing.
 */
interface OnMamPreferencesFetched {
    fun onPreferencesFetched(prefs: Element)

    fun onPreferencesFetchFailed()
}
