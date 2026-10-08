package uk.xa0.tulkki.xmpp.services

/**
 * Tulkki: the port accessors, lifted out of `XmppConnectionService`
 *.
 *
 * Every slot stays on the service: those fields are chunk `C74`'s, written by the installation
 * `onCreate` performs and read by chunks that have not moved, so each accessor takes its slot **by
 * value** and the service's own method keeps the single read. The shared build-fault guard, the
 * Java's private static `require(T, String)`, moves with the chunk; the callers that reached it
 * through the service - chunk `C55`'s two getters, `BlockedMedia`, `UiConversationLookup`'s avatar
 * search, `ServiceDiscovery` and `AccountMaintenance` - now name it here, so the message is written
 * once instead of twice.
 *
 * The messages are the Java's byte for byte, because `PortInstallGuardTest` scans them for the
 * install token they name and refuses a message naming an install its table never reviewed.
 * `require` is public only because Java callers in the island resolve it. Nothing here answers null;
 * a missing install is a build fault. No overload was collapsed and no visibility was widened: the
 * package-private `compatibility()` keeps its Java spelling on the service's side of the seam.
 */
object PortAccessors {

    /**
     * The translation queue's one question, asked from the parse layer.
     *
     * Pair 10's ruling holds and matters more than it looks: `MessageParser` runs on the socket
     * thread, so reading an unset slot as "nothing is queued" would silently rotate the uuid of a
     * message the engine is still working on - the orphan the comment in `MessageParser` exists to
     * prevent - and nothing would report it. A missing install is therefore named rather than
     * answered.
     */
    @JvmStatic
    fun translationQueue(
        queue: TranslationQueue?,
    ): TranslationQueue =
        queue
            ?: throw IllegalStateException(
                "Tulkki's translation queue was never installed: the composition root must call" +
                    " XmppConnectionService.installPortsFactory at process start, and it" +
                    " has not",
            )

    @JvmStatic
    fun liveLocationHook(
        hook: LiveLocationHook?,
    ): LiveLocationHook =
        hook
            ?: throw IllegalStateException(
                "Tulkki's live-location hook was never installed: the composition root must call" +
                    " XmppConnectionService.installPortsFactory at process start, and it" +
                    " has not",
            )

    @JvmStatic
    fun rtpSessionPort(
        port: RtpSessionPort?,
    ): RtpSessionPort =
        port
            ?: throw IllegalStateException(
                "Tulkki's RTP session port was never installed: the composition root must call" +
                    " XmppConnectionService.installPortsFactory at process start, and it" +
                    " has not",
            )

    @JvmStatic
    fun themePort(port: ThemePort?): ThemePort =
        port
            ?: throw IllegalStateException(
                "Tulkki's theme port was never installed: the composition root must call" +
                    " XmppConnectionService.installPortsFactory at process start, and it" +
                    " has not",
            )

    @JvmStatic
    fun profilePictureActivityPort(
        port: ProfilePictureActivityPort?,
    ): ProfilePictureActivityPort =
        port
            ?: throw IllegalStateException(
                "Tulkki's profile-picture activity port was never installed: the composition" +
                    " root must call XmppConnectionService.installPortsFactory at process" +
                    " start, and it has not",
            )

    @JvmStatic
    fun decisionScreenPort(
        port: MemorizingTrustManager.DecisionScreenPort?,
    ): MemorizingTrustManager.DecisionScreenPort =
        port
            ?: throw IllegalStateException(
                "Tulkki's TLS decision-screen port was never installed: the composition root must" +
                    " call XmppConnectionService.installPortsFactory at process start, and" +
                    " it has not",
            )

    @JvmStatic
    fun tulkkiPorts(
        ports: TulkkiPorts?,
    ): TulkkiPorts =
        ports
            ?: throw IllegalStateException(
                "Tulkki's ports were never installed: the composition root must call" +
                    " XmppConnectionService.installPortsFactory at process start, and it" +
                    " has not",
            )

    @JvmStatic
    fun compatibility(
        port: CompatibilityPort?,
    ): CompatibilityPort = require(port, "platform-compatibility")

    @JvmStatic
    fun phoneHelper(
        port: PhoneHelperPort?,
    ): PhoneHelperPort = require(port, "device-identity")

    @JvmStatic
    fun wakeLock(port: WakeLockPort?): WakeLockPort =
        require(port, "wake-lock")

    @JvmStatic
    fun transcoderStrategies(
        port: TranscoderStrategiesPort?,
    ): TranscoderStrategiesPort = require(port, "transcoder-strategy")

    @JvmStatic
    fun systemEvent(
        port: SystemEventPort?,
    ): SystemEventPort = require(port, "system-event receiver")

    @JvmStatic
    fun messageSearch(
        port: MessageSearchPort?,
    ): MessageSearchPort = require(port, "message-search")

    @JvmStatic
    fun contactListSync(
        port: ContactListSyncPort?,
    ): ContactListSyncPort = require(port, "contact-list sync")

    @JvmStatic
    fun attachFile(port: AttachFilePort?): AttachFilePort = require(port, "file attachment")

    @JvmStatic
    fun avatar(port: AvatarPort?): AvatarPort =
        require(port, "avatar")

    @JvmStatic
    fun notification(
        port: NotificationPort?,
    ): NotificationPort = require(port, "notification")

    @JvmStatic
    fun channelDiscovery(
        port: ChannelDiscoveryPort?,
    ): ChannelDiscoveryPort = require(port, "channel discovery")

    @JvmStatic
    fun shortcuts(port: ShortcutPort?): ShortcutPort =
        require(port, "launcher shortcut")

    @JvmStatic
    fun unifiedPush(
        port: UnifiedPushPort?,
    ): UnifiedPushPort = require(port, "unified push")

    @JvmStatic
    fun pushManagement(
        port: PushManagementPort?,
    ): PushManagementPort = require(port, "push registration")

    /** The file observer is only stored; the factory that builds it is asked for on the way. */
    @JvmStatic
    fun fileObserverPort(ports: TulkkiPorts?): FileObserverPort =
        tulkkiPorts(ports).fileObserver()

    /** Stateless, so it lives on the factory rather than in a slot of its own. */
    @JvmStatic
    fun bobTransfer(
        ports: TulkkiPorts?,
    ): BobTransferPort = tulkkiPorts(ports).bobTransfer()

    /** Stateless, so it lives on the factory rather than in a slot of its own. */
    @JvmStatic
    fun incomingMessageHook(
        ports: TulkkiPorts?,
    ): IncomingMessageHook = tulkkiPorts(ports).incomingMessageHook()

    /** Stateless, so it lives on the factory rather than in a slot of its own. */
    @JvmStatic
    fun callIntegration(
        ports: TulkkiPorts?,
    ): CallIntegrationFactory = tulkkiPorts(ports).callIntegration()

    /**
     * The build-fault guard every slot accessor shares. Public because Java callers in the island
     * resolve it; the missing-install message names the one install that fixes it.
     */
    @JvmStatic
    fun <T : Any> require(port: T?, what: String): T =
        port
            ?: throw IllegalStateException(
                "Tulkki's " +
                    what +
                    " port was never installed: the composition root must call" +
                    " XmppConnectionService.installPortsFactory at process start, and it" +
                    " has not",
            )
}
