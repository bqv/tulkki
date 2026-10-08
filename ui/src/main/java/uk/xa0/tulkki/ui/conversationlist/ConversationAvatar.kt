package uk.xa0.tulkki.ui.conversationlist

import android.graphics.drawable.Drawable

/** The three shapes the tree's `AvatarView` clipped with, from the owner's `avatar_shape`. */
enum class AvatarShape {
    OVAL,
    ROUNDED_SQUARE,
    SQUARE,
}

/**
 * The row's avatar and its shape, asked for by the screen and answered by the host.
 *
 * <p>**Why a port and not a read.** The avatar is the contact's photo, and the tree produced it through
 * `AvatarService`, which `:ui` reaches as an island type (`XmppActivity.avatarService()`) whose loading
 * half lives with the image library in `:app`. The screen may do neither: the `ui-reaches-island` ratchet
 * is a floor that only goes down, and a Composable has no business reading a file. So the screen says
 * *which* row and the host answers with a `Drawable` it has already resolved - `XmppActivity` on the
 * shared base, so the list and the picker resolve avatars once, off the main thread, and neither loads in
 * the composition.
 *
 * <p>**The identity is the row's own uuid**, which `UiConversation` already carries as its key, rather than
 * the peer's address: a room's avatar is the room's and the note-to-self row's is the account's, and the
 * conversation is the one thing all three have in common. No field was added to the row for this.
 *
 * <p>`of` answers `null` until the avatar is in hand - the host swaps in a fresh map when its background
 * pass lands and the state is re-rendered - and the screen then draws the row without one rather than
 * blocking on it.
 */
interface ConversationAvatar {

    /** The avatar for the row named by `conversationUuid`, or `null` while it is not ready. */
    fun of(conversationUuid: String): Drawable?

    /**
     * The owner's `avatar_shape` (declared by the deleted `preferences_interface.xml:145`, still a
     * live preference), which the tree's `AvatarView` clipped every avatar with. The screen clips with
     * the host's answer rather than guessing a circle.
     */
    fun shape(): AvatarShape
}
