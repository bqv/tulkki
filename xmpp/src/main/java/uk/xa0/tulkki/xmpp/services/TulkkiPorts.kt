package uk.xa0.tulkki.xmpp.services

import uk.xa0.tulkki.xmpp.mam.SyncAnchors
import uk.xa0.tulkki.xmpp.mam.SyncEvents

interface TulkkiPorts {

    fun sendGate(): SendGate

    fun translationQueue(): TranslationQueue

    /**
     * S5-4's seam: the sync engine's two island interfaces, installed by the same factory as every
     * other port here. {@code syncEvents} is what the engine learns from, and it is what the
     * retired catch-up hook and its `!anyCatchup` heuristic were replaced by; {@code syncAnchors}
     * is what {@code MessageArchiveService.catchup} asks instead of reading the database itself.
     */
    fun syncEvents(): SyncEvents

    fun syncAnchors(): SyncAnchors

    fun trustPort(): TrustPort

    fun pgpEngineFactory(): PgpEngineFactory

    fun omemoSetting(): OmemoSettingsPort

    // Pair 11's four handles. They are additive to pair 10's interface on purpose: the install is
    // one call and one object, so a second factory would be a second thing to forget.
    fun liveLocationHook(): LiveLocationHook

    fun rtpSessionPort(): RtpSessionPort

    fun themePort(): ThemePort

    fun profilePictureActivityPort(): ProfilePictureActivityPort

    /** Pair 11: the screen that asks the owner to accept one certificate. */
    fun decisionScreenPort(): MemorizingTrustManager.DecisionScreenPort

    /**
     * Turn the island's own continuation into the object the PGP engine's callback slot expects.
     *
     * <p>Two of the island's PGP call sites pass a continuation that came from {@code :ui} and is
     * therefore already the type the engine casts to; one builds its callback here, at the
     * notification quick reply, because the success action ({@code markRead} or a pushed reply) is
     * this island's own work. The slot's type is {@code :crypto}'s and cannot be named here at all
     * — which is why {@link PgpEnginePort} already takes it as an {@code Object} — so the
     * composition root, the one place that names both ends, builds the adapter.
     */
    fun pgpCallback(callback: UiCallbackPort<*>): Any

    // Pair 4's handles and helpers. Like pair 11's, they are additive to this interface on
    // purpose: the install is one call and one object, so a second factory would be a second
    // thing to forget. The members the island only *calls* are stateless adapters in `:app`;
    // the ones it *holds* are the `:app` instances themselves, built here because this is the
    // one place that may name them.
    fun compatibility(): CompatibilityPort

    fun phoneHelper(): PhoneHelperPort

    fun wakeLock(): WakeLockPort

    fun transcoderStrategies(): TranscoderStrategiesPort

    fun systemEvent(): SystemEventPort

    fun loggingHook(): LoggingHook

    fun messageSearch(): MessageSearchPort

    fun bobTransfer(): BobTransferPort

    fun contactListSync(): ContactListSyncPort

    fun attachFile(): AttachFilePort

    fun fileObserver(): FileObserverPort

    fun avatar(): AvatarPort

    fun notification(): NotificationPort

    fun channelDiscovery(): ChannelDiscoveryPort

    fun shortcuts(): ShortcutPort

    fun unifiedPush(): UnifiedPushPort

    fun pushManagement(): PushManagementPort

    fun incomingMessageHook(): IncomingMessageHook

    /** Pair 4's last surface: the telecom call integration the two jingle classes build. */
    fun callIntegration(): CallIntegrationFactory

    /**
     * Declared {@code fun interface} because `TulkkiApplication.onCreate` builds it with a Kotlin
     * SAM lambda; a plain Kotlin interface would not accept that spelling.
     */
    fun interface Factory {
        fun create(service: XmppConnectionService): TulkkiPorts
    }
}
