package uk.xa0.tulkki.ui.projection

import uk.xa0.tulkki.data.model.Presence

/**
 * One row's presence, "Design: the Compose UI" §2.2's row without a case for it until now: the dot the
 * deleted `ConversationAdapter` drew at the avatar's foot.
 *
 * <p>**The model's own vocabulary, narrowed.** `Presence.Status` has six values - `CHAT`, `ONLINE`,
 * `AWAY`, `XA`, `DND`, `OFFLINE` - and the tree's indicator told them apart by exactly three colours
 * (`UIHelper.getColorForStatus`: `CHAT`/`ONLINE` green, `AWAY` orange, `XA`/`DND` red, and nothing for
 * `OFFLINE`). This enum keeps the three the tree drew, keeps `OFFLINE` as a state of its own so the screen
 * can say it, and adds `UNKNOWN` for the two cases the tree answered by drawing nothing at all: a
 * conversation with no contact (a room), and a contact whose account is not connected.
 *
 * <p>[UNKNOWN] is the seam's own "I do not know", which is why the row draws nothing for it rather than an
 * empty ring.
 */
enum class UiPresence {
    ONLINE,
    AWAY,
    DND,
    OFFLINE,
    UNKNOWN;

    companion object {

        /**
         * The tree's rule, in one place: `PresenceIndicator.setStatus` read
         * `contact.shownStatus` and kept it only while the contact's account was connected, and the whole
         * dot existed only while the owner's `show_contact_status` preference was on. Both gates are
         * arguments here rather than reads, so the decision has a cell.
         *
         * @param status the contact's shown presence, or `null` when nobody read one.
         * @param contactOnline whether that contact's account is online and connected.
         * @param shown whether the owner wants contact status drawn at all.
         */
        @JvmStatic
        fun of(status: Presence.Status?, contactOnline: Boolean, shown: Boolean): UiPresence {
            if (!shown || !contactOnline) {
                return UNKNOWN
            }
            return when (status) {
                Presence.Status.CHAT, Presence.Status.ONLINE -> ONLINE
                Presence.Status.AWAY -> AWAY
                Presence.Status.XA, Presence.Status.DND -> DND
                Presence.Status.OFFLINE -> OFFLINE
                null -> UNKNOWN
            }
        }
    }
}
