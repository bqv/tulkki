package uk.xa0.tulkki.data.utils

import uk.xa0.tulkki.data.model.Contact
import uk.xa0.tulkki.libs.Jid

/**
 * Which full JID a message should be addressed to, given the bare JID and one resource.
 *
 * Pair 8b's move: this is the pure half of `uk.xa0.tulkki.ui.util.PresenceSelector`, which is a dialog
 * (a `MaterialAlertDialogBuilder` over a `ListView` of resources) and stays in `:ui`. The arithmetic part -
 * a bare JID when there is no resource, the JID with the resource when there is - is what `:data`'s
 * `Message.fixCounterpart` needs, and it reads nothing but the two arguments.
 *
 * `PresenceSelector.getNextCounterpart` keeps both overloads and delegates, so no `:ui` caller changed.
 */
object Counterparts {

    /** The counterpart of [contact]'s bare JID and [resource]. */
    @JvmStatic
    fun getNextCounterpart(contact: Contact?, resource: String): Jid =
        getNextCounterpart((contact ?: throw NullPointerException()).getJid(), resource)

    /** The full JID [jid] plus [resource], or the bare JID when [resource] is empty. */
    @JvmStatic
    fun getNextCounterpart(jid: Jid, resource: String): Jid =
        if (resource.isEmpty()) {
            jid.asBareJid()
        } else {
            jid.withResource(resource)
        }
}
