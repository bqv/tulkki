package uk.xa0.tulkki.ui.interfaces

import androidx.annotation.StringRes

/**
 * The avatar publication screen's continuation: whether publishing the picture worked, and if not,
 * which string to show.
 *
 * Pair 11 of `docs/MIGRATION.md` "The cycle rules" §3 (D4). The service is an island and may not name
 * `:ui`, so the island declares the continuation as the port
 * `uk.xa0.tulkki.xmpp.services.AvatarPublicationHook` and this interface is its
 * `:ui` implementation. No method and no call site moved - the two activities that implement it keep
 * compiling, and the service's seven methods take the port.
 *
 * The island's name is written in full rather than imported: an `import` line naming an island class
 * would be a new `ui-reaches-island` site, and pair 11's gate holds that ratchet at 242.
 */
interface OnAvatarPublication :
    uk.xa0.tulkki.xmpp.services.AvatarPublicationHook {

    override fun onAvatarPublicationSucceeded()

    override fun onAvatarPublicationFailed(@StringRes res: Int)
}
