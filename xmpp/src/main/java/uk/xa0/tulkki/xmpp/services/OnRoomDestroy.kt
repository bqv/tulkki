package uk.xa0.tulkki.xmpp.services

/**
 * Tulkki: the two outcomes of destroying a room, un-nested out of `XmppConnectionService`
 *.
 *
 * Converted from the Java. Both members are parameterless, so the nullability question that kept
 * this family Java is not in play. `:ui`'s two screens spell it `object : ...OnRoomDestroy`, so the
 * interface stays plain rather than `fun interface`.
 */
interface OnRoomDestroy {
    fun onRoomDestroySucceeded()

    fun onRoomDestroyFailed()
}
