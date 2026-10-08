package uk.xa0.tulkki.xmpp.services

/**
 * Tulkki: the service's action vocabulary, lifted out of `XmppConnectionService`
 * under the owner's 2026-10-08 licence.
 *
 * These were `public static final String` constants on the service, read by seven files outside the
 * island as `XmppConnectionService.ACTION_…`. A `const val` in a Kotlin `object` is the same JVM
 * shape — a `public static final` field with a `ConstantValue`, so Java reads it as a compile-time
 * constant and a `case`/`when` label still resolves — and Java names it at its declaring class, so
 * the sweep moved every call site to `ServiceActions.ACTION_…` in one commit.
 *
 * Three constants deliberately stay on the service. `ACTION_POST_CONNECTIVITY_CHANGE` and
 * `SETTING_LAST_ACTIVITY_TS` were `private`, and a Kotlin `const val` that a Java body must read
 * cannot be private without widening the visibility the by-value rule forbids — so they stay where
 * the Java wrote them, and the chunk that reaches them takes the string as the string it is.
 * `ACTION_EXPIRE_MESSAGES` is public but `OsHeldNamesTest` pins its **declaration site** in
 * `XmppConnectionService.java`: an alarm armed before an app update comes back carrying the old
 * action text, so the text is data the operating system holds and the file the next rename pass
 * reads is part of the pin.
 */
object ServiceActions {

    const val ACTION_REPLY_TO_CONVERSATION = "reply_to_conversations"
    const val ACTION_MARK_AS_READ = "mark_as_read"
    const val ACTION_SNOOZE = "snooze"
    const val ACTION_CLEAR_MESSAGE_NOTIFICATION = "clear_message_notification"
    const val ACTION_CLEAR_MISSED_CALL_NOTIFICATION = "clear_missed_call_notification"
    const val ACTION_DISMISS_ERROR_NOTIFICATIONS = "dismiss_error"
    const val ACTION_TRY_AGAIN = "try_again"

    const val ACTION_TEMPORARILY_DISABLE = "temporarily_disable"
    const val ACTION_PING = "ping"
    const val ACTION_IDLE_PING = "idle_ping"
    const val ACTION_INTERNAL_PING = "internal_ping"
    const val ACTION_FCM_TOKEN_REFRESH = "fcm_token_refresh"
    const val ACTION_FCM_MESSAGE_RECEIVED = "fcm_message_received"
    const val ACTION_DISMISS_CALL = "dismiss_call"
    const val ACTION_END_CALL = "end_call"
    const val ACTION_STOP_LIVE_LOCATION = "stop_live_location"
    const val ACTION_STARTING_CALL = "starting_call"
    const val ACTION_PROVISION_ACCOUNT = "provision_account"
    const val ACTION_CALL_INTEGRATION_SERVICE_STARTED = "call_integration_service_started"
    const val ACTION_RENEW_UNIFIED_PUSH_ENDPOINTS = "uk.xa0.tulkki.app.UNIFIED_PUSH_RENEW"
    const val ACTION_QUICK_LOG = "uk.xa0.tulkki.app.QUICK_LOG"

    /**
     * Pair 4 moved the definition to the island because the Java's `onStartCommand` switched on it
     * (a `case` label must be a compile-time constant, so a port accessor cannot supply it, and the
     * island may not read `:app`'s copy). `AbstractContactListSyncService` still re-exports it, so
     * the value has exactly one definition; the re-export resolves to this `const val` and stays a
     * JVM compile-time constant.
     */
    const val SMS_RETRIEVED_ACTION = "com.google.android.gms.auth.api.phone.SMS_RETRIEVED"
}
