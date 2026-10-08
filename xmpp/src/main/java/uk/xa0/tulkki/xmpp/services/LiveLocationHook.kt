package uk.xa0.tulkki.xmpp.services

/**
 * Tulkki: what the island asks of the live-location manager while it processes a stanza.
 *
 * Un-nested out of `XmppConnectionService` by the `C` lane and
 * converted from the Java. Every member is the manager's own accessor in island vocabulary, and the
 * Java was left Java only because of the nullability this conversion now decides from the two sides
 * of the boundary:
 *
 *  * **`registerIncomingSession`'s `conversationUuid` and `messageUuid` are nullable.** The island's
 *    own caller is the proof and it is explicit: `MessageParser.kt:757-764` passes a literal
 *    `null, null` for both, on the restart-recovery path where a session is known but no message
 *    carries it. The manager's real declaration agrees - `LiveLocationManager.registerIncomingSession`
 *    takes `conversationUuid: String?, messageUuid: String?` - so `:app`'s `UiTulkiPorts.kt:65-66`
 *    had narrowed a signature that was never narrow; it is widened in the same commit, because a
 *    Kotlin override's parameter type must match exactly (measured with kotlinc 2.3.21).
 *  * **`sessionConversationUuid`, `sessionMessageUuid`, `sessionIdForMessage` and
 *    `outgoingMessageUuid` answer `String?`.** Each is a lookup that can miss, and the only
 *    implementation already says so (`UiTulkiPorts.kt:57/60/86/105` all declare `String?`, the last
 *    by falling off the end of its loop with `return null`). Every island caller already guards the
 *    result (`MessageParser.kt:766-767` tests both, `:1583` tests the session id, and
 *    `LiveLocation.kt:241` returns it from a `String?` function), so the declaration only makes the
 *    check the callers were already writing mandatory.
 *  * Everything else is non-null: ids, doubles and longs, with no nullable source on either side.
 *    `setOnSessionExpired` takes the manager's own expiry callback.
 */
interface LiveLocationHook {

    fun hasSession(sessionId: String): Boolean

    fun sessionConversationUuid(sessionId: String): String?

    fun sessionMessageUuid(sessionId: String): String?

    fun registerIncomingSession(
        sessionId: String,
        conversationUuid: String?,
        messageUuid: String?,
        lat: Double,
        lon: Double,
        expiresAt: Long,
    )

    fun updateIncomingPosition(sessionId: String, lat: Double, lon: Double)

    fun isPreviewRefreshDue(sessionId: String, throttleMs: Long): Boolean

    fun expireIncomingSession(sessionId: String)

    fun sessionIdForMessage(messageUuid: String): String?

    fun registerOutgoingSession(
        conversationUuid: String,
        sessionId: String,
        messageUuid: String,
        expiresAt: Long,
        lat: Double,
        lon: Double,
    )

    fun notifyOutgoingPositionUpdate(sessionId: String, lat: Double, lon: Double)

    fun outgoingMessageUuid(sessionId: String): String?

    fun clearOutgoingSession(conversationUuid: String)

    /** The manager's own expiry callback: the island hands it `updateConversationUi`. */
    fun setOnSessionExpired(callback: Runnable)
}
