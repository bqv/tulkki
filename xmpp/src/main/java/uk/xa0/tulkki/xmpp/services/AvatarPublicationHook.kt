package uk.xa0.tulkki.xmpp.services

/**
 * Tulkki: the avatar publication's continuation, implemented by `:ui`'s `OnAvatarPublication`.
 *
 * Un-nested out of `XmppConnectionService`; converted from the
 * Java. [onAvatarPublicationFailed]'s `res` is a Java `int`, so it is a non-null Kotlin `Int`; the
 * success arm takes nothing. Both `:ui` implementers (`PublishProfilePictureActivity`,
 * `PublishGroupChatProfilePictureActivity`) already declare the `@StringRes Int`, and the island's
 * call sites pass a resource id, so nothing is nullable here.
 */
interface AvatarPublicationHook {

    fun onAvatarPublicationSucceeded()

    fun onAvatarPublicationFailed(res: Int)
}
